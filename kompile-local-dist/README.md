# Kompile local execution distribution

`kompile-local-dist` is the native, folder-local subset of the full Kompile
distribution. It is intended for the standard CLI chat wizard and MCP tools that
orchestrate models, pipelines, document extraction, crawl, and knowledge work
inside a project without starting `kompile-app`.

The archive contains:

- `kompile`, including the hidden local-crawl child mode
- `kompile-model-staging`
- `kompile-model-serving`
- `kompile-pipeline-serving`
- `kompile-vlm-test`
- one backend's validated side-loaded native-library closure
- matching SDX runtime packages and platform JARs
- the canonical Kompile build scripts

It intentionally excludes the web/app server, persona and batch services, JVM
fallback runtime and CLI JAR, C/Python bindings, and unrelated product CLIs.

## End-to-end builds

Use the same platform pipeline as the full distribution. The wrappers build the
Kompile checkout they are invoked from and never fetch or switch its branch. The
`--setup` option loads the existing DL4J setup implementation (cloning that
playbook when necessary) and delegates prerequisite installation to its apt, dnf,
brew, and other supported platform package-manager paths.

```bash
./build-scripts/build-kompile-local-cpu.sh --setup
./build-scripts/build-kompile-local-cuda.sh --setup
```

The generic entry point accepts every platform supported by
`build-kompile-platform.sh`:

```bash
./build-scripts/build-kompile-local.sh linux-x86_64 --setup
./build-scripts/build-kompile-local.sh linux-x86_64-cuda-12.9 --setup
```

To reuse an existing DL4J/backend build or existing Kompile artifacts, pass the
canonical skip flags rather than using a separate packaging path:

```bash
./build-scripts/build-kompile-local-cuda.sh --skip-dl4j
./build-scripts/build-kompile-local-cuda.sh --skip-dl4j --skip-java --skip-native
```

The default native target closure is
`cli,staging,model-serving,pipeline-serving,vlm-test`. Override
`NATIVE_TARGETS` only for incremental development; a release archive still
fails closed if any required local executable is absent.

## Maven module

The module participates in the reactor when `-Dkompile.local.dist=true` is
set:

```bash
./mvnw -Dkompile.local.dist=true -pl :kompile-local-dist validate
```

Its package phase calls the canonical `build-dist.sh local` assembler and
attaches both the ZIP and Linux/macOS installer tarball under the same
backend-qualified classifier. It assumes the native executables and SDX assets
were already produced, so the source-build scripts above are the normal
end-to-end entry points. An incremental Maven assembly can reuse those outputs:

```bash
KOMPILE_SDX_ASSETS_DIR=/path/to/cpu-sdk-assets \
  ./mvnw -Dkompile.local.dist=true -pl :kompile-local-dist package

KOMPILE_SDX_ASSETS_DIR=/path/to/cuda-sdk-assets \
  ./mvnw -Dkompile.local.dist=true -Dkompile.backend=cuda-12.9 \
  -Dkompile.local.backend.profile=cuda-12.9 \
  -pl :kompile-local-dist package
```

The root backend profile supplies the SDK and release classifiers, so the CUDA
command attaches `local-linux-x86_64-cuda-12.9` rather than an ambiguous base
platform artifact.

## Installation

Backend-qualified local archives use the same installer and manifest-preserving
upgrade flow as the full distribution:

```bash
./install.sh --variant local --backend-profile cpu
./install.sh --variant local --backend-profile cuda-12.9
```

For a private or staged release, add `--version` and `--url`. The installer
resolves names such as
`kompile-dist-<version>-local-linux-x86_64-cuda-12.9.tar.gz` directly.
