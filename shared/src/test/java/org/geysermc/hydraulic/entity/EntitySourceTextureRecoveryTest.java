package org.geysermc.hydraulic.entity;

import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntitySourceTextureRecoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesRawEntityTextureAtItsBedrockOutputPath() throws Exception {
        Path source = temporaryDirectory.resolve("assets/example/textures/entity/example/beast.png");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] { 1 });

        Method index = EntityPackModule.class.getDeclaredMethod("sourceTextureAssets", ModInfo.class);
        index.setAccessible(true);
        List<?> textures = (List<?>) index.invoke(null, new ModInfo("example", "example", "Example", "1", null, List.of(temporaryDirectory)));

        assertEquals(1, textures.size());
        Method outputPath = textures.getFirst().getClass().getDeclaredMethod("outputPath");
        assertEquals("textures/entity/example/beast", outputPath.invoke(textures.getFirst()));
    }
}
