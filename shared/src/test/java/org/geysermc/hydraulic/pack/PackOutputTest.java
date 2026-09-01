package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PackOutputTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesOnlyTheCompleteStagedArchive() throws IOException {
        Path output = temporaryDirectory.resolve("mod.mcpack");
        Path staged = PackManager.stagedPackPath(output);
        Files.writeString(output, "previous");
        Files.writeString(staged, "complete");

        PackManager.publish(staged, output);

        assertEquals("complete", Files.readString(output));
        assertFalse(Files.exists(staged));
    }

    @Test
    void replacesReportOnlyAfterWritingItsTemporaryFile() throws IOException {
        Path report = temporaryDirectory.resolve("report.json");
        Files.writeString(report, "previous");

        PackManager.writeStringAtomically(report, "complete");

        assertEquals("complete", Files.readString(report));
        assertFalse(Files.exists(report.resolveSibling("report.json.part")));
    }

    @Test
    void quarantinesStructurallyInvalidArchiveWithoutDeletingIt() throws IOException {
        Path output = temporaryDirectory.resolve("alexsmobs.mcpack");
        Path staged = PackManager.stagedPackPath(output);
        Files.writeString(staged, "diagnostic archive");

        Path quarantined = PackManager.quarantineStagedPack(staged, output);

        assertEquals(temporaryDirectory.resolve("alexsmobs.invalid.mcpack"), quarantined);
        assertEquals("diagnostic archive", Files.readString(quarantined));
        assertFalse(Files.exists(staged));
        assertFalse(Files.exists(output));
    }
}
