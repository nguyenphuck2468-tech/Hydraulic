# Explicit entity bindings

Hydraulic reads `entity-bindings` from its existing `config/hydraulic/config.yml`.
The default list is empty. No mod-directory search, model-name inference, embedded
manifest, or provider service is involved. `modId` must match a loaded, enabled
`ModInfo.id()`. Invalid entries are reported with their YAML index. Duplicate
Bedrock entity IDs reject both entries and report both locations; other entries
continue. Rejected input is preserved in the operator's YAML.

```yaml
entity-bindings:
  - modId: examplemod
    fullyQualifiedClassName: example.client.model.ExampleModel
    route: layer
    geometryIdentifier: geometry.example.model
    bedrockEntityIdentifier: 'example:entity'
    runtimeClasspath:
      - '/absolute/path/to/model-mod.jar'
      - '/absolute/path/to/required-library.jar'
      - '/absolute/path/to/matching-minecraft-client-runtime.jar'
      # Include every other required runtime dependency explicitly.
    deadlineMillis: 30000
```

This is a syntax example, not an executable model fixture. Paths must be readable
absolute files. On Windows use quoted paths such as `'C:/server/runtime/mod.jar'`.
`route` is exactly `citadel` or `layer`. The timeout defaults to 30 seconds and
accepts 1–120000 milliseconds. The worker executable is the current Java runtime's
`bin/java` (`java.exe` on Windows), so Hydraulic's Java 25 requirement also applies
to workers. No model/runtime classpath is derived from `ModInfo.roots()`.

`geometryIdentifier` and `bedrockEntityIdentifier` are independent. The first is
the geometry JSON's identifier; the second is a non-`minecraft` Bedrock entity type
ID. Geyser's actual identifier parser and custom-namespace exception are used.
This registration does not link a client entity to geometry, texture or material,
and does not map a Java spawn to the custom type. Those features are not implemented.

## Lifecycle and storage

`PackManager.initializeEntities()` installs `EntityPackListener` during Hydraulic
initialization. The entity callback completes each bounded worker call and calls
`register()` before returning. It skips failed bindings with explicit errors.
An identifier already registered by another listener is rejected before another
registration call. Resource-pack conversion remains in `PackListener`; its
`PackManager.createPack()`/`PackPackager` path writes the prepared geometry without
calling either model reader again.

Successful geometry is held in an immutable map per mod between events. The
`.mcpack` is the only persistent geometry cache, with a checksum index at
`hydraulic/entities.json`. The worker JAR has its own content-addressed extraction
path, which is executable infrastructure, not a second geometry cache. It contains
the pinned converter's entity readers and Gson, avoiding reliance on nested JAR
URLs being usable by a separate JVM. The existing converter enforces its 256 MiB
heap, 128 MiB metaspace, 8 MiB response cap and killable process deadline.

Cache identity folds the existing roots UUID together with sorted binding
fingerprints. Each SHA-256 fingerprint includes all declared runtime file content,
FQCN, route, both identifiers, deadline, converter revision and worker bundle hash.
Classpath order is also encoded: reordering duplicate-class dependencies can change
Java resolution even if the sorted content set is unchanged. YAML binding order
does not affect identity. Inputs are hashed again after a conversion to detect
changes during the read; this does not provide OS-level isolation from a malicious
concurrent writer.

A separate geometry cache alone would not invalidate PackListener's manifest UUID
check, so the fingerprint is part of the pack UUID. On a full cache hit the entity
callback reads and checks geometry without invoking the converter, then registers
the entity type in the new startup registry. Later the pack callback reuses the ZIP.
A checksum/missing-entry failure triggers explicit diagnostics and conversion.
If any binding fails, the output UUID is deliberately different from the desired
complete UUID, forcing another attempt at the next startup. Other successful
geometry and ordinary resources can still be packaged. Failed geometry is omitted,
not silently taken from an older pack.

Pack generation uses a unique sibling staging path. The old ZIP remains untouched
during entity preparation and conversion; it is replaced by an atomic move only
after packaging succeeds. Successful partial packaging may replace the old ZIP,
with the incomplete UUID and explicit failure logs. A failed publish returns false
so PackListener does not register a stale ZIP as a newly successful conversion.
Staging ZIPs and their scratch directories are cleaned after success or failure.
Hydraulic writes portable `/` ZIP entry names and propagates ZIP errors directly:
the pinned converter's older ZipUtils emits Windows separators and swallows I/O
errors, which would break geometry cache lookup and atomic failure handling.
Entity-type registration has already happened at that point: this API does not
make resource-pack publication and entity registration one atomic transaction.

## Geyser source contract

Read from Geyser commit `9b65a39fc1b37f35655c0b2ab0196e231bc37b4e`:

- `api/src/main/java/org/geysermc/geyser/api/entity/custom/CustomEntityDefinition.java:54–68`:
  creates/retrieves a Bedrock entity type; rejects `minecraft` namespace.
- `api/src/main/java/org/geysermc/geyser/api/util/Identifier.java:82–97`:
  unqualified strings use the default `minecraft` namespace.
- `api/src/main/java/org/geysermc/geyser/api/event/lifecycle/GeyserDefineEntitiesEvent.java:37–44`:
  startup registration window.
- `core/src/main/java/org/geysermc/geyser/entity/VanillaEntities.java:1300–1302`:
  invokes `EntityUtils.callEntityEvents()`.
- `core/src/main/java/org/geysermc/geyser/util/EntityUtils.java:380–430`:
  fires the callback, rejects registered identifiers, then builds entity identifier
  NBT immediately from registrations made during the callback.
- `core/src/main/java/org/geysermc/geyser/entity/BedrockEntityDefinition.java:93–94`:
  registration status is registry key presence.
- `core/src/main/java/org/geysermc/geyser/GeyserImpl.java:266,288,326`:
  entity initialization precedes `startInstance()` and resource-pack loading.
- `core/src/main/java/org/geysermc/geyser/registry/loader/ResourcePackLoader.java:127–141`:
  later resource-pack event used by Hydraulic's existing PackListener.

## Validation scope

Hydraulic tests are separate from PackConverter's previous 39 tests. The opt-in
`EntityJarIntegrationTest` hashes the five supplied JARs before running exactly
ModelBaldEagle and MutantCreeperModel, compares their geometry with the reviewed
goldens from PackConverter commit `03aa48fb971030e1df4824bec015b8e6ff125ae7`, and
drives the two actual Hydraulic handlers with mocked Geyser boundaries. Metadata
roots isolate this test from block/item conversion. It is not discovery of all
entities or a whole-mod/server test.

Run `:shared:test -PentityFixtureClasspath=<absolute-classpath-file>` on Java 25.
The file contains the exact semicolon-separated classpath on Windows (colon on
Unix), including the supplied mods and Minecraft client runtime. No fixture JARs
are downloaded by tests. Without that property CI skips the real-JAR tests;
ordinary CI success is not evidence of real-JAR conversion. Expanded YAML and
actual geometry output are retained in `shared/build/entity-integration/`.

Bedrock visual QA, live server boot, Java spawn mapping, client entity resource
links, textures/materials, animation, sound, render controllers and broader model
coverage remain **NOT VERIFIED / NOT IMPLEMENTED**, as applicable. Reader behavior
for Citadel and ModelPart is unchanged by this Hydraulic integration.
