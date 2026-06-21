# Handoff — Initialize, build, and test a kompile RAG project (multi-backend)

Goal: generate a kompile RAG app (a **custom-built `kompile-app-main` spin**), build it **dual-backend (CPU + CUDA)**, run it, crawl the FP&A dataset, and verify multi-backend + graph extraction. This runbook reflects the *verified* path as of 2026-06-20 (kompile-fpna-v5).

---

## 0. Environment / prerequisites

- **Maven:** `/home/agibsonccc/dev-apps/mvn/bin/mvn` (no `-am`; always `install`, never `compile`).
- **JDK:** 17 to run the app; GraalVM 21 only for native images (skip with `--no-native`).
- **CLI:** use the freshly built jar
  `kompile-cli/kompile-cli-main/target/kompile-cli-main-0.1.0-SNAPSHOT-shaded.jar`.
  ⚠️ **Do NOT use `~/.local/bin/kompile`** — that's a *stale native binary* and won't have recent fixes.
- **Staging server** (provides extraction LLM + embedding models) on `:8090` — `curl -s localhost:8090/actuator/health` should be `{"status":"UP"}`. Start with `kompile manage start staging` if down.
- **GPUs:** RTX 4090 = CUDA device 0, RTX 3070 Ti = device 1; `/usr/local/cuda/lib64/libcudart.so` present (the multi-backend CUDA probe needs it, else CUDA self-skips and only CPU loads).
- **Data:** `/home/agibsonccc/Documents/GitHub/kompile/FP&A workflow artifacts 2026-05/` — 15 ingestable HTML + 7 `.xlsx` + 1 `.md` (`.m4v`/`.BACKUP_*` excluded).

---

## 1. (Re)build/install the platform — REQUIRED before generating

The generated app depends on the **installed** `kompile-app-main` jar in `~/.m2`, and `kompile build app` runs from the **installed CLI**. After any platform change, install in this order (cli-common BEFORE cli-main — known stale-jar gotcha; `-Dskip.ui` skips the Angular build; app-main is the slow one):

```bash
MVN=/home/agibsonccc/dev-apps/mvn/bin/mvn
cd /home/agibsonccc/Documents/GitHub/kompile
$MVN install -pl kompile-app/kompile-data/kompile-project-store        -DskipTests -Dmaven.test.skip=true
$MVN install -pl kompile-app/kompile-app-parent/kompile-app-main       -DskipTests -Dmaven.test.skip=true -Dskip.ui
$MVN install -pl kompile-cli/kompile-cli-common                        -DskipTests -Dmaven.test.skip=true
$MVN install -pl kompile-cli/kompile-cli-main                          -DskipTests -Dmaven.test.skip=true
```

> ND4J multi-backend lives in `nd4j-api` (already in `~/.m2`, no DL4J rebuild needed). If you DO need it: build the CPU + CUDA backends per `deeplearning4j/CLAUDE.md`.

---

## 2. Generate the project (`kompile build app`)

```bash
CLI=kompile-cli/kompile-cli-main/target/kompile-cli-main-0.1.0-SNAPSHOT-shaded.jar
java -jar "$CLI" build app \
  --configName kompile-fpna-v5 \
  --preset cli-agent-rag \
  --include loader-excel \
  --backend nd4j-cuda-12.9 \
  --no-native \
  --outputDir /home/agibsonccc/Documents/GitHub/kompile
# add --skipMavenBuild to generate POM + application.properties only (inspect before building)
```

Produces `kompile-fpna-v5/project/` with (generator now does these by default):
- **Dual-backend pom** — both `nd4j-cuda-12.9` *and* `nd4j-native` (+ `linux-x86_64` classifiers). `PomModelBuilder` adds nd4j-native unless `--backend nd4j-native`.
- **Dark features ON** — `kompile.graph.extraction.enabled`, `kompile.react.graph-rag-enabled`, `query-transform`, `evaluation`, `guardrails`, `filterchain`, `kvcache`, plus `spring.main.allow-bean-definition-overriding=true` (ApplicationPropertiesGenerator).

`cli-agent-rag` is the right preset (local, no API keys) — it includes loaders/ocr/knowledge-graph/graph-algorithms/crawl-graph/anserini; add `loader-excel` for the spreadsheets.

---

## 3. Build the app fat jar

```bash
cd kompile-fpna-v5/project
/home/agibsonccc/dev-apps/mvn/bin/mvn clean package -DskipTests
# -> target/kompile-fpna-v5-0.1.0-SNAPSHOT.jar  (~1.35 GB; bundles BOTH backend .so jars)
```

