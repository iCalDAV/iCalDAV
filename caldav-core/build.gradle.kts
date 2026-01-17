plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}

// Source JAR
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

// Javadoc JAR (using Dokka)
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}

dependencies {
    // Internal dependencies
    api(project(":icalendar-core"))
    api(project(":webdav-core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(javadocJar)

            pom {
                name.set("iCalDAV - CalDAV Core")
                description.set("CalDAV client library for Kotlin/JVM with provider quirks handling")
                url.set("https://github.com/iCalDAV/iCalDAV")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("iCalDAV")
                        name.set("iCalDAV Team")
                        url.set("https://github.com/iCalDAV")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/iCalDAV/iCalDAV.git")
                    developerConnection.set("scm:git:ssh://github.com/iCalDAV/iCalDAV.git")
                    url.set("https://github.com/iCalDAV/iCalDAV")
                }
            }
        }
    }

    repositories {
        maven {
            name = "Local"
            url = uri("${rootProject.buildDir}/local-repo")
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD") ?: ""

    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}

tasks.withType<Sign>().configureEach {
    onlyIf { findProperty("signingKey") != null || System.getenv("SIGNING_KEY") != null }
}
