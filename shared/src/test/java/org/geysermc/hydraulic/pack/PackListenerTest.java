package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackListenerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsFinishedWorkWithoutWaitingForAStalledConversion() {
        CompletableFuture<String> stalled = new CompletableFuture<>();
        CompletableFuture<String> completed = CompletableFuture.completedFuture("ready");

        List<String> results = PackListener.awaitCompleted(List.of(stalled, completed), 1);

        assertEquals(List.of("ready"), results);
    }

    @Test
    void reusesCompleteArchiveAcrossRepeatedStartupChecks() throws IOException {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path archive = archive("complete.mcpack", fingerprint, true);

        assertFalse(PackListener.needsConversion(archive, fingerprint));
        assertFalse(PackListener.needsConversion(archive, fingerprint));
        assertTrue(PackListener.needsConversion(archive, "7190253f-8852-4fed-9a23-8028541152e6"));
    }

    @Test
    void metadataOnlyArchiveIsNeverReusedOrRegistered() throws IOException {
        Path archive = archive("metadata-only.mcpack", "e5d87397-0931-4609-a849-01b2da7a3529", false);

        assertTrue(PackListener.needsConversion(archive, "e5d87397-0931-4609-a849-01b2da7a3529"));
    }

    @Test
    void reusesMetadataOnlyResultAcrossRepeatedStartupChecks() throws IOException {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path packPath = temporaryDirectory.resolve("empty.mcpack");

        PackListener.writeMetadataOnlyMarker(packPath, fingerprint);

        assertFalse(Files.exists(PackListener.metadataOnlyMarkerPath(packPath).resolveSibling("empty.mcpack.empty.json.part")));
        assertEquals(PackListener.CacheStatus.SKIPPED_EMPTY, PackListener.cacheStatus(packPath, fingerprint));
        assertEquals(PackListener.CacheStatus.SKIPPED_EMPTY, PackListener.cacheStatus(packPath, fingerprint));
        assertEquals(PackListener.CacheStatus.CONVERT, PackListener.cacheStatus(packPath,
                "7190253f-8852-4fed-9a23-8028541152e6"));
    }

    @Test
    void realArchiveWinsAfterMetadataOnlyMarkerIsRemoved() throws IOException {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path archive = archive("complete.mcpack", fingerprint, true);
        PackListener.writeMetadataOnlyMarker(archive, fingerprint);

        PackListener.deleteMetadataOnlyMarker(archive);

        assertEquals(PackListener.CacheStatus.REUSE, PackListener.cacheStatus(archive, fingerprint));
    }

    @Test
    void realArchiveWinsAndRepairsConflictingMetadataMarker() throws IOException {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path archive = archive("complete.mcpack", fingerprint, true);
        PackListener.writeMetadataOnlyMarker(archive, fingerprint);

        assertEquals(PackListener.CacheStatus.REUSE, PackListener.cacheStatus(archive, fingerprint));
        assertFalse(Files.exists(PackListener.metadataOnlyMarkerPath(archive)));
    }

    @Test
    void freshProcessReusesStartOneArchiveOnStartTwo() throws Exception {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path archive = archive("restart.mcpack", fingerprint, true, true);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path arguments = temporaryDirectory.resolve("restart-probe.args");
        Files.writeString(arguments, "-cp\n" + javaArgument(System.getProperty("java.class.path")) + "\n"
                + PackCacheProbe.class.getName() + "\n" + javaArgument(archive.toString()) + "\n" + fingerprint + "\n");
        Process process = new ProcessBuilder(java.toString(), "@" + arguments).redirectErrorStream(true).start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("REUSE"), output);
        assertTrue(output.contains("entities=1"), output);
    }

    @Test
    void partArchiveIsNeverReusedOrRegistered() throws IOException {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path archive = archive("complete.mcpack.part", fingerprint, true);

        assertTrue(PackListener.needsConversion(archive, fingerprint));
    }

    @Test
    void oversizedMetadataOnlyMarkerIsNeverTrusted() throws IOException {
        Path packPath = temporaryDirectory.resolve("empty.mcpack");
        Files.writeString(PackListener.metadataOnlyMarkerPath(packPath), " ".repeat(64 * 1024 + 1));

        assertEquals(PackListener.CacheStatus.CONVERT, PackListener.cacheStatus(packPath, "fingerprint"));
    }

    @Test
    void writesAtomicMachineReadableDeliveryPlan() throws IOException {
        Path first = temporaryDirectory.resolve("first.mcpack");
        Path second = temporaryDirectory.resolve("second.mcpack");
        Files.write(first, new byte[7]);
        Files.write(second, new byte[11]);
        Path plan = temporaryDirectory.resolve("reports/delivery-plan.json");

        PackListener.writeDeliveryPlan(plan, PackProfile.LITE, List.of(first, second));

        JsonObject json = JsonParser.parseString(Files.readString(plan)).getAsJsonObject();
        assertEquals(1, json.get("schema_version").getAsInt());
        assertEquals("lite", json.get("profile").getAsString());
        assertEquals(2, json.get("pack_count").getAsInt());
        assertEquals(18, json.get("total_bytes").getAsLong());
        assertEquals("first.mcpack", json.getAsJsonArray("packs").get(0).getAsJsonObject().get("file").getAsString());
        assertFalse(Files.exists(plan.resolveSibling("delivery-plan.json.part")));
    }

    private Path archive(String fileName, String fingerprint, boolean includeAsset) throws IOException {
        return archive(fileName, fingerprint, includeAsset, false);
    }

    private Path archive(String fileName, String fingerprint, boolean includeAsset, boolean includeEntity) throws IOException {
        Path archive = temporaryDirectory.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{\"format_version\":2,\"header\":{\"uuid\":\"" + fingerprint + "\"}}");
            entry(zip, PackManager.PACK_GENERATION_MARKER,
                    "{\"revision\":\"" + PackManager.PACK_GENERATION_REVISION + "\",\"fingerprint\":\"" + fingerprint + "\"}");
            if (includeAsset) entry(zip, "textures/example/dependency.png", "texture");
            if (includeEntity) entry(zip, "entity/example.entity.json",
                    "{\"minecraft:client_entity\":{\"description\":{\"identifier\":\"example:entity\"}}}");
        }
        return archive;
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String javaArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
