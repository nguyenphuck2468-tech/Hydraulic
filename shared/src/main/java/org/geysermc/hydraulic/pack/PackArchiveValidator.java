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
        List<EntityTexture> entityTextures = new ArrayList<>();
        Set<String> filesByPath = new HashSet<>();
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
                        if (entry.getName().startsWith("entity/") && parsed.isJsonObject()) {
                            collectEntityTextures(entry.getName(), parsed.getAsJsonObject(), entityTextures);
                        }
                    } catch (RuntimeException exception) {
                        errors.add("invalid JSON " + entry.getName());
                    }
                }
            }
        }
        for (EntityTexture texture : entityTextures) {
            if (!filesByPath.contains(texture.path() + ".png") && !filesByPath.contains(texture.path() + ".tga")) {
                warnings.add("missing entity texture " + texture.entity() + " -> " + texture.path());
            }
        }
        return new Result(files, List.copyOf(errors), List.copyOf(warnings));
    }

    private static void collectEntityTextures(String entity, JsonObject root, List<EntityTexture> textures) {
        JsonObject clientEntity = object(root, "minecraft:client_entity");
        JsonObject description = object(clientEntity, "description");
        JsonObject declared = object(description, "textures");
        if (declared == null) return;
        for (JsonElement value : declared.asMap().values()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String path = value.getAsString();
                if (path.startsWith("textures/")) textures.add(new EntityTexture(entity, path));
            }
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
        return parent.getAsJsonObject(name);
    }

    private record EntityTexture(String entity, String path) {
    }

    public record Result(int files, List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
