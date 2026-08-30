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
    void reportsEntityTextureReferencesThatAreMissingFromTheArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("fixture.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "entity/example.mob.entity.json", "{\"minecraft:client_entity\":{\"description\":{\"identifier\":\"example:mob\",\"textures\":{\"default\":\"textures/entity/example/mob\"},\"geometry\":{\"default\":\"geometry.example.missing\"},\"animations\":{\"idle\":\"animation.example.missing\"},\"animation_controllers\":[\"controller.animation.example.missing\"]}}}");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing entity texture")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing entity geometry")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing entity animation")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing animation controller")));
    }

    @Test
    void reportsMissingTextureAtlasEntries() throws IOException {
        Path archive = temporaryDirectory.resolve("fixture.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "textures/item_texture.json", "{\"texture_data\":{\"example:missing\":{\"textures\":[\"textures/items/example/missing\"]}}}");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing atlas texture")));
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

    @Test
    void recordsAffectedIdsAndPhaseTimings() {
        ConversionReport report = new ConversionReport();
        report.outcome("entity-hitbox", "example:beast");
        report.resolution("entity-texture", "example:beast", "textures/entity/example/beast");
        report.validationWarnings(java.util.List.of("missing atlas texture textures/item_texture.json -> example:beast"));

        var json = report.json(0, 0, 1, 12, 34, 56);

        assertTrue(json.getAsJsonObject("outcome_ids").getAsJsonArray("entity-hitbox").contains(new com.google.gson.JsonPrimitive("example:beast")));
        assertTrue(json.getAsJsonObject("asset_resolutions").getAsJsonObject("entity-texture").get("example:beast").getAsString().endsWith("/beast"));
        assertTrue(json.getAsJsonArray("validation_warnings").getFirst().getAsString().startsWith("missing atlas texture"));
        assertTrue(json.getAsJsonObject("timings_ms").get("package").getAsLong() == 34);
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
