package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManagerRuntimeClasspathTest {
    @Test
    void addsLauncherLibraryCodeSourceForIsolatedReflection() {
        var classpath = new ArrayList<Path>();

        PackManager.addCodeSource(classpath, "org.joml.Quaternionfc");
        PackManager.addCodeSource(classpath, "org.joml.Quaternionfc");

        assertEquals(1, classpath.size());
        assertTrue(classpath.stream().allMatch(Files::exists));
    }
}
