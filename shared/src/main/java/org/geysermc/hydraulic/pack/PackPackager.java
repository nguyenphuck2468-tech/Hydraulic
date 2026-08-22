package org.geysermc.hydraulic.pack;

import org.geysermc.pack.converter.PackConverter;
import org.geysermc.pack.converter.PackageHandler;
import org.geysermc.pack.converter.util.LogListener;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Packs the pack into a zip file unless it is empty.
 * <p>
 * Delegates content conversion to PackConverter without rewriting generated files:
 * models, animations and texture references must retain the paths emitted by the
 * converter so gameplay mods can reference their assets reliably.
 */
public class PackPackager implements PackageHandler {
    @Override
    public void pack(@NotNull PackConverter converter, @NotNull Path path, @NotNull Path outputPath, @NotNull LogListener logger) throws IOException {
        boolean notEmptyPack = true;
        try (Stream<Path> walker = Files.walk(path)) {
            notEmptyPack = walker.filter(Files::isRegularFile)
                    .anyMatch(filePath -> !("manifest.json".equals(filePath.getFileName().toString())
                            || "pack_icon.png".equals(filePath.getFileName().toString())));
        } catch (IOException ignored) {
            // Let the ZIP handler report the actionable error for an unreadable pack.
        }

        if (!notEmptyPack) {
            return;
        }

        PackageHandler.ZIP.pack(converter, path, outputPath, logger);
    }
}
