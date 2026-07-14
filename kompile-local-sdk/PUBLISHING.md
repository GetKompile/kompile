# Kompile Local SDK — Publishing Guide

This document describes how to publish the language-specific registry packages for
the Kompile Local SDK bindings.  **No publishing is required to USE the SDK** — all
bindings ship as vendored source inside the zip produced by `assemble-local-sdk.sh`.
Registry publishing is a Phase 2 goal once the native artifacts stabilise.

## Packages

| Language | Package name | Registry | Status |
|---|---|---|---|
| Python | `kompile-local-sdk` | PyPI | Phase 2 |
| TypeScript/Node | `@kompile/local-sdk` | npm | Phase 2 |
| Rust | `kompile-local-sdk` | crates.io | Phase 2 |
| Swift | (Swift Package Manager) | Swift Package Index | Phase 3 |
| C# | `Kompile.LocalSdk` | NuGet | Phase 3 |

## What CI must stamp before publishing

1. **Version**: the `kompile-local-sdk` version matches the `kompileVersion` field in
   `manifest.json` (set by `assemble-local-sdk.sh`).

2. **ABI pairing**: `kgrAbiVersion` and `sdxLlmAbiVersion` in manifest.json must match
   the `KGR_ABI_VERSION` and `SDX_LLM_ABI_VERSION` macros in the respective headers.

3. **Native artifacts present**: `lib/libkompile_reasoning.so` and `lib/libsdx_llm.so`
   must be present in the assembled zip (no MISSING placeholder files).

4. **Platform tag**: the zip name must contain the platform classifier
   (currently `linux-x86_64`; add `macos-arm64`, `windows-x86_64` as they ship).

## Python (PyPI)

```bash
# From kompile-local-sdk/bindings/python/
pip install build twine
python -m build
twine upload dist/*
```

The `pyproject.toml` declares no runtime dependencies (pure ctypes).
Set `KOMPILE_LIB_DIR` at runtime to point to the directory containing the .so files.

## TypeScript / Node.js (npm)

```bash
# From kompile-local-sdk/bindings/typescript/
npm install
npm publish --access public
```

The `koffi` dependency is declared in `package.json`.
The native .so files must be on `LD_LIBRARY_PATH` or set `KOMPILE_LIB_DIR`.

## Rust (crates.io)

```bash
# From kompile-local-sdk/bindings/rust/
cargo publish
```

The crate is pure FFI — no runtime crate dependencies.
The native libraries must be available at link time or via `LD_LIBRARY_PATH`.

## Swift (Swift Package Index)

Create a `Package.swift` manifest in `bindings/swift/` pointing at the vendored
`KompileReasoning.swift` and `SdxLlm.swift` sources.  Publish to Swift Package Index
by tagging the repo with the SPI-compatible `v{version}` tag after including a
`Package.swift`.  Prerequisite: xcframework artifacts for the target platforms.

## C# (NuGet)

Create a `.nuspec` file in `bindings/csharp/` with the package metadata from
`pyproject.toml` as a reference template.  Use `dotnet pack` and `dotnet nuget push`.
Prerequisite: the `.so`/`.dll`/`.dylib` native libraries included as content files.

## Version bump checklist

- [ ] Bump version in `manifest.json` (`kompileVersion` field)
- [ ] Bump version in `bindings/python/pyproject.toml`
- [ ] Bump version in `bindings/typescript/package.json`
- [ ] Bump version in `bindings/rust/Cargo.toml`
- [ ] Tag the kompile repo: `kgr-v{version}` (for `libkompile_reasoning` releases)
  and `kompile-local-sdk-v{version}` (for combined SDK releases)
- [ ] Re-run `assemble-local-sdk.sh` to produce the new zip
- [ ] Upload the zip to the corresponding GitHub release tag
