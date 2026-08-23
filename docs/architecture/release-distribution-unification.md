# Release & Distribution Unification

**Status:** Implemented 2026-07-02 (this document is the canon; deviations are bugs)
**Scope:** every "deployment" concept in the repo — the zip distribution, the unified native
image, the JBang/jar fallback, kompile-app-main as a meta-library application, and custom
DL4J backend jars built from `../deeplearning4j`.

---

## 1. The canonical model

There is **one distribution layout** with two published execution forms. The cross-platform
`cli-only` archives are AOT-compiled GraalVM binaries; the canonical Linux `full` archive is
the JVM form produced by `build-dist.sh --jars-only`, with shaded/exec JARs and a bundled
runtime. Backend-specific native `full` archives use the same layout and remain supplemental
large-runner builds. Everything else (Dockerfiles, install script, CI, JBang) is a delivery
channel for those canonical artifacts.

```
kompile-dist-<version>-<variant>-<os>-<arch>.tar.gz
├── bin/
│   ├── kompile                  # CLI native image (kompile-cli-main)  [symlink back-compat: kompile-cli]
│   ├── kompile-server           # UNIFIED app native image (kompile-app-main, mainClass MainApplication)
│   │                            #   boots the web app AND every subprocess via --subprocess=<type>
│   ├── kompile-agent, kompile-app-cli, kompile-model, kompile-component   # sub-CLIs
│   ├── kompile-model-staging    # optional native staging (falls back to lib/ jar)
│   └── lib*.so                  # GraalVM-emitted JDK shims (awt etc.) — must ship next to binaries
├── lib/
│   ├── kompile-cli.jar          # shade jar of the CLI          (jar fallback tier)
│   ├── kompile-server.jar       # Spring Boot exec jar of app-main (jar fallback tier)
│   ├── kompile-model-staging.jar
│   └── kompile-sdk-serving.jar
├── jbang-catalog.json           # aliases over lib/*.jar (relative refs) — see §4
├── JBANG.md
├── conf/, config/, data/, anserini/   # scaffolding
└── .dist-info.json / .version / .variant
```

**Naming canon** (three names existed for each binary before; these win):
`bin/kompile` (never `kompile-cli-main`), `bin/kompile-server` (never `kompile-app` /
`kompile-app-main`). Release assets additionally publish stable-named jars
`kompile-cli.jar`, `kompile-server.jar`, `kompile-model-staging.jar` so the JBang catalog
can reference `releases/latest/download/<name>`.

**Launch tiers** (highest preference first):
1. **Native binaries** in `bin/` — no JVM required, subprocesses re-exec the same image.
2. **JBang/jar** — `jbang --catalog ./jbang-catalog.json kompile-server`, or plain
   `java -jar lib/kompile-server.jar`. Same `--subprocess=` semantics as the binary.
3. **Dev tree** — `mvn spring-boot:run`, generated fpna-vN projects, `.boot-inf-extracted`.

## 2. The unified native image (`kompile-server`)

`ai.kompile.app.MainApplication.main()` is the single entrypoint. With no flag it boots the
web app; with `--subprocess=<type>` it dispatches to the subprocess main **in the same
image**. Complete type table (all statically linked except `training`):

| type | main class | notes |
|---|---|---|
| `ingest` | `ai.kompile.app.subprocess.IngestSubprocessMain` | |
| `vector-population` | `…VectorPopulationSubprocessMain` | |
| `embedding` | `ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain` | persistent |
| `model-init` | `…model.ModelInitSubprocessMain` | |
| `serving` | `…ServingSubprocessMain` | own Spring context — see gaps §7 |
| `graph-matrix` | `…GraphMatrixSubprocessMain` (:8094) | added 2026-07-02 |
| `learning` | `ai.kompile.app.learning.subprocess.LearningSubprocessMain` | added 2026-07-02 |
| `pipeline-serving` | `ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain` | MCP-owned reusable stdio runtime for every model-backed pipeline |
| `training` | `ai.kompile.staging.subprocess.TrainingSubprocessMain` | reflective **by design** — kompile-model-staging must NEVER be an app-main dependency; works only on staging-inclusive JVM classpaths or via the separate staging binary |

