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
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.util.GeoUtil;
import org.geysermc.hydraulic.util.IOUtil;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
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
        List<TextureAsset> textures = textureAssets(pack);
        List<SourceTextureAsset> sourceTextures = sourceTextureAssets(context.mod());
        List<SourceGeometryAsset> sourceGeometries = sourceGeometryAssets(context.mod());

        for (EntityType<?> type : context.entityTypes()) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key == null) continue;

            String namespace = key.getNamespace();
            String path = key.getPath();
            TextureMatch texture = findTexture(path, textures);
            if (texture == null) {
                texture = recoverSourceTexture(path, sourceTextures, pack);
            }
            if (texture == null) {
                context.logger().warn("Skipping custom Bedrock entity {}: no converted texture", key);
                context.report().outcome("entity-missing-texture", key.toString());
                continue;
            }
            if (texture.source() != null) {
                context.logger().warn("Entity {} has no converted texture; recovered source texture {}", key, texture.source());
                context.report().fallback("entity-source-texture");
                context.report().outcome("entity-source-texture-recovery", key.toString());
                context.report().resolution("entity-source-texture-recovery", key.toString(), texture.source());
            } else if (texture.fallback()) {
                context.logger().warn("Entity {} has no exact texture; using related texture {}", key, texture.path());
                context.report().fallback("entity-texture");
                context.report().outcome("entity-texture-fallback", key.toString());
                context.report().resolution("entity-texture-fallback", key.toString(), texture.path());
            } else {
                context.report().resolution("entity-texture", key.toString(), texture.path());
            }

            if (!hasNativeGeometry(namespace, path, pack)) {
                SourceGeometryAsset sourceGeometry = findSourceGeometry(namespace, path, sourceGeometries);
                if (sourceGeometry != null) {
                    pack.addEntityModel(sourceGeometry.model(), namespace + "." + path + ".json");
                    context.logger().info("Entity {} has no converted geometry; recovered static resource geometry {}", key, sourceGeometry.source());
                    context.report().fallback("entity-source-geometry");
                    context.report().outcome("entity-source-geometry-recovery", key.toString());
                    context.report().resolution("entity-source-geometry-recovery", key.toString(), sourceGeometry.source());
                }
            }
            boolean hitboxFallback = !hasNativeGeometry(namespace, path, pack);
            if (hitboxFallback) {
                addHitboxGeometry(namespace, path, type.getDimensions(), pack);
                context.logger().warn("Entity {} has no converted geometry; using hitbox fallback", key);
                context.report().fallback("entity-hitbox");
                context.report().outcome("entity-hitbox", key.toString());
            } else {
                context.report().outcome("entity-native-geometry", key.toString());
            }
            GeometryProfile geometryProfile = geometryProfile(namespace, path, pack, hitboxFallback);
            context.report().outcome("entity-geometry-" + geometryProfile.kind().reportName(), key.toString());
            context.report().resolution("entity-geometry-profile", key.toString(), geometryProfile.description());
            JsonObject animations = collectAnimations(namespace, path, pack);
            if (animations.size() == 0 && geometryProfile.anchorBone() != null) {
                animations = fallbackAnimations(namespace, path, geometryProfile);
                pack.addExtraFile(animationFile(animations), "animations/" + namespace + "." + path + ".animation.json");
                context.logger().warn("Entity {} has no converted animation; using {} generic idle/walk fallback", key,
                        geometryProfile.kind().reportName());
                context.report().fallback("entity-animation");
                context.report().outcome(hitboxFallback ? "entity-generic-animation" : "entity-native-generic-animation", key.toString());
                context.report().resolution("entity-generic-animation", key.toString(), geometryProfile.description());
            } else if (animations.size() > 0) {
                context.report().outcome("entity-native-animation", key.toString());
            }
            AnimationRefs refs = resolveAnimationRefs(key.toString(), animations);

            pack.addExtraFile(
                    clientEntity(namespace, path, type.getDimensions(), texture.path(), animations, refs, pack),
                    "entity/" + namespace + "." + path + ".entity.json");
            pack.addExtraFile(
                    renderController(namespace, path),
                    "render_controllers/" + namespace + "." + path + ".render_controllers.json");
            if (refs != null) {
                pack.addExtraFile(
                        animationController(namespace, path, refs),
                        animationControllerFile(namespace, path));
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
            description.add("animations", animationBindings(convertedAnimations, refs));
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

    static JsonObject animationBindings(JsonObject convertedAnimations, AnimationRefs refs) {
        JsonObject animations = new JsonObject();
        for (String name : convertedAnimations.keySet()) {
            animations.addProperty(name, name);
        }
        // Controllers use stable local aliases. Without these entries a
        // two-state controller references "idle"/"walk" keys that the client
        // entity never declared, so native animations never play.
        if (refs != null && refs.idle() != null) animations.addProperty("idle", refs.idle());
        if (refs != null && refs.walk() != null) animations.addProperty("walk", refs.walk());
        return animations;
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

    static String animationControllerFile(String namespace, String path) {
        return "animation_controllers/" + namespace + "." + path + ".ac.json";
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

    static JsonObject fallbackAnimations(String namespace, String path, GeometryProfile profile) {
        String prefix = "animation." + namespace + "." + path;
        JsonObject idle = new JsonObject();
        idle.addProperty("loop", true);
        JsonObject idleBones = new JsonObject();
        addBoneAnimation(idleBones, profile.anchorBone(), "math.sin(query.life_time * 3.0) * 0.25", null);
        idle.add("bones", idleBones);
        JsonObject walk = new JsonObject();
        walk.addProperty("loop", true);
        JsonObject walkBones = new JsonObject();
        int index = 0;
        for (String bone : profile.motionBones()) {
            String direction = index++ % 2 == 0 ? "" : "-";
            addBoneAnimation(walkBones, bone, null,
                    direction + "math.sin(query.life_time * 12.0) * " + profile.kind().walkAmplitude());
        }
        if (walkBones.size() == 0) {
            addBoneAnimation(walkBones, profile.anchorBone(), null,
                    "math.sin(query.life_time * 12.0) * " + profile.kind().walkAmplitude());
        }
        walk.add("bones", walkBones);
        JsonObject animations = new JsonObject();
        animations.add(prefix + ".idle", idle);
        animations.add(prefix + ".walk", walk);
        return animations;
    }

    private static void addBoneAnimation(JsonObject bones, String name, String y, String xRotation) {
        JsonObject bone = new JsonObject();
        if (y != null) {
            JsonArray position = new JsonArray();
            position.add(0); position.add(y); position.add(0);
            bone.add("position", position);
        }
        if (xRotation != null) {
            JsonArray rotation = new JsonArray();
            rotation.add(xRotation); rotation.add(0); rotation.add(0);
            bone.add("rotation", rotation);
        }
        bones.add(name, bone);
    }

    private static JsonObject animationFile(JsonObject animations) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", animations);
        return root;
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

            JsonObject entities = JsonParser.parseString(IOUtil.readString(path, StandardCharsets.UTF_8, 1024 * 1024))
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

    record AnimationRefs(String idle, String walk) {
    }

    record GeometryProfile(GeometryKind kind, String anchorBone, List<String> motionBones, int boneCount) {
        String description() {
            return "kind=" + kind.reportName() + ", bones=" + boneCount + ", anchor=" + anchorBone
                    + ", motion_bones=" + String.join(",", motionBones);
        }
    }

    enum GeometryKind {
        HITBOX("hitbox", 4),
        STATIC("static", 4),
        FRAGMENTED("fragmented", 6),
        SKELETAL("skeletal", 8);

        private final String reportName;
        private final int walkAmplitude;

        GeometryKind(String reportName, int walkAmplitude) {
            this.reportName = reportName;
            this.walkAmplitude = walkAmplitude;
        }

        String reportName() {
            return reportName;
        }

        int walkAmplitude() {
            return walkAmplitude;
        }
    }

    private JsonObject clientEntityDescription(JsonObject description) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("description", description);
        return wrapper;
    }

    /**
     * Binds the entity's own converted geometry as {@code default}. Sibling
     * part models are not exposed as variants unless a render controller can
     * actually select them. When no file is named exactly after the entity, the
     * converted geometries' own identifiers are searched for the entity name -
     * most GeckoLib mods name models after the entity inside the file rather
     * than in the file name.
     *
     * <p><b>Fallback:</b> if a mod only builds its model at Java runtime and
     * ships no transferable geometry, a pack-owned hitbox silhouette is added
     * from the entity dimensions. This keeps the entity visible without
     * depending on a particular vanilla geometry or mod naming rule.</p>
     */
    private JsonObject collectGeometries(String namespace, String path, EntityDimensions dimensions, BedrockResourcePack pack) {
        String defaultGeometry = null;

        if (pack.entityModels() != null) {
            String locationPrefix = "models/entity/" + namespace + ".";
            String fuzzyMatch = null;

            for (Map.Entry<String, ModelEntity> entry : pack.entityModels().entrySet()) {
                String location = entry.getKey();
                if (!location.startsWith(locationPrefix) || !location.endsWith(".json")) continue;

                String base = location.substring(locationPrefix.length(), location.length() - ".json".length());
                if (base.equals(path)) {
                    String identifier = geometryIdentifierFor(entry.getValue(), path);
                    defaultGeometry = identifier == null ? "geometry." + namespace + "." + base : identifier;
                    fuzzyMatch = null;
                    break;
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

    private static GeometryProfile geometryProfile(String namespace, String path, BedrockResourcePack pack, boolean hitboxFallback) {
        return classifyGeometry(geometryFor(namespace, path, pack), hitboxFallback);
    }

    /**
     * Classifies transferable geometry only by bone structure. It never relies
     * on mod ids or bone naming conventions: a parent chain is skeletal,
     * several independent bones are fragmented, and one bone is static.
     */
    static GeometryProfile classifyGeometry(Geometry geometry, boolean hitboxFallback) {
        List<Bones> bones = geometry == null || geometry.bones() == null ? List.of() : geometry.bones();
        Map<String, List<String>> children = new HashMap<>();
        Map<String, Bones> byName = new HashMap<>();
        for (Bones bone : bones) {
            if (bone.name() != null) byName.put(bone.name(), bone);
        }

        List<String> roots = new ArrayList<>();
        boolean hierarchy = false;
        for (Bones bone : bones) {
            String name = bone.name();
            if (name == null) continue;
            if (bone.parent() == null || !byName.containsKey(bone.parent())) {
                roots.add(name);
            } else {
                hierarchy = true;
                children.computeIfAbsent(bone.parent(), ignored -> new ArrayList<>()).add(name);
            }
        }
        String anchor = roots.isEmpty() ? (byName.isEmpty() ? null : byName.keySet().iterator().next()) : roots.getFirst();
        List<String> motionBones = new ArrayList<>();
        if (hierarchy) {
            for (String name : byName.keySet()) {
                if (!name.equals(anchor) && children.getOrDefault(name, List.of()).isEmpty()) {
                    motionBones.add(name);
                }
            }
        } else {
            for (String name : byName.keySet()) {
                if (!name.equals(anchor)) motionBones.add(name);
            }
        }
        if (motionBones.isEmpty() && anchor != null) motionBones.add(anchor);
        motionBones = motionBones.stream().sorted().limit(4).toList();

        GeometryKind kind = hitboxFallback ? GeometryKind.HITBOX
                : hierarchy ? GeometryKind.SKELETAL
                : bones.size() > 1 ? GeometryKind.FRAGMENTED : GeometryKind.STATIC;
        return new GeometryProfile(kind, anchor, motionBones, bones.size());
    }

    private static boolean hasNativeGeometry(String namespace, String path, BedrockResourcePack pack) {
        return geometryFor(namespace, path, pack) != null;
    }

    private static Geometry geometryFor(String namespace, String path, BedrockResourcePack pack) {
        if (pack.entityModels() == null) return null;
        String prefix = "models/entity/" + namespace + ".";
        for (Map.Entry<String, ModelEntity> entry : pack.entityModels().entrySet()) {
            String location = entry.getKey();
            if (!location.startsWith(prefix) || !location.endsWith(".json")) continue;
            String base = location.substring(prefix.length(), location.length() - ".json".length());
            if (base.equals(path) && entry.getValue().geometry() != null && !entry.getValue().geometry().isEmpty()) {
                Geometry matching = geometryFor(entry.getValue(), path);
                return matching == null ? entry.getValue().geometry().getFirst() : matching;
            }
            Geometry matching = geometryFor(entry.getValue(), path);
            if (matching != null) return matching;
        }
        return null;
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

        // ponytail: this is deliberately an adaptive silhouette, not a fake
        // model parser; use a converted geometry whenever one exists.
        pack.addEntityModel(GeoUtil.fromShape(fallbackShape(dimensions),
                "geometry." + namespace + "." + path, true), fileName);
    }

    /**
     * Builds a visible body plan from dimensions alone when no transferable
     * model exists. Broad entities get a quadruped silhouette, tall entities
     * get a biped silhouette, and compact entities retain a body/head form.
     * No namespace or entity-name rule is involved.
     */
    private static net.minecraft.world.phys.shapes.VoxelShape fallbackShape(EntityDimensions dimensions) {
        float width = dimensions.width();
        float height = dimensions.height();
        float half = width / 2f;
        if (width >= height * 1.15f) {
            float leg = Math.max(width * 0.12f, 0.08f);
            float legHeight = height * 0.35f;
            float bodyHalf = half * 0.85f;
            var body = Shapes.box(-bodyHalf, legHeight, -bodyHalf, bodyHalf, height * 0.78f, bodyHalf);
            var head = Shapes.box(-half * 0.45f, height * 0.55f, -half, half * 0.45f, height, -half * 0.25f);
            return Shapes.or(body, head,
                    Shapes.box(-bodyHalf, 0, -bodyHalf, -bodyHalf + leg, legHeight, -bodyHalf + leg),
                    Shapes.box(bodyHalf - leg, 0, -bodyHalf, bodyHalf, legHeight, -bodyHalf + leg),
                    Shapes.box(-bodyHalf, 0, bodyHalf - leg, -bodyHalf + leg, legHeight, bodyHalf),
                    Shapes.box(bodyHalf - leg, 0, bodyHalf - leg, bodyHalf, legHeight, bodyHalf));
        }
        if (height >= width * 1.5f) {
            float legHalf = half * 0.22f;
            float torsoHalf = half * 0.55f;
            float legTop = height * 0.42f;
            var torso = Shapes.box(-torsoHalf, legTop, -half * 0.45f, torsoHalf, height * 0.76f, half * 0.45f);
            var head = Shapes.box(-half * 0.48f, height * 0.76f, -half * 0.45f, half * 0.48f, height, half * 0.45f);
            return Shapes.or(torso, head,
                    Shapes.box(-half * 0.58f, height * 0.45f, -half * 0.32f, -torsoHalf, height * 0.72f, half * 0.32f),
                    Shapes.box(torsoHalf, height * 0.45f, -half * 0.32f, half * 0.58f, height * 0.72f, half * 0.32f),
                    Shapes.box(-half * 0.48f, 0, -half * 0.35f, -half * 0.48f + legHalf * 2, legTop, half * 0.35f),
                    Shapes.box(half * 0.48f - legHalf * 2, 0, -half * 0.35f, half * 0.48f, legTop, half * 0.35f));
        }
        return Shapes.or(
                Shapes.box(-half, 0, -half, half, height * 0.72f, half),
                Shapes.box(-half * 0.6f, height * 0.62f, -half, half * 0.6f, height, -half * 0.15f));
    }

    /**
     * Looks for a geometry whose identifier ends in the entity name, e.g.
     * {@code geometry.example.beast} for entity {@code beast}.
     */
    private static String geometryIdentifierFor(ModelEntity model, String path) {
        Geometry geometry = geometryFor(model, path);
        return geometry == null || geometry.description() == null ? null : geometry.description().identifier();
    }

    private static Geometry geometryFor(ModelEntity model, String path) {
        if (model.geometry() == null) return null;
        String suffix = "." + path;
        for (Geometry geometry : model.geometry()) {
            String identifier = geometry.description() != null ? geometry.description().identifier() : null;
            if (identifier != null && (identifier.equals("geometry." + path) || identifier.endsWith(suffix))) {
                return geometry;
            }
        }
        return null;
    }

    /**
     * Resolves the entity texture. Exact convention first, then a scan of the
     * converted entity textures for a file named after (or containing) the
     * entity name, since mods lay out texture folders in many ways.
     */
    private static List<TextureAsset> textureAssets(BedrockResourcePack pack) {
        Path textures = pack.directory().resolve("textures");
        if (!Files.isDirectory(textures)) {
            return List.of();
        }
        try (Stream<Path> candidates = Files.walk(textures)) {
            return candidates.filter(Files::isRegularFile)
                    .map(candidate -> textureAsset(pack, candidate))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(TextureAsset::path))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static TextureAsset textureAsset(BedrockResourcePack pack, Path candidate) {
        String name = candidate.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".tga")) {
            return null;
        }
        int extension = name.lastIndexOf('.');
        String relative = pack.directory().relativize(candidate).toString().replace('\\', '/');
        return new TextureAsset(relative.substring(0, relative.lastIndexOf('.')), name.substring(0, extension));
    }

    /**
     * Finds source images that PackConverter did not carry across. The files
     * are indexed once per mod and only used after every converted texture
     * lookup has failed, so converter output always remains authoritative.
     */
    private static List<SourceTextureAsset> sourceTextureAssets(ModInfo mod) {
        List<SourceTextureAsset> textures = new ArrayList<>();
        for (Path root : mod.roots()) {
            Path sourceRoot = root.resolve("assets").resolve(mod.namespace()).resolve("textures");
            if (!Files.isDirectory(sourceRoot)) continue;
            try (Stream<Path> candidates = Files.walk(sourceRoot)) {
                candidates.filter(Files::isRegularFile)
                        .map(candidate -> sourceTextureAsset(mod.namespace(), sourceRoot, candidate))
                        .filter(java.util.Objects::nonNull)
                        .forEach(textures::add);
            } catch (Exception ignored) {
                // A single unreadable mod root should not stop its other assets.
            }
        }
        return textures.stream().sorted(Comparator.comparing(SourceTextureAsset::outputPath)).toList();
    }

    /**
     * Indexes only already-Bedrock geometry resources. Java block models and
     * renderer source are deliberately excluded: they do not contain enough
     * transferable geometry to reconstruct a client model safely.
     */
    private static List<SourceGeometryAsset> sourceGeometryAssets(ModInfo mod) {
        List<SourceGeometryAsset> geometries = new ArrayList<>();
        for (Path root : mod.roots()) {
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (Stream<Path> namespaces = Files.list(assets)) {
                namespaces.filter(Files::isDirectory).forEach(namespace -> {
                    try (Stream<Path> files = Files.walk(namespace)) {
                        files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".geo.json"))
                                .map(path -> sourceGeometryAsset(namespace.getFileName().toString(), path))
                                .filter(java.util.Objects::nonNull)
                                .forEach(geometries::add);
                    } catch (IOException ignored) {
                        // A bad optional geometry must not hide the remaining assets in the mod.
                    }
                });
            } catch (IOException ignored) {
                // A bad resource root is already isolated by the converter path.
            }
        }
        return geometries.stream().sorted(Comparator.comparing(SourceGeometryAsset::source)).toList();
    }

    private static SourceGeometryAsset sourceGeometryAsset(String namespace, Path source) {
        try {
            ModelEntity model = Constants.GSON.fromJson(IOUtil.readString(source, StandardCharsets.UTF_8, 8 * 1024 * 1024), ModelEntity.class);
            if (model == null || model.geometry() == null || model.geometry().isEmpty()) return null;
            String name = source.getFileName().toString();
            return new SourceGeometryAsset(namespace, source.toString(), name.substring(0, name.length() - ".geo.json".length()), model);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SourceGeometryAsset findSourceGeometry(String namespace, String path, List<SourceGeometryAsset> geometries) {
        SourceGeometryAsset best = null;
        int bestScore = 0;
        for (SourceGeometryAsset geometry : geometries) {
            if (!geometry.namespace().equals(namespace)) continue;
            int score = sourceGeometryScore(geometry.fileName(), geometry.model(), path);
            if (score > bestScore) {
                best = geometry;
                bestScore = score;
            }
        }
        return best;
    }

    static int sourceGeometryScore(String fileName, ModelEntity model, String entityPath) {
        for (Geometry geometry : model.geometry()) {
            String identifier = geometry.description() == null ? null : geometry.description().identifier();
            if (identifier != null && (identifier.equals("geometry." + entityPath) || identifier.endsWith("." + entityPath))) {
                return 1_000;
            }
        }
        if (fileName.equals(entityPath)) return 900;
        return fileName.startsWith(entityPath + "_") ? 800 : 0;
    }

    private static SourceTextureAsset sourceTextureAsset(String namespace, Path sourceRoot, Path candidate) {
        String name = candidate.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".tga")) return null;

        int extension = name.lastIndexOf('.');
        String relative = sourceRoot.relativize(candidate).toString().replace('\\', '/');
        String outputFile = "textures/" + relative;
        return new SourceTextureAsset(candidate, outputFile, outputFile.substring(0, outputFile.lastIndexOf('.')),
                name.substring(0, extension), "assets/" + namespace + "/textures/" + relative);
    }

    private static TextureMatch findTexture(String path, List<TextureAsset> textures) {
        TextureAsset best = null;
        int bestScore = 0;
        for (TextureAsset texture : textures) {
            if (texture.name().equals(path)) {
                return new TextureMatch(texture.path(), false, null);
            }
            // A fuzzy match must stay in entity assets. A matching item or
            // armor name is usually a different visual asset.
            if (!texture.path().startsWith("textures/entity/")) continue;
            int score = textureScore(path, texture.path());
            if (score > bestScore) {
                best = texture;
                bestScore = score;
            }
        }
        return best == null ? null : new TextureMatch(best.path(), true, null);
    }

    private static TextureMatch recoverSourceTexture(String path, List<SourceTextureAsset> textures, BedrockResourcePack pack) {
        SourceTextureAsset best = null;
        int bestScore = 0;
        for (SourceTextureAsset texture : textures) {
            if (texture.name().equals(path)) {
                best = texture;
                break;
            }
            if (!texture.outputPath().startsWith("textures/entity/")) continue;
            int score = textureScore(path, texture.outputPath());
            if (score > bestScore) {
                best = texture;
                bestScore = score;
            }
        }
        if (best == null) return null;

        try {
            Path destination = pack.directory().resolve(best.outputFile());
            Files.createDirectories(destination.getParent());
            Files.copy(best.source(), destination, StandardCopyOption.REPLACE_EXISTING);
            return new TextureMatch(best.outputPath(), true, best.description());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int textureScore(String entityPath, String texturePath) {
        String lower = texturePath.toLowerCase(Locale.ROOT);
        if (lower.contains("/" + entityPath)) {
            return 1_000;
        }

        int score = 0;
        for (String token : entityPath.split("_")) {
            if (token.length() >= 4 && lower.contains(token)) {
                score += token.length();
            }
        }
        return score;
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

    private record TextureMatch(String path, boolean fallback, String source) {
    }

    private record TextureAsset(String path, String name) {
    }

    private record SourceTextureAsset(Path source, String outputFile, String outputPath, String name, String description) {
    }

    private record SourceGeometryAsset(String namespace, String source, String fileName, ModelEntity model) {
    }

    /**
     * Reads back a converted GeckoLib animation file from the pack (written by
     * the GeckoAnimationConverter as {@code animations/<ns>.<name>.animation.json})
     * and maps every animation it declares onto itself.
     */
    static JsonObject collectAnimations(String namespace, String path, BedrockResourcePack pack) {
        JsonObject animations = new JsonObject();

        // The map is lazily created on the first addExtraFile, so it is null for
        // a fresh pack that has not received extra files yet.
        Map<String, byte[]> extraFiles = pack.extraFiles();
        if (extraFiles == null) {
            return animations;
        }

        String exactFile = "animations/" + namespace + "." + path + ".animation.json";
        List<Map.Entry<String, byte[]>> candidates = extraFiles.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("animations/" + namespace + "."))
                .filter(entry -> entry.getKey().endsWith(".animation.json"))
                .sorted(Map.Entry.comparingByKey())
                .toList();

        for (Map.Entry<String, byte[]> candidate : candidates) {
            try {
                JsonElement parsed = JsonParser.parseString(new String(candidate.getValue(), StandardCharsets.UTF_8));
                JsonObject declared = parsed.getAsJsonObject().getAsJsonObject("animations");
                if (declared == null) continue;

                boolean exact = candidate.getKey().equals(exactFile);
                boolean fileNamedForEntity = animationFileMatchesEntity(candidate.getKey(), path);
                List<String> matchingNames = declared.keySet().stream()
                        .filter(name -> animationNameMatchesEntity(name, path))
                        .toList();
                // A namespace with exactly one animation document is common in
                // small mods; it is unambiguous even when the file is named
                // animations.json instead of after the entity.
                if (!exact && !fileNamedForEntity && matchingNames.isEmpty() && candidates.size() != 1) continue;

                Iterable<String> names = exact || fileNamedForEntity || matchingNames.isEmpty()
                        ? declared.keySet() : matchingNames;
                for (String name : names) {
                    animations.addProperty(name, name);
                }
            } catch (Exception ignored) {
                // A malformed converted animation file must not break pack generation.
            }
        }
        return animations;
    }

    private static boolean animationFileMatchesEntity(String file, String entityPath) {
        String normalized = file.toLowerCase(Locale.ROOT).replace('-', '_');
        String entity = entityPath.toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.contains("." + entity + ".") || normalized.contains("/" + entity + ".");
    }

    private static boolean animationNameMatchesEntity(String name, String entityPath) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('-', '_');
        String entity = entityPath.toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.equals(entity) || normalized.startsWith(entity + ".")
                || normalized.contains("." + entity + ".") || normalized.endsWith("." + entity);
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<EntityPackModule> context) {
        return !context.entityTypes().isEmpty();
    }
}
