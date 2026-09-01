package org.geysermc.hydraulic.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackUtilTest {
    @Test
    void packIdentityTracksWholeConversionContext() {
        var firstContext = PackUtil.getContextUUID(java.util.Map.of("a", "1", "b", "2"));
        var reorderedContext = PackUtil.getContextUUID(new java.util.LinkedHashMap<>(java.util.Map.of("b", "2", "a", "1")));
        var changedContext = PackUtil.getContextUUID(java.util.Map.of("a", "1", "b", "3"));

        assertEquals(firstContext, reorderedContext);
        assertNotEquals(PackUtil.getPackUUID("source", firstContext.toString(), "26.2", "23"),
                PackUtil.getPackUUID("source", changedContext.toString(), "26.2", "23"));
        assertNotEquals(PackUtil.getPackUUID("source", firstContext.toString(), "26.2", "23"),
                PackUtil.getPackUUID("source", firstContext.toString(), "26.2", "24"));
    }

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

    @Test
    void hashFailureInvalidatesInsteadOfProducingPartialIdentity() {
        Path missing = temporaryDirectory.resolve("missing-root");
        assertThrows(java.io.UncheckedIOException.class, () -> PackUtil.getModUUID(List.of(missing)));
    }
}
