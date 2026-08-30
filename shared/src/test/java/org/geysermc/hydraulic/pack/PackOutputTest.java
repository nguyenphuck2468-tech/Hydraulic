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
}
