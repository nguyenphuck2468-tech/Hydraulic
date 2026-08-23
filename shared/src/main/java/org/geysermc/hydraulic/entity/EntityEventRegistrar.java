package org.geysermc.hydraulic.entity;

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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
