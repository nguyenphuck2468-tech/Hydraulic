val modId = project.property("mod_id") as String

architectury {
    platformSetupLoomIde()
    fabric()
}

fabricApi {
    configureDataGeneration() {
        client = true
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

sourceSets {
    named("main") {
        resources.srcDir("src/main/generated")
    }
}

tasks {
    val runDatagen = named("runDatagen")

    named<ProcessResources>("processResources") {
        from("src/main/generated")
        // Ordering constraint only: runDatagen already participates in the
        // build lifecycle below. Do not use dependsOn here, because Fabric's
        // datagen task compiles the test sources and that would create a cycle.
        mustRunAfter(runDatagen)
    }

    sourcesJar {
        dependsOn(runDatagen) // Make sure the sources jar gets our generated files
    }

    named<Jar>("mergeShadowAndJarJar") {
        from (
            zipTree( shadowJar.map { it.outputs.files.singleFile } ).matching {
                exclude("fabric.mod.json")
                exclude("LICENSE")
            },
            zipTree( jar.map { it.outputs.files.singleFile } ).matching {
                include("META-INF/jars/**")
                include("fabric.mod.json")
                include("LICENSE")
            }
        )
        archiveBaseName.set("${modId}-test-mod-fabric")
    }

    shadowJar {
        archiveClassifier.set("dev-shadow")
    }

    jar {
        archiveClassifier.set("dev")
    }
}

// Ensure datagen completes before the build's resource/jar tasks consume its output.
tasks.named("build") { dependsOn(tasks.named("runDatagen")) }

dependencies {
    implementation(libs.fabric.loader)
    api(libs.fabric.api)
}