package org.geysermc.hydraulic.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
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

    private PackArchiveValidator() {
    }

    public static Result validate(Path archive) throws IOException {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<EntityReferences> entityReferences = new ArrayList<>();
        Set<String> filesByPath = new HashSet<>();
        Set<String> geometries = new HashSet<>();
        Set<String> animations = new HashSet<>();
        Set<String> animationControllers = new HashSet<>();
        int files = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("manifest.json") == null) errors.add("missing manifest.json");
            if (zip.getEntry(PackManager.PACK_GENERATION_MARKER) == null) errors.add("missing " + PackManager.PACK_GENERATION_MARKER);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                files++;
                filesByPath.add(entry.getName());
                if (entry.getName().length() >= BEDROCK_PATH_WARNING_LENGTH) {
                    warnings.add("long path " + entry.getName());
                }
                if (entry.getName().endsWith(".json")) {
                    try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                        JsonElement parsed = JsonParser.parseReader(reader);
                        if (parsed.isJsonObject()) {
                            collectReferences(entry.getName(), parsed.getAsJsonObject(), entityReferences, geometries, animations, animationControllers);
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
        return new Result(files, List.copyOf(errors), List.copyOf(warnings));
    }

    private static void collectReferences(String file, JsonObject root, List<EntityReferences> entities, Set<String> geometries,
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

    private static List<String> arrayValues(JsonElement array) {
        if (array == null || !array.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement value : array.getAsJsonArray()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) values.add(value.getAsString());
        }
        return values;
    }

    private record EntityReferences(String file, String namespace, List<String> textures, List<String> geometries,
                                    List<String> animations, List<String> animationControllers) {
    }

    public record Result(int files, List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
