package org.geysermc.hydraulic.util;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.mojang.logging.LogUtils;
import net.kyori.adventure.key.Key;
import org.geysermc.hydraulic.Constants;
import org.geysermc.pack.converter.util.JsonMappings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Utility class for packs.
 */
public class PackUtil {
    protected static final Logger LOGGER = LogUtils.getLogger();

    public static String getTextureName(@NotNull String modelName) {
        // Some mods reference their own texture through the minecraft namespace.
        // Use the Bedrock vanilla mapping when it exists, but preserve the original
        // location when the mapping is absent instead of failing conversion.
        if (!modelName.startsWith(Key.MINECRAFT_NAMESPACE)) {
            return modelName.replace("block/", "").replace("item/", "");
        }

        String modelValue = modelName.substring(Key.MINECRAFT_NAMESPACE.length());
        JsonMappings mappings = JsonMappings.getMapping("textures");
        if (mappings == null) {
            return stripTextureDirectory(modelValue);
        }

        var mapped = mappings.map(modelValue);
        if (mapped == null || mapped.isEmpty()) {
            LOGGER.debug("No Bedrock texture mapping for {}", modelName);
            return stripTextureDirectory(modelValue);
        }

        String output = mapped.getFirst();
        if (output == null || output.isBlank()) {
            return stripTextureDirectory(modelValue);
        }

        String value = output.contains("/") ? output.substring(output.indexOf("/") + 1) : output;
        return modelValue.equals(output) ? value : Constants.MOD_ID + ":" + value;
    }

    private static String stripTextureDirectory(@NotNull String value) {
        int separator = value.indexOf("/");
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    /**
     * Generates a UUID from resource contents and paths relative to each root.
     * Root order is preserved because later resource roots can override files
     * from earlier roots. Absolute paths are deliberately excluded so moving a
     * server or its mods directory does not force needless regeneration.
     */
    public static UUID getModUUID(Collection<Path> modRoots) {
        final HashingOutputStream output = new HashingOutputStream(Hashing.murmur3_128(), OutputStream.nullOutputStream());

        for (Path root : modRoots) {
            final HashingOutputStream rootOutput = new HashingOutputStream(Hashing.murmur3_128(), OutputStream.nullOutputStream());
            try (Stream<Path> stream = Files.walk(root)) {
                stream.sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                        .forEachOrdered(path -> {
                            try {
                                rootOutput.write(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                                rootOutput.write(0);
                                if (Files.isRegularFile(path)) {
                                    rootOutput.write(1);
                                    Files.copy(path, rootOutput);
                                } else {
                                    rootOutput.write(2);
                                }
                                rootOutput.write(0);
                            } catch (IOException exception) {
                                LOGGER.warn("Failed to hash {}", path, exception);
                            }
                        });
            } catch (IOException exception) {
                LOGGER.warn("Failed to walk resource root {}", root, exception);
            }
            try {
                output.write(rootOutput.hash().asBytes());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to hash mod resources", exception);
            }
        }
        return UUID.nameUUIDFromBytes(output.hash().asBytes());
    }
}

