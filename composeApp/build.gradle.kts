import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
val githubRepository = "mengxyz/modbus_tool"
val generatedBuildConfigDirectory = layout.buildDirectory.dir("generated/appBuildConfig/kotlin")

kotlin {
    jvmToolchain(17)
    jvm("desktop")

    sourceSets {
        getByName("commonMain").dependencies {
            implementation("org.jetbrains.compose.components:components-resources:1.12.0")
        }
        getByName("desktopMain").dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.compose.material:material:1.12.0")
            implementation(libs.coroutines.core)
            implementation(libs.coroutines.swing)
            implementation(libs.jserialcomm)
            implementation(libs.serialization.json)
        }
        getByName("desktopMain").kotlin.srcDir(generatedBuildConfigDirectory)
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

val generateBuildConfig by tasks.registering {
    inputs.property("appVersion", appVersion)
    inputs.property("githubRepository", githubRepository)
    outputs.dir(generatedBuildConfigDirectory)

    doLast {
        val output = generatedBuildConfigDirectory.get()
            .file("dev/modbustool/BuildConfig.kt")
            .asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            package dev.modbustool

            internal object BuildConfig {
                const val VERSION = "$appVersion"
                const val RELEASES_URL = "https://github.com/$githubRepository/releases"
                const val LATEST_RELEASE_API = "https://api.github.com/repos/$githubRepository/releases/latest"
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn(generateBuildConfig)
}

compose.desktop {
    application {
        mainClass = "dev.modbustool.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Modbus Tool"
            packageVersion = appVersion
            description = "Modbus RTU, TCP, and UDP engineering client"
            modules("java.base", "java.desktop", "java.logging", "java.prefs")
            macOS {
                bundleID = "dev.modbustool"
            }
        }
    }
}
