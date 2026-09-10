# Kompile Chat Local — Android accelerator builds

This is a mobile-first, local-first Compose chat application. Graph search and
reasoning run locally through the Kompile graph engine. Prepared `.sdz` text generation
uses one of four device-specific accelerator flavors:

| Flavor | Prepared model input | Prepared execution route | Prepared-route CPU fallback |
| --- | --- | --- | --- |
| `vulkan` | canonical `.sdz` | SDX Vulkan lowering, command capture, and replay on Android GPU | forbidden |
| `hexagon` | canonical `.sdz` | SDX AOT replay on Qualcomm Hexagon/HTP | forbidden |
| `tensorG3` | canonical `.sdz` | libnd4j NNAPI pinned to one complete-graph `DEVICE_ACCELERATOR` | forbidden |
| `tensorG5` | canonical `.sdz` | Google LiteRT-LM dispatch on Tensor G5 TPU/NPU | forbidden |

Every flavor additionally packages the explicit provider-independent `SDX_GGUF_AOT`
route for app-owned `.gguf`/`.ggml` imports. Format selects that SDX AOT CPU route; it is
never a fallback from a failed prepared provider and never changes the selected `.sdz`
accelerator. All four variants are ARM64-only. Inference and graph reasoning remain local.
`INTERNET` is confined to public Hugging Face repository metadata and selected model
transfer; cleartext traffic, tokens, remote inference, host runtimes, implicit NNAPI
partitioning, and alternate prepared providers are rejected. The raw route carries its
own exact, audited CPU/OpenBLAS closure; prepared-provider OpenBLAS leakage remains
forbidden.

## Model acquisition and activation

The Settings screen exposes two independent paths:

- `owner/repository` and canonical Hugging Face repository/tree/blob/resolve references
  are resolved directly through the public Hugging Face API. Repository/tree discovery
  pins the returned commit; exact file URLs retain the requested revision and skip the
  metadata request. One complete candidate starts app-owned transfer immediately, several
  GGUF/GGML quantizations require explicit selection, and split GGUF shards are not offered
  individually. The app follows only strict HTTPS Hugging Face redirects, streams through
  a bounded cancellable temporary file, and atomically publishes into private storage.
- an optional Kompile artifact service opens only prepared `.sdz`/`.kproject` downloads in
  an external browser.

A Hugging Face transfer is not success. A commit-pinned selection has a stable app-private
cache name, but it is reused only when repository discovery supplied an LFS SHA-256 and both
the file size and recomputed digest still match. Corrupt, incomplete, size-mismatched, unhashed,
or unpinned entries are downloaded again; a repository-supplied digest is also verified while
streaming before atomic publication. The exact downloaded file must pass GGUF/GGML validation,
`libsdx_llm` ABI-v2 load in `runtime_quantized` mode, retain at least one structural
`ggml_qmatmul`, render a prompt, return nonblank text with generated tokens, and report that
`ggml_qmatmul` actually executed during the bounded real decode. Prompt rendering prefers the model/tokenizer chat template, then a proven ChatML
protocol, and finally an explicit plain completion transcript. Merely parsing or loading a
GGUF is not activation.

Activation is transactional: the selection is staged durably, the previous runtime is detached
before opening the candidate to avoid keeping two model sessions resident, and the candidate
performs a real decode before publication to `ChatEngine`. The pending canonical SDZ selection
is promoted only after publication. Failure closes the candidate and attempts to restore the
previous selection/runtime, retaining any rollback failures in the diagnostic. Engine startup
opens the persisted SDZ through the ordinary provider path without repeating the import-time smoke decode. A failed
commit-pinned candidate remains cached so a newer APK can retry it without another download; a
failed unpinned download is removed. Kompile
staging receives only the target profile and prepared artifact kind and never participates
in the Hugging Face path.

## Architecture

The application layer depends only on `ChatModel` and
`AcceleratedChatModelAndroid`. Every `PlatformLocalChatSession` receives canonical SDZ:

