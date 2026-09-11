package org.geysermc.hydraulic.pack;

import org.geysermc.hydraulic.config.EntityBinding;
import org.geysermc.hydraulic.util.PackUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EntityPackIdentityTest {
    @TempDir Path directory;
    final EntityPackListener.Worker worker = new EntityPackListener.Worker(Path.of("unused"), "revision", "worker-digest");

    EntityBinding binding() throws Exception {
        var binding = new EntityBinding();
        binding.modId = "fixture";
        binding.fullyQualifiedClassName = "fixture.Model";
        binding.route = "citadel";
        binding.geometryIdentifier = "geometry.fixture";
        binding.bedrockEntityIdentifier = "fixture:entity";
        Path file = directory.resolve("runtime.jar");
        Files.write(file, new byte[]{1, 2});
        binding.runtimeClasspath = List.of(file.toString());
        return binding;
    }

    @Test void oneByteChangeSamePathChangesPackUUID() throws Exception {
        var binding = binding();
        UUID roots = UUID.randomUUID();
        UUID first = PackUtil.withEntityFingerprints(roots, List.of(EntityPackListener.fingerprint(binding, worker)));
        Files.write(Path.of(binding.runtimeClasspath.getFirst()), new byte[]{1, 3});
        assertNotEquals(first, PackUtil.withEntityFingerprints(roots, List.of(EntityPackListener.fingerprint(binding, worker))));
        assertEquals(roots, PackUtil.withEntityFingerprints(roots, List.of()));
    }

    @Test void classRouteIdentifiersRevisionAndClasspathOrderAffectIdentity() throws Exception {
        var binding = binding();
        String original = EntityPackListener.fingerprint(binding, worker);
        binding.route = "layer";
        assertNotEquals(original, EntityPackListener.fingerprint(binding, worker));
        binding.route = "citadel";
        binding.fullyQualifiedClassName = "fixture.OtherModel";
        assertNotEquals(original, EntityPackListener.fingerprint(binding, worker));
        binding.fullyQualifiedClassName = "fixture.Model";
        binding.geometryIdentifier += ".other";
        assertNotEquals(original, EntityPackListener.fingerprint(binding, worker));
        binding.geometryIdentifier = "geometry.fixture";
        binding.bedrockEntityIdentifier += "_other";
        assertNotEquals(original, EntityPackListener.fingerprint(binding, worker));
        binding.bedrockEntityIdentifier = "fixture:entity";
        assertNotEquals(original, EntityPackListener.fingerprint(binding, new EntityPackListener.Worker(Path.of("unused"), "new-revision", "worker-digest")));
        Path second = directory.resolve("second.jar");
        Files.write(second, new byte[]{3});
        String first = binding.runtimeClasspath.getFirst();
        binding.runtimeClasspath = List.of(first, second.toString());
        String ordered = EntityPackListener.fingerprint(binding, worker);
        binding.runtimeClasspath = List.of(second.toString(), first);
        assertNotEquals(ordered, EntityPackListener.fingerprint(binding, worker));
        UUID roots = UUID.randomUUID();
        assertEquals(PackUtil.withEntityFingerprints(roots, List.of("a", "b")), PackUtil.withEntityFingerprints(roots, List.of("b", "a")));
    }

    @Test void unsafeAndFailedOutputCannotReplacePreviousFile() throws Exception {
        Path original = directory.resolve("old.geo.json");
        Files.writeString(original, "previous");
        assertThrows(Exception.class, () -> EntityPackListener.writeAtomically(directory, "../escaped.json", new byte[]{1}));
        assertThrows(Exception.class, () -> EntityPackListener.writeAtomically(directory, "old.geo.json/child", new byte[]{1}));
        assertEquals("previous", Files.readString(original));
        assertFalse(Files.exists(directory.getParent().resolve("escaped.json")));
    }
}
