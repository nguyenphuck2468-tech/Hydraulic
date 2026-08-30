package org.geysermc.hydraulic.entity;

import com.google.gson.JsonObject;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPackModuleTest {
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

    private static Geometry geometry(Bones... bones) {
        Geometry geometry = new Geometry();
        geometry.bones(List.of(bones));
        return geometry;
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
