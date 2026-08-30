package org.geysermc.hydraulic.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PackUtilTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cacheFingerprintPreservesResourceRootOverrideOrder() throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        Path file = Path.of("assets/example/textures/item/gadget.png");
        Files.createDirectories(first.resolve(file).getParent());
        Files.createDirectories(second.resolve(file).getParent());
        Files.write(first.resolve(file), new byte[] { 1 });
        Files.write(second.resolve(file), new byte[] { 2 });

        assertNotEquals(PackUtil.getModUUID(List.of(first, second)), PackUtil.getModUUID(List.of(second, first)));
    }
}
