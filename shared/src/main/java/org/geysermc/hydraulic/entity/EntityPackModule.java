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
import net.minecraft.world.phys.shapes.Shapes;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.util.GeoUtil;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pack module that writes the Bedrock client-side files for modded entities:
 * client entity definitions, render controllers and animation controllers,
 * bound to the geometry/animations produced by the PackConverter GeckoLib
 * converters.
 *
 * <p>Registration with Geyser ({@code GeyserDefineEntitiesEvent}) and spawn
 * swapping ({@code ServerSpawnEntityEvent}) live in {@link EntityEventRegistrar},
 * which subscribes early enough to catch Geyser's definition event.</p>
 *
 * <p><b>Asset binding:</b> an entity {@code modid:example_mob} first tries the
 * naming convention (geometry {@code geometry.modid.example_mob}, texture
 * {@code textures/entity/modid/example_mob}, animations from
 * {@code animations/modid.example_mob.animation.json}). When no asset is named
 * exactly after the entity, converted geometries are matched by their inner
 * identifier ({@code geometry.alexsmobs.grizzly_bear}) and textures are
 * searched by file name, which covers the naming style most GeckoLib mods
 * use. Part models ({@code <path>_head.geo.json}) bind as geometry
 * variants.</p>
 */
@AutoService(PackModule.class)
public class EntityPackModule extends PackModule<EntityPackModule> {
    private static final String ANIMATION_MAPPING_FILE = "entity-animations.json";
    private Map<String, AnimationRefs> configuredAnimations = Map.of();
    private Path configuredAnimationsPath;

