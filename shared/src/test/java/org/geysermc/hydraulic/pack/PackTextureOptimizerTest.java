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

    @Test
    void reusesContentAddressedDownscaleAcrossPacks() throws Exception {
        Path first = temporaryDirectory.resolve("first/textures/entity/example.png");
        Path second = temporaryDirectory.resolve("second/textures/entity/example.png");
        Path cache = temporaryDirectory.resolve("cache");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        BufferedImage source = new BufferedImage(512, 1024, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(source, "png", first.toFile());
        ImageIO.write(source, "png", second.toFile());

        var firstResult = PackTextureOptimizer.optimize(temporaryDirectory.resolve("first"), PackProfile.LITE, cache);
        var secondResult = PackTextureOptimizer.optimize(temporaryDirectory.resolve("second"), PackProfile.LITE, cache);

        assertEquals(0, firstResult.cacheHits());
        assertEquals(1, secondResult.cacheHits());
        assertEquals(128, ImageIO.read(second.toFile()).getWidth());
    }
}
