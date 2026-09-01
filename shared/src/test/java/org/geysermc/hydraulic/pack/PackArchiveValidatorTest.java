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
    void rejectsOversizedJsonBeforeParsingIt() throws IOException {
        Path archive = temporaryDirectory.resolve("oversized.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "animations/oversized.json", " ".repeat(8 * 1024 * 1024 + 1));
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("oversized JSON")));
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
    void identifiesArchivesContainingOnlyGenerationMetadata() throws IOException {
        Path archive = temporaryDirectory.resolve("metadata-only.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, "pack_icon.png", "icon");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertTrue(result.valid());
        assertTrue(result.metadataOnly());
        assertTrue(result.assetFiles() == 0);
    }

    @Test
    void retainsTextureOnlyPacksAsRealAssets() throws IOException {
        Path archive = temporaryDirectory.resolve("texture-only.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "textures/example/dependency.png", "texture");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertFalse(result.metadataOnly());
        assertTrue(result.assetFiles() == 1);
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

        assertFalse(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing entity texture")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing entity geometry")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing entity animation")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing animation controller")));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("broken-geometry-link")
                && finding.severity() == PackArchiveValidator.Severity.ERROR));
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

        assertFalse(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith("missing atlas texture")));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("broken-texture-link")
                && finding.severity() == PackArchiveValidator.Severity.ERROR));
    }

    @Test
    void filtersVanillaAtlasReferencesToInfo() throws IOException {
        Path archive = temporaryDirectory.resolve("vanilla-atlas.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "textures/item_texture.json", "{\"texture_data\":{\"example:atlas\":{\"textures\":[\"textures/atlas/blocks\"]}}}");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertTrue(result.findings().stream().anyMatch(finding -> finding.severity() == PackArchiveValidator.Severity.INFO
                && finding.message().contains("textures/atlas/blocks")));
    }

    @Test
    void reportsGeometryWithMostCubes() throws IOException {
        Path archive = temporaryDirectory.resolve("geometry.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{}");
            entry(zip, PackManager.PACK_GENERATION_MARKER, "{}");
            entry(zip, "models/entity/example.json", "{\"minecraft:geometry\":[{\"bones\":[{\"cubes\":[{},{}]}]}]}");
        }

        PackArchiveValidator.Result result = PackArchiveValidator.validate(archive);

        assertTrue(result.largestGeometry().endsWith("example.json"));
        assertTrue(result.largestGeometryCubes() == 2);
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
    void recordsReflectionFallbackClassAndReason() {
        ConversionReport report = new ConversionReport();
        String detail = "namespace=example, entity=broken, class=example.client.model.ModelBroken, reason=IncompatibleClassChangeError: broken runtime link";
        report.fallback("entity-reflection");
        report.outcome("entity-reflection-fallback", "example:broken");
        report.resolution("entity-reflection-fallback", "example:broken", detail);

        var json = report.json(0, 0, 1, 0);
        assertTrue(json.getAsJsonObject("asset_resolutions").getAsJsonObject("entity-reflection-fallback")
                .get("example:broken").getAsString().contains("class=example.client.model.ModelBroken"));
    }

    @Test
    void recordsAffectedIdsAndPhaseTimings() {
        ConversionReport report = new ConversionReport();
        report.status("invalid");
        report.failure("missing controller");
        report.outcome("entity-hitbox", "example:beast");
        report.resolution("entity-texture", "example:beast", "textures/entity/example/beast");
        report.validationWarnings(java.util.List.of("missing atlas texture textures/item_texture.json -> example:beast"));
        report.timing("input", 12);
        report.timing("package", 34);
        report.timing("validation", 56);

        var json = report.json(0, 0, 1, 789);

        assertTrue(json.get("status").getAsString().equals("invalid"));
        assertTrue(json.get("failure").getAsString().equals("missing controller"));
        assertTrue(json.getAsJsonObject("outcome_ids").getAsJsonArray("entity-hitbox").contains(new com.google.gson.JsonPrimitive("example:beast")));
        assertTrue(json.getAsJsonObject("asset_resolutions").getAsJsonObject("entity-texture").get("example:beast").getAsString().endsWith("/beast"));
        assertTrue(json.getAsJsonArray("validation_warnings").get(0).getAsString().startsWith("missing atlas texture"));
        assertTrue(json.getAsJsonObject("timings_ms").get("package").getAsLong() == 34);
        assertTrue(json.get("archive_bytes").getAsLong() == 789);
    }

    @Test
    void separatesFullNativeGeometryGenericAnimationAndHitboxFallbacks() {
        com.google.gson.JsonObject report = new com.google.gson.JsonObject();
        report.add("outcomes", com.google.gson.JsonParser.parseString("""
                {"entity-native-geometry":2,"entity-hitbox":1,"entity-generic-animation":1,
                 "entity-native-generic-animation":1,"item-unresolved":3,"item-missing-output-texture":2}
                """).getAsJsonObject());
        report.add("outcome_ids", com.google.gson.JsonParser.parseString("""
                {"entity-hitbox":["example:one"],"entity-generic-animation":["example:one"],
                 "entity-native-generic-animation":["example:two"]}
                """).getAsJsonObject());

        PackManager.Quality quality = PackManager.qualityFromReport(report);

        assertTrue(quality.fullNativeGeometries() == 1);
        assertTrue(quality.nativeGeometriesWithGenericAnimation() == 1);
        assertTrue(quality.hitboxGeometryFallbacks() == 1);
        assertTrue(quality.unresolvedItemAssets() == 5);
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