- Raw `.gguf`/`.ggml` is accepted only by `SdxGgufModelImporter`, a direct JNI ingestion adapter
  over the stable `libsdx_llm` ABI-v2 surface. The disposable Graal process imports directly
  to sharded canonical SDZ, populates the shared `SdxModelCompiler` cache, destroys its runtime,
  and is observed dead before the application opens a session. It never renders prompts or runs
  generation.
- Vulkan, Hexagon, and Tensor G3 canonical `.sdz` share one SDX JavaCPP session lifecycle
  using `SdxRuntime`, `NativeTokenizer`, and `SdxTextSession`. Only their strict model
  options and route identity differ: `mobileVulkan()`, `mobileHexagon()`, and
  `mobileNnapiArmHybrid()`.
- Tensor G5 prepared `.sdz` uses the SDX JavaCPP LiteRT-LM session and Google's dispatch
  runtime.

The ART-facing `libjnisdx_llm.so` transport is consumer code owned and compiled here from
`app/src/main/cpp/sdx_llm_android_jni.cpp`. It links only to the immutable SDX
`sdx_llm_c.h`/`libsdx_llm.so` contract. The DL4J SDX SDK does not contain Kompile package
names, graph APIs, this JNI bridge, or any other application-specific artifact.

The ART-facing ingestion transport uses `SdxAndroidLlmAbi` and the Kompile-owned
`libjnisdx_llm.so`. JNA, `libjnidispatch`, and remote/legacy chat transports are excluded;
`verify-offline-apk.sh` rejects their DEX classes. JavaCPP inside the Graal importer
stays separate from the accelerator runtime's JavaCPP state. Streaming, stop tokens,
cancellation, reset, and ownership stay at the canonical provider session seams.

The importer runs in `:sdx_model_import` under an important service binding and must be
observed dead before the canonical runtime opens. Vulkan, Hexagon, and Tensor G3 also
isolate model execution in `:sdx_model_runtime`; the UI process supervises it and retains
process-exit diagnostics. JavaCPP native-memory limits are installed before native class
initialization in each process, independently of ART's smaller heap limit. Preparation records
memory immediately, without a fixed RAM/swap wait or veto before cache lookup or conversion.
The historical 3.5 GB available RAM / 2 GB free swap references are advisory observations, not
a model-fit budget; logical zram/swap headroom is not an independent physical RAM reserve.
Controlled device qualification retains its separate 2.5 GB / 2 GB environment criteria and
strict cold/warm cache, identity, route, and decode gates. Failure of those environment criteria
does not establish that the model cannot load.

For a data-preserving functional check, target
`TensorG3QualificationTest#functionalQwenDecodeUsesRequestedPrecision` in the installed
instrumentation APK. Supply the same required build/provenance/model identity arguments as the
qualifier and an explicit `-e weight_optimization BF16` (or another exact `WeightOptimization`
enum name). Stage its inputs under `files/functional/model.gguf` with adjacent tokenizer sidecars,
separate from `files/qualification/`; it does not clear
data, records the requested conversion profile plus resolved cache/profile/context and phase
memory snapshots, and attempts the real packaged runtime regardless of the reference floors.
Missing/invalid precision is an error; the controlled cold/warm qualifier remains pinned to Q4_K.
BF16 preparation of a Q4 source uses BF16 storage but cannot restore the original source precision.
`FUNCTIONAL_DECODE_PASS` is not a qualification receipt and cannot promote an APK. The canonical
qualification script clears app data: do not use it as the functional check.

The bundled `fixture.kgraph` is checksum-verified on every launch. Imports run off the UI
thread and move transactionally into app-owned storage. Graphs are opened once for format
validation before activation. Prepared models accept the canonical SameDiff `.sdz`
identity; provider formats are compiler-cache details under `META-INF/sdx-cache`, extracted
into a checksummed app-private cache and selected by APK target. Raw models instead use the
common SDX AOT route described above.

