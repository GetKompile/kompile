# Model Staging: Integration Audit, Serving Health, Emitted-Model Wiring & Registry Roadmap

**Date**: 2026-07-02
**Scope**: kompile-model-staging ↔ kompile-app-main integration gaps; serving runtime health; census of models emitted by the graph-reasoning stack and unified crawl (and their path into the registry); feature-gap analysis vs mainstream serving platforms; versioning + deployment roadmap.
**Method**: 5 parallel subagent audits; every load-bearing claim in the P0/P1 lists below was re-verified by direct source reads (file:line cited). Two subagent claims were found wrong and are corrected in §9.

---

## 1. Executive summary

- **The standalone-app architecture is healthy.** kompile-model-staging (:8090) is NOT a dependency or scan target of app-main — the constraint holds (comments in app-main's pom restate it; the training subprocess dispatch uses reflection by design).
- **The quota-free LOCAL_MODEL serving lane is dead on arrival (P0, verified).** The serving subprocess (:8091) registers only `/api/llm/load|status|unload`; the `/api/llm/generate` endpoint that `ServingSubprocessBackend`/`ServingSubprocessLauncher` call does not exist there → guaranteed HTTP 404. The subprocess javadoc promises the endpoint; the code never delivers it.
- **Reasoning-model wiring is 2/5 done.** PSL weights and MEBN strengths already flow to staging via `ModelTrainedEvent` → `ModelDeploymentHook`. KGE fires the hook but ships a **metadata stub JSON instead of the actual model**. The answer-synthesis logistic scorer and mined process-mining PSL rules are completely unwired — and the scorer is **trained-then-discarded by default** (empty default save path).
- **Versioning exists only for graph-scoped models.** `GraphScopedDeployService` (KGE/PSL/MEBN) has timestamped versions + activate/rollback. Global models (encoders, LLMs) are last-write-wins by modelId; MEBN's weight file is a single overwrite.
- **Registry fundamentals are solid** (atomic flat-file registry, checksums, staging pipeline states, rich type enum, export/import, .karch archives, training/eval/PEFT/alignment UI). Gaps vs MLflow/KServe-class platforms are in immutable versioning, aliases, lineage, signatures, post-promotion auto-deploy, audit trail, and live serve metrics.

---

## 2. What model-staging is today (grounded)

- Standalone Spring Boot app, port **8090**, main `ai.kompile.staging.ModelStagingApplication` (scans `ai.kompile.staging` + `ai.kompile.modelmanager`). Dual-mode: REST server or picocli CLI (`download`/`promote`/`pipeline`).
- **Registry**: flat `~/.kompile/models/registry.json` via `kompile-model-manager` `RegistryService` (RW-lock, atomic tmp+move, `.bak`). `ModelEntry`: modelId, type, path, modelFile, vocabFile, sha-256 checksum, status (STAGED/ACTIVE/FAILED/DOWNLOADING), promotedAt, metadata bag, tokenizer/preprocessor configs, projectId/graphId.
- **Types**: DENSE/SPARSE/CROSS encoders, LLM_GGML, VLM_PIPELINE, OCR×5, **KGE, PSL, MEBN**, DOCUMENT_CLASSIFIER.
- **Staging pipeline**: PENDING→DOWNLOADING→CONVERTING→VALIDATING→(OPTIMIZING)→READY→promote→ACTIVE, with SSE progress. Converters: ONNX, TF SavedModel, Keras h5, GGUF/GGML → SameDiff (`.sdz`/`.sdnb`, sharded).
- **Inference inside staging**: full LLM generate/stream/batch/chat (`LlmExecutionController`), VLM/OCR, OpenAI-compat `/v1/chat/completions` + `/v1/models`. `kompile.staging.app.enabled: true` **is the shipped default** (`application.yml:21-22`) so these are live by default.
- **Training side**: training/PEFT/alignment/distillation/eval REST + Angular UI, H2 (`~/.kompile/data/staging-db`) `training_job_history` + `eval_result_history`. ⚠️ Training and evaluation **simulate** when the subprocess flag is off or model files are missing (`TrainingService`, `EvaluationService.simulateBenchmarkResult()`) — plausible-looking but fake curves/metrics.
- **Graph-scoped deploys**: `GraphScopedDeployService` — versioned id `<base>__<pid>__<gid>__<epochMillis>`, artifacts under `~/.kompile/models/graph-scoped/<type>/<versionedId>/`, STAGED→ACTIVE with demotion of prior, `POST /api/staging/graph/models/{id}/rollback`.
- **Launch**: app-main auto-starts it (`StagingAutoStartService` → `StagingServerLifecycleService`; binary/jar search: `./staging/`, `~/.kompile/components/kompile-model-staging/<ver>/`); `kompile.staging.auto-start=true`, `kompile.staging.url`, heap 4g. `build-dist.sh:338-344` bundles it in every dist variant.

## 3. Verified bugs (each re-checked by direct read)

| # | Bug | Evidence | Fix |
|---|-----|----------|-----|
| B1 | **LOCAL_MODEL serving lane 404s.** Subprocess whitelist config imports only `LlmModelController` (mappings: `/api/llm/load` :113, `/status` :251, `/unload` :296 — **no `/generate`**), while `ServingSubprocessLauncher.generate()` posts `/api/llm/generate` (`:516`) and `ServingSubprocessBackend` (the `LocalServingBackend` impl) depends on it. `isAvailable()` = process-running, so the dispatcher believes the lane is healthy. | `SubprocessServingConfiguration.java:63-67`; `kompile-pipelines-app-llm/.../LlmModelController.java`; `ServingSubprocessMain.java:64` (javadoc promises the endpoint) | Add a generate (+ status-aware availability) controller **in `kompile-pipelines-app-llm`** next to `LlmModelController`, backed by the already-imported `SameDiffLanguageModelImpl`, returning the `{finishReason, generatedText}` contract the launcher parses. (Importing staging's `LlmExecutionController` is impossible — staging is not on app-main's classpath, by mandate.) |
| B2 | **Serving subprocess off-heap halved.** `-Xmx` uses `DEFAULT_HEAP_SIZE="16g"` (`:100`, `:721`) but the off-heap calc hardcodes `heapBytes = 8GB` (`:732`) → JavaCPP `maxbytes/maxphysicalbytes` = 16 GB instead of the intended 2×heap = 32 GB. | `ServingSubprocessLauncher.java:721,732-735` | Derive `heapBytes` by parsing `DEFAULT_HEAP_SIZE` (or share one constant). |
| B3 | **Wrong staging port reported.** `ports.put("kompile-model-staging", 8081)` — actual default is **8090** (`application.yml:2`, `kompile.staging.port`). | `app/web/controllers/SystemInfoController.java:54` | Change to 8090 (or read from `kompile.staging.port`). |
| B4 | **KGE deploy hook ships a stub, not the model.** `ModelTrainedEvent("kge", …)` carries `writeKgeCheckpointStub()` output — `~/.kompile/models/kge/<factSheetId>-v<epochMillis>.json` containing only {entitiesCount, relationsCount, finalLoss}. The real embeddings live in the graph adapter / `.sdz`+`.vocab.json` (SameDiff) or ObjectOutputStream binary (RotatE). Staging registers an unservable path. | `KGEmbeddingJobService` (4 call sites) in kompile-knowledge-graph | Capture the real artifact path from `KGEmbeddingStorageService.storeEmbeddings()` / `SameDiffKgeModel.saveEmbeddings(Path)` and pass it in the event; enrich stub with algorithm/dim/hyperparams either way. |
| B5 | **Answer scorer trained-then-discarded by default.** `AnswerSynthesisService.trainAnswerScorer(...)` saves only when a savePath is supplied; default `KbConfig.getAnswerScorerModelPath()` resolves empty. `TrainingReport.beatsFold()` is computed but **not enforced** at the persist site. No `ModelTrainedEvent` published. | `AnswerSynthesisService` (~:365) in kompile-knowledge-graph; `AnswerScorerTrainingHarness` in kompile-graph-reasoning | See §6 wiring plan (W3). Default the path to `~/.kompile/models/answer-scorers/` (ship-enabled mandate), enforce beatsFold as the deploy gate, publish the event. |

## 4. Integration seams: app-main ↔ model-staging

**Constraint verdict: PASSES.** No `<dependency>` on model-staging in app-main/app-core (only dependencyManagement in the root pom); `MainApplication.java:242-275` dispatches the `training` subprocess type via reflection on `"ai.kompile.staging.subprocess.TrainingSubprocessMain"` with a comment restating the mandate. Shared DTOs live in app-core `ai.kompile.core.staging.*` (compile-time contracts only).

**Existing seams** (all arms-length):
- `StagingAutoStartService` / `StagingServerLifecycleService` — process lifecycle (launch/health/kill).
- `ModelStagingWiringConfiguration` — `kompile.staging.url` → `AnseriniEncoderFactory.configureStagingService()`; encoders/cross-encoders discovered via `GET /api/staging/active`, files pulled via `GET /api/staging/registry/model/{id}/download/model`.
- `ModelDeploymentHook` (app-main) — listens for `ModelTrainedEvent`, POSTs trained artifacts to staging's graph-scoped deploy API. **This is the seam to reuse for all reasoning-model wiring.**
- `TrainingSchedulerBridge` — `TrainingJobStartedEvent` → `ResourceAwareJobScheduler`; staging calls back via `kompile.staging.callback-url`.
- `ModelInitSubprocessMain` (subprocess-model) — `sourceType="staging"` pulls encoder files over HTTP for the embedding subprocess.
- `ServingSubprocessMain` (:8091) receives `stagingUrl` at startup to pull model files.
- MCP tool `kompile-tool-model-staging/ModelStagingTool` (near-duplicate of staging's own tool impl — unify later).

**Missing links (the model journey today)**:
1. **Nothing loads a model into the serving subprocess after promotion/training.** `ModelDeploymentHook` registers with staging, but no listener bridges to `POST :8091/api/llm/load`. Lane stays `isAvailable()=false` (and would then 404 per B1).
2. **No default `ProcessingRouteConfig` contains a `LOCAL_MODEL/serving` backend** — the lane requires manual operator config. Should be auto-provisioned when the subprocess is up (ship-enabled mandate).
3. **Silent degradation**: if staging isn't found/promoted, `AnseriniEncoderFactory` has no models and embedding init fails with only `SetupStatusService` breadcrumbs; `StagingAutoStartService` no-ops silently when the binary is missing.
4. `VectorStorePopulationService:422` hardcodes `~/.kompile/models/anserini/indexes/vector_index` (not in KbConfig).

## 5. Serving surfaces (inventory + canonicality)

| Surface | Module | Port | Status |
|---|---|---|---|
| Serving subprocess (`ServingSubprocessMain` + whitelist config; gate `kompile.llm.direct-serving.enabled` set programmatically at `:124`) | kompile-app-subprocess-serving (+controllers from kompile-pipelines-app-llm) | 8091 | **Broken**: load/status/unload only; no generate (B1) |
| Staging app inference (`LlmExecutionController`, VLM, OpenAI-compat `/v1`) | kompile-model-staging | 8090 | **Live by default** (`kompile.staging.app.enabled: true`); used by `LocalStagingLlmService` (kompile-app-agent) |
| Pipeline serving subprocess | kompile-pipeline-serving | dynamic | Partially live; unfiltered `java.class.path` passed when launched from uber jar (risk of full autoconfig in child); no managed-subprocess registry/watchdog |
| OpenAI-compat Vert.x server | kompile-sdk-serving | CLI arg | Standalone dev tool; zero production integration (candidate: delete or document) |
| `SameDiffLLMController` model-set mgmt | kompile-pipelines-app-llm | app port | Management-only; no inference call sites |

**Canonical path** should be: staging registry (:8090) as source of truth → serving subprocess (:8091) for isolated-heap inference → `LocalServingBackend` lane in `CrawlLlmDispatcher`. B1 + missing-load-bridge are the two breaks in that chain. The agent chat path already uses staging :8090 directly (`LocalStagingLlmService`), which works because staging's own controllers are complete.

## 6. Emitted-model census (graph reasoning + unified crawl) and wiring plan

| Artifact | Producer (trigger) | Persistence | Versioning | Reaches staging? |
|---|---|---|---|---|
| **KGE embeddings** (TransE/RotatE/SameDiffKge) | `KGEmbeddingJobService`, 4 paths incl. learning subprocess (crawl GRAPH_EXTRACTION or `POST /api/kg-embeddings/train`) | vectors → graph adapter; `.sdz`+`.vocab.json` (SameDiff) / ObjectOutputStream (RotatE); stub JSON `~/.kompile/models/kge/<fsId>-v<epoch>.json` | epoch-millis filename | **Partially — stub only (B4)** |
| **PSL rule weights** | `IncrementalReasoningOrchestrator` step 5b → `FileBackedWeightStore` (every cascade when learning enabled) | `~/.kompile/data/graph/reasoning/<fsId>/psl-weights/<programKey>.v<N>.json` | **integer v1..vN + backups (max 10)** — best in codebase | **YES** (`ModelTrainedEvent("psl")` ~:1298) |
| **MEBN edge strengths** | orchestrator step 9 → `MebnWeightPersistenceAdapter` (cascade when ontology bound) | `~/.kompile/data/graph/reasoning/<fsId>/mebn-weights.json` | **none — single overwrite** | **YES** (`ModelTrainedEvent("mebn")` ~:1578) |
| **Logistic answer scorer** (12-feature re-ranker) | `AnswerSynthesisService.trainAnswerScorer()` (manual only) | Jackson JSON {weights[], bias} at caller-supplied path; **default path empty → discarded** | none | **NO** |
| **Mined process-mining PSL rules** | `MiningProcessDiscoveryService.persistCausalRules()` (L4 mining step) | `<dataDir>/rules/<fsId>-mined.psl` | none — overwrite | **NO** |
| PageRank/Louvain epoch stats | `GraphAlgorithmService` → node metadata | graph-state | n/a | n/a — not a registry candidate |
| CSR adjacency cache | `AdjacencyMatrixGraph.getCsrMatrix` | in-memory, evicted on mutation | n/a | n/a — transient cache |
| OWL-derived ontology + rules | `CrawlOntologySchemaEnrichmentProvisioner` (crawl deriveOntology) | graph-state; rules passed inline | per-fsId | n/a |
| Opinion/Beta-MAP store | grounding cascade / `KbGroundingService` | **in-memory only** (`InMemoryOpinionStore`) + confidence projections in node metadata | n/a | n/a — but durability gap: opinions lost on restart |
| DOCUMENT_CLASSIFIER | none — inference-only interface (`LayoutModel`) | n/a | n/a | registry type exists for pretrained only |

**Wiring plan** (reuse `ModelTrainedEvent` → `ModelDeploymentHook` → `GraphScopedDeployService`; the graph-reasoning lib stays infra-free — hooks live in the adapter/app layer):

- **W1 (KGE, P0)**: forward the real artifact path (see B4). Registry-readiness metadata available at emit: factSheetId, epoch version, counts, finalLoss; add algorithm/dim/hyperparams from `KGEmbeddingConfigService`.
- **W2 (mined rules, trivial)**: after `persistCausalRules()` succeeds, `publishEvent(new ModelTrainedEvent(this, "psl", fsId, rulePath, "psl-mined"))` — hook and staging already handle type "psl" end-to-end. ~5 lines.
- **W3 (answer scorer)**: new app-layer SPI `AnswerScorerDeployCallback { onScorerTrained(Path, TrainingReport, fsId) }`, optional-autowired into `AnswerSynthesisService`; invoked **only when `report.beatsFold()`** (the gate finally enforced); app-main impl publishes `ModelTrainedEvent("answer_scorer", …)`; add `ModelType.ANSWER_SCORER`; default `answerScorerModelPath` to `~/.kompile/models/answer-scorers/scorer.json` so training persists by default. `TrainingReport` is manifest-gold (MRR learned/baseline/fold, logloss, recall@1/3, ndcg@3, weights, featureNames) — send it as deploy metadata.
- **W4 (MEBN versioning)**: `persist()` writes `mebn-weights.v<epoch>.json` snapshot alongside the canonical file; event points at the snapshot → staging rollback becomes meaningful.
- **W5 (opinions durability, correctness not registry)**: `FileBackedOpinionStore` at `~/.kompile/data/graph/reasoning/<fsId>/opinions.json` — today all confidence opinions vanish on restart.

## 7. Competitive gap matrix (vs MLflow Registry / KServe / Seldon / BentoML / Triton / TorchServe / Ray Serve / SageMaker / Vertex)

| Dimension | Kompile verdict | Best-in-class reference |
|---|---|---|
| Immutable versioning | PARTIAL (graph-scoped only; encoders/LLMs overwrite) | MLflow integer versions per name |
| Aliases / champion-challenger | MISSING (single ACTIVE) | MLflow aliases; Vertex default-version |
| Stage labels | PARTIAL (STAGED/ACTIVE/DEPRECATED ≈ but two disjoint enums: StagingStatus vs ModelStatus) | MLflow stage transitions |
| Version comparison | PARTIAL (`/api/compiler/compare`, benchmark `regression` flag) | MLflow metric charts |
| Lineage | PARTIAL (source origin/repo/format/archive; **no trainingRunId/dataset hash link** — H2 TrainingJobHistory not connected to registry) | MLflow run→version graph |
| Signatures | PARTIAL (VLM IO auto-probe as strings; no typed schema, no serve-time validation) | MLflow ModelSignature; BentoML pydantic IO |
| Storage backends | Local FS only (S3 auth exists for archive downloads only) | MLflow pluggable artifact stores |
| Promotion gates | MISSING (promote is unconditional; beatsFold precedent exists but unwired) | SageMaker ApprovalStatus; MLflow webhooks |
| Deploy-to-runtime | PARTIAL (poll `/api/staging/active` for encoders; **no post-promotion auto-load for LLM serving**) | KServe/Seldon auto-rollout |
| Canary/A-B/shadow | MISSING | KServe traffic split — **non-goal** (single node) |
| Rollback | PARTIAL (graph-scoped + optimization-backup; no global version rollback) | KServe trafficPercent=0 |
| Dynamic batching | PARTIAL (`InferenceBatchPlanner` exists; manual at serve API) | Triton per-model batching config |
| Multi-model serving | PARTIAL (registry holds many; one loaded at a time) | Triton concurrent execution |
| Live metrics / drift | MISSING (benchmark-time only; actuator present but inference not instrumented) | Seldon+Alibi; KServe Prometheus |
| Model cards / audit | MISSING (SLF4J only; no append-only promotion log) | SageMaker Model Cards |
| Formats | ONNX/TF/Keras/GGUF/SameDiff — no safetensors/TorchScript/PMML | Triton multi-backend |
| API | REST + OpenAI-compat; no gRPC | Triton REST+gRPC — gRPC is a **non-goal** |

## 8. Roadmap

**P0 — restore the broken paths (small, high value) — ✅ IMPLEMENTED 2026-07-02 (same day, after this audit)**
1. ✅ B1 generate endpoint: new `LlmGenerateController` (kompile-pipelines-app-llm) over `SameDiffLanguageModelImpl`, registered in `SubprocessServingConfiguration`. Error contract = HTTP 200 + `finishReason:"error: …"` (deliberate: `postJson` throws on non-2xx, which would mask generation errors as transport errors; `ServingSubprocessBackend:66` detects the error prefix and IOExceptions → dispatcher lane failover).
2. ✅ Auto-load bridge: `StagingServingBridge` (app-main) polls `GET /api/staging/active` (KbConfig `kbServingAutoLoadEnabled=true`, interval `kbServingAutoLoadPollIntervalSeconds=15`), detects active `llm_ggml` (reasoning types ignored), resolves file via registry API, downloads to `~/.kompile/llm-cache/`, `loadModel()`s the subprocess; stops it when deactivated. `ServingSubprocessBackend.isAvailable()` now = running AND model-loaded (`isModelLoaded()` with 3s TTL cache). Route participation: `ProcessingRouteConfig.servingLaneEnabled=true` (per-route opt-out) — `CrawlLlmDispatcher` fast path tries the serving lane availability-gated, falls through on failure/timeout; explicit-backend routes unchanged.
3. ✅ KGE real artifacts: all 4 `KGEmbeddingJobService` paths write `<dataDir>/models/kge/<fsId>-v<epoch>/` with native model (`model.sdz`+`model.sdz.vocab.json` / `model.rotate` / `model.transe` / `embeddings.json`) + enriched `metadata.json`; event points at the primary file; old stub removed (zero readers). PLUS: `GraphScopedDeployService.stage()` now copies `<primary>.*` sidecar siblings (strict filename-prefix rule) so staged `.sdz` entries stay loadable. ✅ Mined-rules event: `MiningProcessDiscoveryService` publishes `ModelTrainedEvent("psl", fsId, <fsId>-mined.psl, "psl-mined")` on successful non-empty persist; coexists with `psl-cascade` entries.
4. ✅ B2 off-heap derives from shared `DEFAULT_HEAP_GB`; ✅ B3 port 8090.

Verified: kompile-pipelines-app-llm 17 tests; subprocess-serving build; KGE-scoped 122 tests; staging `GraphScopedModelLifecycleTest` 10; process-discovery 269 (incl. 3 new event tests; 2 pre-existing broken tests repaired); app-main `StagingServingBridgeTest` 7 + `ServingAvailabilityTest` 7 + `ModelDeploymentHookTest` 9; `KbConfigTest` 4; full offline compile chain knowledge-graph → app-core → crawl-graph → app-main. NOT yet done: deploy + live e2e (crawl with a promoted LLM exercising the serving lane).

**P1 — versioning + lineage foundation (generalize what GraphScopedDeployService proved)**
5. Immutable integer versions + aliases in `ModelEntry`/`RegistryService`: layout `~/.kompile/models/<type>/<name>/<version>/{artifact, manifest.json}`; `modelId = <name>@v<N>`; prior versions never deleted; `GET /api/staging/{name}/versions`; alias endpoints (champion/challenger).
6. Global rollback: `POST /api/staging/{name}/rollback?toVersion=N` (status+alias flip, no file moves) firing the same promoted-event as promotion.
7. Lineage: manifest gains trainingRunId (H2 `training_job_history` FK), datasetId/checksum, and full metric blocks (TrainingReport for scorers, finalLoss for KGE/PSL/MEBN).
8. Promotion quality gate ON by default: reject promote when `benchmarkResult.regression == true` or (scorer) `!beatsFold` — operator override param, never silent. W3 + W4 land here.
9. Append-only `~/.kompile/models/promotion-audit.jsonl` + `GET /api/staging/audit`.

**P2 — parity & polish**
10. Typed `ModelSignature` (promote the VLM IO probe to a typed record; extract ONNX/SameDiff IO at promote time; warn-level validation at serve).
11. Micrometer timers/counters on generate/embed paths (actuator already present) + `GET /api/staging/metrics/{name}/{version}`.
12. Model cards (sidecar JSON, no template enforcement); durable in-flight staging checkpoints (currently in-memory `ConcurrentHashMap`, lost on restart); label simulated training/eval results as SIMULATED in API+UI (today fake eval metrics are indistinguishable — dangerous once gates exist); unify the two `ModelStagingTool` impls; document-or-delete `kompile-sdk-serving` Vert.x server; safetensors import.

**Non-goals** (deliberate, single-node/on-prem product): K8s CRDs/KServe machinery, gRPC protocol, live canary/shadow traffic splitting (offline eval suite + promote-the-winner instead), content-addressable blob dedup, streaming drift-detection infra, auto-rollback on live metric thresholds (needs P2 metrics first; manual rollback suffices).

## 9. Corrections to subagent claims (verified against source)

1. **"OpenAI-compat is default-disabled / violates ship-enabled mandate" — WRONG.** `kompile.staging.app.enabled: true` is the shipped default (`kompile-model-staging/application.yml:21-22`); `/v1/*` and `/api/llm/*` are live by default in the staging app.
2. **"Fix B1 by importing `LlmExecutionController` into `SubprocessServingConfiguration`" — IMPOSSIBLE as stated.** That controller lives in kompile-model-staging, which must never be on app-main's classpath. The generate implementation for :8091 must live in kompile-pipelines-app-llm (or a shared LLM module already on the subprocess classpath).
3. **`ServingSubprocessMain.java:64` javadoc lists `POST /api/llm/generate` as exposed — doc/code drift**; one earlier report repeated the javadoc as fact. The mapping does not exist (see B1).
4. Serving-module unit tests were **not run** (offline `.m2` coverage uncertain; avoided long builds). All §3 items rest on direct source reads, not test runs.
