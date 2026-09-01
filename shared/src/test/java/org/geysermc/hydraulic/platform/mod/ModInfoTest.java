package org.geysermc.hydraulic.platform.mod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModInfoTest {
    @TempDir Path temporaryDirectory;

    @Test
    void recoversExactArchiveFromMountedLoaderRoot() throws Exception {
        Path archive = temporaryDirectory.resolve("example.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("assets/example/test.txt"));
            zip.write(1);
            zip.closeEntry();
        }
        try (var fileSystem = FileSystems.newFileSystem(archive, Map.of())) {
            ModInfo mod = new ModInfo("example", "example", "Example", "1", null,
                    List.of(fileSystem.getPath("/")));
            assertEquals(archive.toAbsolutePath(), mod.sourceJar().toAbsolutePath());
        }
    }
}
