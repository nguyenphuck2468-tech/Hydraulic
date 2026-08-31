package org.geysermc.hydraulic.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IOUtilTest {
    @TempDir
    Path directory;

    @Test
    void rejectsFilesBeyondTheDeclaredReadBudget() throws IOException {
        Path file = directory.resolve("input.json");
        Files.writeString(file, "12345");

        assertEquals("12345", IOUtil.readString(file, StandardCharsets.UTF_8, 5));
        assertThrows(IOException.class, () -> IOUtil.readString(file, StandardCharsets.UTF_8, 4));
    }
}
