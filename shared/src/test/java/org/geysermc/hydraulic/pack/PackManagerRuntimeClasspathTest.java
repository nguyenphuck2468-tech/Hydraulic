package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManagerRuntimeClasspathTest {
    @Test
    void addsLauncherLibraryCodeSourceForIsolatedReflection() {
        var classpath = new ArrayList<Path>();

        PackManager.addCodeSource(classpath, "org.joml.Quaternionfc");
        PackManager.addCodeSource(classpath, "org.joml.Quaternionfc");
        PackManager.addCodeSource(classpath, "com.mojang.serialization.Keyable");
        PackManager.addCodeSource(classpath, "com.google.common.collect.Maps");
        PackManager.addCodeSource(classpath, "it.unimi.dsi.fastutil.objects.ObjectList");

        assertEquals(4, classpath.size());
        assertTrue(classpath.stream().allMatch(Files::exists));
    }

    /**
     * Regression guard for C2 in the PR #5 audit: a class that does not exist
     * on the classpath must not crash {@code addCodeSource} (catch-ignored
     * path in {@link PackManager}) and must not pollute the classpath with
     * a null or empty entry. PR #5 grew the reflection probe list from 2 to
     * 5 in a single round; the next mod is likely to add another class we
     * have not seen yet, so this test ensures the "unknown class" branch is
     * exercised at build time, not just at runtime on a real server.
     */
    @Test
    void missingClassIsSwallowedWithoutPollutingClasspath() {
        var classpath = new ArrayList<Path>();

        assertDoesNotThrow(() -> PackManager.addCodeSource(classpath, "hydraulic.audit.this.class.really.does.not.Exist"));
        assertTrue(classpath.isEmpty(),
                "addCodeSource must not add anything when the class is missing; got " + classpath);
    }

    /**
     * Companion test: a missing class mixed with real ones must not disturb
     * the real ones either. This catches a future regression where someone
     * accidentally adds the missing-class handling after the real-class
     * resolution branch.
     */
    @Test
    void missingClassDoesNotAffectRealClassEntries() {
        var classpath = new ArrayList<Path>();

        PackManager.addCodeSource(classpath, "hydraulic.audit.this.class.really.does.not.Exist");
        PackManager.addCodeSource(classpath, "org.joml.Quaternionfc");
        PackManager.addCodeSource(classpath, "hydraulic.audit.another.missing.Class");

        assertEquals(1, classpath.size(),
                "Only the real class should be added; missing ones must not leak through");
        assertTrue(Files.exists(classpath.get(0)));
    }
}
