package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackArchiveValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsBrokenJsonAndBedrockUnsafePaths() throws IOException {
        Path archive = temporaryDirectory.resolve("fixture.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "models/" + "x".repeat(75) + ".json", "{");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("invalid JSON")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("long path")));
    }

    @Test
    void acceptsACompleteSmallArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("fixture.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "models/f.json", "{}");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertTrue(result.valid());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void summarizesFallbacksWithoutPerModRules() {
        ConversionReport report = new ConversionReport();
        report.fallback("item-texture");
        report.fallback("item-texture");
        report.fallback("entity-hitbox");

        assertTrue(report.fallbackSummary().contains("item-texture=2"));
        assertTrue(report.fallbackSummary().contains("entity-hitbox=1"));
        assertTrue(report.fallbacks() == 3);
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
