package org.geysermc.hydraulic.entity;

import com.google.auto.service.AutoService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pack module that makes non-vanilla entities visible to Bedrock players.
 *
 * <p>Three parts are needed for a modded entity to render on Bedrock, and this
 * module wires up all of them:</p>
 *
 * <ul>
 *     <li>{@link GeyserDefineEntitiesEvent} registers a Bedrock
 *     {@link CustomEntityDefinition} for every entity type owned by the mod.</li>
 *     <li>{@link ServerSpawnEntityEvent} swaps the default Bedrock definition
 *     for the custom one whenever the Java server spawns a matching entity.</li>
 *     <li>The post processor emits the Bedrock client entity definition and
 *     render controller into the converted resource pack, referencing the
 *     geometry/animations produced by the PackConverter GeckoLib converters.</li>
 * </ul>
 *
 * <p><b>Asset binding is convention-based in this first version:</b> an entity
 * {@code modid:example_mob} is bound to geometry {@code geometry.modid.example_mob}
 * (the identifier the GeckoLib model converter produces for
 * {@code assets/modid/geo/example_mob.geo.json}), texture
 * {@code textures/entity/modid/example_mob} and, when present, every animation in
 * {@code animations/modid.example_mob.animation.json}. Mods that name their
 * assets differently will render with missing geometry until per-mod overrides
 * exist.</p>
 */
@AutoService(PackModule.class)
public class EntityPackModule extends PackModule<EntityPackModule> {
    /**
     * Bedrock entity identifier to the definition registered for it.
     * Shared across mods because identifiers are namespaced and unique.
     */
    private final Map<String, CustomEntityDefinition> definitions = new HashMap<>();

    public EntityPackModule() {
        this.listenOn(GeyserDefineEntitiesEvent.class, this::onDefineEntities);
        this.listenOn(ServerSpawnEntityEvent.class, this::onSpawnEntity);

        this.postProcess(this::postProcess);
    }

    private void onDefineEntities(@NotNull PackEventContext<GeyserDefineEntitiesEvent, EntityPackModule> context) {
        GeyserDefineEntitiesEvent event = context.event();

        for (EntityType<?> type : context.entityTypes()) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key == null) continue;

            String bedrockIdentifier = key.getNamespace() + ":" + key.getPath();
            try {
                CustomEntityDefinition definition = CustomEntityDefinition.of(bedrockIdentifier);
                event.register(definition);
                this.definitions.put(bedrockIdentifier, definition);

                context.logger().info("Registered custom entity {}", bedrockIdentifier);
            } catch (Exception e) {
                context.logger().warn("Failed to register custom entity {}: {}", bedrockIdentifier, e.getMessage());
            }
        }
    }

    private void onSpawnEntity(@NotNull PackEventContext<ServerSpawnEntityEvent, EntityPackModule> context) {
        ServerSpawnEntityEvent event = context.event();

        CustomEntityDefinition definition = this.definitions.get(event.entityType().identifier().toString());
        if (definition != null) {
            event.definition(definition);
        }
    }

    private void postProcess(@NotNull PackPostProcessContext<EntityPackModule> context) {
        BedrockResourcePack pack = context.bedrockResourcePack();

        for (EntityType<?> type : context.entityTypes()) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key == null) continue;

            String namespace = key.getNamespace();
            String path = key.getPath();

            pack.addExtraFile(
                    clientEntity(namespace, path, pack),
                    "entity/" + namespace + "." + path + ".entity.json");
            pack.addExtraFile(
                    renderController(namespace, path),
                    "render_controllers/" + namespace + "." + path + ".render_controllers.json");
        }
    }

    /**
     * Builds the Bedrock client entity definition. Animations, when a converted
     * GeckoLib animation file matches the entity name, are attached by their own
     * names so simple walk/idle setups play without extra wiring.
     */
    private JsonObject clientEntity(String namespace, String path, BedrockResourcePack pack) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", namespace + ":" + path);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", "textures/entity/" + namespace + "/" + path);
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", "geometry." + namespace + "." + path);
        description.add("geometry", geometry);

        JsonObject animations = collectAnimations(namespace, path, pack);
        if (animations.size() > 0) {
            description.add("animations", animations);
        }

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render." + namespace + "." + path);
        description.add("render_controllers", renderControllers);

        JsonObject clientEntity = new JsonObject();
        clientEntity.addProperty("format_version", "1.10.0");
        clientEntity.add("minecraft:client_entity", clientEntityDescription(description));
        return clientEntity;
    }

    private JsonObject clientEntityDescription(JsonObject description) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("description", description);
        return wrapper;
    }

    private JsonObject renderController(String namespace, String path) {
        JsonObject controller = new JsonObject();
        controller.addProperty("geometry", "Geometry.default");

        JsonArray materials = new JsonArray();
        JsonObject materialBinding = new JsonObject();
        materialBinding.addProperty("*", "Material.default");
        materials.add(materialBinding);
        controller.add("materials", materials);

        JsonArray textures = new JsonArray();
        textures.add("Texture.default");
        controller.add("textures", textures);

        JsonObject controllers = new JsonObject();
        controllers.add("controller.render." + namespace + "." + path, controller);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("render_controllers", controllers);
        return root;
    }

    /**
     * Reads back a converted GeckoLib animation file from the pack (written by
     * the GeckoAnimationConverter as {@code animations/<ns>.<name>.animation.json})
     * and maps every animation it declares onto itself.
     */
    private JsonObject collectAnimations(String namespace, String path, BedrockResourcePack pack) {
        JsonObject animations = new JsonObject();

        byte[] converted = pack.extraFiles().get("animations/" + namespace + "." + path + ".animation.json");
        if (converted == null) {
            return animations;
        }

        try {
            JsonElement parsed = JsonParser.parseString(new String(converted, StandardCharsets.UTF_8));
            JsonObject declared = parsed.getAsJsonObject().getAsJsonObject("animations");
            if (declared == null) return animations;

            for (String name : declared.keySet()) {
                animations.addProperty(name, name);
            }
        } catch (Exception ignored) {
            // A malformed converted animation file must not break pack generation.
        }
        return animations;
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<EntityPackModule> context) {
        return !context.entityTypes().isEmpty();
    }
}
