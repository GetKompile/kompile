# Kompile local execution distribution

`kompile-local-dist` is the folder-local subset of the full Kompile distribution.
Its default Maven package is the JVM/JAR tier for the standard CLI chat wizard and
MCP tools that orchestrate models, pipelines, crawl, and knowledge work inside a
project without starting `kompile-app`. The native executable tier is an explicit
`native` profile (`-Pnative`) over the same assembler.

The local archive contains the CLI, model-serving and pipeline-serving
artifacts, one backend's validated runtime closure, matching SDX packages, and the
canonical Kompile build scripts. The JVM tier carries the runnable JARs; the
native tier carries prebuilt native executables and side-loaded libraries when
those artifacts have been staged.

Both tiers intentionally exclude model provisioning/staging, the web/app server,
persona and batch services, C/Python bindings, and unrelated product CLIs. Staging
remains an explicit build/distribution target for workflows that need to download,
convert, validate, or promote new model assets.

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

The native source-build target closure is
`cli,model-serving,pipeline-serving`. Override `NATIVE_TARGETS` only
for incremental development; a release archive still fails closed if any
required local execution executable is absent.

## Maven module

The module participates in the reactor when `-Dkompile.local.dist=true` is
set:

```bash
./mvnw -Dkompile.local.dist=true -pl :kompile-local-dist validate
```

Its default package phase calls the canonical `build-dist.sh local --jars-only` assembler and
attaches both the ZIP and Linux/macOS installer tarball under the same
backend-qualified classifier. This JVM package does not require native images. The native tier is selected explicitly with the `native` profile and assumes its
executables were already staged by the source-build scripts:

```bash
KOMPILE_SDX_ASSETS_DIR=/path/to/cpu-sdk-assets \
  ./mvnw -Dkompile.local.dist=true -Dkompile.backend=cpu \
  -pl :kompile-local-dist package

KOMPILE_SDX_ASSETS_DIR=/path/to/cuda-sdk-assets \
  ./mvnw -Dkompile.local.dist=true -Dkompile.backend=cuda-12.9 \
  -pl :kompile-local-dist package

# Native archive from staged native outputs:
KOMPILE_SDX_ASSETS_DIR=/path/to/cuda-sdk-assets \
  ./mvnw -Pnative -Dkompile.local.dist=true -Dkompile.backend=cuda-12.9 \
  -pl :kompile-local-dist package
```

`-Dkompile.backend` selects the root backend profile for either tier. The optional
`-Dkompile.local.backend.profile` property remains an explicit packager override.
`-Dkompile.dist=true` activates the native profile without requiring `-Pnative`.
The root backend profile supplies the SDK and release classifiers, so the CUDA
commands attach `local-linux-x86_64-cuda-12.9` rather than an ambiguous base
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
