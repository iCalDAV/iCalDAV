plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "org.onekash.icaldav.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Enable desugaring for java.time APIs on API 21+
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core library desugaring for java.time on API 21+
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Expose icaldav-core models to consumers
    api(project(":icaldav-core"))

    // Optional: icaldav-sync for provider implementations
    // Consumers who want to use CalendarContractEventProvider/SyncHandler
    // must add icaldav-sync as a dependency
    compileOnly(project(":icaldav-sync"))

    // Android annotations
    implementation("androidx.annotation:annotation:1.7.1")

    // JUnit 4 for Robolectric tests (Robolectric doesn't support JUnit 5 directly)
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2") // Run JUnit 4 tests on JUnit Platform
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    // Instrumented tests
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("com.google.truth:truth:1.1.5")

    // icaldav-client for integration tests against real CalDAV servers
    androidTestImplementation(project(":icaldav-client"))
}

tasks.withType<Test> {
    useJUnitPlatform() // Use vintage engine for JUnit 4 tests
}

// Source JAR
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

// Javadoc JAR (using Dokka)
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(sourcesJar)
                artifact(javadocJar)

                groupId = "org.onekash"
                artifactId = "icaldav-android"
                version = project.version.toString()

                pom {
                    name.set("iCalDAV Android")
                    description.set("Android CalendarContract mapper for iCalDAV - bridges RFC 5545 events to Android's system calendar")
                    url.set("https://github.com/iCalDAV/iCalDAV")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }

                    developers {
                        developer {
                            id.set("onekash")
                            name.set("OneKash")
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

    // Fix task dependency: metadata generation needs sourcesJar and javadocJar
    tasks.named("generateMetadataFileForReleasePublication") {
        dependsOn(sourcesJar)
        dependsOn(javadocJar)
    }
}

signing {
    val signingKeyFile = System.getenv("SIGNING_KEY_FILE")
    val signingKey: String? = (findProperty("signingKey") as String?)?.replace("\\n", "\n")
        ?: System.getenv("SIGNING_KEY")
        ?: signingKeyFile?.let { path ->
            val file = File(path)
            if (file.exists()) file.readText() else null
        }
    val signingPassword: String = (findProperty("signingPassword") as String?)
        ?: System.getenv("SIGNING_PASSWORD")
        ?: ""

    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

tasks.withType<Sign>().configureEach {
    onlyIf {
        val signingKeyFile = System.getenv("SIGNING_KEY_FILE")
        val key = (findProperty("signingKey") as String?)
            ?: System.getenv("SIGNING_KEY")
            ?: signingKeyFile?.let { path ->
                val file = File(path)
                if (file.exists()) file.readText() else null
            }
        !key.isNullOrBlank()
    }
}
