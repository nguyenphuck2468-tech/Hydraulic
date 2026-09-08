# Block geometry fix: Hydraulic integration and validation

Hydraulic previously reused a converted pack whenever its mod-resource UUID matched. That would continue delivering old oversized block geometry even after fixing PackConverter. This change uses PackConverter 3.5.2-SNAPSHOT from exact commit `ff3d22e484abf7551ca0f05be5e35737f4faf1dc` and includes a geometry-generation marker in the same UUID calculation used by manifest generation and cache checks.

Both CI workflows check out that exact fork commit, test it on JVM 25 (Gradle itself launches on Java 21 because of its older wrapper), publish its converter/schema artifacts to local Maven, and restore Java 25 before building Hydraulic. The project repository filter resolves this version exclusively from Maven local rather than an unrelated public snapshot.

For a local build, check out the pinned PackConverter commit and use JDK 21 to launch:

```text
./gradlew :converter:test :converter:publishToMavenLocal :pack-schema-api:publishToMavenLocal :bedrock-pack-schema:publishToMavenLocal -PtestJavaVersion=25
```

With JDK 25, build Hydraulic:

```text
./gradlew :shared:test :fabric:build
```

## Verified locally

- PackConverter: 15 tests passed on JVM 25, including cause-level oversized-block failure, transformed/aggregate bounds, warning, healthy siblings and golden JSON using the real serializer. Before the fix, the oversized assertion failed and the normal slab passed.
- Hydraulic: `:shared:test :fabric:build` passed on Java 25. PackGenerationTest: 2 tests, 0 failures. Legacy identity changes while unchanged inputs remain stable; a modified resource changes the UUID.
- Opened the final `fabric/build/libs/hydraulic-fabric.jar`, located `META-INF/jars/converter-3.5.2-SNAPSHOT.jar`, and compared all entries against the tested local converter publication. All original entries match byte for byte. Loom adds only `fabric.mod.json` to the nested dependency.
- Existing compiler/Shadow warnings remain. A successful build is not a clean runtime log.

## Limits

This is block geometry failure containment, with an explicit visible cube replacement for unsupported shapes. It does not restore Ad Astra rocket rendering or add a mob pipeline. The original issue world/JAR is not available. Server startup with fixture mobs, two-start runtime reuse, and actual Bedrock chunk rendering remain unverified. NeoForge is disabled on current master and was not built. Do not mark upstream #12 fully resolved or release as visually verified based on these checks.

Related PRs: https://github.com/nguyenphuck2468-tech/PackConverter/pull/7 and source audit https://github.com/nguyenphuck2468-tech/Hydraulic/pull/7 .
