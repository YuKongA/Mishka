import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity)
    implementation(libs.quickie.bundled)
    implementation(libs.hiddenapibypass)
}


@Suppress("UnstableApiUsage")
android {
    val properties = Properties()
    runCatching { properties.load(project.rootProject.file("local.properties").inputStream()) }
    val keystorePath = properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
    val keystorePwd = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
    val pwd = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        versionName = ProjectConfig.VERSION_NAME
        versionCode = getGitVersionCode()
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    namespace = ProjectConfig.PACKAGE_NAME
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a")
        }
    }
}

abstract class DownloadGeoFilesTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val geoFilesUrls = mapOf(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        )
        val dir = outputDir.get().asFile
        dir.mkdirs()
        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val outputPath = File(dir, outputFileName)
            URI(downloadUrl).toURL().openStream().use { input ->
                Files.copy(input, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("$outputFileName downloaded to $outputPath")
            }
        }
    }
}

val downloadGeoFiles = tasks.register<DownloadGeoFilesTask>("downloadGeoFiles") {
    outputDir.set(layout.projectDirectory.dir("src/main/assets"))
}

tasks.configureEach {
    if (name.startsWith("assemble") || name.startsWith("merge") && name.contains("Assets")) {
        dependsOn(downloadGeoFiles)
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.add("**")
    }
}

base {
    archivesName.set(
        "${ProjectConfig.APP_NAME}-v${ProjectConfig.VERSION_NAME}(${getGitVersionCode()})",
    )
}
