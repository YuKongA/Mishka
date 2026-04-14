plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val generatedSrcDir = layout.buildDirectory.dir("generated/projectConfig")

kotlin {
    android {
        androidResources.enable = true
        compileSdk {
            version = release(ProjectConfig.Android.COMPILE_SDK) {
                minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
            }
        }
        minSdk = ProjectConfig.Android.MIN_SDK
        namespace = "${ProjectConfig.PACKAGE_NAME}.shared"
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedSrcDir.map { it.dir("kotlin") })
        }
        commonMain.dependencies {
            api(libs.miuix.ui)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.navigation3.ui)
            implementation(libs.material.icons.extended)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.jetbrains.navigationevent)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.components.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.websockets)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
        }

        named("androidMain").dependencies {
            implementation(libs.androidx.activity)
            implementation(libs.ktor.client.okhttp)
        }

        named("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

compose.resources {
    publicResClass = true
}

val generateVersionInfo by tasks.registering(GenerateVersionInfoTask::class) {
    versionName.set(ProjectConfig.VERSION_NAME)
    versionCode.set(getGitVersionCode())
    outputFile.set(generatedSrcDir.map { it.file("kotlin/misc/VersionInfo.kt") })
}

tasks.named("generateComposeResClass").configure {
    dependsOn(generateVersionInfo)
}
