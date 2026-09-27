plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineProfile)
}

dependencies {
    implementation(libs.bundles.baselineprofile)
}

android {
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    defaultConfig {
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        buildConfigField("String", "TARGET_APP_ID", "\"${ProjectConfig.APPLICATION_ID}\"")
    }
    namespace = "${ProjectConfig.NAMESPACE}.baselineprofile"
    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}
