package org.geysermc.hydraulic.pack;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        int files = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("manifest.json") == null) errors.add("missing manifest.json");
            if (zip.getEntry(PackManager.PACK_GENERATION_MARKER) == null) errors.add("missing " + PackManager.PACK_GENERATION_MARKER);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                files++;
                if (entry.getName().length() >= BEDROCK_PATH_WARNING_LENGTH) {
                    warnings.add("long path " + entry.getName());
                }
                if (entry.getName().endsWith(".json")) {
                    try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                        JsonParser.parseReader(reader);
                    } catch (RuntimeException exception) {
                        errors.add("invalid JSON " + entry.getName());
                    }
                }
            }
        }
        return new Result(files, List.copyOf(errors), List.copyOf(warnings));
    }

    public record Result(int files, List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
