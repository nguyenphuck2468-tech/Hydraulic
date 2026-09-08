package org.geysermc.hydraulic.util;

import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PackGenerationTest {
    @Test
    void geometryFixInvalidatesLegacyCacheEvenWithUnchangedModInput() {
        UUID legacy = UUID.nameUUIDFromBytes(Hashing.murmur3_128().hashBytes(new byte[0]).asBytes());
        assertNotEquals(legacy, PackUtil.getModUUID(List.of()));
    }

    @Test
    void unchangedInputReusesGenerationAndChangedResourceInvalidatesIt(@TempDir Path root) throws Exception {
        Path model = root.resolve("model.json");
        Files.writeString(model, "{\"elements\":[]}");
        UUID first = PackUtil.getModUUID(List.of(root));
        assertEquals(first, PackUtil.getModUUID(List.of(root)));
        Files.writeString(model, "{\"elements\":[{}]}");
        assertNotEquals(first, PackUtil.getModUUID(List.of(root)));
    }
}
