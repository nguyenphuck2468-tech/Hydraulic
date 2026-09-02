package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    void missingClassIsSwallowedWithoutPollutingClasspath() {
        var classpath = new ArrayList<Path>();

        assertDoesNotThrow(() -> PackManager.addCodeSource(classpath, "hydraulic.audit.this.class.really.does.not.Exist"));
        assertTrue(classpath.isEmpty(),
                "addCodeSource must not add anything when the class is missing; got " + classpath);
    }

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

    @Test
    void walkClassLoaderUrlsCollectsEveryReachableJarFromCustomLoader(@TempDir Path tmp) throws Exception {
        // Two temporary files pretending to be mod JARs, plus a directory.
        Path jarA = tmp.resolve("mod-a.jar");
        Path jarB = tmp.resolve("mod-b.jar");
        Path dir = tmp.resolve("class-dir");
        Files.createFile(jarA);
        Files.createFile(jarB);
        Files.createDirectory(dir);

        URLClassLoader custom = new URLClassLoader(new URL[]{
                jarA.toUri().toURL(),
                jarB.toUri().toURL(),
                dir.toUri().toURL()
        }, PackManager.class.getClassLoader());

        List<Path> sink = new ArrayList<>();
        PackManager.walkClassLoaderUrls(custom, sink);

        assertTrue(sink.contains(jarA.toAbsolutePath()),
                "Custom loader URL must appear in walk output, got " + sink);
        assertTrue(sink.contains(jarB.toAbsolutePath()),
                "Custom loader URL must appear in walk output, got " + sink);
        assertTrue(sink.contains(dir.toAbsolutePath()),
                "Custom loader URL for a directory must appear in walk output, got " + sink);
    }

    @Test
    void walkClassLoaderUrlsDeduplicatesAcrossParentChain(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("shared.jar");
        Files.createFile(jar);

        URLClassLoader child = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                PackManager.class.getClassLoader());

        List<Path> sink = new ArrayList<>();
        PackManager.walkClassLoaderUrls(child, sink);

        long matches = sink.stream()
                .filter(path -> path.equals(jar.toAbsolutePath()))
                .count();
        assertEquals(1, matches,
                "Same JAR reachable through both child and parent loaders must appear exactly once");
    }

    @Test
    void walkClassLoaderUrlsToleratesNullLoader() {
        List<Path> sink = new ArrayList<>();
        // Must not throw — the bootstrap and platform loaders are reachable
        // through getParent() and the recursion must terminate cleanly.
        assertDoesNotThrow(() -> PackManager.walkClassLoaderUrls(null, sink));
    }
}
