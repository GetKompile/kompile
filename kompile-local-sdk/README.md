# Kompile Local SDK

The Kompile Local SDK composes an extracted canonical DL4J AOT SDK with the
Kompile-owned graph-reasoning runtime. The upstream SDK is copied intact; Kompile
overlays only `libkompile_reasoning`, its header, its language bindings, and the
reasoning examples.

## Required input

Provide the canonical release manifest (with its adjacent `.sha256` sidecar) and the
exact AOT archive selected from it. The assembler verifies the manifest sidecar, exact
`aot/aot-sdk/platform/variant` record, filename, size, and SHA-256 before extraction.

```bash
SDX_SDK_MANIFEST=/downloads/sdx-sdk-manifest.json \
SDX_SDK_ARTIFACT=/downloads/sdx-aot-1.0.0-linux-x86_64-cpu-aot.zip \
SDX_SDK_PLATFORM=linux-x86_64 \
SDX_SDK_VARIANT=cpu \
KGR_LIB_SRC=/path/to/libkompile_reasoning.so \
./assemble-local-sdk.sh
```

The output is `target/kompile-local-sdk-<kompile-version>-<manifest-classifier>.zip`.
The classifier is always the verified manifest value and cannot be overridden.
`KGR_LIB_SRC` is required explicitly so a stale repository-local binary cannot be
packaged by accident. `KOMPILE_SDK_OUTPUT_DIR` may redirect staging and output
(useful for CI contracts).

## Composition and provenance

The assembly preserves all upstream paths and files, including the canonical
`sdx-sdk-manifest.json`. It adds `kompile-composition-manifest.json`, which records:

- upstream `schemaVersion`, `releaseVersion`, and `releaseTag`;
- SHA-256 of the exact upstream manifest;
- the independent Kompile/KGR version and ABI;
- the Kompile-owned overlay paths.

This repository does not vendor SDX headers, libraries, or SDX language wrappers.
Those files always come from the verified, manifest-selected `SDX_SDK_ARTIFACT` input.
The archive is expanded into a private staging directory with traversal, link,
duplicate-entry, size-limit, and internal AOT-layout validation before promotion.

## Overlay contents

- `include/kompile_reasoning.h`
- `lib/libkompile_reasoning.so`
- Kompile reasoning bindings for Python, Rust, TypeScript, Swift, and C#
- cross-language graph-reasoning examples (plus `fixture.kgraph` when supplied locally)

All SDX runtime files and companion native dependencies retain the canonical
upstream layout. Consumers should use that layout and the upstream README rather
than assuming a synthesized `libsdx_llm` filename.

## Runtime environment

The Kompile examples accept `KGR_LIBRARY` for an explicit reasoning-library path,
`KOMPILE_SDK_KGRAPH` for a graph, and `KOMPILE_SDK_MODEL_PATH` for a model. SDX
runtime variables and platform-specific loading behavior are defined by the
canonical upstream SDK included in the composition.
