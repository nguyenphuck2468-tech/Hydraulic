package org.geysermc.hydraulic.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.pack.ClientPackTelemetry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Registers custom Bedrock entity definitions for every non-vanilla entity
 * type, and swaps them in on spawn.
 *
 * <p>Geyser fires {@link GeyserDefineEntitiesEvent} early inside its own
 * initialization, before the resource-pack phase that Hydraulic's pack
 * pipeline hooks into, so subscriptions made there always miss it. This
 * registrar instead starts a daemon poller at mod init that subscribes the
 * moment the Geyser api instance exists - well before the event fires.</p>
 *
 * <p>This class is the single registrant of custom entity definitions;
 * {@link EntityPackModule} only writes pack-side files.</p>
 */
public final class EntityEventRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("EntityEventRegistrar");
    private static final long POLL_TIMEOUT_MS = 300_000;
    private static final java.util.Set<String> PACK_BACKED_ENTITIES = ConcurrentHashMap.newKeySet();

    public static void markPackBacked(String entityId) {
        PACK_BACKED_ENTITIES.add(entityId);
    }

    /** Starts a fresh Geyser pack-definition pass (including reloads). */
    public static void resetPackBacked() {
        PACK_BACKED_ENTITIES.clear();
    }

    /** Restores spawn substitutions from the pack that is actually reused. */
    public static int restorePackBacked(Path packPath) {
        int restored = 0;
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("entity/") || !entry.getName().endsWith(".entity.json")
                        || entry.getSize() > 1024 * 1024) continue;
                try (var input = zip.getInputStream(entry)) {
                    byte[] json = input.readNBytes(1024 * 1024 + 1);
                    if (json.length > 1024 * 1024) continue;
                    JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject clientEntity = root.getAsJsonObject("minecraft:client_entity");
                    JsonObject description = clientEntity == null ? null : clientEntity.getAsJsonObject("description");
                    if (description == null || !description.has("identifier")) continue;
                    String identifier = description.get("identifier").getAsString();
                    if (!identifier.isBlank() && PACK_BACKED_ENTITIES.add(identifier)) restored++;
                } catch (RuntimeException ignored) {
                    // Pack validation reports malformed JSON; one broken entry
                    // must not disable every valid cached entity.
                }
            }
        } catch (IOException ignored) {
            return restored;
        }
        return restored;
    }

    static void clearPackBackedForTest() {
        resetPackBacked();
    }

    static boolean isPackBacked(String entityId) {
        return PACK_BACKED_ENTITIES.contains(entityId);
    }

    /**
     * Bedrock entity identifier to the definition registered for it.
     */
    private final Map<String, CustomEntityDefinition> definitions = new ConcurrentHashMap<>();

    /**
     * Bedrock entity identifier to the Java entity type it mirrors, kept so
     * the spawn handler can apply the Java-side hitbox.
     */
    private final Map<String, EntityType<?>> javaTypes = new ConcurrentHashMap<>();

    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    /**
     * Starts the daemon poller that subscribes to Geyser's entity events as
     * soon as the api instance is reachable. Safe to call during mod init.
     */
    public void startPolling() {
        if (!subscribed.compareAndSet(false, true)) {
            return;
        }
        Thread poller = new Thread(this::pollAndSubscribe, "Hydraulic-EarlyEntitySubscribe");
        poller.setDaemon(true);
        poller.start();
    }

    private void pollAndSubscribe() {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (GeyserApi.api() != null && GeyserApi.api().eventBus() != null) {
                    GeyserApi.api().eventBus().register(HydraulicImpl.instance(), this);
                    GeyserApi.api().eventBus().register(HydraulicImpl.instance(), new ClientPackTelemetry());
                    LOGGER.info("Subscribed to Geyser entity events ahead of definition registration");
                    return;
                }
            } catch (Throwable ignored) {
                // Geyser not constructed yet - keep polling.
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOGGER.warn("Gave up waiting for the Geyser api; custom entities will not be registered this boot");
    }

    @Subscribe
    public void onDefineEntities(@NotNull GeyserDefineEntitiesEvent event) {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key == null || key.getNamespace().equals("minecraft")) continue;

            String bedrockIdentifier = key.getNamespace() + ":" + key.getPath();
            try {
                CustomEntityDefinition definition = CustomEntityDefinition.of(bedrockIdentifier);
                event.register(definition);
                this.definitions.put(bedrockIdentifier, definition);
                this.javaTypes.put(bedrockIdentifier, type);
                LOGGER.info("Registered custom entity {}", bedrockIdentifier);
            } catch (Exception e) {
                LOGGER.warn("Failed to register custom entity {}: {}", bedrockIdentifier, e.getMessage());
            }
        }
    }

    @Subscribe
    public void onSpawnEntity(@NotNull ServerSpawnEntityEvent event) {
        String javaId = event.entityType().identifier().toString();
        // Definitions are registered before conversion. Do not replace a Java
        // entity with a Bedrock definition that has no usable texture.
        if (!PACK_BACKED_ENTITIES.contains(javaId)) {
            return;
        }
        CustomEntityDefinition definition = this.definitions.get(javaId);
        if (definition == null) {
            return;
        }

        event.definition(definition);

        // Carry the Java-side hitbox across so Bedrock collision and name-tag
        // placement match the mod's intent instead of a generic default.
        EntityType<?> javaType = this.javaTypes.get(javaId);
        if (javaType != null) {
            EntityDimensions dimensions = javaType.getDimensions();
            event.preSpawnConsumer(entity -> {
                entity.override(GeyserEntityDataTypes.WIDTH, dimensions.width());
                entity.override(GeyserEntityDataTypes.HEIGHT, dimensions.height());
            });
        }
    }
}
