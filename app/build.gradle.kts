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

// 每次求值都 fork 一个 git 进程，且 versionCode 与 archivesName 都要用，求值一次共用
val gitVersionCode = getGitVersionCode()

val properties = Properties()
runCatching { project.rootProject.file("local.properties").inputStream().use { properties.load(it) } }
val keystorePath: String? = properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
val keystorePwd: String? = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
val alias: String? = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
val pwd: String? = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")

@Suppress("UnstableApiUsage")
android {
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
            optimization.enable = true
            vcsInfo.include = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
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
        applicationId = ProjectConfig.APPLICATION_ID
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
    namespace = ProjectConfig.NAMESPACE
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
            include("arm64-v8a")
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
        // 插件只关旧 DSL 的 isMinifyEnabled，管不到随 initWith(release) 继承来的 optimization.enable
        ext.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
        if (keystorePath == null) {
            val debugSigning = ext.signingConfigs.getByName("debug")
            listOf("nonMinifiedRelease", "benchmarkRelease").forEach { name ->
                ext.buildTypes.findByName(name)?.signingConfig = debugSigning
            }
        }
    }
}

abstract class DownloadGeoFilesTask : DefaultTask() {
    /** 下载地址 → 落盘文件名。声明为 @Input，改地址才会让任务失效重跑。 */
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
                // 无超时时对端挂起就是构建无限期卡住，CI 上只表现为「这次特别慢」
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
            // 404 / 限流返回的 HTML 会被原样写成 geoip.metadb：构建全绿，运行时 GeoIP 加载失败。
            // 三个数据文件最小的也有数 MB，下限足以拦住任何错误页
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
    // 上游 `latest` tag 是原地重发布的：URL 不变、本地文件也没被别人动过，Gradle 的
    // up-to-date 判定会一直跳过，本地永远停在第一次抓到的那份数据上。任务只在被显式
    // 调用时才跑（没有任何任务依赖它），「调了就去取最新」正是它该有的语义
    outputs.upToDateWhen { false }
}

// outputDir 既是本任务的 @OutputDirectory 又是 mergeAssets 的输入源。两者同时出现在一次
// 调用里时，Gradle 会以「消费了未声明依赖的任务输出」中止构建；下载仍保持手动 / CI 触发
// （需要网络，不该挂进 assemble），这里只补上先后关系
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    mustRunAfter(downloadGeoFiles)
}

val mihomoSubmoduleDir = rootProject.layout.projectDirectory.dir("mihomo")
val mishkaCoreSourceDir = rootProject.layout.projectDirectory.dir("app/src/main/native/mishka_core")
val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")
val mihomoVersion = providers.gradleProperty("mihomo.version").orElse("dev").get()
val mihomoVersionPath = "github.com/metacubex/mihomo/constant.Version"
val ndkDirectoryProvider = androidComponents.sdkComponents.ndkDirectory

val supportedAbis = listOf("arm64-v8a")

// libmihomo.so 同时承担两个职责：
//  1. 订阅导入 JNI 路径（libmishka_jni.so dlopen + dlsym mishkaFetchAndValid 等）
//  2. mihomo runtime（薄 wrapper libmihomo_runner.so dlopen + dlsym mihomoEntry，fork+exec 启动）
val buildMihomoTasks = supportedAbis.map { abi ->
    val suffix = abi.replace("-", "_")
    tasks.register<GoBuildTask>("buildMihomo_$suffix") {
        group = "mihomo"
        description = "Build unified mihomo + JNI c-shared library (libmihomo.so) for $abi"
        goSourceDir.set(mishkaCoreSourceDir)
        // mihomo 经 go.mod replace 引入，绝大部分被编译的代码在这里。反向过滤（全收再排除）
        // 而非按扩展名列举：component/ca 有 go:embed 嵌 .crt，白名单漏一种后缀就是同一个坑
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

tasks.named("preBuild") {
    dependsOn(buildMihomoTasks)
}

// CMake 链接阶段引用 libmihomo.so + libmihomo.h
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(buildMihomoTasks)
    }
}

androidComponents {
    // release 剥掉全部打包资源（LICENSE、kotlin 元数据等）。Ktor 的 ServiceLoader 描述符不受
    // 影响：AGP 默认的 packaging.resources.merges 含 META-INF/services/**，merge 优先于 excludes
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.add("**")
    }
}

base {
    archivesName.set(
        "${ProjectConfig.APP_NAME}-v${ProjectConfig.VERSION_NAME}($gitVersionCode)",
    )
}
