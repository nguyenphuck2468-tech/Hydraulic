package org.geysermc.hydraulic.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.hydraulic.config.EntityBinding;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.converter.type.entity.EntityConverter;
import org.geysermc.pack.converter.util.PackPaths;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

/** Explicit bindings only. All preparation and registration finish on the entity-event callback. */
public final class EntityPackListener {
    private static final int MAX_GEOMETRY_BYTES = 8 * 1024 * 1024;
    private static final String INDEX = "hydraulic/entities.json";
    private final List<EntityBinding> bindings;
    private final Map<String, ModInfo> mods = new LinkedHashMap<>();
    private final Function<ModInfo, Path> packPath;
    private final Path workerDirectory;
    private final Consumer<String> errors;
    private final EntityConverter converter = new EntityConverter();
    private final Map<String, Prepared> prepared = new HashMap<>();
    private int conversionCalls;

    private record Prepared(UUID wanted, UUID output, Map<String, String> geometry, boolean needsPackaging) { }
    record Worker(Path jar, String revision, String digest) { }

    public EntityPackListener(List<EntityBinding> bindings, Collection<ModInfo> mods,
                              Function<ModInfo, Path> packPath, Path workerDirectory, Consumer<String> errors) {
        this.bindings = List.copyOf(bindings);
        mods.forEach(mod -> this.mods.put(mod.id(), mod));
        this.packPath = packPath;
        this.workerDirectory = workerDirectory;
        this.errors = errors;
    }

    @Subscribe
    public void onDefineEntities(GeyserDefineEntitiesEvent event) {
        prepared.clear();
        var grouped = new LinkedHashMap<String, List<EntityBinding>>();
        var seen = new HashSet<String>();
        // ConfigLoader rejects both conflicting YAML entries. Also guard programmatic callers.
        Set<String> duplicates = new HashSet<>();
        for (EntityBinding binding : bindings) {
            if (!seen.add(binding.bedrockEntityIdentifier)) duplicates.add(binding.bedrockEntityIdentifier);
        }
        for (EntityBinding binding : bindings) {
            try {
                binding.validate();
                if (duplicates.contains(binding.bedrockEntityIdentifier)) throw new IllegalArgumentException("Duplicate bedrockEntityIdentifier: " + binding.label());
                if (!mods.containsKey(binding.modId)) throw new IllegalArgumentException("modId is not a loaded, enabled mod: " + binding.modId);
                grouped.computeIfAbsent(binding.modId, key -> new ArrayList<>()).add(binding);
            } catch (Exception failure) {
                errors.accept(binding.label() + ": " + failure.getMessage());
            }
        }

        if (grouped.isEmpty()) return;
        Worker worker;
        try {
            worker = worker();
        } catch (IOException failure) {
            errors.accept("Cannot prepare entity worker: " + failure.getMessage());
            return;
        }
        Set<String> registered = new HashSet<>();
        event.entities().forEach(definition -> registered.add(definition.identifier().toString()));
        for (var group : grouped.entrySet()) {
            ModInfo mod = mods.get(group.getKey());
            List<String> fingerprints = new ArrayList<>();
            var usable = new ArrayList<EntityBinding>();
            boolean complete = true;
            for (EntityBinding binding : group.getValue()) {
                try {
                    fingerprints.add(fingerprint(binding, worker));
                    usable.add(binding);
                } catch (IOException failure) {
                    complete = false;
                    errors.accept(binding.label() + ": cannot hash runtimeClasspath: " + failure.getMessage());
                }
            }
            UUID wanted = PackUtil.withEntityFingerprints(PackUtil.getModUUID(mod.roots()), fingerprints);
            Map<String, String> cached = complete ? readCache(packPath.apply(mod), wanted, usable) : Map.of();
            var geometry = new LinkedHashMap<String, String>();
            for (EntityBinding binding : usable) {
                try {
                    if (registered.contains(binding.bedrockEntityIdentifier)) throw new IllegalStateException("Entity identifier already registered: " + binding.bedrockEntityIdentifier);
                    String json = cached.get(binding.geometryIdentifier);
                    if (json == null) {
                        var input = new EntityConverter.Input(javaExecutable(), List.of(worker.jar()), binding.classpath(),
                                binding.fullyQualifiedClassName, binding.geometryIdentifier, Duration.ofMillis(binding.deadlineMillis));
                        conversionCalls++;
                        json = (binding.route.equals("citadel") ? converter.convert(input) : converter.convertLayer(input)).toString();
                        // Do not cache output under a fingerprint if inputs changed while the worker was reading them.
                        if (!fingerprints.contains(fingerprint(binding, worker))) throw new IOException("runtimeClasspath changed during conversion");
                    }
                    // Geyser 9b65a39 EntityUtils:380-430 consumes registrations immediately after
                    // this callback. Never hand the event to pack threads or defer register().
                    event.register(CustomEntityDefinition.of(binding.bedrockEntityIdentifier));
                    registered.add(binding.bedrockEntityIdentifier);
                    geometry.put(binding.geometryIdentifier, json);
                } catch (Exception failure) {
                    complete = false;
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    errors.accept(binding.label() + ": conversion/registration FAILED: " + failure.getMessage());
                }
            }
            // Partial output is useful for other entities/blocks, but must never become a
            // complete cache hit on the next startup. A later successful retry changes UUID.
            UUID output = complete ? wanted : UUID.nameUUIDFromBytes((wanted + ":entity-incomplete").getBytes(StandardCharsets.UTF_8));
            prepared.put(mod.id(), new Prepared(wanted, output, Map.copyOf(geometry), !complete || cached.isEmpty()));
        }
    }

