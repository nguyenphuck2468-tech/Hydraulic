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
 * Packs the pack into a zip file unless conversion emitted metadata only.
 * <p>
 * Delegates content conversion to PackConverter without rewriting generated files:
 * models, animations and texture references must retain the paths emitted by the
 * converter so gameplay mods can reference their assets reliably.
 */
public class PackPackager implements PackageHandler {
    private boolean metadataOnly;

    @Override
    public void pack(@NotNull PackConverter converter, @NotNull Path path, @NotNull Path outputPath, @NotNull LogListener logger) throws IOException {
        metadataOnly = !hasPackAssets(path);
        if (metadataOnly) return;

        PackPathRewriter.Result rewritten = PackPathRewriter.rewrite(path);
        if (rewritten.collision()) {
            logger.warn("Skipped path shortening because its deterministic target already exists");
        } else if (rewritten.rewritten() > 0) {
            logger.info("Shortened " + rewritten.rewritten() + " Bedrock-unsafe asset path(s) with updated JSON references");
        }

        PackageHandler.ZIP.pack(converter, path, outputPath, logger);
    }

    boolean metadataOnly() {
        return metadataOnly;
    }

    static boolean hasPackAssets(Path path) throws IOException {
        try (Stream<Path> walker = Files.walk(path)) {
            return walker.filter(Files::isRegularFile)
                    .map(path::relativize)
                    .map(file -> file.toString().replace('\\', '/'))
                    .anyMatch(file -> !PackArchiveValidator.isMetadataFile(file));
        }
    }
}