Graph reasoning runs in `libkompile_reasoning_android.so`, built by stock
GraalVM `native-image` plus Android NDK r28b for arm64/bionic API 28. A small
generated `libjnikompile_graph.so` JavaCPP transport exposes the stable
`kgr_*` C ABI to the app. Gluon is not used, and graph lifecycle calls remain
on one native thread because the Graal isolate thread handle is thread-affine.

## Build the APKs

Required inputs:

- Linux with Bash 4+, GNU coreutils/findutils, Info-ZIP, and CMake 3.20+
- JDK 17 (Temurin or Amazon Corretto; do not use GraalVM for AGP)
- Android SDK API 35 and NDK r28b
- the pinned stock-Graal/LabsJDK inputs consumed by the graph module's Maven
  `android-aot` profile
- populated Gradle and Maven caches
- the selected SDX provider AAR installed in the local Maven repository
- the explicitly built DL4J `nd4j/sdx-aot/target/android-aot` ABI-v2 SDK for direct
  GGUF/GGML execution; its `android-aot` profile is opt-in and never runs in a default build.
  The SDK supplies `sdx_llm_c.h` and `libsdx_llm.so`; Kompile builds the ART JNI adapter.

The graph AOT SDK is no longer a manually supplied prerequisite. Maven builds
`kompile-graph-reasoning-local` with its Android profile, invokes the retained
`build-android-ndk.sh`, and attaches the result as the `android-arm64` ZIP
classifier. The mobile module consumes that classifier from the reactor.

### Publication-only recovery of a retained Tensor G3 APK

`tools/publish-retained-tensor-g3.sh` publishes **exact retained bytes**, without
Gradle, producer builds, JNI regeneration, version allocation, current-source
inference, candidate pruning, or stable promotion. This is distinct from the
source-build wrapper's `--resume-publish`, which still prepares staging and runs
Gradle. Do not use that wrapper to recover the sole retained APK.

Supply the original identity and independently recorded SHA-256 values, not
newly invented build metadata. The retained directory basename must be
`.kompile-android-app-build.<original-build-id>.<six-character-suffix>`; it must
contain the app APK, instrumentation APK, and exact normalized Tensor G3 AAR in
the original Gradle layout. Source AAR receipt is the adjacent `.build-receipt`;
AOT receipt is `metadata/build-receipt` inside the explicitly selected SDK.
The AOT `current` alias is frozen to its canonical target at entry. Other selected
paths (including retained children and receipts) must not traverse symlinks.
The receipt binds the original AAR path/hash and NDK revision. The shared full
host verifier checks APK build ID/version, provenance, native closure, and all
normal gates against the retained normalized AAR and selected AOT SDK.

Example from `mobile/android` (substitute the original recorded values and
existing, disjoint output/staging/SDK directories):

```bash
bash tools/publish-retained-tensor-g3.sh \
  --retained-root /exact/apk-stage/.kompile-android-app-build.v123.ABCdef \
  --build-id v123 --version-code 123 \
  --expected-apk-sha256 "$ORIGINAL_APK_SHA256" \
  --tensor-g3-aar /exact/producer/dist/sdx-runtime-android-arm64-tensor-g3.aar \
  --expected-aar-sha256 "$ORIGINAL_AAR_SHA256" \
  --expected-aar-receipt-sha256 "$ORIGINAL_AAR_RECEIPT_SHA256" \
  --sdx-llm-sdk /exact/aot-sdk/current \
  --expected-aot-receipt-sha256 "$ORIGINAL_AOT_RECEIPT_SHA256" \
  --android-sdk "$ANDROID_SDK" --android-ndk "$ANDROID_NDK" \
  --output /existing/recovery-candidates --staging-root /existing/recovery-staging
```

