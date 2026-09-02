package org.geysermc.hydraulic.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-mod ceiling and overall startup budget for Bedrock pack conversion.
 *
 * <p>The total budget stays as a hard cap so the server-start watchdog is
 * respected. Within that ceiling, every mod has its own per-mod budget; a mod
 * that exceeds it is cancelled immediately so the remaining mods still have a
 * chance to convert.</p>
 *
 * <p>Configuration is read from (highest priority first):</p>
 * <ol>
 *   <li>JVM system properties:
 *     <ul>
 *       <li>{@code hydraulic.budget.totalMs} (default 35000)</li>
 *       <li>{@code hydraulic.budget.perModMs} (default 5000)</li>
 *       <li>{@code hydraulic.budget.perModMs.<modid>} (default 0 — falls back to perModMs)</li>
 *     </ul>
 *   </li>
 *   <li>{@code config/hydraulic/budget.json} with the same fields under a
 *       {@code budget} object. Missing fields fall back to defaults.</li>
 * </ol>
 */
public final class ConversionBudget {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Default total startup budget in milliseconds. Matches the previous hard-coded cap. */
    public static final long DEFAULT_TOTAL_MS = 35_000;
    /** Default per-mod ceiling in milliseconds. Chosen to be small enough that one heavy mod
     *  (Alex's Mobs, Biomes O' Plenty) cannot starve the remaining mods of the 35-second cap. */
    public static final long DEFAULT_PER_MOD_MS = 5_000;

    private static final String SYSTEM_TOTAL = "hydraulic.budget.totalMs";
    private static final String SYSTEM_PER_MOD_PREFIX = "hydraulic.budget.perModMs";
    private static final String JSON_FILE_NAME = "budget.json";

    private final long totalMs;
    private final long defaultPerModMs;

    private ConversionBudget(long totalMs, long defaultPerModMs) {
        this.totalMs = totalMs;
        this.defaultPerModMs = defaultPerModMs;
    }

    /** Total startup budget in milliseconds (hard cap, was the previous 35_000 constant). */
    public long totalMs() {
        return totalMs;
    }

    /** Default ceiling for a mod that does not override it. */
    public long defaultPerModMs() {
        return defaultPerModMs;
    }

    /**
     * Ceiling in milliseconds for {@code modId}. System property first
     * ({@code hydraulic.budget.perModMs.<modid>}), then the configured
     * default, then {@link #DEFAULT_PER_MOD_MS}.
     */
    public long ceilingFor(String modId) {
        if (modId == null) return defaultPerModMs;
        String property = SYSTEM_PER_MOD_PREFIX + "." + modId;
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed > 0) return parsed;
                LOGGER.warn("Ignoring non-positive {}={}; falling back to default", property, value);
            } catch (NumberFormatException exception) {
                LOGGER.warn("Ignoring invalid {}={}; expected a positive integer (milliseconds)", property, value);
            }
        }
        return defaultPerModMs;
    }

    /**
     * Resolve configuration from system properties + an optional JSON file.
     *
     * @param configFile location of {@code budget.json}; may be {@code null} if the
     *                   host did not provision a config file (system properties still apply).
     */
    public static ConversionBudget load(Path configFile) {
        long total = DEFAULT_TOTAL_MS;
        long perMod = DEFAULT_PER_MOD_MS;

        // JSON file is the persistent baseline for sysadmins.
        if (configFile != null && Files.isRegularFile(configFile)) {
            try {
                String contents = Files.readString(configFile, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(contents).getAsJsonObject();
                if (root.has("totalMs")) {
                    long candidate = root.get("totalMs").getAsLong();
                    if (candidate > 0) total = candidate;
                }
                if (root.has("perModMs")) {
                    long candidate = root.get("perModMs").getAsLong();
                    if (candidate > 0) perMod = candidate;
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Could not read {}; falling back to system properties and defaults", configFile, exception);
            }
        }

        // System properties win, so operators can override without editing the file.
        total = readLongMs(SYSTEM_TOTAL, total);
        perMod = readLongMs(SYSTEM_PER_MOD_PREFIX, perMod);

        return new ConversionBudget(total, perMod);
    }

    private static long readLongMs(String key, long fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                LOGGER.warn("Ignoring non-positive {}={}; falling back to {}", key, value, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            LOGGER.warn("Ignoring invalid {}={}; expected a positive integer (milliseconds)", key, value);
            return fallback;
        }
    }

    /** Default config file path: {@code <hydraulic data folder>/budget.json}. */
    public static Path defaultConfigFile(Path dataFolder) {
        return dataFolder.resolve(JSON_FILE_NAME);
    }
}