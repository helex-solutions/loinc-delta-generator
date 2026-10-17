// A single runnable jar with no runtime dependencies: `java -jar loinc-delta-generator.jar
// <previous.zip> <current.zip>`. Releases attach the jar to a GitHub release (.github/workflows/release.yml),
// so a host can download a pinned version and verify its SHA-256.
plugins {
    application
}

group = "org.helex.loinc"
// `releaseVersion`, not `version`: CI passes -PreleaseVersion=<tag without v>.
version = providers.gradleProperty("releaseVersion").orNull ?: "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Built with any JDK 21+, runs on Java 21+. `--release`, not a toolchain, so no JDK download.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.helex.loinc.delta.LoincDeltaGenerator")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.jar {
    archiveBaseName.set("loinc-delta-generator")
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "loinc-delta-generator",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
