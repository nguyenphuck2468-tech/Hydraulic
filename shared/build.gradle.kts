architectury {
    common("neoforge", "fabric")
}

dependencies {
    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras)
    compileOnly(libs.geyser.api)
    compileOnly(libs.geyser.core) {
        exclude(group = "io.netty")
        exclude(group = "io.netty.incubator")
    }

    // Examinable is exposed by Kyori Creative/Geyser types used during compilation,
    // but it is not reliably surfaced transitively on the shared compile classpath.
    compileOnly("net.kyori:examination-api:1.3.0")

    api(libs.pack.converter)

    implementation(libs.auto.service)
    annotationProcessor(libs.auto.service)

    // Only here to suppress "unknown enum constant EnvType.CLIENT" warnings.
    compileOnly(libs.fabric.loader)

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Java 25 validates type annotations on PackModule's Geyser event generic.
    // Keep the production dependency compile-only, but make that API available
    // to test compilation and execution.
    testImplementation(libs.geyser.core) {
        exclude(group = "io.netty")
        exclude(group = "io.netty.incubator")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
