package org.geysermc.hydraulic.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Shortens Bedrock-unsafe output paths while updating exact JSON path references. */
final class PackPathRewriter {
    private static final int BEDROCK_PATH_LIMIT = 80;

    private PackPathRewriter() {
    }

    static Result rewrite(Path root) throws IOException {
        MovePlan plan = moves(root);
        if (plan.collision()) return new Result(0, true);
        if (plan.moves().isEmpty()) return Result.NONE;

        Map<String, String> replacements = replacements(root, plan.moves());
        JsonPlan initialJson = jsonRewrites(root, plan.moves(), replacements);
        Map<Path, Path> referencedMoves = referencedMoves(root, plan.moves(), initialJson.references());
        if (referencedMoves.isEmpty()) return Result.NONE;

        replacements = replacements(root, referencedMoves);
        JsonPlan json = jsonRewrites(root, referencedMoves, replacements);
        apply(referencedMoves, json.rewrites());
        return new Result(referencedMoves.size(), false);
    }

    static Path shortenedPath(Path relative) {
        String name = relative.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String suffix = extension < 0 ? "" : name.substring(extension);
        return Path.of("h", UUID.nameUUIDFromBytes(relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8)) + suffix);
    }

    private static MovePlan moves(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        Map<Path, Path> moves = new LinkedHashMap<>();
        Set<Path> targets = new HashSet<>();
        for (Path source : files) {
            Path relative = root.relativize(source);
            if (relative.toString().replace('\\', '/').length() < BEDROCK_PATH_LIMIT || PackArchiveValidator.isMetadataFile(relative.toString().replace('\\', '/'))) {
                continue;
            }
            Path target = root.resolve(shortenedPath(relative));
            if (Files.exists(target) || !targets.add(target)) return new MovePlan(Map.of(), true);
            moves.put(source, target);
        }
        return new MovePlan(moves, false);
    }

    private static Map<String, String> replacements(Path root, Map<Path, Path> moves) {
        Map<String, String> replacements = new HashMap<>();
        for (Map.Entry<Path, Path> move : moves.entrySet()) {
            String source = root.relativize(move.getKey()).toString().replace('\\', '/');
            String target = root.relativize(move.getValue()).toString().replace('\\', '/');
            replacements.put(source, target);
            replacements.put(withoutExtension(source), withoutExtension(target));
        }
        return replacements;
    }

    private static Map<Path, Path> referencedMoves(Path root, Map<Path, Path> moves, Set<String> references) {
        Map<Path, Path> referenced = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> move : moves.entrySet()) {
            String source = root.relativize(move.getKey()).toString().replace('\\', '/');
            if (references.contains(source) || references.contains(withoutExtension(source))) {
                referenced.put(move.getKey(), move.getValue());
            }
        }
        return referenced;
    }

    private static JsonPlan jsonRewrites(Path root, Map<Path, Path> moves, Map<String, String> replacements) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        List<JsonRewrite> rewrites = new ArrayList<>();
        Set<String> references = new HashSet<>();
        for (Path source : files) {
            byte[] original = Files.readAllBytes(source);
            JsonElement json;
            try {
                json = JsonParser.parseString(new String(original, StandardCharsets.UTF_8));
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!rewrite(json, replacements, references)) continue;
            if (contains(json, replacements.keySet())) {
                throw new IOException("Unrewritten path reference in " + root.relativize(source));
            }
            Path target = moves.getOrDefault(source, source);
            rewrites.add(new JsonRewrite(source, target, original, json.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return new JsonPlan(rewrites, references);
    }

    private static boolean rewrite(JsonElement json, Map<String, String> replacements, Set<String> references) {
        if (json.isJsonObject()) {
            boolean changed = false;
            JsonObject object = json.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String replacement = replacements.get(value.getAsString());
                    if (replacement != null) {
                        references.add(value.getAsString());
                        entry.setValue(new JsonPrimitive(replacement));
                        changed = true;
                    }
                } else {
                    changed |= rewrite(value, replacements, references);
                }
            }
            return changed;
        }
        if (!json.isJsonArray()) return false;
        boolean changed = false;
        JsonArray array = json.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            JsonElement value = array.get(index);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String replacement = replacements.get(value.getAsString());
                if (replacement != null) {
                    references.add(value.getAsString());
                    array.set(index, new JsonPrimitive(replacement));
                    changed = true;
                }
            } else {
                changed |= rewrite(value, replacements, references);
            }
        }
        return changed;
    }

    private static boolean contains(JsonElement json, Set<String> values) {
        if (json.isJsonPrimitive()) return json.getAsJsonPrimitive().isString() && values.contains(json.getAsString());
        if (json.isJsonObject()) return json.getAsJsonObject().asMap().values().stream().anyMatch(value -> contains(value, values));
        if (json.isJsonArray()) return json.getAsJsonArray().asList().stream().anyMatch(value -> contains(value, values));
        return false;
    }

    private static void apply(Map<Path, Path> moves, List<JsonRewrite> rewrites) throws IOException {
        List<Map.Entry<Path, Path>> completedMoves = new ArrayList<>();
        List<PreparedRewrite> prepared = new ArrayList<>();
        List<JsonRewrite> published = new ArrayList<>();
        try {
            for (JsonRewrite rewrite : rewrites) {
                Files.createDirectories(rewrite.target().getParent());
                Path temporary = rewrite.target().resolveSibling(rewrite.target().getFileName() + ".part");
                Files.deleteIfExists(temporary);
                Files.write(temporary, rewrite.rewritten());
                prepared.add(new PreparedRewrite(rewrite, temporary));
            }
            for (Map.Entry<Path, Path> move : moves.entrySet()) {
                Files.createDirectories(move.getValue().getParent());
                move(move.getKey(), move.getValue());
                completedMoves.add(move);
            }
            for (PreparedRewrite rewrite : prepared) {
                replace(rewrite.temporary(), rewrite.rewrite().target());
                published.add(rewrite.rewrite());
            }
        } catch (IOException exception) {
            for (int index = completedMoves.size() - 1; index >= 0; index--) {
                Map.Entry<Path, Path> move = completedMoves.get(index);
                move(move.getValue(), move.getKey());
            }
            for (JsonRewrite rewrite : published) PackManager.writeStringAtomically(rewrite.source(), new String(rewrite.original(), StandardCharsets.UTF_8));
            for (PreparedRewrite rewrite : prepared) Files.deleteIfExists(rewrite.temporary());
            throw exception;
        }
    }

    private static String withoutExtension(String path) {
        int extension = path.lastIndexOf('.');
        return extension < 0 ? path : path.substring(0, extension);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record MovePlan(Map<Path, Path> moves, boolean collision) {
    }

    private record JsonRewrite(Path source, Path target, byte[] original, byte[] rewritten) {
    }

    private record PreparedRewrite(JsonRewrite rewrite, Path temporary) {
    }

    private record JsonPlan(List<JsonRewrite> rewrites, Set<String> references) {
    }

    record Result(int rewritten, boolean collision) {
        static final Result NONE = new Result(0, false);
    }
}
