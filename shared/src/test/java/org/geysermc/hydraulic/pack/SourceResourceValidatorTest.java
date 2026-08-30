package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceResourceValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void identifiesOnlyMalformedAssetWithJarRelativeSourceLocation() throws IOException {
        Path bad = temporaryDirectory.resolve("assets/example/models/item/bad.json");
        Path good = temporaryDirectory.resolve("assets/example/lang/en_us.json");
        Files.createDirectories(bad.getParent());
        Files.createDirectories(good.getParent());
        Files.writeString(bad, "{\n  \"broken\":\n}");
        Files.writeString(good, "{\"item.example.good\":\"Good\"}");

        var findings = SourceResourceValidator.validate(temporaryDirectory);

        assertEquals(1, findings.size());
        var finding = findings.getFirst();
        assertEquals("example", finding.namespace());
        assertEquals("assets/example/models/item/bad.json", finding.resource());
        assertEquals("model", finding.type());
        assertTrue(finding.line() > 0);
        assertTrue(finding.column() > 0);
    }
}
