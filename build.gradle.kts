// A single runnable jar with no runtime dependencies: `java -jar loinc-delta-generator.jar
// <previous.zip> <current.zip>`. Releases attach the jar to a GitHub release (.github/workflows/release.yml),
// so a host can download a pinned version and verify its SHA-256. The same jar is published to
// GitHub Packages as org.helex.loinc:loinc-delta-generator.
plugins {
    application
    `maven-publish`
}

group = "org.helex.loinc"
// `releaseVersion`, not `version`: CI passes -PreleaseVersion=<tag without v>.
version = providers.gradleProperty("releaseVersion").orNull ?: "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Built with any JDK 21+, runs on Java 21+. `--release`, not a toolchain, so no JDK download.
java {
    withSourcesJar()
}

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

// GitHub Packages Maven registry. Reading from it needs a token even though the repository is public.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "loinc-delta-generator"
            from(components["java"])
            pom {
                name.set("loinc-delta-generator")
                description.set("Diffs two LOINC release zips into a delta zip: added, modified and removed rows for every file.")
                url.set("https://github.com/helex-solutions/loinc-delta-generator")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/helex-solutions/loinc-delta-generator")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/helex-solutions/loinc-delta-generator")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
