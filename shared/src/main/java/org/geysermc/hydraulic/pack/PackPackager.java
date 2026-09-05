package org.geysermc.hydraulic.pack;

import com.mojang.logging.LogUtils;
import org.geysermc.pack.converter.PackConverter;
import org.geysermc.pack.converter.PackageHandler;
import org.geysermc.pack.converter.util.LogListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

// TODO Probably just do an empty pack check in PackConverter?
/**
 * Packs the pack into a zip file unless its empty.
 * <p>
 * Passes over to {@link PackageHandler#ZIP} after its finished its checks.
 */
public class PackPackager implements PackageHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void pack(@NotNull PackConverter converter, @NotNull Path path, @NotNull Path outputPath, @NotNull LogListener logger) throws IOException {
        boolean notEmptyPack = true;
        try (Stream<Path> walker = Files.walk(path)) {
            // Check if there is a file other than manifest.json and pack_icon.png
            notEmptyPack = walker.filter(Files::isRegularFile).anyMatch(filePath -> !(filePath.getFileName().toString().equals("manifest.json") || filePath.getFileName().toString().equals("pack_icon.png")));
        } catch (IOException exception) {
            // GĐ5.5 audit: previously swallowed silently, so a transient
            // I/O error during directory walk (e.g. permission change,
            // disappearing symlink) would leave notEmptyPack at its
            // initial value of true and produce an empty .mcpack with
            // no log entry. Log at debug since the operator is unlikely
            // to act on this alone; the subsequent ZIP.pack() failure
            // (if any) is logged separately.
            LOGGER.debug("Failed to walk pack directory {} to check for emptiness; assuming non-empty", path, exception);
        }

        // Ignore empty packs
        if (!notEmptyPack) {
            return;
        }

        PackageHandler.ZIP.pack(converter, path, outputPath, logger);
    }
}