    public UUID wantedUUID(ModInfo mod) {
        Prepared result = prepared.get(mod.id());
        return result == null ? PackUtil.getModUUID(mod.roots()) : result.wanted();
    }

    public UUID outputUUID(ModInfo mod) {
        Prepared result = prepared.get(mod.id());
        return result == null ? PackUtil.getModUUID(mod.roots()) : result.output();
    }

    public int conversionCalls() {
        return conversionCalls;
    }

    public boolean needsPackaging(ModInfo mod) {
        Prepared result = prepared.get(mod.id());
        // A corrupt ZIP can have the right manifest UUID. Once rebuilt in memory,
        // it must be republished rather than accepted by PackListener's UUID-only check.
        return result != null && result.needsPackaging();
    }

    /** Called from resource-pack packaging; no model execution is allowed here. */
    public void writePrepared(ModInfo mod, Path packRoot) throws IOException {
        Prepared result = prepared.get(mod.id());
        if (result == null) return;
        JsonObject index = new JsonObject();
        for (var entry : result.geometry().entrySet()) {
            String path = geometryPath(entry.getKey());
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            writeAtomically(packRoot, path, bytes);
            index.addProperty(path, sha256(bytes));
        }
        writeAtomically(packRoot, INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String geometryPath(String identifier) {
        return "models/entity/hydraulic/" + identifier + ".geo.json";
    }

    private Map<String, String> readCache(Path path, UUID wanted, List<EntityBinding> bindings) {
        if (!Files.isRegularFile(path)) return Map.of();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            JsonObject manifest = JsonParser.parseString(read(zip, "manifest.json")).getAsJsonObject();
            if (!wanted.toString().equals(manifest.getAsJsonObject("header").get("uuid").getAsString())) return Map.of();
            JsonObject index = JsonParser.parseString(read(zip, INDEX)).getAsJsonObject();
            Map<String, String> cached = new HashMap<>();
            for (EntityBinding binding : bindings) {
                String file = geometryPath(binding.geometryIdentifier);
                String json = read(zip, file);
                if (!sha256(json.getBytes(StandardCharsets.UTF_8)).equals(index.get(file).getAsString())) throw new IOException("Cached geometry checksum mismatch: " + file);
                String identifier = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("minecraft:geometry")
                        .get(0).getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString();
                if (!binding.geometryIdentifier.equals(identifier)) throw new IOException("Cached geometry identifier mismatch: " + file);
                cached.put(binding.geometryIdentifier, json);
            }
            return cached;
        } catch (Exception failure) {
            errors.accept("Entity pack cache invalid, reconverting " + path + ": " + failure.getMessage());
            return Map.of();
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing cache entry " + name);
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_GEOMETRY_BYTES + 1);
            if (bytes.length > MAX_GEOMETRY_BYTES) throw new IOException("Oversized cache entry " + name);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Worker worker() throws IOException {
        byte[] bytes;
        String revision;
        try (InputStream jar = EntityPackListener.class.getResourceAsStream("/hydraulic/entity-worker.jar");
             InputStream ref = EntityPackListener.class.getResourceAsStream("/hydraulic/pack-converter.ref")) {
            if (jar == null || ref == null) throw new IOException("Missing bundled entity worker/revision");
            bytes = jar.readAllBytes();
            revision = new String(ref.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        String digest = sha256(bytes);
        // Content-addressed: a failed replacement never destroys a previous working worker.
        String filename = digest + ".jar";
        Path path = PackPaths.resolveSafeRelative(workerDirectory, filename);
        if (!Files.isRegularFile(path) || !sha256(Files.readAllBytes(path)).equals(digest)) writeAtomically(workerDirectory, filename, bytes);
        return new Worker(path, revision, digest);
    }

    static String fingerprint(EntityBinding binding, Worker worker) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF("hydraulic-entity-v1");
            output.writeUTF(worker.revision());
            output.writeUTF(worker.digest());
            output.writeUTF(binding.fullyQualifiedClassName);
            output.writeUTF(binding.route);
            output.writeUTF(binding.geometryIdentifier);
            output.writeUTF(binding.bedrockEntityIdentifier);
            output.writeLong(binding.deadlineMillis);
            // Hash sorted file content as requested, and retain the declared order separately:
            // Java classpath order affects class resolution when dependencies overlap.
            var hashes = new HashMap<String, String>();
            for (String entry : binding.runtimeClasspath.stream().sorted().toList()) {
                MessageDigest digest = digest();
                try (InputStream input = Files.newInputStream(Path.of(entry))) {
                    byte[] buffer = new byte[65536];
                    for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
                }
                String hash = HexFormat.of().formatHex(digest.digest());
                output.writeUTF(entry);
                output.writeUTF(hash);
                hashes.put(entry, hash);
            }
            for (String entry : binding.runtimeClasspath) output.writeUTF(entry + ":" + hashes.get(entry));
        }
        return sha256(bytes.toByteArray());
    }

    static void writeAtomically(Path root, String relative, byte[] bytes) throws IOException {
        Path target = PackPaths.resolveSafeRelative(root, relative);
        Files.createDirectories(target.getParent());
        target = PackPaths.resolveSafeRelative(root, relative);
        Path temporary = Files.createTempFile(target.getParent(), "entity-", ".part");
        try {
            Files.write(temporary, bytes);
            PackPaths.resolveSafeRelative(root, relative);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }
}
