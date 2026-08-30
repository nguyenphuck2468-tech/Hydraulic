package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackPackagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recognizesOnlyManifestIconAndGenerationMarkerAsMetadata() throws IOException {
        Files.writeString(temporaryDirectory.resolve("manifest.json"), "{}");
        Files.writeString(temporaryDirectory.resolve("pack_icon.png"), "icon");
        Files.writeString(temporaryDirectory.resolve(PackManager.PACK_GENERATION_MARKER), "{}");

        assertFalse(PackPackager.hasPackAssets(temporaryDirectory));
    }

    @Test
    void preservesModelAndTextureDependencyOnlyPacks() throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("textures/example"));
        Files.writeString(temporaryDirectory.resolve("textures/example/dependency.png"), "texture");

        assertTrue(PackPackager.hasPackAssets(temporaryDirectory));
    }
}
