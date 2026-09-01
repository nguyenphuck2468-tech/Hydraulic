package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void assetDetectionDoesNotRenameOrRewriteModelFiles() throws IOException {
        Path model = temporaryDirectory.resolve("models/entity/" + "long_model_name_".repeat(8) + ".geo.json");
        Files.createDirectories(model.getParent());
        byte[] expected = "{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.example.long\"}}]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(model, expected);

        assertTrue(PackPackager.hasPackAssets(temporaryDirectory));
        assertTrue(Files.isRegularFile(model));
        assertArrayEquals(expected, Files.readAllBytes(model));
    }
}