Run without concurrent writers to the retained inputs or destination. Recovery
refuses pre-existing files for the same candidate generation; use a fresh output
directory after a partially published attempt. All inputs remain intact on success
and failure; cleanup is restricted to temporary files/directories created by this
invocation. Existing candidates and stable aliases are untouched. File fsync,
stream-copy, digest and ZIP failures remain fatal: this path does **not** mask an
ongoing filesystem EIO. A successful run writes the normal format-3 candidate
manifest through the shared publisher, not a hand-created receipt. Stable promotion
remains separately device-receipt-gated.

Fast regression checks (synthetic fixtures only; no device/build/publication):

```bash
python3 tools/test_apk_publication.py
bash -n tools/apk-publication.sh
bash -n tools/publish-retained-tensor-g3.sh
bash -n tools/build-offline-accelerators.sh
```

### 1. Install the selected DL4J SDX AAR

The DL4J and Kompile trees are separate reactors, so first install the selected
provider AAR in the local Maven repository. This Vulkan example is entirely
Maven-driven; Maven invokes the retained native/tokenizer helper scripts:

```bash
DL4J_ROOT=/path/to/deeplearning4j
DL4J_MVN=/path/to/mvn
ANDROID_NDK=/path/to/android-sdk/ndk/28.1.13356709

# Android tokenizer base JAR, JNI classifier, and preset
"$DL4J_MVN" -f "$DL4J_ROOT/nd4j/nd4j-tokenizers/pom.xml" \
  -Pandroid-arm64 \
  -Dandroid.ndk="$ANDROID_NDK" \
  -Dandroid.api=28 \
  -DskipTests install

# Accelerator-native AAR; buildnativeoperations.sh is owned by this Maven profile
"$DL4J_MVN" -f "$DL4J_ROOT/libnd4j/pom.xml" \
  -Psdx-android-aar \
  -Dlibnd4j.vulkan=true \
  -Djavacpp.platform=android-arm64 \
  -Dandroid.ndk="$ANDROID_NDK" \
  -Dlibnd4j.android.api=28 \
  -Dsdx.android.variant=vulkan \
  -Dlibnd4j.buildthreads=12 \
  -DskipTests install

# Full JavaCPP SDX AAR consumed by this application
"$DL4J_MVN" -f "$DL4J_ROOT/nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx/pom.xml" \
  -Pandroid-arm64 \
  -Dandroid.ndk="$ANDROID_NDK" \
  -Dandroid.api=28 \
  -Dsdx.android.variant=vulkan \
  -DskipTests install
```

Use the corresponding libnd4j/SDX producer profile and classifier for Hexagon,
Tensor G3, or Tensor G5. Tensor G5 remains a specialized LiteRT-LM artifact
produced by `nd4j-sdx-litertlm`; its vendor/Bazel helper is retained behind that
Maven boundary.

Raw-model ingestion is a separate explicit producer. Build the DL4J `sdx-aot`
Android SDK with its opt-in `android-aot` profile, an explicit repository-standard
`-Dbackend.artifactId=<importer-backend>` input, and the NDK helper, then pass its root to
the lower-level packager as `--sdx-llm-sdk` (or publish/consume the matching classifier
through the Maven lifecycle). That ND4J backend supplies GGUF/GGML-to-SDZ conversion; it
never replaces the independently packaged SDX NNAPI/Vulkan/Hexagon execution provider. The
packager requires `abi.version=2`, `direct.gguf=true`, and
`jni/arm64-v8a/libsdx_llm.so`, and rejects an SDX SDK that contains the consumer-owned
`libjnisdx_llm.so`; a default DL4J build does not create this artifact.

### 2. Build from the Kompile root reactor

`kompile-chat-local` is in the root reactor. Its Android child remains opt-in and
is activated only by `-Dkompile.mobile`:

```bash
./mvnw -o \
  -Dkompile.mobile=vulkan \
  -Dmobile.android.sdk=/path/to/android-sdk \
  -Dmobile.android.ndk=/path/to/android-sdk/ndk/28.1.13356709 \
  -Dmobile.java.home=/path/to/jdk-17 \
  -Dmobile.graalvm.home=/path/to/graalvm-21.0.10 \
  -DskipTests \
  -pl :kompile-chat-local-mobile -am \
  install
```

