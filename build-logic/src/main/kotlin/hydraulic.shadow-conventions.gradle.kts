import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("hydraulic.base-conventions")
    id("com.gradleup.shadow")
}

val currentProjectName = project.name
val licenseFile = rootProject.file("LICENSE")

tasks {
    named<Jar>("jar") {
        archiveClassifier.set("unshaded")
        from(licenseFile)
    }
    val shadowJar = named<ShadowJar>("shadowJar") {
        archiveBaseName.set(currentProjectName)
        archiveVersion.set("")
        archiveClassifier.set("")

        val sJar: ShadowJar = this

        doFirst {
            providedDependencies[currentProjectName]?.forEach { string ->
                sJar.dependencies {
                    println("Excluding $string from $currentProjectName")
                    exclude(dependency(string))
                }
            }

            sJar.dependencies {
                exclude(dependency("org.checkerframework:checker-qual:.*"))
                exclude(dependency("org.jetbrains:annotations:.*"))
            }
        }
    }
    named("build") {
        dependsOn(shadowJar)
    }
}
