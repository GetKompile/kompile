# Apple/iOS source-only SDX SDK

The Linux final distribution carries a **source-only** Apple SDK contract. It does not claim an iOS build and must not contain an XCFramework, compiled MLX archive, `default.metallib`, Core ML package, or codesigned application.

Two production provider paths share the stable `sdxAppleProviderGetApiV1` C ABI and Swift facade:

- `sdx.metal.v1` is the strict Apple arm64 DSP/MLX/Metal provider. `SdxMetalProvider.mm` delegates model loading, shape-key compilation/cache, graph replay, execution, and fallback telemetry to the shared libnd4j SDX runtime with `SDX_BACKEND_MLX` and `SDX_GPU_TARGET_METAL`.
- `sdx.coreml-ane.v1` loads a staged `.mlmodelc` and requests `MLComputeUnitsCPUAndNeuralEngine`. Apple does not expose per-operation placement, so an Instruments/Core ML device trace is required for ANE evidence.

A third, separately gated `sdx.metal-aot.v1` source path implements raw content-addressed metallib/plan indirect-command-buffer replay. It is disabled unless an in-tree exporter target and exporter provenance are supplied; it is not substituted for the production MLX provider.

## Release-consumer selection

For an SDK release, use the canonical `sdx-sdk-manifest.json` beside its artifact
root. Select exactly one record with
`component=runtime`, `packageRole=apple-xcframework`, the required Apple
`platform`, and the required provider `variant`. Use that record's exact
`fileName`; do not synthesize a name from the selectors or release version.
Before extraction, require a safe basename and verify the artifact's exact `size`
and SHA-256 from the record. Missing, duplicate, or corrupt matches are fatal.

Extract that verified XCFramework archive into `mobile/ios/Frameworks/` and link
the framework it contains. The Kompile reasoning/KGR XCFramework is a separate
Kompile-owned input; it is never selected from the SDX runtime record and must not
be folded into or substituted for the SDX archive.

## Apple-host source build

The producer workflow remains separate. From the deeplearning4j checkout on a
macOS/Xcode host:

```sh
cmake -S libnd4j -B build-ios-apple -G Xcode \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=iphoneos \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
  -DSD_PACKAGE_APPLE_METAL_PROVIDER=ON \
  -DSD_PACKAGE_APPLE_COREML_ANE_PROVIDER=ON
cmake --build build-ios-apple --config Release --target sdx_apple_sdk_publish
```

The enabled targets final-link the in-tree libnd4j object closure, create exact-provider XCFrameworks, stage the C/Swift module hierarchy and license resources, run Swift acceptance links, and emit deterministic Swift SDK archives plus SHA-256 sidecars. The Metal package materializes the pinned MLX and Metal-cpp licenses as `MLX-LICENSE.txt` and `METAL-CPP-LICENSE.txt`.

Raw Metal AOT is opt-in and additionally requires `SD_PACKAGE_APPLE_METAL_AOT_PROVIDER=ON`, `SDX_METAL_AOT_EXPORTER_TARGET=<in-tree-target>`, and the exporter provenance contract. Do not enable it with an external/manual artifact.

## Linux source contract

Linux can validate the source/header/publication contract without pretending to compile Apple frameworks:

```sh
cmake -DSDX_SOURCE_DIR=/absolute/path/to/libnd4j \
  -P libnd4j/cmake/tests/SdxAppleProviderContractTest.cmake
cmake -DSDX_SOURCE_DIR=/absolute/path/to/libnd4j \
  -P libnd4j/cmake/tests/SdxMlxStrictRuntimeContractTest.cmake
```

These checks cover pinned provenance, provider identity, direct Swift calls, bundle/hash validation, replay and telemetry source contracts, deterministic publication metadata, and the source-only boundary. Final Objective-C++/Swift linking, Xcode packaging, signing, and physical Metal/Core ML execution remain Apple-host acceptance gates.