`mobile.java.home` is the JDK used by Android Gradle. The graph AOT producer
independently requires Oracle GraalVM 21.0.10 / Native Image 23.1.10 through
`mobile.graalvm.home`.

Use `vulkan`, `hexagon`, `tensor-g3`, `tensor-g5`, or `all` as the mobile value.
Maven owns lifecycle, graph-AOT artifact production, dependency staging, and
variant selection; libnd4j CMake owns accelerator-native compilation; Gradle is
the APK packaging boundary. The mobile helper runs in Maven-artifact mode and
does not launch nested Maven builds or copy generated AARs into the source tree.

The retained lower-level `android/tools/build-offline-accelerators.sh` remains
directly usable for CI or diagnosis. It has two deliberately separate artifact modes:

- Source-build mode accepts `--vulkan-aar`, `--hexagon-aar`,
  `--tensor-g3-aar`, and `--tensor-g5-aar` (or their matching environment
  variables). Tensor G3 resolution is deterministic:
  `--tensor-g3-aar` > `SDX_TENSOR_G3_AAR` > the canonical published AAR at
  `libnd4j/build/mobile/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar`
  > the native SDK AAR at
  `libnd4j/build/mobile/tensor-g3/native/sdx-runtime-sdk/dist/sdx-runtime-android-arm64-tensor-g3.aar`.
  The last fallback is accepted only when the provider build left an atomic
  `.build-receipt` whose exact artifact path and SHA-256 still match; an
  interrupted rebuild removes that authorization before compiling.
- Release-consumer mode requires `--sdx-release-version`,
  `--sdx-release-manifest`, and `--sdx-release-artifact-root`. The independently
  pinned version must match `releaseVersion`, and `releaseTag` must be
  `sdk-v<releaseVersion>`. For each requested flavor it reads the `releaseSelector`
  in `accelerators.json`, selects exactly one `component=runtime` record, retains
  its exact `fileName`, and rejects an incompatible role/packaging/classifier,
  missing or ambiguous selection, or wrong-size/wrong-SHA-256 AAR before Gradle runs.

The direct helper resolves its Android build JDK in the order
`--java-home` > `JAVA_HOME` > the selected SDX AOT SDK receipt > `java` on
`PATH`. The same receipt supplies the exact Maven executable and JavaCPP JAR and
selects the one installed NDK whose `source.properties` digest matches the
producer build. Every route must resolve a complete JDK (both `java` and
`javac`) with `java.specification.version=17`; it never silently builds with
Java 11 or 21 or mixes an AOT SDK with a different toolchain.

After a focused low-level native repair build, publish/verify its complete provider
AAR through the existing wrapper rather than reconstructing Maven inputs:

```bash
MVN_CMD=/path/to/mvn \
libnd4j/tools/mobile/build-android-accelerator.sh \
  --profile libnd4j/tools/mobile/profiles/tensor-g3-nnapi.env \
  --android-ndk /path/to/android-sdk/ndk/28.1.13356709 \
  --output-root libnd4j/build/mobile/tensor-g3 \
  --jobs 1 \
  --offline \
  --skip-native
```

For focused repair builds, use one canonical work root rather than manually
correlating Gradle, JNI, provider, and output paths. The existing producer
interfaces populate these stable inputs:

```text
<work-root>/aot-sdk/current
<work-root>/accelerator/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar
```

The checked-in Tensor G3 entry point owns both producers, builds the Kompile graph
and generic Native Image support closure first, refreshes the chat-core JAR in the same
Maven reactor, passes the native support inputs explicitly to the SDX producer, then builds,
host-verifies, and publishes the APK. A normal build refreshes graph AOT from source even
when its output already exists; only explicit `--resume-publish` may reuse that output. Keep this as one command: the
managed process runner gives separate commands separate `/tmp` namespaces, so a
producer started independently may not be visible to a later packaging process.

