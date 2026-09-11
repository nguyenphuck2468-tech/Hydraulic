package org.geysermc.hydraulic.pack;

import org.geysermc.pack.converter.PackConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class EntityPackPackagingTest {
    @TempDir Path directory;

    @Test void zipEntriesUsePortableNamesForGeometryAndChecksumIndex() throws Exception {
        Path root = directory.resolve("root");
        Path geometry = root.resolve("models/entity/geometry.fixture.geo.json");
        Files.createDirectories(geometry.getParent());
        Files.writeString(geometry, "geometry");
        Path archive = directory.resolve("output.zip");
        new PackPackager().pack(new PackConverter(), root, archive, new PackLogListener(LoggerFactory.getLogger(getClass())));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("models/entity/geometry.fixture.geo.json"));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("\\")));
        }
    }

    @Test void outputFailurePropagatesRatherThanReportingPackagingSuccess() throws Exception {
        Path root = Files.createDirectories(directory.resolve("root"));
        Files.writeString(root.resolve("geometry.json"), "geometry");
        Path blocked = Files.createDirectories(directory.resolve("blocked.zip"));
        assertThrows(IOException.class, () -> new PackPackager().pack(new PackConverter(), root, blocked,
                new PackLogListener(LoggerFactory.getLogger(getClass()))));
    }
}
