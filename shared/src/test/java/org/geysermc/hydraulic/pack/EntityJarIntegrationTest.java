package org.geysermc.hydraulic.pack;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import org.geysermc.api.Geyser;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.entity.CustomBedrockEntityDefinition;
import org.geysermc.geyser.entity.properties.GeyserEntityProperties;
import org.geysermc.geyser.impl.IdentifierImpl;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.config.ConfigLoader;
import org.geysermc.hydraulic.config.HydraulicConfig;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.storage.ModStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real worker/model JARs, real YAML/PackManager/PackListener/ZIPs; Geyser boundary is mocked.
 * This is not a live Geyser server boot, Java spawn mapping, or Bedrock rendering test.
 */
@EnabledIfSystemProperty(named = "entity.fixture.classpath", matches = ".+")
class EntityJarIntegrationTest {
    @TempDir Path directory;
    List<String> classpath;
    List<ModInfo> mods;
    static EventBus bus;
    final List<CustomEntityDefinition> registered = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        SharedConstants.tryDetectVersion();
        try (var core = new ZipFile(Path.of(CustomBedrockEntityDefinition.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile())) {
            var properties = new java.util.Properties();
            try (var input = core.getInputStream(core.getEntry("git.properties"))) { properties.load(input); }
            assertEquals("9b65a39fc1b37f35655c0b2ab0196e231bc37b4e", properties.getProperty("git.commit.id"), "Real-JAR evidence is pinned to the inspected Geyser contract");
        }
        classpath = new ArrayList<>(Arrays.asList(Files.readString(Path.of(System.getProperty("entity.fixture.classpath")))
                .trim().split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
        checkJar("alexsmobs-2.1.11-fabric+26.2.jar", "a548655daf4b8336cbc43544d5eae6e0883217afbe7a5f62c0587c4b62f2484d");
        checkJar("codxlib-1.6.0-fabric+26.2.jar", "d6887454c1b4cab58b66bd4c0433ea0a76452084535686e7b91c7865ae721282");
        checkJar("MutantMonsters-v26.2.2-mc26.2.x-Fabric.jar", "618b07e168abc83ebbdb548f5247eda0cabc90a40fa0e4ed3a587cbf2ce82e71");
        checkJar("PuzzlesLib-v26.2.3-mc26.2.x-Fabric.jar", "746d90c6723fccce4fca076e7e87753354abc4670016e1c461120a3306ee9af1");
        checkJar("ForgeConfigAPIPort-v26.2.1-mc26.2.x-Fabric.jar", "1b4b17fc4150481447201152604688c74726d6631c9a626dd21f2c5e2b3df1ac");
        mods = new ArrayList<>();
        for (String name : List.of("alexsmobs", "mutantmonsters")) {
            // Isolate entity integration from unrelated block/item pipelines. Model input is
            // the exact JAR classpath above; these roots supply only pack metadata for the test.
            Path root = Files.createDirectories(directory.resolve(name + "-root"));
            Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":75,\"description\":\"Entity integration fixture\"}}");
            mods.add(new ModInfo(name, name, name, "fixture", null, List.of(root)));
        }
        bus = mock(EventBus.class);
        GeyserApi api = mock(GeyserApi.class, invocation -> {
            if (invocation.getMethod().getName().equals("eventBus")) return bus;
            if (invocation.getMethod().getName().equals("provider")) {
                Class<?> type = invocation.getArgument(0);
                Object[] arguments = (Object[]) invocation.getRawArguments()[1];
                if (type == Identifier.class) return IdentifierImpl.of((String) arguments[0], (String) arguments[1]);
                if (type == CustomEntityDefinition.class) return new CustomBedrockEntityDefinition((Identifier) arguments[0], new GeyserEntityProperties());
                // ResourcePack/PackCodec boundaries only. The actual generated ZIP is checked below.
                return mock(type, RETURNS_DEEP_STUBS);
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        // Geyser's API singleton can only be installed once. Each simulated startup
        // below uses a fresh event/registration collection, not another API singleton.
        if (!Geyser.isRegistered()) Geyser.set(api);
    }

    void checkJar(String name, String expected) throws Exception {
        Path jar = classpath.stream().map(Path::of).filter(p -> p.getFileName().toString().equals(name)).findFirst().orElseThrow();
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))), name);
    }