    public EntityPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<EntityPackModule> context) {
        BedrockResourcePack pack = context.bedrockResourcePack();
        loadAnimationMappings(context);

        for (EntityType<?> type : context.entityTypes()) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key == null) continue;

            String namespace = key.getNamespace();
            String path = key.getPath();
            String texture = findTexture(namespace, path, pack);
            if (texture == null) {
                context.logger().warn("Skipping custom Bedrock entity {}: no converted texture", key);
                continue;
            }

            if (!hasNativeGeometry(namespace, path, pack)) {
                addHitboxGeometry(namespace, path, type.getDimensions(), pack);
                context.logger().warn("Entity {} has no converted geometry; using hitbox fallback without custom animation", key);
                context.report().fallback("entity-hitbox");
            }
            JsonObject animations = collectAnimations(namespace, path, pack);
            AnimationRefs refs = resolveAnimationRefs(key.toString(), animations);

            pack.addExtraFile(
                    clientEntity(namespace, path, type.getDimensions(), texture, animations, refs, pack),
                    "entity/" + namespace + "." + path + ".entity.json");
            pack.addExtraFile(
                    renderController(namespace, path),
                    "render_controllers/" + namespace + "." + path + ".render_controllers.json");
            if (refs != null) {
                pack.addExtraFile(
                        animationController(namespace, path, refs),
                        "animation_controllers/" + namespace + "." + path + ".animation_controllers.json");
            }
            EntityEventRegistrar.markPackBacked(key.toString());
        }
    }

    /**
     * Builds the Bedrock client entity definition. When a walk/idle animation
     * could be resolved, the matching animation controller is referenced so the
     * animations actually play; otherwise any converted animations are attached
     * as plain references.
     */
    private JsonObject clientEntity(String namespace, String path, EntityDimensions dimensions, String texture, JsonObject convertedAnimations,
                                    AnimationRefs refs, BedrockResourcePack pack) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", namespace + ":" + path);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);
        description.add("textures", textures);

        description.add("geometry", collectGeometries(namespace, path, dimensions, pack));

        if (convertedAnimations.size() > 0) {
            JsonObject animations = new JsonObject();
            for (String name : convertedAnimations.keySet()) {
                animations.addProperty(name, name);
            }
            description.add("animations", animations);
        }

        if (refs != null) {
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
    private AnimationRefs resolveAnimationRefs(String entityId, JsonObject animations) {
        if (animations.size() == 0) {
            return null;
        }

        AnimationRefs configured = this.configuredAnimations.get(entityId);
        if (configured != null) {
            AnimationRefs valid = validAnimationRefs(configured, animations);
            if (valid != null) {
                return valid;
            }
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
            return new AnimationRefs(names.getFirst(), null);
        }
        return new AnimationRefs(idle, walk);
    }

    private static AnimationRefs validAnimationRefs(AnimationRefs refs, JsonObject animations) {
        String idle = refs.idle() != null && animations.has(refs.idle()) ? refs.idle() : null;
        String walk = refs.walk() != null && animations.has(refs.walk()) ? refs.walk() : null;
        return idle == null && walk == null ? null : new AnimationRefs(idle, walk);
    }

    /**
     * Loads optional per-entity animation bindings. The generated template is
     * intentionally small: it is a user-editable escape hatch for mod names
     * that do not contain conventional idle/walk suffixes.
     */
    private void loadAnimationMappings(PackPostProcessContext<EntityPackModule> context) {
        Path path = context.hydraulic().dataFolder(Constants.MOD_ID).resolve(ANIMATION_MAPPING_FILE);
        if (path.equals(this.configuredAnimationsPath)) {
            return;
        }
        this.configuredAnimationsPath = path;
        this.configuredAnimations = Map.of();

        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.writeString(path, "{\n  \"entities\": {}\n}\n", StandardCharsets.UTF_8);
                return;
            }

            JsonObject entities = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("entities");
            if (entities == null) {
                return;
            }

            Map<String, AnimationRefs> parsed = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject mapping = entry.getValue().getAsJsonObject();
                String idle = stringOrNull(mapping, "idle");
                String walk = stringOrNull(mapping, "walk");
                if (idle != null || walk != null) {
                    parsed.put(entry.getKey(), new AnimationRefs(idle, walk));
                }
            }
            this.configuredAnimations = Map.copyOf(parsed);
        } catch (Exception e) {
            context.logger().warn("Unable to load entity animation mappings from {}: {}", path, e.getMessage());
        }
    }

    private static String stringOrNull(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
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
     * geometry variant. When no file is named exactly after the entity, the
     * converted geometries' own identifiers are searched for the entity name -
     * most GeckoLib mods name models after the entity inside the file rather
     * than in the file name.
     *
     * <p><b>Fallback:</b> if the mod never ships a static geometry in the
     * first place (Fabric-only mods whose models are generated at runtime
     * with no raw .geo.json on disk - e.g. Alex's Mobs), every lookup
     * here will return null and the entity would otherwise be invisible
     * on Bedrock. To make those mobs at least show up, fall back to
     * vanilla Bedrock geometry references chosen by entity size: small
     * mobs get a humanoid, four-legged mobs get a quadruped skeleton,
     * etc. The model is generic but visible, which is strictly better
     * than a silent no-render.</p>
     */
    private JsonObject collectGeometries(String namespace, String path, EntityDimensions dimensions, BedrockResourcePack pack) {
        JsonObject geometries = new JsonObject();
        String defaultGeometry = null;

        if (pack.entityModels() != null) {
            String locationPrefix = "models/entity/" + namespace + ".";
            String fuzzyMatch = null;

            for (Map.Entry<String, ModelEntity> entry : pack.entityModels().entrySet()) {
                String location = entry.getKey();
                if (!location.startsWith(locationPrefix) || !location.endsWith(".json")) continue;

                String base = location.substring(locationPrefix.length(), location.length() - ".json".length());
                if (base.equals(path)) {
                    defaultGeometry = "geometry." + namespace + "." + base;
                    fuzzyMatch = null;
                    break;
                }
                if (base.startsWith(path + "_")) {
                    geometries.addProperty(base.substring(path.length() + 1), "geometry." + namespace + "." + base);
                    continue;
                }

                if (fuzzyMatch == null) {
                    String byIdentifier = geometryIdentifierFor(entry.getValue(), path);
                    if (byIdentifier != null) {
                        fuzzyMatch = byIdentifier;
                    }
                }
            }

            if (fuzzyMatch != null) {
                defaultGeometry = fuzzyMatch;
            }
        }

        if (defaultGeometry == null) {
            defaultGeometry = staticFallbackGeometry(dimensions);
        }

        // Order matters for readability only: default first in the JSON.
        JsonObject ordered = new JsonObject();
        ordered.addProperty("default", defaultGeometry);
        for (Map.Entry<String, JsonElement> variant : geometries.entrySet()) {
            ordered.add(variant.getKey(), variant.getValue());
        }
        return ordered;
    }

    /**
     * Picks a generic vanilla Bedrock geometry from the Java hitbox for every
     * mod. This deliberately avoids per-mod name lists: the fallback is only
     * used when no transferable model asset exists.
     */
    private static String staticFallbackGeometry(EntityDimensions dimensions) {
        float width = dimensions.width();
        float height = dimensions.height();
        if (width >= height * 1.25f) {
            return "geometry.pig";
        }
        if (width >= 0.9f || height >= 2.4f) {
            return "geometry.cow";
        }
        return "geometry.humanoid.custom";
    }

    private static boolean hasNativeGeometry(String namespace, String path, BedrockResourcePack pack) {
        if (pack.entityModels() == null) return false;
        String prefix = "models/entity/" + namespace + ".";
        for (Map.Entry<String, ModelEntity> entry : pack.entityModels().entrySet()) {
            String location = entry.getKey();
            if (!location.startsWith(prefix) || !location.endsWith(".json")) continue;
            String base = location.substring(prefix.length(), location.length() - ".json".length());
            if (base.equals(path) || base.startsWith(path + "_") || geometryIdentifierFor(entry.getValue(), path) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives every textured entity a pack-owned geometry when no converter can
     * recover its client model. The recovered model always wins; this one-cube
     * fallback follows the Java hitbox and never relies on a vanilla entity
     * geometry being present on the Bedrock client.
     */
    private static void addHitboxGeometry(String namespace, String path, EntityDimensions dimensions, BedrockResourcePack pack) {
        String fileName = namespace + "." + path + ".json";
        String location = "models/entity/" + fileName;
        if (pack.entityModels() != null && pack.entityModels().containsKey(location)) {
            return;
        }

        float halfWidth = dimensions.width() / 2f;
        // ponytail: one cuboid is intentionally the generic ceiling; add a
        // framework parser only when a real model cannot be extracted.
        pack.addEntityModel(GeoUtil.fromShape(
                Shapes.box(-halfWidth, 0, -halfWidth, halfWidth, dimensions.height(), halfWidth),
                "geometry." + namespace + "." + path), fileName);
    }

    /**
     * Looks for a geometry whose identifier ends in the entity name, e.g.
     * {@code geometry.alexsmobs.grizzly_bear} for entity {@code grizzly_bear}.
     */
    private static String geometryIdentifierFor(ModelEntity model, String path) {
        if (model.geometry() == null) return null;
        String suffix = "." + path;
        for (Geometry geometry : model.geometry()) {
            String identifier = geometry.description() != null ? geometry.description().identifier() : null;
            if (identifier != null && (identifier.equals("geometry." + path) || identifier.endsWith(suffix))) {
                return identifier;
            }
        }
        return null;
    }

    /**
     * Resolves the entity texture. Exact convention first, then a scan of the
     * converted entity textures for a file named after (or containing) the
     * entity name, since mods lay out texture folders in many ways.
     */
    private String findTexture(String namespace, String path, BedrockResourcePack pack) {
        try {
            Path entityTextures = pack.directory().resolve("textures/entity");
            if (!Files.isDirectory(entityTextures)) {
                return null;
            }

            String exact = path + ".png";
            String exactTga = path + ".tga";
            String contains = null;
            try (Stream<Path> candidates = Files.walk(entityTextures)) {
                for (Path candidate : (Iterable<Path>) candidates::iterator) {
                    if (!Files.isRegularFile(candidate)) continue;

                    String name = candidate.getFileName().toString();
                    String relative = pack.directory().relativize(candidate).toString().replace(File.separatorChar, '/');
                    if (relative.endsWith(".png")) {
                        relative = relative.substring(0, relative.length() - ".png".length());
                    } else if (relative.endsWith(".tga")) {
                        relative = relative.substring(0, relative.length() - ".tga".length());
                    } else {
                        continue;
                    }

                    if (name.equals(exact) || name.equals(exactTga)) {
                        return relative;
                    }
                    if (contains == null && (name.contains(path) || relative.contains("/" + path))) {
                        contains = relative;
                    }
                }
            }
            return contains;
        } catch (IOException e) {
            return null;
        }
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

        // The map is lazily created on the first addExtraFile, so it is null for
        // a fresh pack that has not received extra files yet.
        Map<String, byte[]> extraFiles = pack.extraFiles();
        if (extraFiles == null) {
            return animations;
        }

        byte[] converted = extraFiles.get("animations/" + namespace + "." + path + ".animation.json");
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
