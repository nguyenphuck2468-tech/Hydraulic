package org.geysermc.hydraulic.entity;

import com.google.auto.service.AutoService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * Bedrock entity identifier to the Java entity type it mirrors, kept so the
     * spawn handler can apply the Java-side hitbox to the Bedrock entity.
     */
    private final Map<String, EntityType<?>> javaTypes = new HashMap<>();

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
                this.javaTypes.put(bedrockIdentifier, type);

                context.logger().info("Registered custom entity {}", bedrockIdentifier);
            } catch (Exception e) {
                context.logger().warn("Failed to register custom entity {}: {}", bedrockIdentifier, e.getMessage());
            }
        }
    }

    private void onSpawnEntity(@NotNull PackEventContext<ServerSpawnEntityEvent, EntityPackModule> context) {
        ServerSpawnEntityEvent event = context.event();

        String javaId = event.entityType().identifier().toString();
        CustomEntityDefinition definition = this.definitions.get(javaId);
        if (definition != null) {
            event.definition(definition);

            // Carry the Java-side hitbox across so Bedrock collision and name-tag
            // placement match the mod's intent instead of a generic default.
            EntityType<?> javaType = this.javaTypes.get(javaId);
            if (javaType != null) {
                EntityDimensions dimensions = javaType.dimensions();
                event.preSpawnConsumer(entity -> {
                    entity.override(GeyserEntityDataTypes.WIDTH, dimensions.width());
                    entity.override(GeyserEntityDataTypes.HEIGHT, dimensions.height());
                });
            }
        }
    }

    private void postProcess(@NotNull PackPostProcessContext<EntityPackModule> context) {
        BedrockResourcePack pack = context.bedrockResourcePack();

        for (EntityType<?> type : context.entityTypes()) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key == null) continue;

            String namespace = key.getNamespace();
            String path = key.getPath();

            JsonObject animations = collectAnimations(namespace, path, pack);
            AnimationRefs refs = resolveAnimationRefs(animations);

            pack.addExtraFile(
                    clientEntity(namespace, path, refs, pack),
                    "entity/" + namespace + "." + path + ".entity.json");
            pack.addExtraFile(
                    renderController(namespace, path),
                    "render_controllers/" + namespace + "." + path + ".render_controllers.json");
            if (refs != null) {
                pack.addExtraFile(
                        animationController(namespace, path, refs),
                        "animation_controllers/" + namespace + "." + path + ".animation_controllers.json");
            }
        }
    }

    /**
     * Builds the Bedrock client entity definition. When a walk/idle animation
     * could be resolved, the matching animation controller is referenced so the
     * animations actually play; otherwise any converted animations are attached
     * as plain references.
     */
    private JsonObject clientEntity(String namespace, String path, AnimationRefs refs, BedrockResourcePack pack) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", namespace + ":" + path);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", "textures/entity/" + namespace + "/" + path);
        description.add("textures", textures);

        description.add("geometry", collectGeometries(namespace, path, pack));

        if (refs != null) {
            JsonObject animations = new JsonObject();
            if (refs.idle() != null) {
                animations.addProperty("idle", refs.idle());
            }
            if (refs.walk() != null) {
                animations.addProperty("walk", refs.walk());
            }
            description.add("animations", animations);

            JsonArray animationControllers = new JsonArray();
            animationControllers.add(controllerName(namespace, path));
            description.add("animation_controllers", animationControllers);
        }

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render." + namespace + "." + path);
        description.add("render_controllers", renderControllers);

        JsonObject clientEntity = new JsonObject();
        clientEntity.addProperty("format_version", "1.10.0");
        clientEntity.add("minecraft:client_entity", clientEntityDescription(description));
        return clientEntity;
    }

    /**
     * Builds a two-state (idle/walking) animation controller switching on ground
     * speed, the standard vanilla quadruped pattern. When only one animation is
     * available it plays unconditionally from a single default state.
     */
    private JsonObject animationController(String namespace, String path, AnimationRefs refs) {
        JsonObject states = new JsonObject();

        if (refs.idle() != null && refs.walk() != null) {
            JsonObject idle = new JsonObject();
            JsonArray idleAnimations = new JsonArray();
            idleAnimations.add("idle");
            idle.add("animations", idleAnimations);
            idle.add("transitions", transition("walking", "query.ground_speed > 0.1"));
            idle.addProperty("blend_transition", 0.2);
            states.add("idle", idle);

            JsonObject walking = new JsonObject();
            JsonArray walkAnimations = new JsonArray();
            walkAnimations.add("walk");
            walking.add("animations", walkAnimations);
            walking.add("transitions", transition("idle", "query.ground_speed <= 0.1"));
            walking.addProperty("blend_transition", 0.2);
            states.add("walking", walking);
        } else {
            String only = refs.idle() != null ? refs.idle() : refs.walk();

            JsonObject single = new JsonObject();
            JsonArray singleAnimations = new JsonArray();
            singleAnimations.add(only);
            single.add("animations", singleAnimations);
            states.add("default", single);
        }

        JsonObject controller = new JsonObject();
        controller.addProperty("initial_state", refs.idle() != null && refs.walk() != null ? "idle" : "default");
        controller.add("states", states);

        JsonObject controllers = new JsonObject();
        controllers.add(controllerName(namespace, path), controller);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("animation_controllers", controllers);
        return root;
    }

    private static JsonArray transition(String target, String condition) {
        JsonObject mapping = new JsonObject();
        mapping.addProperty(target, condition);

        JsonArray transitions = new JsonArray();
        transitions.add(mapping);
        return transitions;
    }

    private static String controllerName(String namespace, String path) {
        return "controller.animation." + namespace + "." + path + ".move";
    }

    /**
     * Resolves which of the entity's converted animations drive idle and walk.
     * Names are matched by their conventional suffixes; a lone animation of
     * unrecognized name is treated as a constant idle so simple mobs still move.
     *
     * @return the resolved pair, or {@code null} when nothing can be bound
     */
    private static AnimationRefs resolveAnimationRefs(JsonObject animations) {
        if (animations.size() == 0) {
            return null;
        }

        String idle = null;
        String walk = null;
        List<String> names = new ArrayList<>(animations.keySet());
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (idle == null && (lower.endsWith(".idle") || lower.endsWith(".base") || lower.endsWith(".ambient"))) {
                idle = name;
            } else if (walk == null && (lower.endsWith(".walk") || lower.endsWith(".walking") || lower.endsWith(".move") || lower.endsWith(".fly") || lower.endsWith(".flying") || lower.endsWith(".swim"))) {
                walk = name;
            }
        }

        if (idle == null && walk == null) {
            if (names.size() == 1) {
                return new AnimationRefs(names.get(0), null);
            }
            return null;
        }
        return new AnimationRefs(idle, walk);
    }

    private record AnimationRefs(String idle, String walk) {
    }

    private JsonObject clientEntityDescription(JsonObject description) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("description", description);
        return wrapper;
    }

    /**
     * Binds the entity's own converted geometry as {@code default} plus every
     * sibling part model ({@code <path>_head.geo.json} and friends) as a named
     * geometry variant, so multi-file mods expose all their parts for render
     * controllers to switch between.
     */
    private JsonObject collectGeometries(String namespace, String path, BedrockResourcePack pack) {
        JsonObject geometries = new JsonObject();
        geometries.addProperty("default", "geometry." + namespace + "." + path);

        if (pack.entityModels() == null) {
            return geometries;
        }

        String locationPrefix = "models/entity/" + namespace + ".";
        for (String location : pack.entityModels().keySet()) {
            if (!location.startsWith(locationPrefix) || !location.endsWith(".json")) continue;

            String base = location.substring(locationPrefix.length(), location.length() - ".json".length());
            if (base.equals(path) || !base.startsWith(path + "_")) continue;

            geometries.addProperty(base.substring(path.length() + 1), "geometry." + namespace + "." + base);
        }
        return geometries;
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