    String entry(String mod, String model, String route, String geometry, String entity) {
        String cp = String.join("\n", classpath.stream().map(path -> "      - '" + path.replace("\\", "/").replace("'", "''") + "'").toList());
        return """
              - modId: %s
                fullyQualifiedClassName: %s
                route: %s
                geometryIdentifier: %s
                bedrockEntityIdentifier: '%s'
                deadlineMillis: 120000
                runtimeClasspath:
            %s
            """.formatted(mod, model, route, geometry, entity, cp);
    }

    String eagle() { return entry("alexsmobs", "com.github.alexthe666.alexsmobs.client.model.ModelBaldEagle", "citadel", "geometry.fixture.model", "fixture:bald_eagle"); }
    String creeper() { return entry("mutantmonsters", "fuzs.mutantmonsters.common.client.model.MutantCreeperModel", "layer", "geometry.fixture.mutant_creeper", "fixture:mutant_creeper"); }

    HydraulicConfig config(String entries, List<String> errors) throws Exception {
        Path yaml = directory.resolve("config.yml");
        Files.writeString(yaml, "config-version: 1\nentity-bindings:\n" + entries);
        // Retain the real fully expanded YAML as local evidence, not a portable production config.
        Path evidence = Path.of("build/entity-integration/config.yml");
        Files.createDirectories(evidence.getParent());
        Files.copy(yaml, evidence, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return ConfigLoader.loadConfig(yaml.toFile(), errors::add);
    }

    record Run(PackManager manager, PackListener packs, EntityPackListener entities) { }

    Run start(HydraulicConfig config) throws Exception {
        HydraulicImpl hydraulic = mock(HydraulicImpl.class);
        when(hydraulic.getConfig()).thenReturn(config);
        when(hydraulic.mods()).thenReturn(mods);
        when(hydraulic.dataFolder(anyString())).thenReturn(directory);
        for (ModInfo mod : mods) {
            ModStorage storage = mock(ModStorage.class);
            when(storage.pack()).thenReturn(pack(mod));
            when(hydraulic.modStorage(mod)).thenReturn(storage);
        }
        PackManager manager = new PackManager(hydraulic);
        clearInvocations(bus);
        manager.initializeEntities();
        var capture = ArgumentCaptor.forClass(EntityPackListener.class);
        verify(bus).register(eq(hydraulic), capture.capture());
        var pipelines = PackManager.class.getDeclaredField("packConverters");
        pipelines.setAccessible(true);
        pipelines.set(manager, List.of()); // Real metadata pipeline is appended by createPack().
        Path vanilla = manager.getVanillaPath();
        Files.createDirectories(vanilla.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(vanilla))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":75,\"description\":\"Test vanilla metadata\"}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(vanilla.resolveSibling(vanilla.getFileName() + ".version"), SharedConstants.getCurrentVersion().id());
        return new Run(manager, new PackListener(hydraulic, manager), capture.getValue());
    }

    GeyserDefineEntitiesEvent entityEvent() {
        registered.clear(); // A fresh Geyser startup registry, not re-registering into the same live registry.
        var event = mock(GeyserDefineEntitiesEvent.class);
        when(event.entities()).thenAnswer(call -> List.copyOf(registered));
        doAnswer(call -> {
            CustomEntityDefinition definition = call.getArgument(0);
            assertTrue(registered.stream().noneMatch(old -> old.identifier().equals(definition.identifier())));
            registered.add(definition);
            return null;
        }).when(event).register(any());
        return event;
    }

    Path pack(ModInfo mod) { return directory.resolve(mod.id() + ".mcpack"); }

    void assertGolden(ModInfo mod, String geometry, String golden) throws Exception {
        try (ZipFile zip = new ZipFile(pack(mod).toFile()); InputStream reference = getClass().getResourceAsStream("/entity/" + golden)) {
            assertNotNull(reference);
            var actual = zip.getInputStream(zip.getEntry(EntityPackListener.geometryPath(geometry))).readAllBytes();
            assertEquals(JsonParser.parseString(new String(reference.readAllBytes(), StandardCharsets.UTF_8)),
                    JsonParser.parseString(new String(actual, StandardCharsets.UTF_8)));
            Path evidence = Path.of("build/entity-integration/" + golden);
            Files.write(evidence, actual);
        }
    }

    @Test void twoExactModelsAcrossBothEventsCacheHitAndOneByteDependencyChange() throws Exception {
        Path dependency = directory.resolve("additional-dependency.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(dependency))) { zip.setComment("A"); }
        classpath.add(dependency.toString());
        var errors = new ArrayList<String>();
        HydraulicConfig config = config(eagle() + creeper(), errors);
        Files.copy(directory.resolve("config.yml"), Path.of("build/entity-integration/two-model-bindings.yml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(2, config.entityBindings().size());
        Run first = start(config);
        var event = entityEvent();
        first.entities.onDefineEntities(event);
        assertEquals(2, registered.size(), "Registration must be complete before callback returns");
        assertEquals(List.of("fixture:bald_eagle", "fixture:mutant_creeper"), registered.stream().map(d -> d.identifier().toString()).toList());
        verify(event, times(2)).register(any());
        assertEquals(2, first.entities.conversionCalls());
        var packEvent = mock(GeyserDefineResourcePacksEvent.class);
        first.packs.onLoadResourcePacks(packEvent);
        assertEquals(2, first.entities.conversionCalls(), "Resource event must not call converter");
        verify(packEvent, times(2)).register(any(), any());
        assertGolden(mods.get(0), "geometry.fixture.model", "bald-eagle.geo.json");
        assertGolden(mods.get(1), "geometry.fixture.mutant_creeper", "mutant-creeper.geo.json");
        // Corrupt cached geometry while preserving manifest UUID and checksum index.
        var archive = new java.util.LinkedHashMap<String, byte[]>();
        try (ZipFile zip = new ZipFile(pack(mods.getFirst()).toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (!entry.isDirectory()) archive.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        String geometryPath = EntityPackListener.geometryPath("geometry.fixture.model");
        String original = new String(archive.get(geometryPath), StandardCharsets.UTF_8);
        String corrupted = original.replace("\"texture_width\":64", "\"texture_width\":65");
        assertNotEquals(original, corrupted);
        archive.put(geometryPath, corrupted.getBytes(StandardCharsets.UTF_8));
        try (var zip = new ZipOutputStream(Files.newOutputStream(pack(mods.getFirst())))) {
            for (var entry : archive.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Run repaired = start(config);
        repaired.entities.onDefineEntities(entityEvent());
        assertEquals(1, repaired.entities.conversionCalls());
        assertTrue(repaired.packs.checkNeedsConversion(mods.getFirst(), pack(mods.getFirst())));
        repaired.packs.onLoadResourcePacks(mock(GeyserDefineResourcePacksEvent.class));
        assertEquals(1, repaired.entities.conversionCalls());
        assertGolden(mods.getFirst(), "geometry.fixture.model", "bald-eagle.geo.json");
        var uuidBefore = first.manager.wantedPackUUID(mods.getFirst());

        Run second = start(config);
        second.entities.onDefineEntities(entityEvent());
        assertEquals(2, registered.size());
        assertEquals(0, second.entities.conversionCalls());
        assertFalse(second.packs.checkNeedsConversion(mods.getFirst(), pack(mods.getFirst())));
        second.packs.onLoadResourcePacks(mock(GeyserDefineResourcePacksEvent.class));
        assertEquals(0, second.entities.conversionCalls());

        byte[] bytes = Files.readAllBytes(dependency);
        bytes[bytes.length - 1] = 'B'; // Change exactly one ZIP comment byte; JAR remains valid.
        Files.write(dependency, bytes);
        Run third = start(config);
        third.entities.onDefineEntities(entityEvent());
        assertEquals(2, third.entities.conversionCalls());
        assertEquals(2, registered.size());
        assertNotEquals(uuidBefore, third.manager.wantedPackUUID(mods.getFirst()));
        assertTrue(third.packs.checkNeedsConversion(mods.getFirst(), pack(mods.getFirst())));
        third.packs.onLoadResourcePacks(mock(GeyserDefineResourcePacksEvent.class));
        assertEquals(2, third.entities.conversionCalls());
        assertGolden(mods.get(0), "geometry.fixture.model", "bald-eagle.geo.json");
        assertGolden(mods.get(1), "geometry.fixture.mutant_creeper", "mutant-creeper.geo.json");
        System.out.println("VERIFIED two selected models: cold conversions=" + first.entities.conversionCalls()
                + ", corrupt-cache repair=" + repaired.entities.conversionCalls()
                + ", unchanged restart=" + second.entities.conversionCalls()
                + ", changed dependency=" + third.entities.conversionCalls()
                + "; resource events did not add conversions; golden JSON matched both models.");
    }

    @Test void missingUnknownAndFailedBindingsDoNotPreventValidSibling() throws Exception {
        var errors = new ArrayList<String>();
        var config = config(eagle() + creeper().replace("fullyQualifiedClassName:", "unused:")
                + entry("not_loaded", "fixture.Unknown", "layer", "geometry.unknown", "fixture:unknown")
                + entry("alexsmobs", "fixture.DoesNotExist", "citadel", "geometry.failed", "fixture:failed"), errors);
        assertEquals(1, errors.size(), errors.toString());
        Path old = pack(mods.getFirst());
        Files.writeString(old, "previous pack bytes");
        var listener = new EntityPackListener(config.entityBindings(), mods, this::pack, directory.resolve("worker"), errors::add);
        listener.onDefineEntities(entityEvent());
        assertEquals(1, registered.size());
        assertEquals("fixture:bald_eagle", registered.getFirst().identifier().toString());
        assertEquals(2, listener.conversionCalls());
        assertTrue(errors.stream().anyMatch(s -> s.contains("not_loaded")), errors.toString());
        assertTrue(errors.stream().anyMatch(s -> s.contains("fixture.DoesNotExist") && s.contains("FAILED")), errors.toString());
        assertEquals("previous pack bytes", Files.readString(old), "Entity failure must not mutate the old pack");
        assertNotEquals(listener.wantedUUID(mods.getFirst()), listener.outputUUID(mods.getFirst()), "Partial result must be retried");
        Path prepared = directory.resolve("prepared");
        listener.writePrepared(mods.getFirst(), prepared);
        assertTrue(Files.exists(prepared.resolve(EntityPackListener.geometryPath("geometry.fixture.model"))));
        assertFalse(Files.exists(prepared.resolve(EntityPackListener.geometryPath("geometry.failed"))));
    }

    @Test void duplicateYamlBindingsNeverReachRegistrationOrWorkers() throws Exception {
        var errors = new ArrayList<String>();
        var config = config(eagle() + creeper().replace("fixture:mutant_creeper", "fixture:bald_eagle"), errors);
        assertTrue(config.entityBindings().isEmpty());
        assertTrue(errors.stream().anyMatch(s -> s.contains("entity-bindings[0]") && s.contains("entity-bindings[1]")));
        Run run = start(config);
        var event = entityEvent();
        run.entities.onDefineEntities(event);
        verify(event, never()).register(any());
        assertEquals(0, run.entities.conversionCalls());
    }

    @Test void packagingFailurePreservesOldPackAndDoesNotRegisterStaleOutput() throws Exception {
        var errors = new ArrayList<String>();
        Run run = start(config("", errors));
        EntityPackListener fault = mock(EntityPackListener.class);
        for (ModInfo mod : mods) {
            when(fault.wantedUUID(mod)).thenReturn(java.util.UUID.randomUUID());
            when(fault.outputUUID(mod)).thenReturn(java.util.UUID.randomUUID());
            Files.writeString(pack(mod), "previous bytes for " + mod.id());
        }
        doThrow(new java.io.IOException("injected geometry staging failure")).when(fault).writePrepared(any(), any());
        var field = PackManager.class.getDeclaredField("entities");
        field.setAccessible(true);
        field.set(run.manager, fault);
        var event = mock(GeyserDefineResourcePacksEvent.class);
        run.packs.onLoadResourcePacks(event);
        verify(fault, times(2)).writePrepared(any(), any());
        verify(event, never()).register(any(), any());
        for (ModInfo mod : mods) assertEquals("previous bytes for " + mod.id(), Files.readString(pack(mod)));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("hydraulic-")), "No staging ZIP or scratch directory may remain");
        }
    }
}
