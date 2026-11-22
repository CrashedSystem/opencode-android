plugins {
    id("opencode.android.application")
}

// The languages the app is translated into (docs/translating.md).
val appLanguages = listOf(
    "en", "am", "az", "bg", "bn", "bs", "ca", "cs", "da", "de", "dz", "el", "es", "et", "fi", "fo",
    "fr", "hi", "hr", "hu", "hy", "in", "is", "it", "ja", "ka", "km", "ko", "lo", "lt", "lv", "mk",
    "mn", "ms", "my", "nb", "ne", "nl", "pl", "pt-rBR", "ro", "ru", "si", "sk", "sl", "sq", "sr",
    "sv", "tg", "th", "tk", "tr", "uk", "uz", "vi", "zh-rCN", "zh-rTW",
)

android {
    namespace = "ai.opencode.app"
    defaultConfig {
        applicationId = "ai.opencode.mobilecode"
        // CI stamps builds so testers can tell them apart: the version code (each build installs
        // over the last), the release's version (OC_VERSION_NAME) for releases, else the short
        // commit SHA in the name.
        versionCode = providers.environmentVariable("OC_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("OC_VERSION_NAME").orNull
            ?: ("0.1.0" + (providers.environmentVariable("OC_VERSION_SUFFIX").orNull?.let { "-$it" } ?: ""))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The release bundle is built unsigned; release.yml signs it with the Play upload key.
    signingConfigs {
        // Every CI run starts with an empty ~/.android, so its generated debug key changed on each
        // build and every sideloaded update failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE. This
        // fork signs debug builds with a key that outlives the runner: OPENCODE_DEBUG_KEYSTORE is
        // the base64 PKCS12 file, set from a GitHub secret (or a local file when building here).
        create("forkDebug") {
            val encoded = providers.environmentVariable("OPENCODE_DEBUG_KEYSTORE").orNull
                ?: providers.gradleProperty("opencodeDebugKeystore").orNull
            if (!encoded.isNullOrBlank()) {
                val file = layout.buildDirectory.file("fork-debug.jks").get().asFile
                file.parentFile.mkdirs()
                file.writeBytes(java.util.Base64.getMimeDecoder().decode(encoded))
                storeFile = file
                storePassword = System.getenv("OPENCODE_DEBUG_STORE_PASSWORD") ?: "opencode"
                keyAlias = System.getenv("OPENCODE_DEBUG_KEY_ALIAS") ?: "opencode-debug"
                keyPassword = System.getenv("OPENCODE_DEBUG_KEY_PASSWORD") ?: "opencode"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("forkDebug").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
        release {
            // R8 is what makes Compose fast in release: it strips the debug-only paths, inlines
            // and devirtualises the runtime. Every library we use ships its own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
        }
    }
    buildFeatures {
        buildConfig = true
    }
    androidResources {
        // Library (Material, AndroidX) translations are kept only for the app's own languages, so
        // their labels, e.g. a sheet's "Drag handle", never read in a language the app isn't in.
        localeFilters += appLanguages
        // Android's per-app language setting lists the languages that have a values-xx folder.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":server"))
    implementation(project(":feature:session"))
    implementation(project(":feature:transcript"))
    implementation(project(":feature:composer"))
    implementation(project(":feature:drawer"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:media"))
    implementation(project(":feature:system"))
    implementation(project(":devtools"))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.compose.ui.tooling)
    // TestIsolationGuard, WireFixture and the network block (see AGENTS.md "Tests").
    testImplementation(testFixtures(project(":server")))
    // AppWideWorkTests: real window stores, whose sends need Android's main looper.
    testImplementation(libs.robolectric)
    constraints {
        implementation(libs.androidx.fragment) {
            because("Firebase Messaging's Play services pull in fragment 1.1.0, which Google Play flags as outdated")
        }
    }
}
