import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
    id("jacoco")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // OpenTelemetry SDK
    implementation("io.opentelemetry:opentelemetry-api:1.38.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.38.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.38.0")
    implementation("io.opentelemetry:opentelemetry-sdk-trace:1.38.0")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
    testImplementation("io.mockk:mockk:1.13.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellij {
    pluginName = providers.gradleProperty("pluginName").get()
    version = providers.gradleProperty("platformVersion").get()
    type = providers.gradleProperty("platformType").get()
    plugins = listOf()
    downloadSources = true
    updateSinceUntilBuild = true
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    patchPluginXml {
        sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
        untilBuild = providers.gradleProperty("pluginUntilBuild").get()
    }

    test {
        useJUnitPlatform()
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required = true
            html.required = true
        }
        classDirectories.setFrom(
            fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
                include("**/services/**")
            }
        )
    }

    jacocoTestCoverageVerification {
        violationRules {
            rule {
                element = "PACKAGE"
                includes = listOf("com.github.copilotmonitor.services")
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = "0.70".toBigDecimal()
                }
            }
        }
    }
}