**Launcher → child process rules.** Every launcher decides JVM-vs-native per
`kompile.subprocess.executable.mode=auto` + `NativeImageInfo` (kompile-utils):

- `ManagedSubprocessLauncher` (base of graph-matrix, learning, future launchers): in native
  image mode (`isRunningInNativeImage() && !hasClasspath()`) it now re-execs
  `NativeImageInfo.getExecutablePath()` with `-Xmx`, the propagated `-D` ND4J/JavaCPP props,
  and `--subprocess=<getSubprocessDispatchType()>` (defaults to the subprocess id). In JVM
  mode it builds the classic `java -cp` command with `.boot-inf-extracted` expansion.
  **Native images reject HotSpot `-XX:` flags** — only `-Xm*`/`-D*` are forwarded.
- `SubprocessIngestLauncher`, `ModelInitSubprocessLauncher`, `EmbeddingSubprocessLauncher`,
  `ServingSubprocessLauncher` already had native re-exec via
  `SubprocessConfigService`/`SubprocessExecutableConfig` (operator-overridable per-type
  executable paths, `kompile.subprocess.executable.*`).

**Native reachability metadata** lives per-context under
`kompile-app-main/src/main/resources/META-INF/native-image/{main,ingest,vector,embedding,model-init,graph,serving,subprocess-args-shared,subprocess-args-main}`;
the unified `native` profile's `-H:ConfigurationFileDirectories` lists **all nine**.
Graph, serving, and subprocess-argument metadata come from focused production traces.
The parent-side model-init protocol slice is likewise agent-generated from a production
JSON round trip, with contract tests deriving every sealed variant, nested payload,
record constructor, and accessor. `TrainingSubprocessMain` remains registered in
`main/reflect-config.json` (ignored with a warning when the class is absent — safe).

**Build:** `mvn package -Dkompile.dist` (JDK **21-graal**; JDK 17 hits an unfixable
huge-object layout limit on this image). The same profile now also emits the exec jar
(repackage-exec execution), so a dist build always yields both tiers.

## 3. kompile-app-main as a meta-library application

- Default packaging is a **thin library jar** — generated RAG projects (fpna-vN,
  kompile-rag-builds) depend on it and do their own `spring-boot:repackage`. This must not
  change; provider composition happens in the *consumer* pom.
- Providers (loaders, vector stores, OCR/VLM, tools, embedding backends) are plain
  dependencies of app-main; the generated-project generator (`PomModelBuilder` +
  `ModuleCatalog`) mirrors the chosen subset and injects the ND4J backend as
  `<artifactId>${backend}</artifactId>` (+ nd4j-native alongside for multi-backend CPU
  fallback).
- **`kompile-model-staging` is never a dependency or scan target of app-main** (standing
  mandate) — it is reached over HTTP :8090 and shipped as its own jar/binary.
- New: profile `uber` (`-Dkompile.uber`) attaches
  `target/kompile-app-main-<version>-exec.jar` (classifier `exec`, ~322 MB) — the JBang/jar
  fallback artifact — without touching the thin default. `-Dkompile.dist` produces it too.

## 4. JBang deployment (fallback tier — NEW)

Two catalogs, same 10 aliases (`kompile`, `kompile-server`, `kompile-staging`, plus
`kompile-{ingest,vector,embedding,model-init,graph,learning,serving}` = server jar with a
preset `--subprocess=` argument):

- **Repo root `jbang-catalog.json`** — script-refs point at
  `https://github.com/GetKompile/kompile/releases/latest/download/<stable-jar>`; usable as
  `jbang kompile-server@getkompile/kompile` once CI publishes the stable-named jars.
