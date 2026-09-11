package org.geysermc.hydraulic.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class EntityBindingConfigTest {
    @TempDir Path directory;

    String entry(String identifier) throws Exception {
        Path cp = directory.resolve("runtime.jar");
        Files.write(cp, new byte[]{1});
        return """
              - modId: fixture
                fullyQualifiedClassName: fixture.Model
                route: layer
                geometryIdentifier: geometry.%s
                bedrockEntityIdentifier: '%s'
                runtimeClasspath: ['%s']
            """.formatted(identifier.replace(':', '.'), identifier, cp.toString().replace("\\", "/"));
    }

    HydraulicConfig load(String entries, ArrayList<String> errors) throws Exception {
        Path yaml = directory.resolve("config.yml");
        Files.writeString(yaml, "config-version: 1\nentity-bindings:\n" + entries);
        return ConfigLoader.loadConfig(yaml.toFile(), errors::add);
    }

    @Test void validYamlPreservesIndependentIdentifiersAndDefaultDeadline() throws Exception {
        var errors = new ArrayList<String>();
        var config = load(entry("fixture:bird"), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        var binding = config.entityBindings().getFirst();
        assertEquals("geometry.fixture.bird", binding.geometryIdentifier);
        assertEquals("fixture:bird", binding.bedrockEntityIdentifier);
        assertEquals(30_000, binding.deadlineMillis);
    }

    @Test void duplicatesRejectBothAndNameBothYamlLocations() throws Exception {
        var errors = new ArrayList<String>();
        var config = load(entry("fixture:bird") + entry("fixture:bird") + entry("fixture:other"), errors);
        assertEquals(1, config.entityBindings().size());
        assertTrue(errors.stream().anyMatch(s -> s.contains("entity-bindings[0]") && s.contains("entity-bindings[1]")), errors.toString());
    }

    @Test void missingFieldAndWrongDeadlineTypeDoNotHideValidSibling() throws Exception {
        var errors = new ArrayList<String>();
        String invalid = entry("fixture:missing").replace("fullyQualifiedClassName: fixture.Model", "unused: ignored");
        var config = load(invalid + entry("fixture:bad_type") + "    deadlineMillis: potato\n" + entry("fixture:ok"), errors);
        assertEquals(1, config.entityBindings().size());
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(Files.readString(directory.resolve("config.yml")).contains("potato"), "Never erase rejected input");
    }

    @Test void vanillaAndUnqualifiedIdentifiersUseActualGeyserExceptionText() throws Exception {
        for (String identifier : new String[]{"minecraft:bird", "bird"}) {
            var errors = new ArrayList<String>();
            assertTrue(load(entry(identifier), errors).entityBindings().isEmpty());
            assertTrue(errors.getFirst().contains("Custom entity identifiers cannot use the 'minecraft' namespace. Use a non-vanilla namespace, e.g. 'mymod:my_entity'."), errors.toString());
        }
    }

    @Test void pathAndDeadlineBoundsAreExplicitFailures() throws Exception {
        var errors = new ArrayList<String>();
        String invalid = entry("fixture:bad").replace("runtimeClasspath:", "unused:");
        assertTrue(load(invalid, errors).entityBindings().isEmpty());
        assertTrue(errors.getFirst().contains("runtimeClasspath"));
        for (long deadline : new long[]{0, 120001}) {
            errors.clear();
            assertTrue(load(entry("fixture:bad") + "    deadlineMillis: " + deadline + "\n", errors).entityBindings().isEmpty());
            assertTrue(errors.getFirst().contains("deadlineMillis"));
        }
    }
}
