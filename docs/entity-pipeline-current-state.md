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
