plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val defaultHexagonAar = layout.projectDirectory.file(
    "libs/hexagon/sdx-runtime-android-arm64-hexagon.aar"
).asFile.absolutePath
val defaultVulkanAar = layout.projectDirectory.file(
    "libs/vulkan/sdx-runtime-android-arm64-vulkan.aar"
).asFile.absolutePath
val defaultTensorG5Aar = layout.projectDirectory.file(
    "libs/tensor-g5/sdx-chat-runtime-android-arm64-google-tensor-g5.aar"
).asFile.absolutePath
val defaultTensorG3Aar = layout.projectDirectory.file(
    "libs/tensor-g3/sdx-runtime-android-arm64-tensor-g3.aar"
).asFile.absolutePath
val hexagonAar = providers.gradleProperty("sdxHexagonAar")
    .orElse(providers.environmentVariable("SDX_HEXAGON_AAR"))
    .orElse(defaultHexagonAar)
val vulkanAar = providers.gradleProperty("sdxVulkanAar")
    .orElse(providers.environmentVariable("SDX_VULKAN_AAR"))
    .orElse(defaultVulkanAar)
val tensorG5Aar = providers.gradleProperty("sdxTensorG5Aar")
    .orElse(providers.environmentVariable("SDX_TENSOR_G5_AAR"))
    .orElse(defaultTensorG5Aar)
val tensorG3Aar = providers.gradleProperty("sdxTensorG3Aar")
    .orElse(providers.environmentVariable("SDX_TENSOR_G3_AAR"))
    .orElse(defaultTensorG3Aar)
// Optional connected-mode handoff. The APK itself stays offline; ACTION_VIEW opens the
// configured Kompile staging UI in the user's browser. Override reproducibly with
// -PkompileModelStagingUrl=https://host/staging or KOMPILE_MODEL_STAGING_URL.
val modelStagingUrl = providers.gradleProperty("kompileModelStagingUrl")
    .orElse(providers.environmentVariable("KOMPILE_MODEL_STAGING_URL"))
    .orElse("")
val modelStagingUrlLiteral = modelStagingUrl.map { value ->
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "ai.kompile.chat.local.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kompile.chat.local.android"
        // Stock-Graal graph JNI support is built against Android API 28 bionic.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-SNAPSHOT"
        buildConfigField("String", "MODEL_STAGING_URL", modelStagingUrlLiteral.get())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Accelerator releases are physical-device-only and never carry an x86 CPU path.
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "accelerator"
    productFlavors {
        create("vulkan") {
            dimension = "accelerator"
            applicationIdSuffix = ".vulkan"
            versionNameSuffix = "-vulkan"
            buildConfigField("String", "ACCELERATOR_PROVIDER", "\"vulkan-gpu\"")
            buildConfigField("String", "SDX_TARGET_PROFILE", "\"android-arm64-vulkan\"")
            buildConfigField("boolean", "DEVICE_ONLY", "true")
        }
        create("hexagon") {
            dimension = "accelerator"
            applicationIdSuffix = ".hexagon"
            versionNameSuffix = "-hexagon"
            buildConfigField("String", "ACCELERATOR_PROVIDER", "\"hexagon-htp\"")
            buildConfigField("String", "SDX_TARGET_PROFILE", "\"android-arm64-hexagon-htp\"")
            buildConfigField("boolean", "DEVICE_ONLY", "true")
        }
        create("tensorG5") {
            dimension = "accelerator"
            applicationIdSuffix = ".tensorg5"
            versionNameSuffix = "-tensor-g5"
            buildConfigField("String", "ACCELERATOR_PROVIDER", "\"google-tensor-g5\"")
            buildConfigField("String", "SDX_TARGET_PROFILE", "\"android-arm64-google-tensor-g5\"")
            buildConfigField("boolean", "DEVICE_ONLY", "true")
        }
        create("tensorG3") {
            dimension = "accelerator"
            // The accelerator-only NNAPI AAR uses Android 12 NNAPI compilation APIs.
            // Keep API 28 for Vulkan/Graal; raise only the Tensor G3 flavor.
            minSdk = 31
            applicationIdSuffix = ".tensorg3"
            versionNameSuffix = "-tensor-g3-nnapi"
            buildConfigField("String", "ACCELERATOR_PROVIDER", "\"google-tensor-g3-nnapi\"")
            buildConfigField("String", "SDX_TARGET_PROFILE", "\"android-arm64-nnapi-accelerator\"")
            buildConfigField("boolean", "DEVICE_ONLY", "true")
        }
    }

    sourceSets {
        getByName("vulkan").java.srcDir("src/sdx/java")
        getByName("hexagon").java.srcDir("src/sdx/java")
        getByName("tensorG3").java.srcDir("src/sdx/java")
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
        // Core JARs are Java 17; ART on API 28+ handles Java 8 bytecode;
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
            // Native pipelines already emit release-ready provider, JavaCPP, and Graal AOT
            // libraries. Preserve those audited bytes; AGP's strip transform can corrupt
            // vendor/stock-Graal binaries that intentionally omit GNU debug sections.
            // FastRPC also discovers the bundled Hexagon DSP skeleton by filesystem path,
            // so native libraries must be extracted beside the host runtime at install time.
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/*.so")
        }
    }

}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // The retained legacy JNA source is not part of accelerator builds; JavaCPP owns transport.
    exclude("**/SdxChatModelAndroid.kt")
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

    // ── Separate device-only JavaCPP provider AARs ────────────────────────────
    // Each APK resolves exactly one provider. The build script stages these paths
    // and both runtime verifiers reject BLAS, host, and alternate-backend leakage.
    add("vulkanImplementation", files(vulkanAar))
    add("hexagonImplementation", files(hexagonAar))
    add("tensorG3Implementation", files(tensorG3Aar))
    add("tensorG5Implementation", files(tensorG5Aar))

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
