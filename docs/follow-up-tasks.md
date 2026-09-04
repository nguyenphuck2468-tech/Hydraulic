# Follow-up tasks

Tasks surfaced by the 2026-09-03 audit that are not addressed in any
existing GĐ4 commit. Keep this file in sync as items are closed or
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

## GĐ2.3 — lossless vs pruned pack size comparison

The `measure-lossless.ps1` script in `scripts/` can measure one side of
the comparison (a post-lossless archive). To complete the comparison:

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

Both GĐ4.3 (`gd4/version-snapshot`, commit `611678b`) and GĐ4.5.1
(commit `c055a4f`) uncommented `mavenLocal()` in
`settings.gradle.kts` and added the same to `fabric/build.gradle.kts`,
but `./gradlew :shared:compileJava` still fails with
`Could not find org.geysermc.pack:converter:3.5.8-SNAPSHOT`.
Hydraulic's `.gradle/caches/fabric-loom/minecraftMaven` is the only
resolver that actually fires for the `includeTransitive`
configuration. Likely fix: configure
`loom { repositories { mavenLocal() } }` somewhere, or publish the
fork via a JitPack or GitHub Packages endpoint so Gradle resolves
through `https://` rather than `file:///`.

This is the only remaining block on getting GĐ4.1 + GĐ4.2 into a
fully reproducible build.
