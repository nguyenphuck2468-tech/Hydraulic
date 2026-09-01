# Hydraulic

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](https://discord.gg/geysermc)

Hydraulic is a companion to Geyser which allows for Bedrock players to join modded Minecraft: Java Edition servers. 

Hydraulic is an open collaboration project by [CubeCraft Games](https://cubecraft.net).

## What is Hydraulic?
Hydraulic is a server-side mod, which allows for Bedrock players to join modded Minecraft: Java Edition servers. This project works alongside [Geyser](https://github.com/GeyserMC/Geyser) to make this possible.

Hydraulic is still evolving. Test every converted pack with the exact Java
modpack, Minecraft version, Geyser version, and Bedrock clients that will use
it before treating it as production-ready. You can get
[Hydraulic](https://geysermc.org/download?project=other-projects&hydraulic=expanded)
from the GeyserMC website.

## What Hydraulic converts

Hydraulic converts the resource-facing part of a Java mod into a Bedrock
resource pack: block states and models where possible, textures, item icons,
and entity client geometry/animations when portable source data exists. The
converter is deliberately general-purpose; it does not contain rules for a
particular mod.

If a source asset cannot be represented exactly, Hydraulic keeps the rest of
the namespace usable and records the fallback in a per-mod report. Examples
include a VoxelShape-derived block geometry, an icon texture recovered from a
model parent/layer, a static Bedrock `.geo.json` source geometry, or a generic
idle/walk animation selected from the geometry hierarchy.

This is resource conversion, not a Java client runtime. Hydraulic cannot
translate custom Java AI or gameplay, custom renderer code, shaders, GeckoLib
runtime animation, or procedural Java animation into Bedrock. Those outcomes
are intentionally reported as fallbacks rather than being presented as fully
native conversion.

## Cache, reports, and safe restart verification

Hydraulic writes generated packs under `config/hydraulic/storage/<mod-id>/`
and reports under `config/hydraulic/reports/<mod-id>.json` on both Fabric and
NeoForge. A complete `.mcpack` contains a generation marker with the source
fingerprint. A metadata-only result is stored as an adjacent
`<mod-id>.mcpack.empty.json` marker instead of being registered with Geyser.

Choose the client resource budget in `config/hydraulic/pack-profile.txt`.
`lite` limits textures to a 256-pixel edge and 64 million pixels per mod pack;
`balanced` (the default) uses a 1024-pixel edge and 256 million pixels; `full`
preserves source resolution. Changing this value invalidates the generated
pack identity so Bedrock does not retain content from another profile.
Before packaging, Hydraulic follows Bedrock JSON references and removes
unbound geometry, animation, controller, and texture files. Downscaled PNGs
are cached by source content and target size, so unchanged textures are not
decoded and resized again for every pack rebuild.
To reduce negotiation and import work, `lite` also omits packs from mods that
own no custom block, item, or entity; use `balanced` if such a library mod
provides shared sounds, UI, or textures required by another pack.

Per-client logs now mark session initialization, pack offer, Java login, world
join, and disconnect. Geyser's public API does not expose separate download,
verify, import, and apply callbacks, so `pack offer -> login` is intentionally
reported as their combined client pack-processing time.

On a normal, unchanged restart, the startup log should contain a planning
line such as:

```text
Pack planning completed in ... ms [reuse=12, skipped-empty=32, conversion=0]
Hydraulic: 44 detected | 12 reused | 0 converted | 32 skipped-empty | 0 deferred
```

`reuse` means the complete archive and fingerprint were verified;
`skipped-empty` means an unchanged metadata-only marker was verified and no
archive is registered; `conversion` means a source fingerprint or generation
revision changed. Remove neither the pack nor its `.empty.json` marker when
testing cache reuse. A `.part` archive is never registered or reused.

The report separates full native entity geometries, native geometries with
generic animation, hitbox geometry fallbacks, unresolved item assets, model
and texture resolutions, source-resource diagnostics, and validation warnings.
It also lists Java item components that have no Bedrock custom-item equivalent
and runtime item predicates that could not be bound. Damage, count, custom
model data, component presence, broken/damaged state, trim material, and
crossbow charge-type branches use Geyser runtime predicates when present.
For an unresolved item, use `asset_resolutions.item-reason` to distinguish
`missing-texture`, `unresolved-parent`, `special-renderer`, and `custom-tint`
instead of assuming that the entire mod failed.

Entity animation bindings whose names do not use the usual `idle`, `walk`,
`move`, `fly`, or `swim` suffixes can be selected explicitly in the generated
`config/hydraulic/entity-animations.json` file:

```json
{
  "entities": {
    "examplemod:example_entity": {
      "idle": "animation.examplemod.example_entity.rest",
      "walk": "animation.examplemod.example_entity.move"
    }
  }
}
```

This only binds already-converted Bedrock animations; it does not emulate a
Java animation runtime.

## Contributing
Any contributions are appreciated. Please feel free to reach out to us on [Discord](https://discord.gg/geysermc) if
you're interested in helping out with Hydraulic.

### Project Setup
1. Clone the repo to your computer.
2. Navigate to the Hydraulic root directory and run `git submodule update --init --recursive`. This command downloads all the needed submodules for Hydraulic and is a crucial step in this process.
3. If your default JVM/JDK is not Java 25, please set your IDE to use a valid Java 25 JVM. Otherwise, you will run into an error while building Hydraulic. 
4. The project should import into your IDE after the loom setup is complete. For more detailed information, see the [Fabric setup](https://docs.fabricmc.net/develop/getting-started/setting-up).
5. Use `./gradlew build` to compile both platform artifacts. It produces
   `fabric/build/libs/hydraulic-fabric.jar` and
   `neoforge/build/libs/hydraulic-neoforge.jar`. For local Fabric development,
   use `./gradlew :fabric:runServer`. Make sure you have Geyser in your `mods`
   folder along with Hydraulic.

When changing PackConverter and Hydraulic together, publish the exact local
PackConverter modules before building Hydraulic:

```text
PackConverter> gradlew :pack-schema-api:publishToMavenLocal :converter:publishToMavenLocal
Hydraulic> gradlew build
```

Hydraulic intentionally does not use Gradle composite substitution here. The
published `pack-schema-api` JAR embeds the generated Bedrock schema classes,
while a raw composite project output does not; treating those classpaths as
equivalent produced successful-looking source wiring followed by compile
failures.

NeoForge is included in the build and release artifacts. Its dedicated
   `runServer` development task includes Geyser and can be used for a local
   startup smoke test; still test the produced NeoForge JAR in its intended
   server environment before release.

### Validation checklist

1. Run `./gradlew build` with Java 25.
2. Start the server once and retain the generated packs, cache markers, and
   reports.
3. Restart without changing any mod, Hydraulic JAR, or configuration. Confirm
   that unchanged complete packs appear as `reuse` and metadata-only mods as
   `skipped-empty`; neither should be converted again.
4. Inspect any report outcomes and test the affected Bedrock client. A
   fallback means the pack is valid but its visual fidelity is reduced.

Only install mods from sources you trust. Optional Java-model reflection loads
model classes from the installed modpack to recover resource geometry; failures
are isolated per model and are reported instead of disabling the namespace.

## Links:
- Website: https://geysermc.org
- Docs: https://wiki.geysermc.org/geyser/
- Download: https://geysermc.org/download
- Discord: https://discord.gg/geysermc
- Donate: https://opencollective.com/geysermc
