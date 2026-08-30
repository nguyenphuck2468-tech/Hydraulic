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
    void partArchiveIsNeverReusedOrRegistered() throws IOException {
        String fingerprint = "e5d87397-0931-4609-a849-01b2da7a3529";
        Path archive = archive("complete.mcpack.part", fingerprint, true);

        assertTrue(PackListener.needsConversion(archive, fingerprint));
    }

    private Path archive(String fileName, String fingerprint, boolean includeAsset) throws IOException {
        Path archive = temporaryDirectory.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip, "manifest.json", "{\"format_version\":2,\"header\":{\"uuid\":\"" + fingerprint + "\"}}");
            entry(zip, PackManager.PACK_GENERATION_MARKER,
                    "{\"revision\":\"" + PackManager.PACK_GENERATION_REVISION + "\",\"fingerprint\":\"" + fingerprint + "\"}");
            if (includeAsset) entry(zip, "textures/example/dependency.png", "texture");
        }
        return archive;
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
