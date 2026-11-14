plugins {
    id("opencode.android.application")
}

// The languages the app is translated into (docs/translating.md).
val appLanguages = listOf("en")

android {
    namespace = "ai.opencode.app"
    defaultConfig {
        // Matches the iOS bundle identifier.
        applicationId = "ai.opencode.mobilecode"
        // CI stamps builds so testers can tell them apart: the workflow run number as the
        // version code (each build installs over the last), the release tag's version
        // (OC_VERSION_NAME) for releases, else the short commit SHA in the name.
        versionCode = providers.environmentVariable("OC_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("OC_VERSION_NAME").orNull
            ?: ("0.1.0" + (providers.environmentVariable("OC_VERSION_SUFFIX").orNull?.let { "-$it" } ?: ""))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Google Play upload key, from CI secrets only (Play App Signing holds the app signing key).
    // Without OC_UPLOAD_KEYSTORE the release build is unsigned, as before.
    val uploadKeystore = providers.environmentVariable("OC_UPLOAD_KEYSTORE").orNull
    if (uploadKeystore != null) {
        signingConfigs.create("upload") {
            storeFile = file(uploadKeystore)
            storeType = "PKCS12"
            storePassword = providers.environmentVariable("OC_UPLOAD_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("OC_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("OC_UPLOAD_KEYSTORE_PASSWORD").get()
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 is what makes Compose fast in release: it strips the debug-only paths, inlines
            // and devirtualises the runtime. Every library we use ships its own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
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
