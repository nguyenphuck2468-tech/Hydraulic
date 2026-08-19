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

    /**
     * Bedrock has problematic clients/platforms that fail to load resource-pack files with very
     * long paths. Keep generated Hydraulic paths below the conservative 80-character threshold.
     */
    public static String limitPathLength(@NotNull String path, int maxLength) {
        if (path.length() <= maxLength) {
            return path;
        }

        final String hash = Integer.toHexString(path.hashCode())
                + Integer.toHexString((path + "|hydraulic").hashCode());
        final int slash = path.lastIndexOf('/');
        final String filename = slash >= 0 ? path.substring(slash + 1) : path;
        final int dot = filename.lastIndexOf('.');
        final String extension = dot > 0 ? filename.substring(dot) : "";
        final String stem = dot > 0 ? filename.substring(0, dot) : filename;
        final String readableStem = stem.length() > 10 ? stem.substring(0, 10) : stem;

        String result = "textures/" + hash + "/" + readableStem + extension;
        if (result.length() <= maxLength) {
            return result;
        }

        int allowedStem = Math.max(1, maxLength - ("textures/" + hash + "/" + extension).length());
        return "textures/" + hash + "/" + readableStem.substring(0, Math.min(readableStem.length(), allowedStem)) + extension;
    }

    public static String getTextureName(@NotNull String modelName) {
        // Sometimes things end up in the minecraft namespace when they shouldn't.
        // EG: betternether:wall_mushroom_red referencing both its own and vanilla textures.
        if (modelName.startsWith(Key.MINECRAFT_NAMESPACE)) {
            String modelValue = modelName.split(":")[1];

            // Need to use the Bedrock value for vanilla textures
            JsonMappings mappings = JsonMappings.getMapping("textures");
            if (mappings != null) {
                String output = mappings.map(modelValue).getFirst();

                String value = output.substring(output.indexOf("/") + 1);

                if (modelValue.equals(output)) {
                    return value;
                }

                return Constants.MOD_ID + ":" + value;
            }

            return modelValue.substring(modelValue.indexOf("/") + 1);
        }

        return modelName.replace("block/", "").replace("item/", "");
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
