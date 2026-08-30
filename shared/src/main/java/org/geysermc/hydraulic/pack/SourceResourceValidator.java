package org.geysermc.hydraulic.pack;

import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds malformed source resources before conversion while leaving other assets runnable. */
final class SourceResourceValidator {
    private static final Pattern LOCATION = Pattern.compile("line (\\d+) column (\\d+)");

    private SourceResourceValidator() {
    }

    static List<Finding> validate(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        List<Finding> findings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .forEach(file -> validate(root, file, findings));
        } catch (Exception ignored) {
            // The converter already isolates unreadable roots. Diagnostics must
            // never become a second reason to abort the rest of the mod.
        }
        return List.copyOf(findings);
    }

    private static void validate(Path root, Path file, List<Finding> findings) {
        String resource = root.relativize(file).toString().replace('\\', '/');
        if (!resource.startsWith("assets/")) return;
        try {
            JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            String namespace = namespace(resource);
            int[] location = location(exception.getMessage());
            findings.add(new Finding(namespace, resource, type(resource), location[0], location[1], exception.getMessage()));
        }
    }

    private static String namespace(String resource) {
        String[] parts = resource.split("/", 4);
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    private static String type(String resource) {
        String[] parts = resource.split("/", 4);
        if (parts.length < 3) return "json";
        return switch (parts[2]) {
            case "models" -> "model";
            case "blockstates" -> "blockstate";
            case "lang" -> "lang";
            case "advancements" -> "advancement";
            case "loot_tables" -> "loot-table";
            case "recipes" -> "recipe";
            case "items" -> "item-definition";
            default -> "json";
        };
    }

    private static int[] location(String message) {
        if (message == null) return new int[]{0, 0};
        Matcher matcher = LOCATION.matcher(message);
        return matcher.find() ? new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))} : new int[]{0, 0};
    }

    record Finding(String namespace, String resource, String type, int line, int column, String message) {
        String description() {
            return "namespace=" + namespace + ", resource=" + resource + ", type=" + type
                    + ", line=" + line + ", column=" + column + ", reason=" + message;
        }
    }
}
