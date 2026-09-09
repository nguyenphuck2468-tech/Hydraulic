# Entity pipeline current state

Inspection date: 2026-09-08. This document describes source, not runtime validation.

## Exact baselines

- Hydraulic master: `1f4037439ee1ad9e062ac3c620aa5a9e2f7fd3d9`.
- PackConverter master: `0e3a21912fc85d876fc002f045e2b72e47eb2de3`.
- Hydraulic modules: `shared`, `fabric`, `test`, `build-logic`; `neoforge` exists but is disabled in settings. Assets live under shared resources.
- PackConverter modules: `converter`, `bootstrap`, `pack-schema/api`, `pack-schema/bedrock`, `pack-schema/generator`, `build-logic`.
- Hydraulic uses Maven `org.geysermc.pack:converter:3.5.1-SNAPSHOT`. Neither its Git tree nor settings contains a PackConverter submodule/composite build. The .gitmodules entry refers to the Blockception schema path, not PackConverter. A dependency change must explicitly bind the tested converter artifact.

## Actual flow and inspected entry points

1. `shared/.../pack/PackManager.java` indexes BLOCK and ITEM registries, reads resource roots, creates a cross-mod/vanilla ModelStitcher provider, loads PackModule services, replaces the standard MODEL extractor with CustomModelConverter, and runs per-mod PackConverter instances.
2. `shared/.../pack/converter/CustomModelConverter.java` only stitches Java resource-pack models. It does not read Java entity classes.
3. `converter/.../pipeline/AssetConverters.java` registers manifest, icon, splash text, language, model, sound registry, sound, and texture pipelines. **There is no entity/mob pipeline on this master.**
4. `converter/.../type/texture/TextureConverter.java` transforms and writes PNG/TGA assets. `resolveSafeRelative` checks normalized output containment; the helper is package-private and needs generalization for new entity outputs. Copying an entity texture is not an entity definition.
5. `converter/.../type/model/ModelStitcher.java` recursively inherits resource model elements/textures/display. It has no parent-cycle guard and performs extraction before per-asset conversion exception handling.
6. `converter/.../type/model/ModelConverter.java` turns Java JSON elements into Bedrock cubes with UV/material references. It guesses ENTITY from whether the *namespace* contains `entity`; this is not discovery of entity renderer models. It does not enforce block geometry bounds and uses basename-only output names.
7. `converter/.../pipeline/ConverterPipeline.java` catches conversion Exceptions per asset and counts CombineContext errors. Extraction/include exceptions can escape; there is no timeout or circuit breaker.
8. `converter/.../PackConverter.java` runs the selected pipelines, invokes postprocessors, serializes BedrockResourcePack, then packages. There is no entity registry/model/classpath input.
9. `shared/.../pack/PackListener.java` converts mods on a shared fixed pool and joins all futures during resource-pack registration. Cache identity uses mod resources. It does not validate the full archive, restore entity bindings, or invalidate for a converter generation change.
10. `shared/.../pack/PackPackager.java` checks empty output then delegates ZIP packaging. It does not remove/clip geometry or rename generated assets.
11. `shared/.../block/BlockPackModule.java` binds custom block geometry names before pack publication, including all state permutations. A converter-only rejection would leave dangling block geometry references unless registration uses the same decision.

## Every tracked file with Entity in the filename

Hydraulic master: none.

PackConverter (all under pack-schema/bedrock/src/main/java/org/geysermc/pack/bedrock/resource):
- `entity/Entity.java`, `entity/ClientEntity.java` (serialization schema).
- `models/entity/ModelEntity.java` (geometry schema also used for block models).
- `sounds/EntitySounds.java`, `sounds/interactivesounds/EntitySounds.java` (sound schema).
- `particles/particleeffect/components/EmitterShapeEntityAabb.java` (particle schema).

`converter/src/main/resources/vanilla/builtin/entity.json` is a vanilla builtin resource model, not a converter. No EntityConverter, entity scanner, proxy selector, or entity tests exist at these baselines. Existing converter tests are TextureConverterPathSafetyTest and CombineContextErrorCounterTest, but converter/build.gradle.kts does not declare JUnit/test-platform wiring; running them must be verified, not inferred from their presence.

## Prior entity hardening

