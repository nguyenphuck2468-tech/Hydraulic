package org.geysermc.hydraulic.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModStorageTest {
    @TempDir Path temporaryDirectory;

    @Test
    void readsLegacyAndVersionedMaterialsAndRejectsUnknownVersions() throws Exception {
        Path file = temporaryDirectory.resolve("materials.json");
        Files.writeString(file, "{\"materials\":{}}");
        assertNotNull(ModStorage.readMaterials(file));

        Files.writeString(file, "{\"version\":1,\"materials\":{\"materials\":{}}}");
        assertNotNull(ModStorage.readMaterials(file));

        Files.writeString(file, "{\"version\":2,\"materials\":{}}");
        assertThrows(java.io.IOException.class, () -> ModStorage.readMaterials(file));
    }
}
