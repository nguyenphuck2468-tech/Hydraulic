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
        outputDirectory = file("src/main/generated")
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

sourceSets {
    named("main") {
        resources.srcDir(file("src/main/generated"))
    }
}

tasks {
    sourcesJar {
        dependsOn(named("runDatagen"))
    }

    named<Jar>("mergeShadowAndJarJar") {
        dependsOn(named("runDatagen"))
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

tasks.named("build") {
    dependsOn(tasks.named("runDatagen"))
}

dependencies {
    implementation(libs.fabric.loader)
    api(libs.fabric.api)
}