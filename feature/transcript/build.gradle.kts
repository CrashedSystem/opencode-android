plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.transcript"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

// The widget runner's libraries, built by scripts/psychoscript.sh (gitignored).
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(rootProject.file("vendor/psychoscript/jniLibs").path)
    }
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:media"))
    implementation(libs.coil.compose)
    implementation(libs.jlatexmath.android)
    // The aar carries JNA's native dispatch library for every ABI.
    implementation(libs.jna) { artifact { type = "aar" } }
    // android.icu (time stamps), MimeTypeMap/Uri/ClipData (drag payloads) in JVM tests.
    testImplementation(libs.robolectric)
}
