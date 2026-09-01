package org.geysermc.hydraulic.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lightweight post-export checks for failures Bedrock otherwise reports only to clients. */
public final class PackArchiveValidator {
    private static final int BEDROCK_PATH_WARNING_LENGTH = 80;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final int MAX_JSON_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final long MAX_TOTAL_JSON_BYTES = 64L * 1024 * 1024;

    private PackArchiveValidator() {
    }

    public static Result validate(Path archive) throws IOException {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<EntityReferences> entityReferences = new ArrayList<>();
        List<AtlasReferences> atlasReferences = new ArrayList<>();
        Set<String> filesByPath = new HashSet<>();
        Set<String> geometries = new HashSet<>();
        Set<String> animations = new HashSet<>();
        Set<String> animationControllers = new HashSet<>();
        int files = 0;
        int assetFiles = 0;
        long totalJsonBytes = 0;
        String largestGeometry = null;
        int largestGeometryCubes = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("manifest.json") == null) errors.add("missing manifest.json");
            if (zip.getEntry(PackManager.PACK_GENERATION_MARKER) == null) errors.add("missing " + PackManager.PACK_GENERATION_MARKER);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                files++;
                if (files > MAX_ARCHIVE_ENTRIES) {
                    errors.add("archive contains too many files");
                    break;
                }
                if (!isMetadataFile(entry.getName())) assetFiles++;
                filesByPath.add(entry.getName());
                if (entry.getName().length() >= BEDROCK_PATH_WARNING_LENGTH) {
                    warnings.add("long path " + entry.getName());
                }
                if (entry.getName().endsWith(".json")) {
                    if (entry.getSize() > MAX_JSON_ENTRY_BYTES) {
                        errors.add("oversized JSON " + entry.getName());
                        continue;
                    }
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] bytes = input.readNBytes(MAX_JSON_ENTRY_BYTES + 1);
                        if (bytes.length > MAX_JSON_ENTRY_BYTES) {
                            errors.add("oversized JSON " + entry.getName());
                            continue;
                        }
                        totalJsonBytes += bytes.length;
                        if (totalJsonBytes > MAX_TOTAL_JSON_BYTES) {
                            errors.add("archive JSON exceeds validation budget");
                            break;
                        }
                        JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                        if (parsed.isJsonObject()) {
                            if (entry.getName().startsWith("models/entity/")) {
                                int cubes = countNamedArrays(parsed, "cubes");
                                if (cubes > largestGeometryCubes) {
                                    largestGeometryCubes = cubes;
                                    largestGeometry = entry.getName();
                                }
                            }
                            collectReferences(entry.getName(), parsed.getAsJsonObject(), entityReferences, atlasReferences, geometries, animations, animationControllers);
                        }
                    } catch (RuntimeException exception) {
                        errors.add("invalid JSON " + entry.getName());
                    }
                }
            }
        }
        for (EntityReferences entity : entityReferences) {
            for (String texture : entity.textures()) {
                if (!filesByPath.contains(texture + ".png") && !filesByPath.contains(texture + ".tga")) {
                    warnings.add("missing entity texture " + entity.file() + " -> " + texture);
                }
            }
            for (String geometry : entity.geometries()) {
                if (geometry.startsWith("geometry." + entity.namespace() + ".") && !geometries.contains(geometry)) {
                    warnings.add("missing entity geometry " + entity.file() + " -> " + geometry);
                }
            }
            for (String animation : entity.animations()) {
                if (animation.startsWith("animation." + entity.namespace() + ".") && !animations.contains(animation)) {
                    warnings.add("missing entity animation " + entity.file() + " -> " + animation);
                }
            }
            for (String controller : entity.animationControllers()) {
                if (controller.startsWith("controller.animation." + entity.namespace() + ".") && !animationControllers.contains(controller)) {
                    warnings.add("missing animation controller " + entity.file() + " -> " + controller);
                }
            }
        }
        for (AtlasReferences atlas : atlasReferences) {
            for (String texture : atlas.textures()) {
                if (!filesByPath.contains(texture + ".png") && !filesByPath.contains(texture + ".tga")) {
                    warnings.add("missing atlas texture " + atlas.file() + " -> " + atlas.key() + " -> " + texture);
                }
            }
        }
        return new Result(files, assetFiles, List.copyOf(errors), List.copyOf(warnings), classify(errors, warnings),
                largestGeometry, largestGeometryCubes);
    }

    private static int countNamedArrays(JsonElement element, String name) {
        if (element == null) return 0;
        if (element.isJsonArray()) {
            int count = 0;
            for (JsonElement child : element.getAsJsonArray()) count += countNamedArrays(child, name);
            return count;
        }
        if (!element.isJsonObject()) return 0;
        int count = 0;
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals(name) && entry.getValue().isJsonArray()) count += entry.getValue().getAsJsonArray().size();
            count += countNamedArrays(entry.getValue(), name);
        }
        return count;
    }

    static boolean hasAssets(ZipFile zip) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && !isMetadataFile(entry.getName())) return true;
        }
        return false;
    }

    static boolean isMetadataFile(String path) {
        return path.equals("manifest.json") || path.equals("pack_icon.png") || path.equals(PackManager.PACK_GENERATION_MARKER);
    }

    private static void collectReferences(String file, JsonObject root, List<EntityReferences> entities, List<AtlasReferences> atlases, Set<String> geometries,
                                          Set<String> animations, Set<String> animationControllers) {
        if (file.startsWith("entity/")) {
            collectEntityReferences(file, root, entities);
        } else if (file.startsWith("models/entity/")) {
            JsonElement declared = root.get("minecraft:geometry");
            if (declared != null && declared.isJsonArray()) {
                for (JsonElement geometry : declared.getAsJsonArray()) {
                    JsonObject description = geometry.isJsonObject() ? object(geometry.getAsJsonObject(), "description") : null;
                    string(description, "identifier", geometries);
                }
            }
        } else if (file.startsWith("animations/")) {
            strings(object(root, "animations"), animations);
        } else if (file.startsWith("animation_controllers/")) {
            strings(object(root, "animation_controllers"), animationControllers);
        }
        collectAtlasReferences(file, root, atlases);
    }

    private static void collectAtlasReferences(String file, JsonObject root, List<AtlasReferences> atlases) {
        JsonObject textureData = object(root, "texture_data");
        if (textureData == null) return;
        for (var entry : textureData.entrySet()) {
            JsonObject definition = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
            List<String> textures = textureValues(definition == null ? null : definition.get("textures"));
            if (!textures.isEmpty()) atlases.add(new AtlasReferences(file, entry.getKey(), textures));
        }
    }

    private static void collectEntityReferences(String file, JsonObject root, List<EntityReferences> entities) {
        JsonObject clientEntity = object(root, "minecraft:client_entity");
        JsonObject description = object(clientEntity, "description");
        if (description == null) return;
        String identifier = string(description, "identifier");
        if (identifier == null || !identifier.contains(":")) return;
        List<String> textures = values(object(description, "textures"), "textures/");
        entities.add(new EntityReferences(file, identifier.substring(0, identifier.indexOf(':')), textures,
                values(object(description, "geometry"), null), values(object(description, "animations"), null),
                arrayValues(description.get("animation_controllers"))));
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
        return parent.getAsJsonObject(name);
    }

    private static void strings(JsonObject object, Set<String> values) {
        if (object == null) return;
        values.addAll(object.keySet());
    }

    private static void string(JsonObject object, String name, Set<String> values) {
        String value = string(object, name);
        if (value != null) values.add(value);
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) return null;
        return object.get(name).getAsString();
    }

    private static List<String> values(JsonObject object, String prefix) {
        if (object == null) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement value : object.asMap().values()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            String string = value.getAsString();
            if (prefix == null || string.startsWith(prefix)) values.add(string);
        }
        return values;
    }

    private static List<String> textureValues(JsonElement textures) {
        if (textures == null) return List.of();
        if (textures.isJsonPrimitive() && textures.getAsJsonPrimitive().isString()) {
            String value = textures.getAsString();
            return value.startsWith("textures/") ? List.of(value) : List.of();
        }
        if (!textures.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement texture : textures.getAsJsonArray()) {
            if (texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isString() && texture.getAsString().startsWith("textures/")) {
                values.add(texture.getAsString());
            }
        }
        return values;
    }

    private static List<String> arrayValues(JsonElement array) {
        if (array == null || !array.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement value : array.getAsJsonArray()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) values.add(value.getAsString());
        }
        return values;
    }

    private static List<Finding> classify(List<String> errors, List<String> warnings) {
        List<Finding> findings = new ArrayList<>();
        for (String error : errors) findings.add(finding(error, Severity.ERROR));
        for (String warning : warnings) findings.add(finding(warning, severityForWarning(warning)));
        return List.copyOf(findings);
    }

    private static Severity severityForWarning(String warning) {
        if (warning.contains("textures/atlas/") || warning.contains("minecraft:/atlas") || warning.contains("minecraft:atlas")) {
            return Severity.INFO;
        }
        if (warning.startsWith("missing atlas texture") || warning.startsWith("missing entity geometry")
                || warning.startsWith("missing entity animation") || warning.startsWith("missing animation controller")) {
            return Severity.ERROR;
        }
        return Severity.WARNING;
    }

    private static Finding finding(String message, Severity severity) {
        String code = message.startsWith("missing manifest") ? "broken-manifest"
                : message.startsWith("invalid JSON") ? "invalid-json"
                : message.startsWith("missing entity geometry") ? "broken-geometry-link"
                : message.startsWith("missing entity animation") || message.startsWith("missing animation controller") ? "broken-animation-link"
                : message.startsWith("missing atlas texture") ? "broken-texture-link"
                : message.startsWith("missing entity texture") ? "missing-mod-texture"
                : message.startsWith("long path") ? "bedrock-path-limit" : "validation";
        String resource = resource(message);
        return new Finding(severity, code, resource, message);
    }

    private static String resource(String message) {
        int start = message.indexOf(' ');
        String value = start < 0 ? message : message.substring(start + 1);
        int arrow = value.indexOf(" -> ");
        return arrow < 0 ? value : value.substring(0, arrow);
    }

    private record EntityReferences(String file, String namespace, List<String> textures, List<String> geometries,
                                    List<String> animations, List<String> animationControllers) {
    }

    private record AtlasReferences(String file, String key, List<String> textures) {
    }

    public record Result(int files, int assetFiles, List<String> errors, List<String> warnings, List<Finding> findings,
                         String largestGeometry, int largestGeometryCubes) {
        public boolean valid() {
            return errors.isEmpty() && findings.stream().noneMatch(finding -> finding.severity() == Severity.ERROR);
        }

        public boolean metadataOnly() {
            return assetFiles == 0;
        }
    }

    public enum Severity { INFO, WARNING, ERROR }

    public record Finding(Severity severity, String code, String resource, String message) {
    }
}
