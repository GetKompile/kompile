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
| RAM | CLI native peaks ~6.5 GB. **app-main native runs with `-J-Xmx80g`** (see the `native` profile in `kompile-app/kompile-app-parent/kompile-app-main/pom.xml`) — plan for a 96 GB+ box or lower the flag and expect longer GC pauses. |
| Node/npm | The app-main build compiles the Angular UI (skip with `-Dskip.ui` for experiments only; distribution builds keep the UI). |
| Disk | ~20 GB free for build outputs; CUDA classpaths add several GB. |

## DL4J prerequisite (read this first)

The reactor pins `nd4j.version=1.0.0-SNAPSHOT` and the root pom declares **no
snapshot repository** — the ND4J/DL4J artifacts do NOT resolve from Maven
Central. **The working assumption is that DL4J is already installed in your
local `~/.m2`**, built from a deeplearning4j source checkout
(`mvn clean install -Dmaven.test.skip=true`, plus the libnd4j/CUDA backend
builds you need). Dev boxes that build kompile regularly already have this —
do not re-clone on such a machine.

Known-good reference at the time of writing:
`deeplearning4j/deeplearning4j` branch `ag_new_release_updates_2`, commit
`5ededbe232` (2026-07-03) — the tree these kompile sources compile against.
Treat that pin as the compatibility contract until a stable DL4J release is
cut; a released version is the long-term fix for this whole prerequisite.

Only for a machine that does NOT have DL4J installed yet:

- `kompile build clone-build --buildDl4j --dl4jBranchName <branch>` clones and
  builds it (branch-level pinning only — there is no exact-commit option yet);
  CUDA backends via `kompile build build-nd4j-backend --backend=cuda
  --cuda-version=12.9`.
- Or install the CI-published artifacts (`publish-release.yml` uploads
  `nd4j-cuda-*` etc. via `.github/workflows/build-native-linux-cuda.yml` and
  friends) with `mvn install:install-file`.

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
./build-dist.sh cuda            # variants: cli-only | hosted | cpu-intel | cpu-arm | cuda | amd-zluda
./build-dist.sh cpu-intel --jars-only    # exec JARs + jlink runtime, no native-image
./build-dist.sh cuda --skip-java-build   # reuse ~/.m2, only native steps
```

Output: `dist/kompile-dist-<version>-<variant>-<platform>.tar.gz` containing
`bin/` (native binaries + GraalVM shim libs), `lib/` (exec JARs + side-loaded
JavaCPP `.so`s — the images exclude native libs via `-H:ExcludeResources` and
load them from `lib/` at runtime), `conf/`, `runtime/` (jlink), seed `data/`.

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
```

The app `native` profile activates on `-Dkompile.dist` (not `-Pnative`) so the
flag reaches child modules. Use `-DskipTests`, not `-Dmaven.test.skip=true` —
the latter breaks the native-maven-plugin `test-native` goal.

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

## Known gaps (current state)

- `graph/` and `serving/` under
  `kompile-app-main/src/main/resources/META-INF/native-image/` are
  agent-capture **placeholders** — the graph-matrix and serving subprocess
  types are not yet proven under a native `kompile-server` (the jar tier +
  `runtime/` covers them; native self-exec re-dispatch works for main, ingest,
  vector, embedding, model-init).
- The `training` subprocess dispatch is reflective (model-staging classes) and
  absent from any native image.
- Release CI publishes `cli-only` archives plus a `full` (jar+runtime) Linux
  dist; CUDA dist archives are built locally via `./build-dist.sh cuda` (the
  nd4j-cuda artifacts themselves are CI-built by `publish-release.yml`).
