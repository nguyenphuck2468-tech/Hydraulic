# Mutant Creeper test data provenance

Input: user-supplied `MutantMonsters-v26.2.2-mc26.2.x-Fabric.jar`.
SHA-256: `618b07e168abc83ebbdb548f5247eda0cabc90a40fa0e4ed3a587cbf2ce82e71`.
Class: `fuzs.mutantmonsters.common.client.model.MutantCreeperModel`.
Factory: `createBodyLayer(CubeDeformation.NONE)`.

`mutant-creeper-factory.json` transcribes the named hierarchy and numeric model
construction data from this class's bytecode. `mutant-creeper.geo.json` is the
transformed static geometry, generated independently from those constants by
ModelPartGoldenTest and compared against the actual JAR worker output.
Both are test data; no renderer, animation, texture image or sound is bundled.

The JAR identifies authors shcott21, Chumbanotz, Fuzs and tdstress, and source
https://github.com/Fuzss/mutant-monsters. Its code license is AGPL-3.0-or-later
(retained in `mutant-monsters-LICENSE.md`). Its separate resource-asset notice
is retained in `mutant-monsters-LICENSE-ASSETS.md`; that notice reserves asset
rights. These supplied model data fixtures are not relicensed under the main
repository's MIT license. Do not infer that the mod's textures/resources are
available for redistribution from its code license.

Reproduction requires the exact JAR/runtime classpath, never a downloaded
replacement. A mismatch writes `.actual` and `.oracle` files under build;
tests do not overwrite the checked-in golden. See docs/entity-modelpart-reader.md
for the pinned Blockbench composition and the exact scope of validation.
