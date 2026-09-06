# Follow-up tasks

Tasks surfaced by the 2026-09-03 / 2026-09-04 audits that are not addressed
in any existing commit. Keep this file in sync as items are closed or
re-prioritised.

## GĐ4.6 — creative-api / unnamed: missing special_render_type / tint_source

**Symptom** (from server log 2026-09-03): 7 lines of
`Unknown special render type: alexsmobs:icon` and
`Unknown tint source type: ...` raised during conversion. Cascades into
17 lines of `no layer0 texture, skipping` because the affected items
cannot be parsed down to a single layer.

**Root cause**: `team.unnamed:creative-api` (bundled inside
`hydraulic-fabric.jar`) does not yet recognise the
`alexsmobs:icon` and related custom `special_render_type` /
`tint_source` values used by Alex's Mobs 1.21.4+ item models. This is
a library compatibility lag, not a Hydraulic bug.

**Action**: watch for upstream releases of `team.unnamed:creative-api`
and `team.unnamed:creative-serializer-minecraft`. When a new version
adds support for these render types, bump the dependency in
`gradle/libs.versions.toml` and the 17 `no layer0 texture, skipping`
lines for affected items should disappear automatically.

**Affected mods (from the live log)**: alexsmobs (icon, straddleboard
_base, etc.), biomesoplenty. Likely a growing list as more Fabric
mods adopt the newer item-model format.

## GĐ5.9 — biomesoplenty: parent model for sign blocks missing (logged twice per sign)

**Symptom** (from server log 2026-09-03): repeated ERROR lines

```
Could not find parent model minecraft:block/template_sign_rot_0 for model
  biomesoplenty:block/willow_sign_rot_0
Could not find parent model minecraft:block/template_sign_rot_1 for model
  biomesoplenty:block/willow_sign_rot_1
... (one line per rotation × 4 rotations × 2 sign types × N wood types)
```

Each rotation logs twice (rot_0 etc. duplicated), suggesting the
model resolution path is invoked twice for the same input.

**Source traced** (GeyserMC/PackConverter fork):
- `converter/src/main/java/org/geysermc/pack/converter/type/model/ModelStitcher.java:159`
  logs `"Could not find parent model " + parentKey + " for model " + model.key()`
  and `return;` when parent is not found.
- `converter/src/main/java/org/geysermc/pack/converter/type/model/ModelConverter.java:77`
  iterates `pack.models().stream()` and calls
  `new ModelStitcher(modelProvider, model, context.logListener()).stitch()`
  per model — one log per missing parent per model.

**Root cause of "logged twice"**: each biomesoplenty wood type
(`willow`, `umbran`, `redwood`, `pine`, `palm`, `origin_oak`, `maple`,
`mahogany`, `fir`, `magic`, `hellbark`, `jacaranda`, `dead`) has TWO
model entries for the same sign — one for the wall sign
(`<wood>_wall_sign_rot_N`) and one for the hanging sign
(`<wood>_hanging_sign_attached_rot_N`). Each goes through
`extract()` → `new ModelStitcher()` → `stitch()` once, so the error
log fires once per (wood × sign type × rotation) — 4 rotations × 2
sign types = 8 logs per wood, not "double work". The "logged twice"
per line in the audit report is actually 1 log per (wood × rotation)
for the two different sign types appearing next to each other in the
log, not 2 invocations per single model.

The actual issue is upstream biomesoplenty: their sign model
references `minecraft:block/template_sign_rot_N` parents which do not
exist in vanilla Minecraft 26.2 (sign model format changed in
recent versions). This is a biomesoplenty mod bug, not a PackConverter
or Hydraulic bug — Hydraulic is correctly reporting the missing
parents. Fix lives in `Biomes O' Plenty` itself, not here.

**Action**: file an issue upstream on `Biomes O' Plenty` with the
log excerpt. Optionally dedupe at the log layer in PackConverter
(to reduce spam) — but this is a low-priority P2 since the operator
gets no incorrect behaviour, only noisy logs.

## GĐ2.3 — lossless vs pruned pack size comparison

The `scripts/measure-lossless.ps1` script in `scripts/` can measure one
side of the comparison (a post-lossless archive). To complete the
comparison:

1. Capture a pre-PR-5 archive by downgrading Hydraulic to a
   pre-lossless commit (any commit before `cff55f7b1` on
   `GeyserMC/Hydraulic`), running the same modpack, and zipping the
   resulting `hydraulic/storage/` directory.
2. Run `scripts/measure-lossless.ps1` against both archives and diff
   the totals.

Without step 1 the script only confirms which packs were produced;
it cannot quantify the size cost of the lossless mode that the
audit's GĐ2.3 proposed.

## fabric-loom override of `dependencyResolutionManagement`

GĐ4.3 (commit `611678b` on branch `gd4/version-snapshot`) and
GĐ4.5.1 (commit `c055a4f` on the same branch) uncommented
`mavenLocal()` in `settings.gradle.kts` and added the same to
`fabric/build.gradle.kts`, but `./gradlew :shared:compileJava` still
fails with
`Could not find org.geysermc.pack:converter:3.5.8-SNAPSHOT`.
Hydraulic's `.gradle/caches/fabric-loom/minecraftMaven` is the only
resolver that actually fires for the `includeTransitive`
configuration. Likely fix: configure
`loom { repositories { mavenLocal() } }` somewhere, or publish the
fork via a JitPack or GitHub Packages endpoint so Gradle resolves
through `https://` rather than `file:///`.

This is the only remaining block on getting GĐ4.1 + GĐ4.2 into a
fully reproducible build — without it, every GeyserMC/Hydraulic
build is still pinning the unpatched `converter-3.4.3-SNAPSHOT` and
the viaversion/viabackwards path-traversal bug is live in production.
