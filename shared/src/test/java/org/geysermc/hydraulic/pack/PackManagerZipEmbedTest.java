package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PackManagerZipEmbedTest {
    @Test
    void embedEntryIntoZipPreservesExistingEntriesAndAddsNewOne(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("pack.mcpack");
        byte[] originalManifest = "{\"format_version\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] originalTexture = new byte[]{(byte) 0x89, 'P', 'N', 'G'}; // fake PNG header

        try (ZipOutputStream writer = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry manifest = new ZipEntry("manifest.json");
            manifest.setSize(originalManifest.length);
            writer.putNextEntry(manifest);
            writer.write(originalManifest);
            writer.closeEntry();
            ZipEntry texture = new ZipEntry("textures/foo.png");
            texture.setSize(originalTexture.length);
            writer.putNextEntry(texture);
            writer.write(originalTexture);
            writer.closeEntry();
        }

        byte[] provenanceBody = "{\"schema_version\":1}".getBytes(StandardCharsets.UTF_8);
        PackManager.embedEntryIntoZip(archive, "hydraulic/provenance.json", provenanceBody);

        try (ZipFile reader = new ZipFile(archive.toFile())) {
            ZipEntry manifestEntry = reader.getEntry("manifest.json");
            assertNotNull(manifestEntry, "Original manifest.json must survive the rewrite");
            assertArrayEquals(originalManifest, readAll(reader.getInputStream(manifestEntry)),
                    "Original manifest bytes must be byte-for-byte unchanged");

            ZipEntry textureEntry = reader.getEntry("textures/foo.png");
            assertNotNull(textureEntry, "Original textures/foo.png must survive the rewrite");
            assertArrayEquals(originalTexture, readAll(reader.getInputStream(textureEntry)),
                    "Original texture bytes must be byte-for-byte unchanged");

            ZipEntry provenanceEntry = reader.getEntry("hydraulic/provenance.json");
            assertNotNull(provenanceEntry, "New provenance entry must be present");
            assertArrayEquals(provenanceBody, readAll(reader.getInputStream(provenanceEntry)),
                    "Provenance bytes must be byte-for-byte unchanged");
        }
    }

    @Test
    void embedEntryIntoZipOverwritesExistingEntryOfSameName(@TempDir Path tmp) throws Exception {
        Path archive = tmp.resolve("pack.mcpack");
        try (ZipOutputStream writer = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry first = new ZipEntry("hydraulic/provenance.json");
            first.setSize(3);
            writer.putNextEntry(first);
            writer.write("OLD".getBytes(StandardCharsets.UTF_8));
            writer.closeEntry();
        }

        byte[] updated = "{\"schema_version\":1,\"mod_id\":\"alexsmobs\"}".getBytes(StandardCharsets.UTF_8);
        PackManager.embedEntryIntoZip(archive, "hydraulic/provenance.json", updated);

        try (ZipFile reader = new ZipFile(archive.toFile())) {
            ZipEntry entry = reader.getEntry("hydraulic/provenance.json");
            assertNotNull(entry);
            assertArrayEquals(updated, readAll(reader.getInputStream(entry)),
                    "Re-embedding must overwrite, not duplicate");
        }
    }

    private static byte[] readAll(InputStream stream) throws java.io.IOException {
        try (InputStream source = stream) {
            return source.readAllBytes();
        }
    }
}