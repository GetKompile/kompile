plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.kompile.chat.local.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kompile.chat.local.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-SNAPSHOT"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // libsdx_llm.so is arm64-v8a on physical devices, x86_64 on the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        // Core JARs are Java 17; ART on API 26+ handles Java 8 bytecode;
        // desugar fills the gap for the handful of Java 11 APIs we use.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            // Exclude slf4j duplicate service file that conflicts between
            // kompile-graph-reasoning and other deps.
            excludes += "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
            // Dorkbox (Annotations + Updates) ship identical license/notice files; keep one.
            excludes += "LICENSE.*"
            excludes += "NOTICE.*"
            excludes += "*.txt"
            excludes += "*.blob"
            // nd4j / dl4j ship META-INF/git.properties in every sub-jar; pick first.
            excludes += "META-INF/git.properties"
            // Broad META-INF de-dup for bundled shaded jars (nd4j, guava, protobuf, etc).
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/native-image/**"
        }
        // For any META-INF duplicates not covered by excludes, pick the first occurrence.
        resources.pickFirsts += "META-INF/**"
        jniLibs {
            // Required for JNA to unpack libsdx_llm.so from the AAR at runtime.
            useLegacyPackaging = true
        }
    }

    // Place mavenLocal JARs (kompile-chat-local-core etc.) on the compile path.
    // Android Gradle Plugin resolves these from the standard maven local cache.
}

dependencies {
    // ── Desugaring (needed for java.time on API < 26 and some Java 9+ APIs) ──
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // ── Compose BOM ───────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // ── Activity / Lifecycle ──────────────────────────────────────────────────
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ── Navigation ────────────────────────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── JNA for libsdx_llm.so binding ────────────────────────────────────────
    // The @aar classifier forces AGP to unpack the native libs into jniLibs.
    // We use the AAR form for the app itself; the plain JNA JAR that comes in
    // transitively through kompile-chat-local-core must be excluded to avoid
    // "Duplicate class" errors from D8.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // ── SDX Runtime AAR (optional — place in app/libs/ when available) ────────
    // The fileTree picks up any .aar dropped in app/libs/. The code guards
    // all JNA load calls with try/catch UnsatisfiedLinkError so the app
    // runs without this artifact (remote-only mode).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // ── Kompile core libraries (installed to mavenLocal via `mvn install`) ────
    // kompile-chat-local-core: ChatEngine, ChatModel, Message, GenOptions, …
    // Exclude the plain JNA JAR it pulls transitively — the @aar above wins.
    implementation("ai.kompile:kompile-chat-local-core:0.1.0-SNAPSHOT") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // kompile-graph-reasoning-local: LocalReasoningSession, LocalToolDispatcher
    implementation("ai.kompile:kompile-graph-reasoning-local:0.1.0-SNAPSHOT") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // kompile-graph-reasoning: UnifiedGraph, ReasoningTrace, …
    implementation("ai.kompile:kompile-graph-reasoning:0.1.0-SNAPSHOT") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }

    // ── org.json (ships on Android; declared for IDE resolution) ─────────────
    // org.json is part of the Android SDK; do NOT add a separate dep —
    // it would conflict. The comment is here only for clarity.

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