[Hydraulic PR #5](https://github.com/nguyenphuck2468-tech/Hydraulic/pull/5), "Comprehensive lossless Bedrock pack and entity hardening", is CLOSED, not merged; current head `4fd1aa5844617a6c1dda50fd6c620b8f3e9f496a` is not an ancestor of master (`git merge-base --is-ancestor` exits 1). Its diff contains EntityPackModule, EntityEventRegistrar, generated client entities/controllers, geometry/texture recovery, animation mappings, cached pack-backed spawn restoration and tests. The registrar polls for early Geyser subscription and uses custom-entity events. These mechanisms must be checked against the current Geyser API rather than blindly restored. Their absence on master is verified; this inspection does not prove a particular revert commit.

PR #5 explicitly leaves Fabric/NeoForge + Geyser + Bedrock client runtime/render gates unverified. Its broad build claims cannot validate this master or new changes.

## Issue #12: verified cause versus missing entity support

[Upstream #12](https://github.com/GeyserMC/Hydraulic/issues/12) reports Ad Astra rockets/custom models and chunks disappearing. The maintainer identifies oversized **block** geometry and closes it in favor of [#11](https://github.com/GeyserMC/Hydraulic/issues/11). #11 includes a content-log error for betternether:jungle_moss_1: boxes outside normalized bounds (-0.875,-0.875,-0.875) to (1.875,1.875,1.875). This is evidence for block geometry containment, not proof of an entity converter bug. Original exact Ad Astra JAR, world and resource-pack input are not attached; a generated oversized fixture can test the cause but must not be called an exact original reproduction.

Current [Microsoft block geometry guidance](https://learn.microsoft.com/en-us/minecraft/creator/documents/customblockoversized?view=minecraft-bedrock-stable) additionally specifies a 30-pixel size bound and overlap with the base block. Block-specific limits must never be applied to mob geometry. Rotations, offsets, multiple cubes, material references and pre-conversion registration require coverage.

## Real mod formats inspected

- Ad Astra `c896a9487f166d8bb9e68172d55d5397b10447c2` (1.21.1): `src/main/java/earth/terrarium/adastra/client/models/entities/vehicles/RocketModel.java` uses Java `LayerDefinition`, `MeshDefinition`, `PartDefinition`, `CubeListBuilder`, `PartPose` and per-tier static factories. Runtime animation uses Java code. It is not a GeckoLib JSON input.
- Alex's Mobs `09755dade2cfbdf14839e026d3af446f9d3ff843` (1.20): `src/main/java/com/github/alexthe666/alexsmobs/client/model/ModelBaldEagle.java` builds Citadel `AdvancedModelBox` trees in its constructor and drives procedural animation from entity state. It is not a generic JSON model either.
- Their Minecraft versions differ from Hydraulic 26.2. A server-only 26.2 classpath cannot be assumed to load their client model classes. Exact mod/dependency/client runtime inputs and actual renderer bindings are required; guessing by filename or instantiating arbitrary mobs to infer behavior is not reliable.

## Validation state / next gates

[VERIFIED: source inspection and Git/GitHub reads] Repository structure, absence of live mob pipeline, PR history and issue cause above.

[NOT VERIFIED] Original issue world reproduction, Bedrock chunk render recovery, entity runtime, two actual mod JAR conversion results, golden outputs, server integration and success-rate metrics. Java 25 is installed locally. Tests/build are separate gates; no runtime completion is claimed here.

## Supplied server evidence (follow-up)

Before the files became unavailable during this session, direct reads verified:
- `latest-2026-09-07.log` lines 1-9: Fabric 0.19.3, Minecraft 26.2, alexsmobs 2.1.6, biomesoplenty 26.2.0.0.27.
- `archive-2026-09-07T154615+0700.tar.gz`: SHA-256 `741e53ea59f720a6189595f91c49ddd927c6ede751a1f4d2b8e5f0d1611743f5`, 43,376,818 bytes, 55 entries, zero JAR files, no mods directory. It contains Hydraulic cache/materials and generated mcpacks including alexsmobs.mcpack.

The user requires exact original server JARs, no downloaded replacement mods, and mod names only in tests/fixtures. The public source examples above describe those pinned historical sources only; they are NOT accepted fixtures or evidence of how the server's alexsmobs 2.1.6 builds its models. The generated mcpack cannot establish the original Java model/renderer format or dependency classpath. Exact JAR location has been requested. No fixture has been invented from a filename, version guess, or downloaded mod.

## Newly supplied original model JAR and bounded probe

The user subsequently supplied `alexsmobs-2.1.11-fabric+26.2.jar` in Downloads. It was copied to the task's work/fixtures directory before further inspection. SHA-256: `a548655daf4b8336cbc43544d5eae6e0883217afbe7a5f62c0587c4b62f2484d` (27,802,705 bytes).

Its actual fabric.mod.json identifies **Alex's Mobs Continued 2.1.11**, Minecraft 26.2, Java >=25, Fabric Loader >=0.18.4, Fabric API >=0.155.2+26.2 and codxlib >=1.6.0. This is the newly accepted fixture, distinct from 2.1.6 in the earlier server log. The source contact is https://github.com/Codx-org/Alexs-Mobs-Updated-Ported and the JAR declares LGPL-3.0, with embedded license/notice files.

Direct ZIP inventory found 124 classes matching `client/model/Model*.class`, zero geometry JSON files in `.geo.json`/`geo/` paths, and zero animation JSON files under `animations/`. javap verifies that ModelBaldEagle extends the bundled Citadel AdvancedEntityModel, exposing getAllParts/parts and Java setupAnim. Its renderer uses Java texture selection. These observations replace the historical public-source examples as evidence about the chosen fixture.

A separate Java 25 diagnostic process was launched with a 20-second kill limit, the exact supplied JAR and the resolved Hydraulic 26.2 compile/client classpath. ModelBaldEagle's constructor succeeded; getAllParts returned **20 parts with 18 cubes**. The probe read actual names and pivots (for example root (0,24,0), body (0,-9.3,-2)). This verifies reflective access to this one model's static tree; it does **not** verify Bedrock coordinates, UVs, animation, entity binding, or a production isolated EntityConverter. No prototype was injected into the running server.

The embedded renderer boxes expose per-face quads/vertex UVs; recovering only the last part-level texture offset would lose cube-specific UV information. Zero-thickness planes are present in real model bytecode and must not be silently removed. The former reflection parser on the historical branch needs review for both issues before reuse.

At that checkpoint, the supplied Downloads had no codxlib dependency JAR or second mod JAR. Both have since been supplied (see the 2026-09-09 update below). No replacement mods have been downloaded. One model probe does not establish that the entire mod can be loaded or every model can be constructed.

## Delivered, separate block-containment changes

- PackConverter PR #7: https://github.com/nguyenphuck2468-tech/PackConverter/pull/7 , commit `ff3d22e484abf7551ca0f05be5e35737f4faf1dc`: 15 converter tests passed on JVM 25; both GitHub build checks passed. Includes cause-level regression and golden output, not an exact Ad Astra-world replay.
- Hydraulic PR #8: https://github.com/nguyenphuck2468-tech/Hydraulic/pull/8 , commit `546d6ab3482637f12745dbe9a1d185b4cf2139f0`: exact converter pin, generation-aware cache identity; two cache tests and Fabric build passed locally on Java 25, both GitHub build checks passed. Nested converter entry comparison confirmed unchanged original entries and only Loom's added fabric.mod.json.

These changes contain the known block geometry failure class. They do not constitute completion of the entity/mob converter, proxy, animation, sound, server-headless or real-client visual gates.

## 2026-09-09: supplied dependencies and static geometry implementation

[VERIFIED: direct ZIP metadata and SHA-256] All five original JARs are present: Alex's Mobs Continued 2.1.11, CodxLib 1.6.0, Mutant Monsters 26.2.2, PuzzlesLib 26.2.3 and Forge Config API Port 26.2.1. Their metadata targets Minecraft 26.2. Mutant Monsters/PuzzlesLib require Fabric API >=0.156.0 and Loader >=0.19.0; the existing resolved Hydraulic runtime uses API 0.158.0+26.2 and Loader 0.19.3. Metadata compatibility is not a server-start result.

[VERIFIED: javap of the supplied Mutant Monsters JAR] MutantCreeperModel uses Minecraft ModelPart and `createBodyLayer(CubeDeformation)` returning LayerDefinition. It is not a Citadel getAllParts tree or GeckoLib JSON. CodxLib and the second mod are no longer missing; successful conversion of the second representation requires an additional reader.

[VERIFIED: local Java 25, 32 tests, zero failures/skips] [PackConverter PR #9](https://github.com/nguyenphuck2468-tech/PackConverter/pull/9) adds an explicit EntityConverter API. Its worker converts the actual Bald Eagle model to a checked-in 20-bone/18-cube golden with per-cube/per-face vertex UVs, zero-thickness planes, hierarchy and the constructor's 1.1 hood scale. Coordinate tests follow the composed Java/Bedrock codec convention. Tests also verify a timed-out busy-loop worker PID is dead, another conversion can succeed concurrently, excessive output is killed, and failed conversion preserves an existing file. The second-JAR test verifies explicit unsupported-layout failure, not successful Mutant Monsters geometry conversion.

[VERIFIED: PackConverter PR #8] Path containment is shared by textures and the new explicit geometry API. Neither change applies block-size/overlap limits to entity geometry or restores PR #5 registration mechanisms. Exact fixture hashes, reproduction command, output license and technical limits are documented in PackConverter's `docs/entity-static-geometry.md`.

[NOT VERIFIED / NOT IMPLEMENTED] This API is not wired into Hydraulic's PackManager or Geyser entity registration. No successful two-mod conversion gate, complete official-schema gate, headless server integration, per-mob success metric, animation/sound conversion or Bedrock visual QA is claimed. This documentation update does not change the converter dependency pin or the deployed server behavior.
