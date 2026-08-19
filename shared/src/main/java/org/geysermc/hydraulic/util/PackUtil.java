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

    public static UUID getModUUID(Collection<Path> modRoots) {
        final HashingOutputStream hos = new HashingOutputStream(Hashing.murmur3_128(), OutputStream.nullOutputStream());
        try (Stream<Path> stream = modRoots.parallelStream()) {
            stream.flatMap(IOUtil.uncheckFunction(Files::walk)).sorted().forEachOrdered(p -> {
                try {
                    hos.write(p.toString().getBytes(StandardCharsets.UTF_8));
                    if (Files.isRegularFile(p)) {
                        Files.copy(p, hos);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to hash {}", p, e);
                }
            });
        }
        return UUID.nameUUIDFromBytes(hos.hash().asBytes());
    }
}
