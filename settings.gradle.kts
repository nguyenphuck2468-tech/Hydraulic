enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        // mavenLocal()
        mavenCentral()

        gradlePluginPortal()

        // Geyser, Floodgate, Cumulus etc.
        maven("https://repo.opencollab.dev/main/")

        // Minecraft
        maven("https://libraries.minecraft.net") {
            name = "minecraft"
            mavenContent {
                releasesOnly()
            }
        }

        // Architectury
        maven("https://maven.architectury.dev/")

        // NeoForge
        maven("https://maven.neoforged.net/releases/")

        // Fabric
        maven("https://maven.fabricmc.net/")

        // Sponge Snapshots
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()

        // Geyser, Floodgate, Cumulus etc.
        maven("https://repo.opencollab.dev/main/")

        // Architectury
        maven("https://maven.architectury.dev/")

        // NeoForge
        maven("https://maven.neoforged.net/releases")

        // Fabric
        maven("https://maven.fabricmc.net/")
    }

    plugins {
        id("net.kyori.blossom") version "2.2.0"
        id("net.kyori.indra")
        id("net.kyori.indra.git")
    }

    includeBuild("build-logic")
}

// When supplied, build Hydraulic against a checked-out PackConverter fork.
// This keeps normal Maven dependency resolution as the default for consumers,
// while CI can validate the exact forked library commit used by the project.
val packConverterPath = providers.gradleProperty("packConverterPath")
    .orElse(providers.environmentVariable("PACK_CONVERTER_PATH"))
    .orNull

if (!packConverterPath.isNullOrBlank()) {
    val packConverterDir = file(packConverterPath)
    require(packConverterDir.isDirectory) {
        "PackConverter composite build directory does not exist: $packConverterDir"
    }
    includeBuild(packConverterDir) {
        dependencySubstitution {
            substitute(module("org.geysermc.pack:converter"))
                .using(project(":converter"))
        }
    }
}

rootProject.name = "hydraulic-parent"

include(":shared")
include(":fabric")
// include(":neoforge")

include(":test")
