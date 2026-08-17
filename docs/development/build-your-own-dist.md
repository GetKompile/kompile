# Build Your Own Distribution (AOT native spins)

How to go from a clone of this repo to an installable distribution with AOT
(GraalVM native-image) binaries — `bin/kompile` (CLI), `bin/kompile-server`
(app-main), `bin/kompile-model-staging` — plus a jlink `runtime/` for the
jar-tier fallback. Covers CPU and CUDA spins.

## Prerequisites

| Requirement | Notes |
|---|---|
| GraalVM JDK **21** | 17 fails with a huge-object image-layout limit on these images. `GRAALVM_HOME` is read by `build-dist.sh` (defaults to sdkman's `21.0.10-graal`). |
| Maven 3.9+ | `MVN` env overrides; otherwise `mvn` from PATH is used. |
| `patchelf` (Linux native archives) | Required. Both packaging routes fail rather than publish an ELF with a host-specific interpreter or RUNPATH. |
| RAM | CLI native peaks ~6.5 GB. **app-main native runs with `-J-Xmx80g`** (see the `native` profile in `kompile-app/kompile-app-parent/kompile-app-main/pom.xml`) — plan for a 96 GB+ box or lower the flag and expect longer GC pauses. |
| Node/npm | The app-main build compiles the Angular UI (skip with `-Dskip.ui` for experiments only; distribution builds keep the UI). |
| Disk | ~20 GB free for build outputs; CUDA classpaths add several GB. |

## DL4J prerequisite (source or Maven)

Kompile can consume the exact DL4J release lane from either a source checkout
or the Maven repository produced by `../deeplearning4j/release`. Repository
mode never falls back to compiling DL4J:

```bash
build-scripts/build-kompile-platform.sh linux-x86_64-cuda-12.9-cudnn \
  --dl4j-repository https://repo.example/snapshots \
  --repository-id dl4j-release \
  --nd4j-version 1.0.0-SNAPSHOT \
  --dl4j-sdk-assets /srv/dl4j-sdk/linux-x86_64-cuda-12.9-cudnn
```

CPU host, CUDA, and Android release lanes publish runtime ZIP/AAR payloads
beside Maven, so a complete Kompile ZIP also needs the matching extracted
`sdk-assets` shard. Maven-only lanes (compat, Vulkan, Hexagon, TPU, and
ZLUDA) collect their exact classified JAR set directly from the configured
repository and do not invent a runtime package.

For source mode, leave off `--dl4j-repository` and point
`--dl4j-root` at the checkout. The platform builder delegates to DL4J's own
release build, collects the same runtime/JAR shard, and applies the same
validator before assembling Kompile.

The ByteDeco CUDA redist jars (`org.bytedeco:cuda:12.9-9.10-1.5.12` etc.) are
release coordinates and resolve from Central normally.

## Backend selection

Either set the artifact directly:

```bash
-Dnd4j.backend=nd4j-native          # CPU (default)
-Dnd4j.backend=nd4j-cuda-12.9       # CUDA 12.9
```

or use the root-pom alias profiles (`pom.xml`, `backend-*`):

```bash
-Dkompile.backend=cpu | cpu-compile | cpu-onednn-avx512 | cuda-12.6 | cuda-12.9 | zluda
```

`-Dkompile.cuda=true` is a script-level marker only — it activates **nothing**
in the poms; the backend property is what selects the artifact. `build-dist.sh`
propagates `-Dnd4j.backend` to every Maven invocation (reactor install,
exec-jar, and all native-image steps).

## One-shot distribution

```bash
./build-dist.sh cuda            # variants: cli-only | full | hosted | cpu-intel | cpu-arm | cuda | amd-zluda
./build-dist.sh full            # CLI native + all service exec JARs + jlink runtime
./build-dist.sh cpu-intel --jars-only    # shaded/exec JARs + JBang wrapper + jlink runtime
./build-dist.sh cuda --skip-java-build   # reuse ~/.m2, only native steps
```

The `--jars-only` lane is the JVM version of the distribution boundary: it skips
native-image compilation, writes the CLI uber JAR to `lib/kompile-cli.jar`, packages
the delegated CLI JARs and executable service JARs beside it, and installs
`bin/kompile` as a JBang-first wrapper. The document-model/VLM worker is currently
native-only and is omitted from this lane.

Output includes both
`dist/kompile-dist-<version>-<variant>-<release-lane>.zip` and `.tar.gz`.
The release-lane identity retains CPU helpers and CUDA version/helpers (for
example `cpu-intel-linux-x86_64-avx2` and
`cuda-linux-x86_64-cuda-12.9-cudnn`). Each archive contains
`bin/` (native binaries + GraalVM shim libs), `lib/` (exec JARs + side-loaded
JavaCPP `.so`s — the images exclude native libs via `-H:ExcludeResources` and
load them from `lib/` at runtime), `conf/`, `runtime/` (jlink), seed
`data/`, and the validated `sdx-sdk/` companion for backend distributions.
JAR-only archives do not contain native binaries or side-loaded native libraries;
they still include `runtime/` when the selected variant bundles a jlink runtime.
The ZIP is installed locally as
`ai.kompile:kompile-dist:<version>:zip:<variant>-<release-lane>`.

Publish the installed reactor and every classified ZIP to the DL4J repository
(or an explicit sibling repository) in the same invocation:

```bash
build-scripts/build-kompile-platform.sh linux-x86_64 \
  --dl4j-repository https://repo.example/snapshots \
  --dl4j-sdk-assets /srv/dl4j-sdk/linux-x86_64 \
  --publish

# Override only when Kompile has a separate deployment endpoint/server id:
#   --deploy-repository https://repo.example/kompile-snapshots
#   --deploy-repository-id kompile-release
```

`build-dist.sh` and the `kompile-dist` Maven assembly both call the same native
stager. It emits a flat `lib/` for exactly one OS/architecture, applies a requested
CPU flavor after the baseline set, and fails on conflicting basenames or an
incomplete optimized ND4J pair. Distribution assembly never falls back to the
mutable JavaCPP user cache. Both routes also call
`kompile-dist/src/main/build/normalize-elf-portability.sh` on staged executable
copies. Module `target/` outputs remain untouched; Linux ELF publication requires
the system interpreter and `$ORIGIN/../lib` RUNPATH.

**CUDA runtime expectation:** the `cuda` variant bundles the ND4J CUDA backend
(`libnd4jcuda.so` via the platform-classified backend jar) and the JavaCPP JNI
wrappers, but NOT the CUDA toolkit itself — the target box needs an NVIDIA
driver plus CUDA 12.x toolkit libraries (`libcudart`, `libcublas`, `libcudnn`,
…) on the loader path, the standard DL4J deployment expectation. Bundling the
ByteDeco `cuda-platform-redist` jars instead would make the archive
self-contained at the cost of ~3 GB.

## Manual per-component spins

```bash
# CLI
mvn -f kompile-cli/kompile-cli-main/pom.xml package -Pnative -DskipTests

# app-main → target/kompile-app (+ exec jar co-built)
mvn -f kompile-app/kompile-app-parent/kompile-app-main/pom.xml package \
    -Dkompile.dist=true -Dkompile.uber -DskipTests -Dnd4j.backend=nd4j-cuda-12.9

# model-staging
mvn -f kompile-app/kompile-models/kompile-model-staging/pom.xml package \
    -Dkompile.dist=true -DskipTests

# chat persona
mvn -f kompile-app/kompile-app-parent/kompile-app-chat/pom.xml package \
    -Dkompile.dist=true -DskipTests -Dnd4j.backend=nd4j-native

# crawl-manager persona
mvn -f kompile-app/kompile-app-parent/kompile-app-crawl-manager/pom.xml package \
    -Dkompile.dist=true -DskipTests -Dnd4j.backend=nd4j-native
```

The app `native` profile activates on `-Dkompile.dist` (not `-Pnative`) so the
flag reaches child modules. Use `-DskipTests`, not `-Dmaven.test.skip=true` —
the latter breaks the native-maven-plugin `test-native` goal. Native-image builds
are memory-heavy; build the server and persona images serially.

## Install and run

```bash
bash install.sh --variant cuda --dir /opt/kompile     # or KOMPILE_INSTALL_DIR=/opt/kompile
export PATH="/opt/kompile/bin:$PATH"
export KOMPILE_INSTALL_DIR=/opt/kompile   # custom dirs only; ~/.kompile needs nothing
kompile doctor
kompile project init --root myproject
kompile project start --root myproject
```

Binary/jar resolution (`ComponentRegistry`): `-Dkompile.install.dir` >
`$KOMPILE_INSTALL_DIR` > `~/.kompile`, searching `bin/` natives, then `lib/`
dist jars, then `components/<id>/`. The bundled Java is found by
`JavaRuntimeLocator` (`KOMPILE_JAVA` > `<install>/runtime/bin/java` >
`JAVA_HOME` > PATH). User/project state stays under `~/.kompile` regardless of
the install dir.

## Native release acceptance

Validate the unpacked archive, not just the Maven targets. Run the service
launchers serially on unused ports, wait for each application to report `Started`
and listen, then terminate only the PID that command created:

```bash
cd dist/kompile-dist-<version>-cpu-intel-linux-x86_64-avx2
env -u LD_LIBRARY_PATH -u DYLD_LIBRARY_PATH bin/kompile --version
bin/kompile-chat.sh --port 9181 --kompile.data.dir=/tmp/kompile-dist-chat
bin/kompile-crawl-manager.sh --port 9182 --kompile.data.dir=/tmp/kompile-dist-crawl
bin/kompile-model-staging.sh --port 9190 --kompile.data.dir=/tmp/kompile-dist-staging
bin/kompile-server.sh --server.port=9184 --kompile.data.dir=/tmp/kompile-dist-server
```

For Linux native variants, every ELF in `bin/` must use the system interpreter
and a `$ORIGIN/../lib` RUNPATH. Service logs must show GraalVM native execution
and the dist `lib/`; they must not contain `MissingReflectionRegistration`,
`NoClassDefFoundError`, or `UnsatisfiedLinkError`. The unified server proof also
requires graph, embedding, model-init, and serving child modes to self-exec the
same native binary with no runtime classpath. `--version` is only a CLI boot
check; inspect its RUNPATH to prove direct commands can resolve side-loaded
libraries without a launcher-provided environment.

## Current boundaries

- The `training` subprocess dispatch is reflective (model-staging classes) and
  absent from any native image.
- `release.yml` is the only workflow that creates or edits GitHub Releases. It
  publishes cross-platform `cli-only`, Linux `full`, checksums, and stable jar
  assets. `publish-release.yml` can add uniquely named SDK/native artifacts only
  after that canonical release exists; it never clobbers existing assets.
- CUDA installable distributions are produced by `./build-dist.sh cuda` on a
  capable runner and must be attached through the canonical `release.yml` job
  when that matrix is enabled. The supplemental workflow publishes the underlying
  ND4J/SDX SDK artifacts, not an independently defined Kompile distribution.
