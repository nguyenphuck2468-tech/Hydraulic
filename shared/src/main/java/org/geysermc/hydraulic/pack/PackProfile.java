package org.geysermc.hydraulic.pack;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Client resource budgets selected by config/hydraulic/pack-profile.txt. */
public enum PackProfile {
    LITE(256, 64_000_000L),
    BALANCED(1024, 256_000_000L),
    FULL(Integer.MAX_VALUE, Long.MAX_VALUE);

    private final int maxTextureEdge;
    private final long maxTexturePixels;

    PackProfile(int maxTextureEdge, long maxTexturePixels) {
        this.maxTextureEdge = maxTextureEdge;
        this.maxTexturePixels = maxTexturePixels;
    }

    int maxTextureEdge() {
        return maxTextureEdge;
    }

    long maxTexturePixels() {
        return maxTexturePixels;
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    static PackProfile load(Path dataFolder, Logger logger) {
        Path path = dataFolder.resolve("pack-profile.txt");
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(path)) PackManager.writeStringAtomically(path, BALANCED.id() + System.lineSeparator());
            return valueOf(Files.readString(path, StandardCharsets.UTF_8).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            logger.warn("Unknown Hydraulic pack profile in {}; using balanced (expected lite, balanced, or full)", path);
        } catch (IOException exception) {
            logger.warn("Could not read Hydraulic pack profile {}; using balanced", path, exception);
        }
        return BALANCED;
    }
}
