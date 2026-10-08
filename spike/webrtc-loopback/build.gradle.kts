plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ai.opencode.spike.webrtc"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "ai.opencode.spike.webrtc"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

val webrtc = when (val choice = providers.gradleProperty("webrtc").getOrElse("livekit")) {
    "livekit" -> "io.github.webrtc-sdk:android:150.7871.01"
    "stream" -> "io.getstream:stream-video-webrtc-android:146.7.0"
    else -> error("unknown -Pwebrtc=$choice")
}

dependencies {
    implementation(webrtc)
}
