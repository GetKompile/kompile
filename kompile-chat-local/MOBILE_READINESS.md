# Mobile-first offline graph + LLM readiness

This document is the capability ledger for the Kompile/SDX offline graph-chat
stack. Android accelerator builds are the primary deliverable; desktop remains
the authoring and graph-native-AOT reference.

## Delivered stack

| Layer | State |
| --- | --- |
| Offline chat application | Compose application with no remote route or `INTERNET` permission; cancellation, local graph tools, asynchronous graph/model import, and exact accelerator badge |
| App/runtime boundary | Thin `ChatModel` seam backed by SDX JavaCPP APIs; legacy JNA source excluded from accelerator builds |
| Graph reasoning on Android | Stock-GraalVM native AOT graph engine for arm64/bionic, reached through a narrow JavaCPP binding; checksum-verified bundled `.kgraph` and imported graph support |
| Graph reasoning native AOT | C ABI v1 shared libraries for Linux desktop and Android arm64, public header, build/verifier scripts, and native SDK artifacts |
| Functional replay | SDX runtime session performs prefill/decode through the compiled bundle/replay contract and rejects missing device artifacts |
| Vulkan build | ARM64 SDX Vulkan lowering/replay AAR and Android flavor; GPU-only, no OpenBLAS/host fallback |
| Hexagon build | ARM64 SDX AOT/runtime AAR and Android flavor; CMake-owned pinned Qualcomm SDK/toolchain/HexKL/compiler downloads; device-only, no OpenBLAS/host fallback |
| Tensor G3 / Pixel 8a build | ARM64 SDX NNAPI AAR and Android flavor; exact accelerator-only device selection with no CPU/GPU/slot fallback |
| Tensor G5 build | ARM64 LiteRT-LM/Google dispatch AAR and Android flavor; device-only, no OpenBLAS/host/GPU/NNAPI fallback |
| Tensor quantization contract | Fail-closed INT8 manifest validator, canonical profile writer, compile/cache integration, and research model matrix; it does not claim to rewrite SDZ weights |
| Model-specific target compilation | Content-addressed compiler/cache/package SPI and staging handoff are delivered; unquantized Tensor G3 uses the mainline NNAPI on-device policy compiler, while real host adapters must still emit Vulkan replay coverage, finalized Hexagon kernels, a genuinely quantized Tensor G3 SDZ, or a valid Tensor G5 LiteRT-LM package |
| Reproducibility | Offline build driver, provider AAR verifiers, APK verifier, machine-readable accelerator contract, checksums, deterministic final ZIP packager |

## Execution topology

```text
Compose UI
  -> ChatEngine / canonical graph-aware prompt
     -> AndroidNativeGraphBackend -> JavaCPP -> stock-Graal/NDK graph AOT
     -> AcceleratedChatModelAndroid
        -> Vulkan flavor: SdxRuntime + SDX Vulkan lowering/replay
        -> Hexagon flavor: SdxRuntime + Qualcomm compiler/runtime seam
        -> Tensor G3 flavor: SdxRuntime + accelerator-only NNAPI
        -> Tensor G5 flavor: SdxLiteRtLmChatSession + Google dispatch
```

No path selects a remote endpoint. Accelerator failure is surfaced to the user
instead of falling through to a host/CPU backend.

## Artifact contract

### Android Vulkan

- ABI: `arm64-v8a` only
- model: canonical `.sdz` resolved through the content-addressed target cache
- execution: SDX Vulkan lowering and functional replay
- OpenBLAS: forbidden
- host/CPU fallback: forbidden
- cross-provider libraries: forbidden

### Android Hexagon

- ABI: `arm64-v8a` only
- model: compiled `.dspb` bundle and tokenizer
- execution: SDX Hexagon AOT/replay
- OpenBLAS: forbidden
- host/CPU fallback: forbidden
- cross-provider libraries: forbidden
- external device requirement: an Android host adapter compatible with the
  Qualcomm compiler/runtime inputs

CMake downloads, checksum-verifies, caches, extracts, and validates Qualcomm's
SDK, Hexagon tools, HexKL, and `hexagon-mlir` sources as external projects.
Qualcomm's public `hexagon-mlir` project supplies the host compiler plugin and
DSP bitcode runtime but does not publish the Android host
`libhexagon_mlir_runtime.so` adapter. The build retains that narrow dynamic
adapter seam; physical HTP acceptance requires a compatible adapter and device.

### Android Google Tensor G3 / Pixel 8a

- ABI: `arm64-v8a` only
- model: canonical `.sdz`; unquantized staging emits the cached mainline Tensor G3 on-device policy, while an INT8 request is accepted only after the configured host compiler emits a genuinely rewritten quantized SDZ plus its policy artifact
- execution: SDX functional replay through an exact NNAPI accelerator device; the Pixel driver performs its device-specific compilation and the runtime persists that cache
- NNAPI mode: whole graph supported by one accelerator or fail closed
- OpenBLAS: forbidden
- CPU, GPU, partition, and slot fallback: forbidden
- cross-provider libraries: forbidden

NNAPI driver compilation is persisted in the application code-cache directory,
keyed by the SDX compile key, segment, shape, and selected device. The model
bundle remains immutable. Final device proof requires Pixel 8a tracing.

### Android Google Tensor G5

- ABI: `arm64-v8a` only
- model: device-compatible `.litertlm`
- execution: pinned LiteRT-LM with Google dispatch
- quantization: INT8 contract required by the research pipeline
- OpenBLAS: forbidden
- host, CPU, GPU, and NNAPI fallback: forbidden
- cross-provider libraries: forbidden