```bash
android/build-tensor-g3-offline-apk.sh                  # 12 native/Graal jobs
android/build-tensor-g3-offline-apk.sh --jobs 12        # explicit override
BUILD_JOBS=8 android/build-tensor-g3-offline-apk.sh     # environment override
```

Parallelism precedence is `--jobs N` (or `--jobs=N`) > `BUILD_JOBS` > **12**.
An unset or empty environment value uses the default. Invalid job counts are
rejected before any builder or cleanup runs. The same positive integer is passed
to graph AOT, SDX native/Graal, and accelerator builds; choose it to fit available
memory and coordinate with other agents before starting a build.

The wrapper discovers the sibling deeplearning4j checkout and uses the stable
`mobile/android/build/sdx-android-build` project-volume root. The checked-in entry point has no
root-selection, quick-build, or caller-provided build-identity switches.

Before starting a producer, the wrapper holds a work-root pipeline lock and runs
the checked-in targeted cleanup preflight. It removes interrupted publication
directories, superseded immutable CPU/AOT generations beyond the active plus
one rollback, abandoned provider Maven quarantines, provider manifest
temporaries, and APK/Gradle staging. It deliberately preserves the single stable
CMake workspace, managed dependency stages, complete checksum-addressed Graal
object stages, ccache, provider native/dist outputs, and current/rollback APKs. The
provider producer also removes its active Maven quarantine on every exit, so a
failed build cannot accumulate another abandoned native tree.

For focused diagnostics, the underlying producer interfaces remain independently
callable and publish the same canonical layouts. SDX AOT calls must provide
`--object-builder`, `--reuse-jdk-libs`, and `--reuse-svm-libs`; only the Kompile
entry point knows that its graph producer is one source for those generic inputs:

```bash
nd4j/sdx-aot/src/main/android/build-android-sdx-sdk.sh
libnd4j/tools/mobile/build-android-accelerator.sh
```

CI and diagnostic callers can also invoke the lower-level
`android/tools/build-offline-accelerators.sh` directly with explicit variant,
artifact mode, and layout options.

`--work-root` derives disjoint `apk-stage`, `apk-jni/arm64-v8a`, and
`apk-output` paths, enables isolated Gradle output, and discovers both producer
artifacts. It cannot be combined with `--jni-output`; `--output` may select a separate final
publication directory, as the Tensor G3 wrapper does. Advanced
explicit layouts remain supported, but the helper rejects any overlap between
the Gradle build root, JNI source root, RAM staging root, and final output.

The Android SDK is resolved from `ANDROID_HOME`/`ANDROID_SDK_ROOT` or the
standard host locations `~/Android/Sdk` and `~/dev-apps/android-sdk`. Only a
nonstandard SDK location needs `--android-sdk`. The helper prints the complete
resolved layout and every selected tool/artifact source before packaging, so each
APK log records the exact handoff it used.

Release-consumer mode passes each verified absolute path to Gradle with an
explicit Gradle property (`-PsdxVulkanAar`,
`-PsdxHexagonAar`, `-PsdxTensorG3Aar`, or `-PsdxTensorG5Aar`). It never
copies the release AAR into the source tree and cannot be combined with source-build
`--*-aar` overrides.

Maven outputs:

```text
mobile/target/offline-dist/kompile-offline-graph-chat-vulkan.apk
mobile/target/offline-dist/kompile-offline-graph-chat-vulkan.apk.sha256
mobile/target/offline-dist/kompile-offline-graph-chat-hexagon.apk
mobile/target/offline-dist/kompile-offline-graph-chat-hexagon.apk.sha256
mobile/target/offline-dist/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk
mobile/target/offline-dist/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk.sha256
mobile/target/offline-dist/kompile-offline-graph-chat-tensor-g5.apk
mobile/target/offline-dist/kompile-offline-graph-chat-tensor-g5.apk.sha256
```

