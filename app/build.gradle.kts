import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.baselineProfile)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    id("mishka.build-logic")
}

dependencies {
    baselineProfile(projects.baselineprofile)
    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.androidx.navigationevent)
    implementation(libs.bundles.room)
    implementation(libs.bundles.kotlinx)
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.miuix)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.material.icons.extended)
    implementation(libs.hiddenapibypass)
    implementation(libs.quickie.bundled)
    implementation(libs.scripta.editor)
    ksp(libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val gitVersionCode = getGitVersionCode()

val properties = Properties()
runCatching { project.rootProject.file("local.properties").inputStream().use { properties.load(it) } }
val keystorePath: String? = (properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH"))?.takeIf { it.isNotBlank() }
val keystorePwd: String? = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
val alias: String? = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
val pwd: String? = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
val keystoreFile = keystorePath?.let { path ->
    file(path).takeIf { it.exists() } ?: project.rootProject.file(path).takeIf { it.exists() }
}

@Suppress("UnstableApiUsage")
android {
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
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
            optimization.enable = true
            vcsInfo.include = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        versionName = ProjectConfig.VERSION_NAME
        versionCode = gitVersionCode
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
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include(*supportedAbis.toTypedArray())
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("app/compose_compiler_config.conf")
    )
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

androidComponents {
    finalizeDsl { ext ->
        // AGP 9: 关闭 nonMinifiedRelease 继承自 release 的 optimization.enable
        ext.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
        if (keystoreFile == null) {
            val debugSigning = ext.signingConfigs.getByName("debug")
            listOf("nonMinifiedRelease", "benchmarkRelease").forEach { name ->
                ext.buildTypes.findByName(name)?.signingConfig = debugSigning
            }
        }
    }
}

abstract class DownloadGeoFilesTask : DefaultTask() {
    @get:Input
    abstract val sources: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        sources.get().forEach { (downloadUrl, fileName) ->
            val target = File(dir, fileName)
            val partial = File(dir, "$fileName.part")
            val connection = (URI(downloadUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
            try {
                val status = connection.responseCode
                check(status == HttpURLConnection.HTTP_OK) { "$downloadUrl responded HTTP $status" }
                connection.inputStream.use {
                    Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                connection.disconnect()
            }
            // 校验文件体积下限，防止 404/限流 HTML 伪装成数据文件落盘
            val size = partial.length()
            if (size < MIN_FILE_BYTES) {
                partial.delete()
                error("$fileName is only $size bytes, expected a data file (bad URL or rate limited?)")
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.lifecycle("$fileName downloaded to $target ($size bytes)")
        }
    }

    private companion object {
        const val HTTP_TIMEOUT_MS = 30_000
        const val MIN_FILE_BYTES = 512 * 1024L
    }
}

val downloadGeoFiles = tasks.register<DownloadGeoFilesTask>("downloadGeoFiles") {
    description = "Download the prebuilt GeoIP/GeoSite data files into the assets source set"
    sources.set(
        mapOf(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        )
    )
    outputDir.set(layout.projectDirectory.dir("src/main/assets"))
    // 上游 latest tag 原地更新时 URL 不变，强制每次显式调用均重新拉取
    outputs.upToDateWhen { false }
}

// 避免 outputDir 与 mergeAssets 输入重叠导致未声明任务依赖冲突
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    mustRunAfter(downloadGeoFiles)
}

val mihomoSubmoduleDir = rootProject.layout.projectDirectory.dir("mihomo")
val mishkaCoreSourceDir = rootProject.layout.projectDirectory.dir("app/src/main/native/mishka_core")
val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")
val mihomoVersion = providers.gradleProperty("mihomo.version").orElse("dev").get()
val mihomoVersionPath = "github.com/metacubex/mihomo/constant.Version"
val ndkDirectoryProvider = androidComponents.sdkComponents.ndkDirectory

// 编译 libmihomo.so（JNI + runtime wrapper 共用）
val buildMihomoTasks = supportedAbis.map { abi ->
    val suffix = abi.replace("-", "_")
    tasks.register<GoBuildTask>("buildMihomo_$suffix") {
        group = "mihomo"
        description = "Build unified mihomo + JNI c-shared library (libmihomo.so) for $abi"
        goSourceDir.set(mishkaCoreSourceDir)
        // 追踪 replace 指向的 submodule 源码；反向过滤以覆盖 go:embed 内嵌资源
        replacedModuleSources.from(
            mihomoSubmoduleDir.asFileTree.matching {
                exclude(".git", ".github/**", "docs/**", "test/**", "**/*_test.go")
            }
        )
        this.abi.set(abi)
        versionName.set(mihomoVersion)
        buildTags.set(mihomoBuildTags)
        cgoEnabled.set(true)
        buildMode.set(GoBuildTask.BuildMode.CShared)
        ndkDirectory.set(ndkDirectoryProvider)
        minSdk.set(ProjectConfig.Android.MIN_SDK)
        moduleVersionPath.set(mihomoVersionPath)
        outputFile.set(layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.so"))
        headerFile.set(layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.h"))
    }
}

val buildMihomo = tasks.register("buildMihomo") {
    group = "mihomo"
    description = "Build unified mihomo library for all supported ABIs"
    dependsOn(buildMihomoTasks)
}

tasks.named("preBuild") {
    dependsOn(buildMihomo)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(buildMihomo)
    }
}

androidComponents {
    // 剥除 release 冗余打包资源（不影响 services/**）
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.add("**")
    }
}

base {
    archivesName.set(
        "${ProjectConfig.APP_NAME}-v${ProjectConfig.VERSION_NAME}($gitVersionCode)",
    )
}
