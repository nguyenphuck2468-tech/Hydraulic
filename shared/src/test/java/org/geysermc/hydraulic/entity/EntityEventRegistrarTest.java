package org.geysermc.hydraulic.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityEventRegistrarTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresOnlyEntitiesPresentInReusedPack() throws Exception {
        Path pack = temporaryDirectory.resolve("cached.mcpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            entry(zip, "entity/alexsmobs.grizzly_bear.entity.json",
                    "{\"minecraft:client_entity\":{\"description\":{\"identifier\":\"alexsmobs:grizzly_bear\"}}}");
            entry(zip, "entity/broken.entity.json", "{");
            entry(zip, "manifest.json", "{}");
        }

        EntityEventRegistrar.clearPackBackedForTest();
        assertEquals(1, EntityEventRegistrar.restorePackBacked(pack));
        assertTrue(EntityEventRegistrar.isPackBacked("alexsmobs:grizzly_bear"));
        assertEquals(0, EntityEventRegistrar.restorePackBacked(pack));
    }

    private static void entry(ZipOutputStream zip, String name, String contents) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