Direct invocation of `build-offline-accelerators.sh` defaults to
`mobile/android/build/offline-dist`; with `--work-root`, final artifacts are
published to `<work-root>/apk-output`.

After either success or failure, the helper removes only disposable Gradle
intermediates, normalized provider AARs, graph-wrapper work, canonical JNI/Maven
staging, and resolver temporary files. Every build mode passes provider AARs to
Gradle and the final APK verifier by their resolved source path, so the helper
never copies or overwrites `app/libs` inputs. Pre-existing/caller-owned AARs, final APKs and checksums, the
standalone DL4J SDX SDK, Gradle caches, and Maven artifacts in the local repository
are preserved. Cleanup is armed before input validation/release resolution, and an
APK output path inside disposable staging is rejected. Work-root mode also
removes only its exact `apk-stage` and `apk-jni` children while preserving
producer artifacts and `apk-output`. Use `--retain-staging` (or
`KOMPILE_ANDROID_RETAIN_STAGING=1`) only when those intermediates are needed for
diagnosis. `--cleanup-only` applies the same exact-path cleanup without assembling an
APK. This cleanup is targeted and does not invoke a Maven, Gradle, or CMake `clean`
lifecycle.

These are debug-signed research APKs. Production distribution must supply its
own release signing configuration.

## What the build verifies

Before and after assembly the build:

1. validates each provider AAR, ELF ABI, accelerator declaration, and forbidden dependency
   set;
2. assembles only `arm64-v8a`;
3. verifies the APK signature;
4. confirms that `INTERNET` is present for direct Hugging Face discovery and transfer,
   cleartext remains disabled, and app networking is confined to the acquisition class;
5. verifies the graph fixture against `offline-assets.json`;
6. requires and audits the stock-Graal graph AOT plus JavaCPP wrapper;
7. requires the selected provider and the provider-independent `libsdx_llm` runtime;
8. requires ABI v2 and the direct load, chat-template render, generate, unload, and destroy
   exports from `libsdx_llm.so`;
9. proves that every AArch64 `DT_NEEDED` dependency is bundled or an explicitly allowed
   Android/vendor system library;
10. compares provider runtime libraries byte-for-byte with the selected AAR;
11. verifies the JavaCPP provider and direct JNI/raw-SDX loader classes in final DEX while
    rejecting JNA, remote, and legacy transports;
12. requires extracted native packaging for filesystem-discovered native side libraries;
13. rejects OpenBLAS, host libraries, other accelerators, and undeclared ABIs; only the
    audited provider-independent SDX raw CPU library set is exempt from provider fallback
    rules.

The machine-readable contract is `accelerators.json`. APK auditing is a fail-closed shell
entry point with a CMake JSON validator. The final ZIP embeds that contract and re-runs the
same verifier against every packaged APK and its exact packaged AAR before accepting the
bundle. There is no host Java tools module and no Python in the supported build, audit, APK,
or runtime path. On-device code is Kotlin/Java plus JavaCPP for prepared providers, direct
JNI for the ABI-v2 raw SDX image, and the packaged native runtimes.

## Install and use

Install only the flavor matching the physical device:

```bash
adb install -r ../target/offline-dist/kompile-offline-graph-chat-vulkan.apk
adb install -r ../target/offline-dist/kompile-offline-graph-chat-hexagon.apk
adb install -r ../target/offline-dist/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk
adb install -r ../target/offline-dist/kompile-offline-graph-chat-tensor-g5.apk
```

In Settings:

- import a `.kgraph` file or use the bundled fixture;
- import one canonical `.sdz` containing checksummed cache objects for prepared APK targets;
- resolve a Hugging Face repository name or canonical URL, select a complete GGUF/GGML, and
  let the app download, SDX-load, decode-probe, and activate it without configuring Kompile;
- optionally browse a configured Kompile service for already prepared `.sdz`/`.kproject`
  artifacts;
