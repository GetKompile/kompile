# Kompile Local SDK — Publishing Guide

This document describes how to publish the language-specific registry packages for
the Kompile Local SDK bindings.  **No publishing is required to USE the SDK** — all
Kompile reasoning bindings ship as source inside the zip produced by `assemble-local-sdk.sh`;
SDX-owned bindings come intact from the canonical, manifest-selected AOT archive.
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

2. **Provenance pairing**: `kgrAbiVersion` matches `KGR_ABI_VERSION`, while
   `kompile-composition-manifest.json` records the upstream release identity and the
   SHA-256 of its untouched `sdx-sdk-manifest.json`.

3. **Native artifacts present**: CI must pass the intended reasoning library through
   explicit `KGR_LIB_SRC`; the canonical upstream SDK root must also be complete.
   Assembly fails rather than using repository-local defaults or creating MISSING
   placeholder files.

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

Create a `Package.swift` manifest for the Kompile-owned `KompileReasoning.swift` source;
reference any SDX Swift package supplied by the canonical upstream SDK. Publish to Swift Package Index
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
- [ ] Verify `SDX_SDK_ARTIFACT` was selected from `sdk-v<version>/sdx-sdk-manifest.json`
- [ ] Verify the composition manifest contains the expected upstream manifest SHA-256
- [ ] Upload the zip to the corresponding GitHub release tag
