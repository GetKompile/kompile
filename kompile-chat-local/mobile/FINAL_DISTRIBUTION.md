# Kompile offline graph chat distribution

This distribution is a mobile-first, locally runnable research bundle. It contains exactly two Android arm64-v8a applications for Pixel 8a testing:

- Vulkan: provider `sdx.vulkan.v1`.
- Tensor G3 TPU/NPU: provider `sdx.nnapi-tensor-g3.v1`.

The prepared `.sdz` routes in both APKs are accelerator-only and never demote to CPU. Each APK also packages the explicit provider-independent `SDX_GGUF_AOT` route for directly imported GGUF/GGML; that route is selected by model format and is not a fallback from a failed provider. The applications contain no runtime JIT, OpenBLAS payload, or remote inference. Android `INTERNET` access is confined to public Hugging Face metadata and selected model transfer; cleartext traffic and credentials are not supported. Each APK must contain its selected SDX provider, JavaCPP bridge, ABI-v2 `libsdx_llm` raw execution image and companion libraries, and GraalVM-AOT graph reasoning libraries. The bundle also carries the exact input AARs, provider manifests, graph native SDK, graph fixture, canonical SDZ fixture, model-staging executable, source, licenses, and SHA-256 provenance used for verification.

No production model weights are bundled. On first launch the app presents project/model import instead of a native-session error. Direct Hugging Face GGUF/GGML is an app-owned download, SDX load/template/decode, and atomic activation flow. Only optional prepared Kompile `.sdz`/`.kproject` acquisition uses a browser; see `MODEL_IMPORT.md` for the activation contract.

## Reproducible pipeline

`cmake/FinalOfflineDistribution.cmake` is the single packaging implementation. It exposes these script modes:

- `PLAN`: inspect the source contract and report missing fresh artifacts without copying anything.
- `VALIDATE`: require exact input hashes and run the APK, provider, staging-JAR, graph, and offline-policy checks.
- `STAGE`: validate, copy the contracted payload, write per-artifact checksums and `MANIFEST.json`, then verify the complete stage.
- `VERIFY_STAGE`: revalidate a stage without source/build inputs.
- `PACKAGE`: verify a stage and create a sorted ZIP with fixed 1980 timestamps and normalized modes.
- `VERIFY_ARCHIVE`: verify the ZIP checksum, member order, timestamps, manifest, and every member digest.

The thin `package-offline-graph-chat.sh` wrapper accepts explicit artifact
paths and full 64-hex expected hashes. It never discovers a "latest" build,
derives an expected digest from the artifact being checked, or accepts a
truncated hash. Canonical path defaults point at the two APK exports, the exact
AARs used by those flavors, provider manifests from their CMake SDK outputs,
the Maven model-staging executable, and the Maven-owned graph AOT outputs.
Every path can be overridden.

```sh
./package-offline-graph-chat.sh --plan
./package-offline-graph-chat.sh --validate \
  --expected-vulkan-sha256 <64-hex> \
  --expected-tensor-g3-sha256 <64-hex> \
  --expected-vulkan-aar-sha256 <64-hex> \
  --expected-vulkan-libjnisdx-sha256 <64-hex> \
  --expected-tensor-g3-aar-sha256 <64-hex> \
  --expected-staging-jar-sha256 <64-hex>
# Repeat with --package after validation; it creates a clean stage, then the ZIP.
./verify-offline-graph-chat-bundle.sh \
  --bundle build/kompile-offline-graph-chat-full.zip
```

The CMake archive worker requires CMake 3.20 or newer, POSIX `touch`, and
Info-ZIP `zip`/`zipinfo`. It sorts the explicit member list, strips variable
ZIP metadata, normalizes modes, and fixes the ZIP wall-clock timestamp to
`1980-01-01 00:00:00` under UTC. The focused contract test builds the archive
twice and requires byte-identical SHA-256 values.

Build provenance records a stable archive-relative source path and SHA-256 for both APKs, both runtime AARs, and the model-staging executable; it never embeds host checkout paths. The canonical Vulkan AAR additionally pins `jni/arm64-v8a/libjnisdx.so`. APK checksum sidecars must name the exact APK and match the separately supplied expected hash.

## Apple/iOS scope

The Linux-produced final ZIP contains an Apple source-only SDK contract, not an iOS binary. It includes the pinned MLX/Metal C++ source, strict SDX provider sources, Core ML/ANE source, Swift package integration, CMake publication targets, patch/provenance data, and license materialization rules. It intentionally contains no XCFramework, `default.metallib`, `libmlx.a`, `.mlpackage`, or `.mlmodelc`. Xcode/iPhoneOS builds, signing, and physical-device Metal/Core ML evidence must be produced on an Apple host; see `docs/APPLE_IOS_SOURCE_SDK.md`.

## Verification boundary

Host verification proves artifact identity, archive integrity, ABI/dependency policy, packaged accelerator provider identity, acquisition permission policy, graph-AOT presence, the ABI-v2 raw SDX load/template/generate surface, independent in-app Hugging Face acquisition and prepared-artifact handoff boundaries, import diagnostics, and source-only Apple claims. It does not claim Android device execution until the APK is exercised on matching physical hardware and the resulting telemetry is retained.