- **In-zip `jbang-catalog.json`** (source: `kompile-dist/src/main/resources/`) — relative
  `lib/*.jar` refs for offline use from an unpacked dist; docs in `JBANG.md`.

All server aliases carry `"java": "17+"`, the canonical `--add-opens`/`--add-exports` block
(ND4J requirement, from `run-cpu.sh`), and `-XX:+ExitOnOutOfMemoryError`. Conventions:
`KOMPILE_MAX_HEAP`, `--kompile.data.dir=…`, `--server.port=…`, CPU pin via
`CUDA_VISIBLE_DEVICES=-1`.

## 5. Custom DL4J jars per backend (compiler backends, cpu/gpu)

kompile consumes DL4J through two root-pom properties: `nd4j.version` (default
`1.0.0-SNAPSHOT`) and `nd4j.backend` (default `nd4j-native`), plus
`javacpp.platform[.extension]`.

**Variant selection** — root-pom profiles keyed on `-Dkompile.backend=<value>`:
`cpu`, `cpu-compile` (MLIR/Triton JIT classifier `-compile`), `cpu-onednn-avx512`,
`cuda-12.6`, `cuda-12.9`, `zluda` (plus the pre-existing `avx2`/`avx512` extension
profiles). All live reactor consumers, including Anserini's test backend, select the
artifact through `${nd4j.backend}`. The remaining literal `nd4j-native` dependencies
are deliberate CPU fallback twins inside CUDA dual-backend profiles.

**Custom builds** — `build-scripts/build-dl4j-backend.sh` wraps `../deeplearning4j`:

```bash
# plain CPU
build-scripts/build-dl4j-backend.sh
# CUDA 12.9 + cuDNN
build-scripts/build-dl4j-backend.sh --chip cuda --cuda-version 12.9 --helper cudnn
# pinned custom CPU build with oneDNN+AVX512
build-scripts/build-dl4j-backend.sh --helper onednn --extension avx512 --pin 1.0.0-kompile-SNAPSHOT
# then consume:
mvn package -Dnd4j.version=1.0.0-kompile-SNAPSHOT -Dkompile.backend=cpu-onednn-avx512
```

It composes the real DL4J invocations (`-Pcpu|-Pcuda`, `-pl :libnd4j,:nd4j-*-preset,:nd4j-*`,
`-Dlibnd4j.helper/extension/triton`), supports `--libnd4j-home`/`--skip-cpp` to reuse a
prebuilt C++ tree, and `--pin` via DL4J's `update-versions.sh` (prints the restore command,
never auto-restores). DL4J's own matrix also offers `nd4j-minimizer`, `nd4j-tpu`,
`nd4j-hexagon`, dtype-reduced builds (`-Dlibnd4j.datatypes=…`) and
`bootstrap-libnd4j-from-url.sh` for CI-hosted prebuilt zips.

**Backend × image rule:** a *native image* bakes its backend at build time (the zip variant
`cpu-intel`/`cuda`/… is chosen by which backend jar was on the image classpath); the *jar
tier* keeps runtime multi-backend selection (`BackendManager`, both jars on classpath).

## 6. Disposition of every legacy deployment concept