The application passes the exact native library directory to the dispatch
session. Final performance/correctness acceptance requires a physical Tensor G5
device because the NPU dispatch path cannot be emulated faithfully.

### Graph AOT

The graph module produces
`kompile-graph-reasoning-local-0.1.0-SNAPSHOT-native-sdk.zip` for desktop and
`libkompile_reasoning_android.so` for Android arm64/bionic. The Android path
uses stock GraalVM `native-image` to emit the AOT object, a pinned LabsJDK as
the target JDK support library, and Android NDK r28b clang/lld for the final
API-28 shared-library link. Gluon is not used.

`libjnikompile_graph.so` is a generated JavaCPP transport over the stable
`kgr_*` C ABI. All isolate and graph-session operations stay on one dedicated
native thread. The APK audit requires both libraries, exact AArch64/DYN ELF
shape, RELRO/BIND_NOW, an Android-only dependency closure, and no OpenBLAS or
host runtime.

## Offline application acceptance gates

Build/package gates:

- [x] exact Vulkan, Hexagon, Tensor G3, and Tensor G5 product flavors;
- [x] ARM64-only packaging;
- [x] no Android `INTERNET` permission;
- [x] no OpenBLAS or host runtime in accelerator artifacts;
- [x] JavaCPP-backed SDX provider seam;
- [x] stock-GraalVM/NDK Android graph AOT with JavaCPP transport;
- [x] checksum-verified local graph asset;
- [x] native token-stream callback seam and cancellation (tool-aware UI commits completed turns);
- [x] asynchronous, transactional model/graph import into app-owned storage;
- [x] provider-specific extension validation;
- [x] AAR and APK verifiers;
- [x] reproducible four-flavor build configuration;
- [x] deterministic full-bundle packager.

Model-artifact compiler gates:

- [x] unquantized Tensor G3 staging emits a source-bound, SoC-bound NNAPI
  on-device compilation policy and packages it through the canonical SDX cache;
- [ ] Vulkan host adapter loads the SDZ, executes every declared shape envelope,
  and publishes complete validated SPIR-V replay coverage;
- [ ] Hexagon host adapter drives functional replay, Qualcomm compilation, and
  `HexagonAot` finalization in one source-identity-checked transaction;
- [ ] Tensor G3 quantization adapter rewrites SameDiff weights to real
  per-channel INT8 and emits a matching accelerator-only policy; a
  metadata-only copy is rejected;
- [ ] Tensor G5 host adapter exports a structurally valid `.litertlm` package
  using a supported Google exporter.

Physical-device gates:

- [ ] Qualcomm device run with licensed HTP adapter and proof that replay occurs
  on HTP;
- [ ] Pixel 8a run with NNAPI tracing and proof that the complete graph executes
  on the Tensor G3 accelerator;
- [ ] Tensor G5 device run with dispatch tracing and proof that decode occurs on
  the TPU/NPU;
- [ ] representative INT8 model accuracy, TTFT, decode tokens/s, peak memory,
  and thermal runs;
- [ ] lifecycle stress: background/foreground, cancellation, repeated
  load/unload, and low-memory recovery.

The unchecked gates are real target-compiler and physical-hardware acceptance work. None is satisfied by CPU output, copied placeholders, or metadata-only packages.

## Research models

`deeplearning4j/libnd4j/tools/mobile/models.yaml` contains small-to-larger
research candidates and separates:

- openly downloadable metadata from gated/licensed weights;
- Tensor G3 and Tensor G5 INT8 conversion expectations;
- Vulkan target-cache and replay expectations;
- Hexagon compiled-bundle expectations;
- tokenizer and context requirements;
- device memory tiers.

No model weights are placed in the APK or final ZIP. The conversion pipeline
requires an explicit quantization manifest; invalid or incomplete INT8 metadata
stops compilation.

## Reproduction

Build, audit, package, and verify all four Android variants through the opt-in
Maven lifecycle module:

```bash
./mvnw -o -f kompile-chat-local/pom.xml verify \
  -Dkompile.mobile=all \
  -Dmobile.android.sdk=/path/to/android-sdk \
  -Dmobile.android.ndk=/path/to/android-sdk/ndk/28.1.13356709 \
  -Dmobile.java.home=/path/to/jdk-17 \
  -Dmobile.maven=/absolute/path/to/mvn
```

The mobile module is not part of the default reactor. Set
`kompile.mobile` to `vulkan`, `hexagon`, `tensor-g3`, or `tensor-g5`
for one APK. The `all` lifecycle additionally creates and verifies
`kompile-chat-local/mobile/target/kompile-offline-graph-chat-full.zip`.

Maven owns lifecycle and profile selection and is required to run offline.
Native compilation stays in libnd4j CMake/profile builds; APK and ZIP boundaries
are Linux/GNU reproducible shell entry points backed by strict CMake validators.
Bundle verification re-runs the exact APK/provider/AAR audit for all four
variants. There is no host Java tools module and Python is not part of the
supported build, audit, APK, or inference path.

The ZIP contains four APKs, four exact runtime AARs, the Android graph AOT and
JavaCPP shared libraries, graph fixture and public header, desktop graph native
AOT SDK, Android application, canonical model-staging/project-store, graph,
and SDX source subsets, model matrix, INT8 contract tooling, CMake-owned vendor
dependency configuration, build scripts, verifiers, and a per-file SHA-256
manifest. Generated targets, Python sources/bytecode, and the retired host-tools
module are excluded.
