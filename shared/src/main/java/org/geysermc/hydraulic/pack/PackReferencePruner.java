package org.geysermc.hydraulic.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.hydraulic.util.IOUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** Removes generated Bedrock assets whose declared identifiers have no consumer. */
final class PackReferencePruner {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;

    private PackReferencePruner() {
    }

    static Result prune(Path root) {
        try {
            int duplicateTextures = deduplicateTextures(root);
            Set<String> entityReferences = references(root.resolve("entity"));
            int geometry = pruneDefinitions(root.resolve("models/entity"), entityReferences,
                    PackReferencePruner::geometryDefinitions);
            int animationControllers = pruneDefinitions(root.resolve("animation_controllers"), entityReferences,
                    json -> objectKeys(json, "animation_controllers"));
            int renderControllers = pruneDefinitions(root.resolve("render_controllers"), entityReferences,
                    json -> objectKeys(json, "render_controllers"));
            Set<String> animationReferences = new HashSet<>(entityReferences);
            animationReferences.addAll(references(root.resolve("animation_controllers")));
            int animations = pruneDefinitions(root.resolve("animations"), animationReferences,
                    json -> objectKeys(json, "animations"));

            Set<String> allReferences = references(root);
            int textures = pruneTextures(root, allReferences);
            return new Result(geometry, animations, animationControllers, renderControllers, textures, duplicateTextures);
        } catch (IOException ignored) {
            return Result.EMPTY;
        }
    }

    private static int deduplicateTextures(Path root) throws IOException {
        Path textures = root.resolve("textures");
        if (!Files.isDirectory(textures)) return 0;
        Map<String, Path> canonicalByHash = new LinkedHashMap<>();
        Map<Path, Path> duplicates = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(textures)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".png") && !name.endsWith(".tga")) continue;
                String key = name.substring(name.lastIndexOf('.')) + ':' + sha256(file);
                Path canonical = canonicalByHash.putIfAbsent(key, file);
                if (canonical != null) duplicates.put(file, canonical);
            }
        }
        if (duplicates.isEmpty()) return 0;

        Map<String, String> replacements = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> duplicate : duplicates.entrySet()) {
            String from = relative(root, duplicate.getKey());
            String to = relative(root, duplicate.getValue());
            replacements.put(from, to);
            replacements.put(withoutExtension(from), withoutExtension(to));
        }
        for (Path jsonFile : jsonFiles(root)) {
            JsonObject json = readObject(jsonFile);
            if (json == null || !rewriteStrings(json, replacements)) continue;
            PackManager.writeStringAtomically(jsonFile, json.toString());
        }
        for (Path duplicate : duplicates.keySet()) Files.deleteIfExists(duplicate);
        return duplicates.size();
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean rewriteStrings(JsonElement json, Map<String, String> replacements) {
        if (json.isJsonObject()) {
            boolean changed = false;
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String replacement = replacements.get(value.getAsString());
                    if (replacement != null) {
                        entry.setValue(new com.google.gson.JsonPrimitive(replacement));
                        changed = true;
                    }
                } else changed |= rewriteStrings(value, replacements);
            }
            return changed;
        }
        if (!json.isJsonArray()) return false;
        boolean changed = false;
        for (int index = 0; index < json.getAsJsonArray().size(); index++) {
            JsonElement value = json.getAsJsonArray().get(index);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String replacement = replacements.get(value.getAsString());
                if (replacement != null) {
                    json.getAsJsonArray().set(index, new com.google.gson.JsonPrimitive(replacement));
                    changed = true;
                }
            } else changed |= rewriteStrings(value, replacements);
        }
        return changed;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String withoutExtension(String path) {
        int extension = path.lastIndexOf('.');
        return extension < 0 ? path : path.substring(0, extension);
    }

    private static int pruneDefinitions(Path directory, Set<String> references,
                                        Function<JsonObject, Set<String>> definitions) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        int removed = 0;
        for (Path file : jsonFiles(directory)) {
            JsonObject json = readObject(file);
            if (json == null) continue;
            Set<String> declared = definitions.apply(json);
            if (!declared.isEmpty() && declared.stream().noneMatch(references::contains)) {
                Files.delete(file);
                removed++;
            }
        }
        return removed;
    }

    private static int pruneTextures(Path root, Set<String> references) throws IOException {
        Path textures = root.resolve("textures");
        if (!Files.isDirectory(textures)) return 0;
        int removed = 0;
        try (Stream<Path> paths = Files.walk(textures)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String lower = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".png") && !lower.endsWith(".tga")) continue;
                String relative = root.relativize(file).toString().replace('\\', '/');
                String noExtension = relative.substring(0, relative.lastIndexOf('.'));
                if (!references.contains(relative) && !references.contains(noExtension)) {
                    Files.delete(file);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static Set<String> references(Path directory) throws IOException {
        Set<String> references = new HashSet<>();
        if (!Files.isDirectory(directory)) return references;
        for (Path file : jsonFiles(directory)) {
            JsonObject json = readObject(file);
            if (json != null) collectStrings(json, references);
        }
        return references;
    }

    private static List<Path> jsonFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        }
    }

    private static JsonObject readObject(Path file) {
        try {
            JsonElement json = JsonParser.parseString(new String(IOUtil.readBytes(file, MAX_JSON_BYTES), StandardCharsets.UTF_8));
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Set<String> geometryDefinitions(JsonObject root) {
        Set<String> definitions = new HashSet<>();
        JsonElement geometries = root.get("minecraft:geometry");
        if (geometries == null || !geometries.isJsonArray()) return definitions;
        for (JsonElement geometry : geometries.getAsJsonArray()) {
            if (!geometry.isJsonObject()) continue;
            JsonObject description = geometry.getAsJsonObject().getAsJsonObject("description");
            if (description != null && description.has("identifier")) {
                definitions.add(description.get("identifier").getAsString());
            }
        }
        return definitions;
    }

    private static Set<String> objectKeys(JsonObject root, String member) {
        JsonElement value = root.get(member);
        return value != null && value.isJsonObject() ? value.getAsJsonObject().keySet() : Set.of();
    }

    private static void collectStrings(JsonElement json, Set<String> strings) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            strings.add(json.getAsString());
        } else if (json.isJsonObject()) {
            json.getAsJsonObject().asMap().values().forEach(value -> collectStrings(value, strings));
        } else if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(value -> collectStrings(value, strings));
        }
    }

    record Result(int geometries, int animations, int animationControllers, int renderControllers, int textures,
                  int duplicateTextures) {
        static final Result EMPTY = new Result(0, 0, 0, 0, 0, 0);

        int total() {
            return geometries + animations + animationControllers + renderControllers + textures + duplicateTextures;
        }
    }
}
