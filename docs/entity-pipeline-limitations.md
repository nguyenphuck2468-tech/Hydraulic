# Entity pipeline limitations and release gates

Status: design/source evidence as of 2026-09-08. This document does not claim that an entity pipeline has shipped.

## Java and Bedrock boundaries

A resource pack provides visuals and sounds. It does not translate arbitrary Java renderer code, shader programs, AI, inventory interaction, vehicle controls or mod networking. On a Java server, AI remains the Java entity's AI; choosing a Bedrock proxy does not replace it with Bedrock vanilla AI. The proxy influences client representation, animations, metadata interpretation and interaction constraints. Dimensions may be supplied through Geyser metadata where supported, but that alone does not guarantee matching collision, riding, attack targeting or boss bars. Those need explicit protocol/runtime support and tests.

Block and entity geometry are distinct. The oversized-block failure in upstream Hydraulic #11/#12 must be contained at block conversion without imposing block size restrictions on mobs. See [the original report](https://github.com/GeyserMC/Hydraulic/issues/11) and [Microsoft's block geometry bounds](https://learn.microsoft.com/en-us/minecraft/creator/documents/customblockoversized?view=minecraft-bedrock-stable).

Bones must have unique, valid identifiers, resolvable parents and an acyclic hierarchy. Transforms must use the correct coordinate space, pivots, units and rotation order. A parser's configured maximum depth/bone/cube/byte count is an implementation resource limit, not a claimed universal Bedrock engine maximum. Unsupported meshes, parent cycles, invalid numbers, or unsupported animation expressions must be reported explicitly. JSON validity alone does not prove geometric or animation equivalence.

Java procedural animation is executable client code. A static default pose or copied animation JSON cannot represent every conditional animation, IK, interpolation, renderer transform or mod-specific entity state. Unsupported animation must be logged as skipped with its actual reason. Generic guessed idle/walk movement must not be reported as successful conversion of the source animation. Sound event definitions also do not by themselves cause the Java server's custom events to be played on Bedrock.

A Java timeout/cancel request cannot forcibly stop arbitrary client/mod bytecode. Any design that executes such code needs a killable process boundary or an explicitly narrower cooperative contract; allowing timed-out workers to mutate a shared Bedrock pack is unsafe. Conversion results must be validated and published only after successful completion. Per-mod exact source/dependency/client runtime input is required for class loading; never infer JARs from namespace prefixes or a global mods directory.

## Accepted test input policy

Use the exact JARs from the user's server archive; record SHA-256, actual loader/version metadata, source asset paths, dependency metadata and redistribution terms. The available archive listing contained only generated Hydraulic output, not original JARs. The log identifies alexsmobs 2.1.6, but does not identify the implementation of its client model classes. No substitute mod download is authorized. A second fixture must be selected only after inspecting a real supplied JAR for custom entity models.

## Required automated gates

- Cause-level oversized block regression, followed separately by the exact original-world reproduction if input becomes available.
- Unit conversion tests for each supported source representation with at least two exact supplied mod inputs; malformed and unsupported cases must prove containment.
- Schema validation plus semantic checks for references, paths, hierarchy, numeric bounds and supported expression subsets.
- Checked-in golden JSON, deterministic regeneration, no failed-entity references or partial files after fault injection/timeout.
- Headless server boot with the exact tested Hydraulic/PackConverter builds and fixture mods. Capture loader/JDK versions, startup result, entity failures and success/attempted counts. No discovered mobs means a zero denominator, not 100% success.
- Keep server bootstrap failures separate from entity conversion failures. Absence of ERROR messages is insufficient if the entity converter never ran.

## Manual release gate

[ASSUMED / CHUA VERIFY] Actual Bedrock client appearance, chunk rendering, animation timing, sound triggering, riding, interactions and reload/join behavior require a real Bedrock client. No client is available for this task. Automated schema, golden, unit and headless checks do not upgrade this gate to VERIFIED. A person with a real Bedrock client must perform and record visual/runtime QA before release.

## Fixture update

The user later supplied Alex's Mobs Continued 2.1.11 for 26.2 (SHA-256 `a548655daf4b8336cbc43544d5eae6e0883217afbe7a5f62c0587c4b62f2484d`). As of 2026-09-09, CodxLib 1.6.0 and Mutant Monsters 26.2.2 plus PuzzlesLib/ForgeConfigAPIPort are also supplied and their metadata/hashes have been read directly. These files are no longer missing.

[VERIFIED: local Java 25 test run] The new explicit PackConverter static-geometry API converts the selected Bald Eagle tree to a deterministic 20-bone/18-cube golden; the test run passes 32 tests including real-JAR checks. Vertex UVs, planes and local hood scale are retained. The worker is a forcibly killable JVM and tests verify PID termination, output overflow and failure isolation. Its heap/depth/part/cube/output caps are implementation limits, not Bedrock block restrictions. It is not an OS sandbox for malicious binary side effects or detached native processes.

[NOT VERIFIED] The second mod uses LayerDefinition/ModelPart and the current test proves explicit rejection, not successful conversion. A reader for that real representation, Hydraulic/Geyser integration, headless boot and Bedrock visual QA remain outstanding. Static geometry has no texture binding, procedural animation or sound delivery. Positive local cube scale is supported, but inherited scale, hidden parts and nonzero render offsets are rejected. The built-in JSON validator covers only the emitted cube subset, not all official geometry schemas. See entity-pipeline-current-state.md and PackConverter's docs/entity-static-geometry.md for the exact evidence and separate release gates.
