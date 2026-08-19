val testModId = "hydraulic_test_mod"

architectury {
    platformSetupLoomIde()
    fabric()
}

fabricApi {
    configureDataGeneration() {
        client = true
        createSourceSet = true
        this.modId = testModId
        outputDirectory = file("${layout.buildDirectory.get()}/generated/datagen")
        addToResources = true
    }
}

val common: Configuration by configurations.creating
val developmentFabric: Configuration = configurations.getByName("developmentFabric")
val includeTransitive: Configuration = configurations.getByName("includeTransitive")

configurations {
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    developmentFabric.extendsFrom(configurations["common"])
}

tasks {
    sourcesJar {
        dependsOn(named("runDatagen"))
    }

    named<Jar>("mergeShadowAndJarJar") {
        dependsOn(named("runDatagen"))
        from(file("${layout.buildDirectory.get()}/generated/datagen")) {
            exclude(".cache/**")
        }
        from(
            zipTree(shadowJar.map { it.outputs.files.singleFile }).matching {
                exclude("fabric.mod.json")
                exclude("LICENSE")
            },
            zipTree(jar.map { it.outputs.files.singleFile }).matching {
                include("META-INF/jars/**")
                include("fabric.mod.json")
                include("LICENSE")
            }
        )
        archiveBaseName.set("$testModId-test-mod-fabric")
    }

    shadowJar {
        archiveClassifier.set("dev-shadow")
    }

    jar {
        archiveClassifier.set("dev")
    }
}

// Generated resources only exist after the datagen process has finished.
// Validate them in Gradle instead of from the mod's normal initialization;
// this prevents the datagen JVM from failing before it can create the files.
tasks.register("verifyGeneratedResources") {
    dependsOn(tasks.named("runDatagen"))

    doLast {
        val outputDir = file("${layout.buildDirectory.get()}/generated/datagen")
        val required = file("$outputDir/assets/$testModId/blockstates/golden_barrel.json")

        check(required.isFile && required.length() > 0) {
            "Missing generated resource: ${required.absolutePath}"
        }

        logger.lifecycle("Verified generated resource: ${required.relativeTo(outputDir)}")
    }
}

tasks.named("build") {
    dependsOn(tasks.named("verifyGeneratedResources"))
}

dependencies {
    implementation(libs.fabric.loader)
    api(libs.fabric.api)
}