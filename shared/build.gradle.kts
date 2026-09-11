architectury {
    common("neoforge", "fabric")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.geyser.core) {
        // These tests exercise registration boundaries, not a network server.
        exclude(group = "io.netty")
        exclude(group = "io.netty.incubator")
    }
    testImplementation(libs.bundles.configurate)
    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras)
    compileOnly(libs.geyser.api)
    compileOnly(libs.geyser.core) {
        exclude(group = "io.netty")
        exclude(group = "io.netty.incubator")
    }

    api(libs.pack.converter)
    compileOnly(libs.examination.api)

    implementation(libs.auto.service)
    annotationProcessor(libs.auto.service)

    annotationProcessor(libs.configurate.`interface`.ap)
    compileOnly(libs.bundles.configurate)

    // Only here to suppress "unknown enum constant EnvType.CLIENT" warnings.
    compileOnly(libs.fabric.loader)
}

// A real filesystem JAR is required by ProcessBuilder, even when Fabric loads our
// dependencies from nested JARs. Bundle only the worker readers and Gson, never mod classes.
val entityWorker by tasks.registering(Jar::class) {
    archiveFileName.set("entity-worker.jar")
    destinationDirectory.set(layout.buildDirectory.dir("entity-worker"))
    from("src/main/worker-licenses") { into("META-INF/licenses") }
    from({ configurations.compileClasspath.get().files.filter {
        it.name.startsWith("converter-") || it.name.startsWith("gson-")
    }.map { zipTree(it) } }) {
        include("org/geysermc/pack/converter/type/entity/**", "com/google/gson/**")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.processResources {
    from(entityWorker) { into("hydraulic") }
    from(rootProject.file("gradle/pack-converter.ref")) { into("hydraulic") }
}
tasks.test {
    useJUnitPlatform()
    providers.gradleProperty("entityFixtureClasspath").orNull?.let {
        systemProperty("entity.fixture.classpath", it)
    }
}