| Concept | Disposition |
|---|---|
| `build-dist.sh` | **Kept — variant orchestrator.** Fixed broken CLI path (`kompile-cli/kompile-cli-main/target/…`), emits canon names + back-compat symlinks, bundles jar tier + jbang files into every variant. Layout must match `dist.xml`. |
| `kompile-dist/` assembly | **Kept — canonical layout definition** (`src/main/assembly/dist.xml`). `prepare-package` stages copies in `target/portable-bin` and applies the shared ELF normalizer before assembly. |
| `install.sh` | Kept. Default `auto` selection tries the published Linux `full` variant first and falls back to `cli-only`; explicit native CPU/CUDA variants remain opt-in. |
| `release.yml` | **Sole canonical release owner.** Owns the `v*` tag, release creation, AOT `cli-only` archives, the JVM `full` archive, checksums, and stable jar assets. Manual dispatch may publish the selected branch SHA as a prerelease. JVM and AOT jobs use separate runner variables. |
| `publish-release.yml` | Manual supplemental SDK builder/uploader only. It shares per-version concurrency, requires an existing canonical release, and never creates, edits, or clobbers release assets. |
| Root `Dockerfile`, `Dockerfile.rockylinux8` | **Deprecated in-place** (headers added). Use `build-scripts/Dockerfile.cpu` / `.cuda`. |
| Root `native-image/` | **Legacy/unwired** (README added). Canonical metadata is per-module `META-INF/native-image/`. |
| Per-subprocess native profiles (`native-ingest`, …) | Kept as opt-in *separate* binaries; the unified image is the default story. |
| fpna-vN trees, `relaunch-jvm.sh` | Dev dogfood instances, not reactor members. `relaunch-jvm.sh` hardcodes a personal JDK path; `dist/` has ~40 stray build logs — hygiene items, not build inputs. |

## 7. Verified native-dist status

The unified JDK-21 GraalVM image and all five application launchers have now been built and
booted from the `cpu-intel` distribution with ambient library-path variables removed. The
release artifact uses the canonical side-loaded `lib/` layout, system ELF interpreters, and
`$ORIGIN/../lib` RUNPATHs. Graph, embedding, model-init, and serving children self-execute the
same native server binary without a runtime classpath; graph and serving bind only to loopback.
Focused native-image-agent captures cover those subprocess entry points, including the complete
`ModelInitMessage` protocol, and structural tests guard the captured metadata. The serving child
uses its narrow `AnnotationConfigApplicationContext` rather than starting the full application.

The former closure items are complete: live child poms honor `${nd4j.backend}`; release CI
publishes the canonical JVM `full` archive, AOT `cli-only` archives, and stable jar names; and both Maven
assembly and `build-dist.sh` normalize staged ELF copies through
`kompile-dist/src/main/build/normalize-elf-portability.sh`. Linux native publication fails
closed when `patchelf` is unavailable.

## 9. 2026-07-02 addendum: bundled jlink runtime + full-variant CI

### What changed

**`build-dist.sh`** — new `build_runtime()` step (runs for all variants except `cli-only`)
produces `<dist-root>/runtime/` via `jlink`. JDK resolution order: `$GRAALVM_HOME` →
`$JAVA_HOME` → `java` on PATH (walks to JDK root). Requires a `jmods/` directory; absent =
warning + continues. jlink invocation tries `--compress=zip-6 --include-locales=en` then
falls back to no `--include-locales`, then `--compress=2`. Module list:
`java.se, jdk.unsupported, jdk.crypto.ec, jdk.crypto.cryptoki, jdk.zipfs, jdk.management,
jdk.management.agent, jdk.security.auth, jdk.naming.dns, jdk.charsets, jdk.localedata,
jdk.httpserver, jdk.jfr`. Runtime is included in the archive and reflected in `.dist-info.json`.

**Launcher scripts** (`kompile-server.sh`, `kompile-model-staging.sh`, `kompile-sdk-serving.sh`)
now resolve Java with this precedence:
1. `$KOMPILE_JAVA` (if executable)
2. `<dist-root>/runtime/bin/java` (bundled JDK)
3. `$JAVA_HOME/bin/java`
4. `java` on PATH
5. Error message directing user to install Java 21+ or rebuild with bundled runtime.

`kompile-server.sh` also gains a JAR-tier fallback (`lib/kompile-server.jar`) when
`bin/kompile-server` native binary is absent.