---

## 4. Run the app

**Supported path (recommended):** from `kompile-fpna-v5/project`:
```bash
java -jar "$CLI" project service open      # or: project service start
```
This passes `--kompile.data.dir=<projectDir>` (PROJECT-scoped config) and standard args.

**Manual path (reliable for iteration / debugging):**
```bash
cd kompile-fpna-v5/project
java \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  -Dnd4j.multibackend.enabled=true -Dnd4j.backend.priority=CUDA_GPU,CPU \
  -jar target/kompile-fpna-v5-0.1.0-SNAPSHOT.jar \
  --kompile.data.dir="$PWD" \
  > data/logs/app.out.log 2> data/logs/app.err.log &
```
⚠️ **Always pass `--kompile.data.dir`** — without it, config services fall back to the GLOBAL `~/.kompile/config`, where a stale `embedding-restart-config.json {autoRestartEnabled:false}` silently disables embeddings. (`nd4j.multibackend.enabled=true` installs the routing `DeviceAwareOpExecutioner`; dual-backend itself auto-activates from the classpath.)

App is up when the log shows `Started MainApplication in …`. Health/readiness is at **`/api/setup/status`** (200), NOT `/actuator/health` (generated apps ship no actuator → 404; the CLI now falls back to `/api/setup/status`).

---

## 5. Verify multi-backend

- Boot log: `Multi-backend mode: 2 backends available — Primary: JCublasBackend (CUDA), Secondary: CPU -> CpuBackend`.
- `curl -s -X POST localhost:8080/api/multi-backend/tests/run | python3 -m json.tool`
  → `executioner_type … DeviceAwareOpExecutioner, isDeviceAware: true`, 5/5 pass.
- `nvidia-smi` during a crawl shows GPU activity (CUDA backend in use).

---

## 6. Crawl the FP&A data

CLI (works now that the reachability probe is fixed):
```bash
java -jar "$CLI" app crawl start "/home/agibsonccc/Documents/GitHub/kompile/FP&A workflow artifacts 2026-05" \
  --graph --graph-auto-start --schema-preset fpna-cpg-channel-v1 --graph-schema-mode LENIENT \
  --exclude "*.m4v" --exclude "*.BACKUP_*" --port 8080
```
Or direct REST: `POST /api/unified-crawl/start` (see `kompile-fpna-v4/.../latest-crawl-request.json` for the DTO).

- **Entity extraction LLM = `opencode-cli`** (free DeepSeek), set in `~/.kompile/config/graph-extraction-config.json` (`extractionModelProvider`). Surfaced in both crawl UIs.
- **CrawlStepPlan footgun:** list ALL graph steps in `enabledSteps` (`GRAPH_PREP,GRAPH_EXTRACTION,SURFACING,ENTITY_RESOLUTION,EDGE_COMPUTATION,VECTOR_INDEXING`). `archivedSteps`-only flips to explicit mode and SKIPS graph steps.
- Known perf issue: CLI-LLM extraction batching is inefficient (≈1 doc/LLM call, 75–120 s each) — being addressed separately.

---

## 7. Verify results

- `curl -s localhost:8080/api/setup/status | python3 -m json.tool` — staging / model source / embedding / index / search readiness (now honest about auto-restart OFF and "using running staging server").
- `curl -s localhost:8080/api/graph-health/1` — `nodeCount` / `edgeCount` / `nodesByType` (rule-based pass alone built ~7.6k nodes / 46.8k edges from this dataset).

---

## Gotchas (this session)
1. Use the fresh CLI jar, not the stale `~/.local/bin/kompile` native binary.
2. `--kompile.data.dir` is essential (project-scoped config vs. global stale kill-switch).
3. Generated apps have NO actuator → `/actuator/health` 404; readiness is `/api/setup/status`.
4. Multi-backend needs BOTH nd4j jars on one classpath (default now) + a GPU/`libcudart` for CUDA to activate.
5. The full `cli-agent-rag` assembly had latent DI bugs (agentTaskService dup, GraphEmbeddingSidecar multi-ctor, ProcessGraphCallback ambiguity, graph-change-tracking persistence packages) — all fixed in-platform this session.
6. Deferred: explicit per-subprocess CPU-pin routing (graph→CPU) — foundation routes by data-locality + CPU fallback today; per-service `-Dnd4j.backend.priority` from launchers is mapped but not yet wired.
