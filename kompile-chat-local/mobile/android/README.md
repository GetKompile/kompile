# Kompile Chat Local — Android accelerator builds

This is a mobile-first, fully offline Compose chat application. Graph search and
reasoning run locally through the Kompile graph engine; local text generation is
provided by one of four device-specific accelerator flavors.

| Flavor | Model input | Execution route | CPU/BLAS fallback |
| --- | --- | --- | --- |
| `vulkan` | canonical `.sdz` | SDX Vulkan lowering, command capture, and replay on Android GPU | forbidden |
| `hexagon` | canonical `.sdz` | SDX AOT replay on Qualcomm Hexagon/HTP | forbidden |
| `tensorG3` | canonical `.sdz` | libnd4j NNAPI pinned to one complete-graph `DEVICE_ACCELERATOR` | forbidden |
| `tensorG5` | canonical `.sdz` | Google LiteRT-LM dispatch on Tensor G5 TPU/NPU | forbidden |

All four variants are ARM64-only, omit the Android `INTERNET` permission, and fail
closed when their exact provider or model contract is unavailable. OpenBLAS,
host runtimes, implicit NNAPI partitioning, and alternate providers are rejected by the
packaging verifier.

## Architecture

The application layer depends only on `ChatModel` and
`AcceleratedChatModelAndroid`. Flavor source sets provide
`PlatformLocalChatSession` implementations:

- Vulkan, Hexagon, and Tensor G3 share one SDX JavaCPP session lifecycle using `SdxRuntime`,
  `NativeTokenizer`, and `SdxTextSession`. Only their strict model options and
  route identity differ: `mobileVulkan()`, `mobileHexagon()`, and
  `mobileNnapiAccelerator()`.
- Tensor G5 uses the SDX JavaCPP LiteRT-LM session and Google's dispatch runtime.

The retained legacy JNA source is excluded from every accelerator compiler task
and is not packaged. There is no remote chat route. Streaming, stop-token
handling, cancellation, session reset, and resource ownership are implemented
at the provider seam.

The bundled `fixture.kgraph` is checksum-verified on every launch. Imports run
off the UI thread and are transactionally moved into app-owned storage. Graphs
are opened once for format validation before activation. The application accepts
only the canonical SameDiff `.sdz` identity. Provider formats are compiler-cache
details embedded under `META-INF/sdx-cache`, extracted into a checksummed
app-private cache, and selected by the APK target profile.

Graph reasoning runs in `libkompile_reasoning_android.so`, built by stock
GraalVM `native-image` plus Android NDK r28b for arm64/bionic API 28. A small
generated `libjnikompile_graph.so` JavaCPP transport exposes the stable
`kgr_*` C ABI to the app. Gluon is not used, and graph lifecycle calls remain
on one native thread because the Graal isolate thread handle is thread-affine.

## Build all four APKs

Required inputs:

- Linux with Bash 4+, GNU coreutils/findutils, Info-ZIP, and CMake 3.20+
- JDK 17 (Temurin or Amazon Corretto; do not use GraalVM for AGP)
- Android SDK API 35 and NDK r28b
- a prebuilt Android graph AOT library, or the pinned stock-Graal/LabsJDK inputs
  consumed by `kompile-graph-reasoning-local/build-android-ndk.sh`
- populated Gradle and Maven caches
- the exact Vulkan, Hexagon, Tensor G3 NNAPI, and Tensor G5 SDX runtime AARs
  produced by the corresponding libnd4j CMake/Maven profiles

The normal entry point is the opt-in Maven module. It is absent from the default
reactor and is activated only by `-Dkompile.mobile`:

```bash
./mvnw -o -f kompile-chat-local/pom.xml verify \
  -Dkompile.mobile=all \
  -Dmobile.android.sdk=/path/to/android-sdk \
  -Dmobile.android.ndk=/path/to/android-sdk/ndk/28.1.13356709 \
  -Dmobile.java.home=/path/to/jdk-17 \
  -Dmobile.maven=/absolute/path/to/mvn
```

Use `vulkan`, `hexagon`, `tensor-g3`, or `tensor-g5` instead of `all`
to assemble one APK. Maven owns lifecycle and variant selection; libnd4j CMake
owns accelerator-native builds, and the Android Gradle build remains the APK
packaging boundary. The profile invokes Gradle and its nested Maven preparation
offline.

The lower-level `android/tools/build-offline-accelerators.sh` remains directly
usable for CI or diagnosis. Runtime AARs may be overridden with its
`--vulkan-aar`, `--hexagon-aar`, `--tensor-g3-aar`, and
`--tensor-g5-aar` options or with the matching Maven properties.

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

Direct invocation of `build-offline-accelerators.sh` instead defaults to
`mobile/android/build/offline-dist`.

These are debug-signed research APKs. Production distribution must supply its
own release signing configuration.

## What the build verifies

Before and after assembly the build:

1. validates each provider AAR, ELF ABI, accelerator declaration, and forbidden
   dependency set;
2. assembles only `arm64-v8a`;
3. verifies the APK signature;
4. confirms that `INTERNET` is absent;
5. verifies the graph fixture against `offline-assets.json`;
6. requires and audits the stock-Graal graph AOT plus JavaCPP wrapper;
7. requires the selected provider libraries;
8. proves that every AArch64 `DT_NEEDED` dependency is bundled or an
   explicitly allowed Android/vendor system library;
9. compares every runtime library byte-for-byte with the selected AAR;
10. verifies the JavaCPP loader hierarchy in the final APK DEX;
11. requires extracted native packaging for filesystem-discovered DSP payloads;
12. rejects OpenBLAS, host/CPU libraries, other accelerators, and undeclared ABIs.

The machine-readable contract is `accelerators.json`. APK auditing is a
fail-closed shell entry point with a CMake JSON validator. The final ZIP embeds
that contract and re-runs the same verifier against every packaged APK and its
exact packaged AAR before accepting the bundle. There is no host Java tools
module and no Python in the supported build, audit, APK, or runtime path.
On-device code is Kotlin/Java plus JavaCPP and the selected native provider.

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
- import one canonical `.sdz` containing checksummed cache objects for the APK
  targets you intend to test;
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

The Tensor build uses pinned LiteRT-LM dispatch components. The app still accepts
only `.sdz`; the compiler/cache extracts the checksummed
`compiledArtifacts.tensorG5LiteRtLm` derivative internally.
INT8 validation is fail-closed.
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
