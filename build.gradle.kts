plugins {
    kotlin("jvm") version "1.9.22" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.dokka") version "1.9.10" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.4" apply false
    id("jacoco")
    id("signing")
    id("maven-publish")
}

allprojects {
    group = "org.onekash"
    version = findProperty("VERSION_NAME") as String? ?: "2.2.0"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "jacoco")
    apply(plugin = "maven-publish")

    // Configure GitHub Packages repository for all subprojects
    afterEvaluate {
        extensions.findByType<PublishingExtension>()?.apply {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/iCalDAV/iCalDAV")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                        password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
                    }
                }
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Configure jacoco after project evaluation to ensure tasks exist
    afterEvaluate {
        tasks.findByName("test")?.let { testTask ->
            tasks.findByName("jacocoTestReport")?.let { jacocoTask ->
                testTask.finalizedBy(jacocoTask)
            }
        }

        tasks.withType<JacocoReport> {
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    }
}