- set temperature and maximum output tokens.

Models are not embedded in the APK. This avoids redistributing gated weights
and lets research devices choose an appropriate memory tier. The research
matrix and conversion/quantization requirements live in
`deeplearning4j/libnd4j/tools/mobile/models.yaml` and
`profiles/int8-per-channel-example.json`.

## Provider-specific limits

### Android Vulkan GPU

The Vulkan AAR is built with the NDK, BLAS disabled, and Android's system Vulkan
loader. `ModelOptions.mobileVulkan()` requires bundle-owned AOT SPIR-V. The
native runtime performs full graph lowering, records the command sequence, and
replays it for decode; an unsupported or unrecordable operation fails closed.
Neither CPU nor generic NNAPI is included as a fallback.

### Qualcomm Hexagon/HTP

The libnd4j CMake graph owns the pinned, checksum-verified Qualcomm Hexagon
SDK, open-access toolchain, HexKL, and hexagon-mlir source downloads. Those are
host compiler inputs for the C++ library and are cached outside the build tree;
they are not manually copied into the APK. The build compiles the project-owned
QAIC/FastRPC host adapter and v75 DSP service, then bundles both the AArch64 host
runtime and `libsdx_hexagon_runtime_skel.so` in the Hexagon APK. A compatible
Qualcomm device supplies only the vendor `libcdsprpc.so` transport; the
Hexagon-only manifest admits it to the app linker namespace. Missing transport,
DSP skeleton, search-path setup, or FastRPC service initialization is reported
separately and fails closed; the app never redirects to CPU.

### Pixel 8a / Google Tensor G3

The `tensorG3` APK uses libnd4j's NNAPI graph compiler, not the Tensor G5
LiteRT dispatch library. At model load it enumerates only
`ANEURALNETWORKS_DEVICE_ACCELERATOR` devices, requires one device to report
support for every operation, and pins every graph segment to that same device
with `ANeuralNetworksCompilation_createForDevices`. One-op, unsupported,
non-contiguous, compilation-failure, and execution-failure paths stop; none
demote to libnd4j CPU kernels. The NNAPI driver compilation is keyed beneath the
content-addressed SDZ cache.

On a Pixel 8a, test `tensorG3` and `vulkan`. The Hexagon APK is intentionally
ineligible because Tensor G3 is not a Qualcomm SoC; the direct `tensorG5` APK is
also intentionally gated to Tensor G5 hardware.

### Google Tensor G5

The prepared Tensor route uses pinned LiteRT-LM dispatch components and accepts only `.sdz`;
the compiler/cache extracts the checksummed `compiledArtifacts.tensorG5LiteRtLm` derivative
internally. Direct `.gguf`/`.ggml` continues to use the common `SDX_GGUF_AOT` route rather
than LiteRT-LM. INT8 validation is fail-closed.
The build is package-verified here; final acceptance requires a Tensor G5
device run with dispatch/NPU tracing.

## Source map

```text
mobile/
├── pom.xml                    opt-in Maven lifecycle adapter
├── cmake/                     profile and final-bundle validators
├── package-offline-graph-chat.sh
├── verify-offline-graph-chat-bundle.sh
└── android/
    ├── accelerators.json
    ├── tools/
    │   ├── build-graph-javacpp.sh
    │   ├── build-offline-accelerators.sh
    │   ├── verify-offline-apk.sh
    │   └── verify-offline-apk-json.cmake
    └── app/src/
        ├── main/              shared UI, graph JavaCPP backend, assets
        ├── sdx/               shared Vulkan/Hexagon/NNAPI session lifecycle
        ├── vulkan/            SDX/Vulkan JavaCPP provider
        ├── hexagon/           SDX/Hexagon JavaCPP provider
        ├── tensorG3/          SDX/NNAPI accelerator-only provider
        └── tensorG5/          LiteRT-LM/Tensor JavaCPP provider
```
