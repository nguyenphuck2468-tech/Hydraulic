package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackTextureOptimizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void liteProfileDownscalesTextureWithoutChangingItsPath() throws Exception {
        Path texture = temporaryDirectory.resolve("textures/entity/example.png");
        Files.createDirectories(texture.getParent());
        ImageIO.write(new BufferedImage(512, 1024, BufferedImage.TYPE_INT_ARGB), "png", texture.toFile());

        var result = PackTextureOptimizer.optimize(temporaryDirectory, PackProfile.LITE);
        BufferedImage optimized = ImageIO.read(texture.toFile());

        assertEquals(1, result.resized());
        assertEquals(128, optimized.getWidth());
        assertEquals(256, optimized.getHeight());
    }
}