**`release.yml`** — `full-dist-linux` runs only on the standard JVM runner, sets the reactor
release version, and calls `./build-dist.sh full --jars-only`. The variant orchestrator owns
the Java build, shaded/exec JAR closure, JBang wrapper, jlink runtime, layout, manifest, and
checksums. The independent AOT matrix runs only the `cli-only` native images and disables
GraalVM quick-build optimization. Linux x64 is validated on its standard public runner
(15.61 GiB physical RAM, no swap, 9.82 GB optimized CLI peak RSS), and macOS ARM64 is
validated on its standard 7 GiB M1 runner (3.32 GB optimized CLI peak RSS). Windows x64
retains a 32 GiB floor until measured independently. A separate optimized `kompile-app-main`
probe exhausted the same Linux runner during native-image and was canceled without a Graal/Maven
footer, so full application AOT remains a serial 64 GiB-runner workload. The canonical release
job uploads both forms plus stable jar names directly from the packaged JVM `lib/` directory. A manual
dispatch with `publish=true` creates `v<version>` at the selected branch commit; tag pushes
remain the normal final-release path. `KOMPILE_AOT_LINUX_X64_RUNNER` is optional because the
standard Linux runner is sufficient; `KOMPILE_AOT_MACOS_ARM64_RUNNER` is likewise optional.
Configure `KOMPILE_AOT_WINDOWS_X64_RUNNER` for a larger hosted or self-hosted machine;
the JVM and publisher jobs use standard runners through `KOMPILE_JAVA_RUNNER` and
`KOMPILE_RELEASE_RUNNER`. Publication is attached to the
protected `release` environment.

**`install.sh`** — default variant is now `auto` (tries `full` first via HEAD request, falls
back to `cli-only` with a printed notice). After extraction, marks the bundled runtime
executable and prints its version. Adds `--modify-path` flag (`KOMPILE_MODIFY_PATH=1`) that
appends the `export PATH` line to the detected shell profile with a grep-guard against
duplicates. Get-started text notes that `kompile project init` works fully only with `full`.

### Assets now published per release

| Asset | Source |
|---|---|
| `kompile-dist-<V>-cli-only-linux-x86_64.tar.gz` | AOT `build` matrix job |
| `kompile-dist-<V>-cli-only-macosx-arm64.tar.gz` | AOT `build` matrix job |
| `kompile-dist-<V>-cli-only-windows-x86_64.zip` | AOT `build` matrix job |
| `kompile-dist-<V>-full-linux-x86_64.tar.gz` | JVM `full-dist-linux` job (`--jars-only`) |
| `kompile-server.jar` | stable name, from full-dist `lib/` |
| `kompile-model-staging.jar` | stable name, from full-dist `lib/` |
| `kompile-cli.jar` | stable name, from full-dist `lib/` |
| `kompile-chat.jar` | stable name, from full-dist `lib/` |
| `kompile-crawl-manager.jar` | stable name, from full-dist `lib/` |

## 8. Further design directions (suggested, not implemented)

- **Busybox-style multi-call binary:** ship `bin/kompile-ingest → kompile-server` symlinks
  and let `MainApplication` infer the subprocess type from `argv[0]` when no
  `--subprocess=` is given — nicer ops ergonomics, trivial to add to the dispatcher.
- **GraalVM native-image bundles** (`--bundle-create`) per variant for reproducible,
  shareable image builds (pairs well with the CUDA/CPU variant matrix).
- **Publish to a Maven repo** (even a GitHub-hosted one) to unlock GAV-based JBang
  (`//DEPS ai.kompile:…`) and generated-project builds without a local `mvn install` of the
  whole reactor.
- **`.dist-info.json` as the manifest of record:** already emitted by `build-dist.sh`;
  extend it with the DL4J backend/classifier, git SHA, and GraalVM version, and teach
  `kompile --version` / the server `/actuator/info` to read it.
- **Dist smoke-test harness:** one script that unpacks a dist, runs `bin/kompile --help`,
  boots `kompile-server` + one `--subprocess=` type (both tiers), and curls
  `/actuator/health` — wire into CI before upload.
- **SDKMAN/Homebrew channels** on top of the stable release-asset names once CI publishes
  them.
