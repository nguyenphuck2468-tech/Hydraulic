package org.geysermc.hydraulic.entity;

import com.google.gson.JsonObject;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPackModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void declaresControllerAliasesForResolvedNativeAnimations() {
        JsonObject converted = new JsonObject();
        converted.addProperty("animation.example.beast.idle", "animation.example.beast.idle");
        converted.addProperty("animation.example.beast.walk", "animation.example.beast.walk");

        JsonObject bindings = EntityPackModule.animationBindings(converted,
                new EntityPackModule.AnimationRefs("animation.example.beast.idle", "animation.example.beast.walk"));

        assertEquals("animation.example.beast.idle", bindings.get("idle").getAsString());
        assertEquals("animation.example.beast.walk", bindings.get("walk").getAsString());
    }

    @Test
    void findsEntityAnimationsByDeclaredNameWhenSourceFileHasAGenericName() {
        BedrockResourcePack pack = new BedrockResourcePack(temporaryDirectory);
        JsonObject declared = new JsonObject();
        declared.add("animation.example.beast.walk", new JsonObject());
        declared.add("animation.example.other.idle", new JsonObject());
        JsonObject document = new JsonObject();
        document.add("animations", declared);
        pack.addExtraFile(document, "animations/example.animations.animation.json");
        pack.addExtraFile(animationDocument("animation.example.other.walk"),
                "animations/example.other.animation.json");

        JsonObject found = EntityPackModule.collectAnimations("example", "beast", pack);

        assertTrue(found.has("animation.example.beast.walk"));
        assertEquals(1, found.size());
    }

    @Test
    void classifiesBoneHierarchyAndAnimatesStructuralLeaves() {
        Geometry geometry = geometry(
                bone("body", null),
                bone("left", "body"),
                bone("right", "body"));

        Object profile = classify(geometry, false);
        JsonObject animations = fallback(profile);
        JsonObject walkBones = animations.getAsJsonObject("animation.example.beast.walk").getAsJsonObject("bones");

        assertEquals("SKELETAL", property(profile, "kind").toString());
        assertEquals("body", property(profile, "anchorBone"));
        assertEquals(List.of("left", "right"), property(profile, "motionBones"));
        assertTrue(walkBones.has("left"));
        assertTrue(walkBones.has("right"));
        assertTrue(walkBones.getAsJsonObject("left").getAsJsonArray("rotation").get(0).getAsString().startsWith("math.sin"));
        assertTrue(walkBones.getAsJsonObject("right").getAsJsonArray("rotation").get(0).getAsString().startsWith("-math.sin"));
    }

    @Test
    void treatsIndependentPartsAsFragmentedWithoutNameRules() {
        Object profile = classify(geometry(
                bone("part_a", null), bone("part_b", null)), false);

        assertEquals("FRAGMENTED", property(profile, "kind").toString());
        assertEquals("part_a", property(profile, "anchorBone"));
        assertEquals(List.of("part_b"), property(profile, "motionBones"));
    }

    @Test
    void keepsHitboxFallbackExplicitInTheGeometryProfile() {
        Object profile = classify(geometry(bone("bone_0", null)), true);

        assertEquals("HITBOX", property(profile, "kind").toString());
        assertTrue(((String) property(profile, "description")).contains("kind=hitbox"));
    }

    @Test
    void prefersStaticResourceGeometryOnlyWhenItsIdentifierOrFileMatchesTheEntity() {
        Geometry matching = geometry(bone("body", null));
        Description description = new Description();
        description.identifier("geometry.example.beast");
        matching.description(description);
        Geometry unrelated = geometry(bone("body", null));
        Description unrelatedDescription = new Description();
        unrelatedDescription.identifier("geometry.example.other");
        unrelated.description(unrelatedDescription);

        assertEquals(1_000, EntityPackModule.sourceGeometryScore("anything", model(matching), "beast"));
        assertEquals(900, EntityPackModule.sourceGeometryScore("beast", model(unrelated), "beast"));
        assertEquals(0, EntityPackModule.sourceGeometryScore("other", model(unrelated), "beast"));
    }

    private static Geometry geometry(Bones... bones) {
        Geometry geometry = new Geometry();
        geometry.bones(List.of(bones));
        return geometry;
    }

    private static JsonObject animationDocument(String name) {
        JsonObject declared = new JsonObject();
        declared.add(name, new JsonObject());
        JsonObject document = new JsonObject();
        document.add("animations", declared);
        return document;
    }

    private static ModelEntity model(Geometry geometry) {
        ModelEntity model = new ModelEntity();
        model.geometry(List.of(geometry));
        return model;
    }

    private static Bones bone(String name, String parent) {
        Bones bone = new Bones();
        bone.name(name);
        if (parent != null) bone.parent(parent);
        return bone;
    }

    private static Object classify(Geometry geometry, boolean hitbox) {
        try {
            Class<?> module = Class.forName("org.geysermc.hydraulic.entity.EntityPackModule");
            Method method = module.getDeclaredMethod("classifyGeometry", Geometry.class, boolean.class);
            method.setAccessible(true);
            return method.invoke(null, geometry, hitbox);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static JsonObject fallback(Object profile) {
        try {
            Class<?> module = Class.forName("org.geysermc.hydraulic.entity.EntityPackModule");
            Method method = java.util.Arrays.stream(module.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("fallbackAnimations"))
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            return (JsonObject) method.invoke(null, "example", "beast", profile);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object property(Object profile, String name) {
        try {
            Method method = profile.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(profile);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
