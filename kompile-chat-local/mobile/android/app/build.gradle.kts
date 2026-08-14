import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

providers.gradleProperty("kompileAppBuildDir").orNull?.let { configuredBuildDir ->
    val configuredFile = File(configuredBuildDir)
    if (!configuredFile.isAbsolute) {
        throw GradleException("kompileAppBuildDir must be an absolute path")
    }
    layout.buildDirectory.set(configuredFile)
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
val tensorG3SourceSha256 = providers.gradleProperty("sdxTensorG3SourceSha256")
val tensorG3ProvenanceSha256 = providers.gradleProperty("sdxTensorG3ProvenanceSha256")
val sdxAotSdkProvenanceSha256 = providers.gradleProperty("sdxAotSdkProvenanceSha256")
val sdxArtifactMode = providers.gradleProperty("sdxArtifactMode").orElse("source-build")
val generatedJniLibsDir = providers.gradleProperty("kompileJniLibsDir")
// Optional prepared-artifact handoff for SDZ/KProject only. Public Hugging Face
// GGML/GGUF acquisition is app-owned; libsdx_llm only ingests it into canonical SDZ, which
// then uses the same local provider path as every other model. It never depends on this service.
// Override the prepared-artifact endpoint with
// -PkompileModelStagingUrl=https://host/staging or KOMPILE_MODEL_STAGING_URL.
val modelStagingUrl = providers.gradleProperty("kompileModelStagingUrl")
    .orElse(providers.environmentVariable("KOMPILE_MODEL_STAGING_URL"))
    .orElse("")
val modelStagingUrlLiteral = modelStagingUrl.map { value ->
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
val apkBuildId = providers.gradleProperty("apkBuildId").orElse("local")
val apkBuildIdLiteral = apkBuildId.map { value ->
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
val apkVersionCode = providers.gradleProperty("apkVersionCode")
    .map { value -> value.toInt() }
    .orElse(1)
val requestedTaskNames = gradle.startParameter.taskNames.joinToString(" ")
val validateTensorG3Provenance = tasks.register("validateTensorG3Provenance") {
    group = "verification"
    description = "Rejects every Tensor G3 task unless exact runtime provenance is supplied."
    doLast {
    mapOf(
        "sdxTensorG3SourceSha256" to tensorG3SourceSha256,
        "sdxTensorG3ProvenanceSha256" to tensorG3ProvenanceSha256,
        "sdxAotSdkProvenanceSha256" to sdxAotSdkProvenanceSha256
    ).forEach { (propertyName, provider) ->
        if (!provider.isPresent || !provider.get().matches(Regex("[0-9a-f]{64}"))) {
            throw GradleException("Tensor G3 builds require -P$propertyName with a lowercase SHA-256 digest")
        }
    }
    }
}
tasks.configureEach {
    if (name != validateTensorG3Provenance.name && name.contains("TensorG3", ignoreCase = true)) {
        dependsOn(validateTensorG3Provenance)
    }
}
val tensorG3SourceSha256Literal = tensorG3SourceSha256.orElse("not-applicable").map { "\"$it\"" }
val tensorG3ProvenanceSha256Literal = tensorG3ProvenanceSha256.orElse("not-applicable").map { "\"$it\"" }
val sdxAotSdkProvenanceSha256Literal = sdxAotSdkProvenanceSha256.orElse("not-applicable").map { "\"$it\"" }

if (sdxArtifactMode.get() !in setOf("source-build", "release-consumer")) {
    throw GradleException("sdxArtifactMode must be source-build or release-consumer")
}
if (sdxArtifactMode.get() == "release-consumer") {
    mapOf(
        "Vulkan" to ("sdxVulkanAar" to vulkanAar),
        "Hexagon" to ("sdxHexagonAar" to hexagonAar),
        "TensorG3" to ("sdxTensorG3Aar" to tensorG3Aar),
        "TensorG5" to ("sdxTensorG5Aar" to tensorG5Aar)
    ).filterKeys { flavor -> requestedTaskNames.contains(flavor, ignoreCase = true) }
        .forEach { (_, handoff) ->
            val (propertyName, provider) = handoff
            if (!providers.gradleProperty(propertyName).isPresent) {
                throw GradleException("release-consumer mode requires explicit -P$propertyName")
            }
            val artifact = file(provider.get())
            if (!artifact.isFile) {
                throw GradleException("release-consumer artifact is missing: ${artifact.absolutePath}")
            }
        }
}

// ── Provider-independent SDX layers ───────────────────────────────────────────
// Provider AARs re-ship model and tokenizer facades beside provider-coupled
// JavaCPP bindings and native payloads. Refresh only the genuinely shared layers.
// org/nd4j/dsp/runtime directly calls the generated SdxNative transport, so those
// classes must remain byte-for-byte paired with the provider's binding and
// libjnisdx.so; replacing only that Java side causes device-only linkage failures.
val sdxVersion = providers.gradleProperty("sdxVersion").orElse("1.0.0-SNAPSHOT")

// Java packages that are identical across providers and safe to refresh in place.
val sdxSharedPackages = setOf(
    "org/nd4j/dsp/model/",
    "org/eclipse/deeplearning4j/tokenizers/"
)

// The tokenizer facade is refreshed together with its native libraries because its
// entry points, applyChatTemplate among them, are native. Refreshing only the Java
// side would compile and then fail on the device with a missing symbol.
val sdxNativePairedPackages = setOf("org/eclipse/deeplearning4j/tokenizers/")

// Classes the application's own sources resolve against. Checked after the refresh so
// a missing or wrong canonical artifact fails the build with a direct explanation
// instead of unresolved references in Kotlin files that nobody changed. A provider
// that ships no tokenizer at all is not held to the tokenizer entries.
val sdxRequiredClasses = setOf(
    "org/nd4j/dsp/model/HuggingFaceGgmlResolver.class",
    "org/nd4j/dsp/model/HuggingFaceGgmlResolver\$Candidate.class",
    "org/nd4j/dsp/model/HuggingFaceGgmlResolver\$Discovery.class",
    "org/nd4j/dsp/model/HuggingFaceGgmlResolver\$Kind.class",
    "org/nd4j/dsp/model/HuggingFaceGgmlResolver\$Reference.class",
    "org/nd4j/dsp/model/HuggingFaceGgmlResolver\$RepositoryFile.class",
    "org/nd4j/dsp/model/ResumableModelDownloader.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$CancellationHandle.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$DownloadCancelledException.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$DownloadPolicy.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$DownloadRequest.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$DownloadResult.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$EventType.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$ProgressEvent.class",
    "org/nd4j/dsp/model/ResumableModelDownloader\$ProgressListener.class",
    "org/nd4j/dsp/model/SdxCompiledModel.class",
    "org/nd4j/dsp/model/SdxModelCache.class",
    "org/nd4j/dsp/model/SdxTargetProfile.class",
    "org/nd4j/dsp/model/SdxTextModelAssets.class",
    "org/nd4j/dsp/runtime/SdxRuntime.class",
    "org/nd4j/dsp/runtime/SdxRuntime\$ModelOptions.class",
    "org/nd4j/dsp/runtime/SdxTextSession.class",
    "org/nd4j/dsp/model/SdxLlmNative.class",
    "org/nd4j/dsp/model/SdxLlmNative\$ChunkCallback.class",
    "org/nd4j/dsp/model/SdxLlmNative\$CancelCallback.class",
    "org/eclipse/deeplearning4j/tokenizers/NativeTokenizer.class",
    "org/eclipse/deeplearning4j/tokenizers/NativeTokenizer\$ChatMessage.class"
)

val sdxSharedJava: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val sdxSharedNative: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

fun registerSdxAarNormalization(
    variant: String,
    providerAar: Provider<String>,
    expectedSourceSha256: Provider<String> = providers.provider { "not-applicable" },
    refreshSharedLayers: Boolean = true
): TaskProvider<NormalizeSdxProviderAar> {
    val taskSuffix = variant.split('-')
        .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
    return tasks.register<NormalizeSdxProviderAar>("normalize${taskSuffix}SdxAar") {
        description = "Refreshes the provider-independent SDX Java and tokenizer " +
            "layers in the $variant AAR from canonical Maven artifacts."
        sourceAar.set(layout.file(providerAar.map { file(it) }))
        sourceAarSha256.set(expectedSourceSha256)
        refreshCanonicalLayers.set(refreshSharedLayers)
        if (refreshSharedLayers) {
            sharedJavaClasses.from(sdxSharedJava)
            sharedNativeLibraries.from(sdxSharedNative)
        }
        providerVariant.set(variant)
        sharedPackages.set(sdxSharedPackages)
        nativePairedPackages.set(sdxNativePairedPackages)
        requiredClasses.set(sdxRequiredClasses)
        normalizedAar.set(
            layout.buildDirectory.file("sdx-normalized-aar/$variant/sdx-runtime-$variant.aar")
        )
    }
}

val normalizedVulkanAar = registerSdxAarNormalization("vulkan", vulkanAar)
val normalizedHexagonAar = registerSdxAarNormalization("hexagon", hexagonAar)
val normalizedTensorG3Aar = registerSdxAarNormalization(
    "tensor-g3",
    tensorG3Aar,
    tensorG3SourceSha256.orElse("not-applicable"),
    refreshSharedLayers = false
)
val normalizedTensorG5Aar = registerSdxAarNormalization("tensor-g5", tensorG5Aar)

android {
    namespace = "ai.kompile.chat.local.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kompile.chat.local.android"
        // Stock-Graal graph JNI support is built against Android API 28 bionic.
        minSdk = 28
        targetSdk = 35
        versionCode = apkVersionCode.get()
        versionName = "0.1.0-SNAPSHOT-${apkBuildId.get()}"
        buildConfigField("String", "APK_BUILD_ID", apkBuildIdLiteral.get())
        buildConfigField("String", "MODEL_STAGING_URL", modelStagingUrlLiteral.get())
        buildConfigField("String", "SOURCE_RUNTIME_AAR_SHA256", "\"not-applicable\"")
        buildConfigField("String", "RUNTIME_PROVENANCE_SHA256", "\"not-applicable\"")
        buildConfigField("String", "SDX_AOT_PROVENANCE_SHA256", "\"not-applicable\"")

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
            buildConfigField("String", "SOURCE_RUNTIME_AAR_SHA256", tensorG3SourceSha256Literal.get())
            buildConfigField("String", "RUNTIME_PROVENANCE_SHA256", tensorG3ProvenanceSha256Literal.get())
            buildConfigField("String", "SDX_AOT_PROVENANCE_SHA256", sdxAotSdkProvenanceSha256Literal.get())
            buildConfigField("boolean", "DEVICE_ONLY", "true")
        }
    }

    sourceSets {
        generatedJniLibsDir.orNull?.let { generatedDir ->
            getByName("main").jniLibs.setSrcDirs(listOf(generatedDir))
        }
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
            // The canonical offline helper emits debug-signed research APKs, but they
            // still must not carry the core module's unused desktop-only JNA and HTTP
            // routes. Run the same reachability pass as release so the final DEX audit
            // verifies the code that can actually execute on a device.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            testProguardFiles("proguard-test-rules.pro")
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
            // Tensor G3's provider and CPU importer use the same NDK OpenMP runtime (same
            // Build ID and dynamic exports). The importer SDK owns the deployment copy
            // after removing only DWARF, so package exactly that audited byte sequence.
            pickFirsts += "**/libomp.so"
        }
    }

}

