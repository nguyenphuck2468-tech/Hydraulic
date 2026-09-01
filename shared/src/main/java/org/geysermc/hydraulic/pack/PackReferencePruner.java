package org.geysermc.hydraulic.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.hydraulic.util.IOUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            // Texture atlases still live in PackConverter's in-memory pack at
            // this stage. Deleting or deduplicating files here can leave the
            // serialized atlas pointing at files that no longer exist.
            int duplicateTextures = 0;
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

            int textures = 0;
            return new Result(geometry, animations, animationControllers, renderControllers, textures, duplicateTextures);
        } catch (IOException ignored) {
            return Result.EMPTY;
        }
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
            json.getAsJsonObject().entrySet().forEach(entry -> {
                // Bedrock permits controller and animation identifiers as object
                // keys, for example conditional animation_controller bindings.
                strings.add(entry.getKey());
                collectStrings(entry.getValue(), strings);
            });
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
