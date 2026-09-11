package org.geysermc.hydraulic.config;

import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.impl.IdentifierImpl;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Explicit operator input. Neither identifiers nor model classpaths are inferred. */
@ConfigSerializable
public final class EntityBinding {
    @Setting("modId") public String modId = "";
    @Setting("fullyQualifiedClassName") public String fullyQualifiedClassName = "";
    @Setting("route") public String route = "";
    @Setting("geometryIdentifier") public String geometryIdentifier = "";
    @Setting("bedrockEntityIdentifier") public String bedrockEntityIdentifier = "";
    @Setting("runtimeClasspath") public List<String> runtimeClasspath = List.of();
    @Setting("deadlineMillis") public long deadlineMillis = 30_000;

    public void validate() {
        require(modId != null && modId.matches("[a-z0-9_.-]+"), "Missing/invalid modId");
        require(fullyQualifiedClassName != null && fullyQualifiedClassName.matches("[A-Za-z_$][A-Za-z0-9_$.]*"),
                "Missing/invalid fullyQualifiedClassName");
        require("citadel".equals(route) || "layer".equals(route), "route must be citadel or layer");
        require(geometryIdentifier != null && geometryIdentifier.matches("geometry\\.[a-z0-9_.]+"),
                "Missing/invalid geometryIdentifier");
        require(bedrockEntityIdentifier != null && !bedrockEntityIdentifier.isBlank(), "Missing bedrockEntityIdentifier");
        // Real Geyser parsing/namespace rejection, including its original exception text.
        // Validation can run before the Geyser provider registry has initialized.
        var identifier = IdentifierImpl.parse(bedrockEntityIdentifier);
        if (identifier.vanilla()) CustomEntityDefinition.of(identifier);
        require(bedrockEntityIdentifier.contains(":"), "bedrockEntityIdentifier requires namespace:path");
        require(runtimeClasspath != null && !runtimeClasspath.isEmpty(), "Missing runtimeClasspath");
        for (String entry : runtimeClasspath) {
            require(entry != null && !entry.isBlank(), "Empty runtimeClasspath entry");
            Path path = Path.of(entry);
            require(path.isAbsolute() && Files.isRegularFile(path) && Files.isReadable(path),
                    "runtimeClasspath must contain readable absolute file paths: " + entry);
        }
        require(deadlineMillis >= 1 && deadlineMillis <= 120_000, "deadlineMillis must be between 1 and 120000");
    }

    public List<Path> classpath() {
        return runtimeClasspath.stream().map(Path::of).toList();
    }

    public String label() {
        return modId + "/" + fullyQualifiedClassName + " (" + bedrockEntityIdentifier + ")";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