/**
 * Drops the host hardware probe ND4J pulls in behind the Kompile libraries.
 *
 * oshi-core discovers host CPUs and GPUs through JNA. The probe never runs on a device;
 * leaving it in place only adds dead host classes. Android uses the JavaCPP transport
 * packaged by the canonical SDX SDK and therefore carries no JNA runtime.
 * kompile-graph-reasoning-local already declares the same host-probe exclusions.
 */
fun ExternalModuleDependency.excludeHostHardwareProbe() {
    exclude(group = "com.github.oshi", module = "oshi-core")
    exclude(group = "net.java.dev.jna", module = "jna")
    exclude(group = "net.java.dev.jna", module = "jna-platform")
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
    // Consumed after normalization. Shared model/tokenizer facades are refreshed,
    // while each provider's runtime facade stays paired with its JavaCPP/native ABI.
    add("vulkanImplementation", files(normalizedVulkanAar))
    add("hexagonImplementation", files(normalizedHexagonAar))
    add("tensorG3Implementation", files(normalizedTensorG3Aar))
    add("tensorG5Implementation", files(normalizedTensorG5Aar))

    // Canonical provider-independent SDX artifacts. Runtime classes from nd4j-sdx
    // are deliberately not overlaid onto provider AARs because they call the provider's
    // generated JavaCPP binding. Tokenizer Java and arm64 libraries refresh together.
    add("sdxSharedJava", "org.eclipse.deeplearning4j:nd4j-sdx-model:${sdxVersion.get()}")
    add("sdxSharedJava", "org.eclipse.deeplearning4j:nd4j-sdx:${sdxVersion.get()}")
    add("sdxSharedJava", "org.eclipse.deeplearning4j:tokenizers-native:${sdxVersion.get()}")
    add("sdxSharedJava", "org.eclipse.deeplearning4j:tokenizers-native-preset:${sdxVersion.get()}")
    add(
        "sdxSharedNative",
        "org.eclipse.deeplearning4j:tokenizers-native:${sdxVersion.get()}:android-arm64"
    )

    // ── Kompile core libraries (installed to mavenLocal via `mvn install`) ────
    // kompile-chat-local-core: ChatEngine, ChatModel, Message, GenOptions, …
    implementation("ai.kompile:kompile-chat-local-core:0.1.0-SNAPSHOT") {
        excludeHostHardwareProbe()
    }
    // kompile-graph-reasoning: MiniJson, the strict parser KgraphArtifactValidator
    // shares with UnifiedGraph.load so both agree on the manifest grammar.
    //
    // kompile-graph-reasoning-local is deliberately absent. Its LocalReasoningSession
    // and LocalToolDispatcher are compiled INTO libkompile_reasoning_android.so by
    // GraalVM native-image; Android reaches them through the JavaCPP kgr_* transport,
    // never as Java. On the classpath they contributed nothing but @CEntryPoint
    // signatures against Graal types that cannot exist outside a native image.
    implementation("ai.kompile:kompile-graph-reasoning:0.1.0-SNAPSHOT") {
        excludeHostHardwareProbe()
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

// Acceptance is explicit. APK assembly must not implicitly run an entire host suite and compile
// Android tests; callers choose that cost when they ask for modelImportAcceptance.
val modelImportAcceptanceFlavors = linkedMapOf(
    "Vulkan" to "testVulkanDebugUnitTest",
    "Hexagon" to "testHexagonDebugUnitTest",
    "TensorG3" to "testTensorG3DebugUnitTest",
    "TensorG5" to "testTensorG5DebugUnitTest"
)
val configuredAcceptanceFlavor = providers.gradleProperty("modelImportAcceptanceFlavor")
    .orNull
    ?.let { requested ->
        modelImportAcceptanceFlavors.keys.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: throw GradleException(
                "modelImportAcceptanceFlavor must be one of " +
                    modelImportAcceptanceFlavors.keys.joinToString()
            )
    }
val representativeAcceptanceFlavor = configuredAcceptanceFlavor ?: "Vulkan"
tasks.register("modelImportAcceptance") {
    group = "verification"
    description =
        "Explicitly runs the selected flavor's complete model-import suite and compiles its viewport regression."
    dependsOn(modelImportAcceptanceFlavors.getValue(representativeAcceptanceFlavor))
    dependsOn("compile${representativeAcceptanceFlavor}DebugAndroidTestKotlin")
}

/**
 * Rewrites the provider-independent layers of an SDX provider AAR from canonical Maven
 * artifacts, leaving the provider-coupled binding, its `libjnisdx.so`, and the provider
 * runtime payload untouched.
 *
 * The provider AARs are produced by separate native builds. Each one re-ships a copy of
 * the shared SDX Java API next to its own provider binding, so a provider whose natives
 * were not rebuilt also carries an outdated copy of that shared API and fails to compile
 * against current application sources — with nothing in the error pointing at the AAR.
 */
abstract class NormalizeSdxProviderAar : DefaultTask() {

    @get:InputFile
    abstract val sourceAar: RegularFileProperty

    @get:Input
    abstract val sourceAarSha256: Property<String>

    @get:InputFiles
    abstract val sharedJavaClasses: ConfigurableFileCollection

    @get:InputFiles
    abstract val sharedNativeLibraries: ConfigurableFileCollection

    @get:Input
    abstract val providerVariant: Property<String>

    @get:Input
    abstract val refreshCanonicalLayers: Property<Boolean>

    @get:Input
    abstract val sharedPackages: SetProperty<String>

    @get:Input
    abstract val nativePairedPackages: SetProperty<String>

    @get:Input
    abstract val requiredClasses: SetProperty<String>

    @get:OutputFile
    abstract val normalizedAar: RegularFileProperty

    @TaskAction
    fun normalize() {
        val variant = providerVariant.get()
        val source = sourceAar.get().asFile
        val expectedSourceSha256 = sourceAarSha256.get()
        if (expectedSourceSha256 != "not-applicable") {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualSourceSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSourceSha256 != expectedSourceSha256) {
                throw GradleException(
                    "The $variant source AAR changed before normalization: " +
                        "expected=$expectedSourceSha256 actual=$actualSourceSha256 file=$source"
                )
            }
        }
        val bundled = readClassesJar(source, variant)

        // A provider is only held to the layers it actually ships. The LiteRT-LM runtime
        // tokenizes inside its own engine and carries no tokenizer facade at all.
        val carried = sharedPackages.get().filter { pkg ->
            bundled.keys.any { it.startsWith(pkg) && it.endsWith(".class") }
        }.toSet()

        if (!refreshCanonicalLayers.get()) {
            verifyRequiredClasses(bundled, carried, variant)
            val target = normalizedAar.get().asFile
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
            val copiedSha256 = sha256(target)
            val sourceSha256 = sha256(source)
            if (copiedSha256 != sourceSha256) {
                throw GradleException(
                    "The provenance-bound $variant AAR changed during exact staging: " +
                        "source=$sourceSha256 staged=$copiedSha256"
                )
            }
            logger.lifecycle(
                "Staged provenance-bound $variant SDX AAR without Maven overlays: $copiedSha256"
            )
            return
        }

        val canonical = readCanonicalClasses(carried)
        if (carried.isNotEmpty() && canonical.isEmpty()) {
            throw GradleException(
                "No canonical SDX classes resolved for the $variant flavor. Build and " +
                    "install nd4j-sdx-model and the tokenizer artifacts from " +
                    "deeplearning4j, or point -PsdxVersion at a version that exists."
            )
        }

        val refreshed = resolveRefreshSet(bundled, canonical, carried, variant)
        val merged = LinkedHashMap<String, ByteArray>()
        bundled.forEach { (name, bytes) ->
            // Only classes are replaced; any resource a provider ships beside them stays.
            val supersededClass = name.endsWith(".class") && carried.any(name::startsWith)
            if (!supersededClass) merged[name] = bytes
        }
        refreshed.forEach { name -> merged[name] = canonical.getValue(name) }

        verifyRequiredClasses(merged, carried, variant)

        val target = normalizedAar.get().asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        val replacedLibraries = rewriteAar(target, merged)

        // The tokenizer facade reaches native code, so refreshing its Java alone would
        // compile and then fail on the device against a library missing the symbol.
        val pairedButUnpaired = nativePairedPackages.get().filter { it in carried }
        if (pairedButUnpaired.isNotEmpty() && replacedLibraries == 0) {
            throw GradleException(
                "The $variant flavor ships a native-backed SDX layer " +
                    "(${pairedButUnpaired.joinToString()}) but no canonical arm64 " +
                    "libraries were applied to it. Install the tokenizer artifact with " +
                    "its android-arm64 classifier before building."
            )
        }

        logger.lifecycle(
            "Normalized $variant SDX AAR: ${refreshed.size} shared class(es) and " +
                "$replacedLibraries native librar(ies) refreshed"
        )
    }

    /**
     * Chooses which canonical classes enter the AAR: those the provider already ships,
     * those the application requires, and whatever those reach transitively.
     *
     * The canonical artifacts also hold host-side tooling that compiles models on a
     * workstation and reaches into ND4J proper, which is absent from an Android
     * classpath. Copying a package wholesale would drag that tooling into the APK, so
     * only classes reachable from what the AAR or the application actually uses are
     * taken, and a class is admitted only if its references stay inside what this AAR
     * already depends on.
     */
    private fun resolveRefreshSet(
        bundled: Map<String, ByteArray>,
        canonical: Map<String, ByteArray>,
        carried: Set<String>,
        variant: String
    ): Set<String> {
        val reachablePrefixes = buildSet {
            addAll(listOf("java/", "javax/", "android/", "androidx/", "kotlin/", "dalvik/"))
            bundled.forEach { (name, bytes) ->
                if (!name.endsWith(".class")) return@forEach
                packageOf(name.removeSuffix(".class"))?.let(::add)
                referencedTypes(bytes).mapNotNull(::packageOf).forEach(::add)
            }
        }

        val seeds = buildSet {
            addAll(bundled.keys.filter { it.endsWith(".class") && carried.any(it::startsWith) })
            addAll(requiredClasses.get().filter { required -> carried.any(required::startsWith) })
        }

        val accepted = LinkedHashSet<String>()
        val pending = ArrayDeque(seeds)
        val rejected = LinkedHashMap<String, String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in accepted || name in rejected) continue
            val bytes = canonical[name] ?: continue
            val references = referencedTypes(bytes)
            val unreachable = references.firstOrNull { reference ->
                "$reference.class" !in canonical &&
                    packageOf(reference) != null &&
                    reachablePrefixes.none(reference::startsWith)
            }
            if (unreachable != null) {
                rejected[name] = unreachable
                continue
            }
            accepted.add(name)
            references.forEach { reference ->
                val candidate = "$reference.class"
                if (candidate in canonical) pending.addLast(candidate)
            }
        }

        val requiredRejection = rejected.keys.firstOrNull { it in seeds && it in requiredClasses.get() }
        if (requiredRejection != null) {
            throw GradleException(
                "The canonical SDX artifact declares $requiredRejection against " +
                    "${rejected.getValue(requiredRejection)}, which the $variant AAR " +
                    "does not carry. The shared SDX API and this provider AAR were " +
                    "built from incompatible sources."
            )
        }
        rejected.forEach { (name, reference) ->
            logger.info("$variant: skipping $name; it reaches $reference, outside this AAR")
        }

        val dropped = bundled.keys.filter {
            it.endsWith(".class") && carried.any(it::startsWith) && it !in canonical
        }
        if (dropped.isNotEmpty()) {
            logger.lifecycle(
                "$variant: ${dropped.size} shared class(es) withdrawn by the canonical " +
                    "artifacts (first: ${dropped.first()})"
            )
        }
        return accepted
    }

    private fun verifyRequiredClasses(
        merged: Map<String, ByteArray>,
        carried: Set<String>,
        variant: String
    ) {
        val missing = requiredClasses.get()
            .filter { required -> carried.any(required::startsWith) }
            .filterNot(merged::containsKey)
            .sorted()
        if (missing.isEmpty()) return
        throw GradleException(
            "The canonical SDX artifacts do not provide API the application compiles " +
                "against for the $variant flavor: ${missing.joinToString()}. Rebuild " +
                "and install nd4j-sdx-model and the tokenizer artifacts from " +
                "deeplearning4j."
        )
    }

    private fun readClassesJar(aar: File, variant: String): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(aar).use { archive ->
            val classesJar = archive.getEntry(CLASSES_JAR)
                ?: throw GradleException("The $variant provider AAR has no $CLASSES_JAR: $aar")
            ZipInputStream(archive.getInputStream(classesJar)).use { jar ->
                while (true) {
                    val entry = jar.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = jar.readBytes()
                }
            }
        }
        return entries
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readCanonicalClasses(carried: Set<String>): Map<String, ByteArray> {
        val classes = LinkedHashMap<String, ByteArray>()
        sharedJavaClasses.files.filter(File::isFile).forEach { jar ->
            ZipFile(jar).use { archive ->
                archive.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .filter { entry -> carried.any(entry.name::startsWith) }
                    .forEach { entry ->
                        classes[entry.name] = archive.getInputStream(entry).use { it.readBytes() }
                    }
            }
        }
        return classes
    }

    /** Returns how many of the AAR's own native libraries were replaced. */
    private fun rewriteAar(target: File, classes: Map<String, ByteArray>): Int {
        var replaced = 0
        FileSystems.newFileSystem(URI.create("jar:${target.toURI()}"), emptyMap<String, Any>())
            .use { aar ->
                Files.write(aar.getPath("/$CLASSES_JAR"), packJar(classes))
                sharedNativeLibraries.files.filter(File::isFile).forEach { jar ->
                    ZipFile(jar).use { archive ->
                        archive.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".so") }
                            .forEach { entry ->
                                val name = entry.name.substringAfterLast('/')
                                val path = aar.getPath("/jni/$ABI/$name")
                                // Only libraries this provider already ships are
                                // replaced; nothing new enters its payload.
                                if (!Files.exists(path)) return@forEach
                                archive.getInputStream(entry).use { source ->
                                    Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING)
                                }
                                replaced++
                            }
                    }
                }
            }
        return replaced
    }

    private fun packJar(classes: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            // Fixed order and timestamps keep the jar byte-identical between builds.
            classes.toSortedMap().forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name).apply { setTimeLocal(FIXED_ENTRY_TIME) })
                out.write(bytes)
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun packageOf(internalName: String): String? {
        val separator = internalName.lastIndexOf('/')
        return if (separator <= 0) null else internalName.substring(0, separator + 1)
    }

    /**
     * Reads the class names a class file refers to straight out of its constant pool.
     * Only the pool is walked, so no bytecode library is needed on the build classpath.
     */
    private fun referencedTypes(bytes: ByteArray): Set<String> {
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.remaining() < 10 || buffer.int != CLASS_FILE_MAGIC) return emptySet()
        buffer.short // minor version
        buffer.short // major version
        val poolSize = buffer.short.toInt() and 0xFFFF
        val strings = HashMap<Int, String>()
        val classEntries = ArrayList<Int>()
        var slot = 1
        while (slot < poolSize) {
            when (val tag = buffer.get().toInt() and 0xFF) {
                1 -> {
                    val raw = ByteArray(buffer.short.toInt() and 0xFFFF)
                    buffer.get(raw)
                    strings[slot] = String(raw, Charsets.UTF_8)
                }
                7, 8, 16, 19, 20 -> {
                    val referenced = buffer.short.toInt() and 0xFFFF
                    if (tag == 7) classEntries.add(referenced)
                }
                15 -> {
                    buffer.get()
                    buffer.short
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> buffer.int
                // Longs and doubles occupy two pool slots, per the class file format.
                5, 6 -> {
                    buffer.long
                    slot++
                }
                else -> return emptySet() // Unrecognised tag: decline to guess.
            }
            slot++
        }
        return classEntries.mapNotNull(strings::get)
            .map { it.trimStart('[') }
            .map { if (it.startsWith("L") && it.endsWith(";")) it.drop(1).dropLast(1) else it }
            .filterTo(LinkedHashSet()) { it.isNotEmpty() }
    }

    private companion object {
        const val CLASSES_JAR = "classes.jar"
        const val ABI = "arm64-v8a"
        val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
        val FIXED_ENTRY_TIME: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
    }
}
