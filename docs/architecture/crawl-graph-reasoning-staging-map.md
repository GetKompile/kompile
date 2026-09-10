# Crawl + Knowledge Graph + Reasoning + Staging — System Map

Date: 2026-07-04
Purpose: pre-flight reference for the domain-planning v10 golden-path dogfood run (project init → service start →
crawl), mapping the subsystems we will exercise and the known gaps we expect to hit. Built from a
six-agent sweep of the working tree at HEAD (`ffc966086` + uncommitted app-agent split).

---

## 1. Unified crawl pipeline (orchestration)

**Entry points**
- REST: `UnifiedCrawlController` — `POST /api/unified-crawl/start`, `/jobs/{id}/retry`, `/steps` (kompile-app-main)
- Boot autostart: `CrawlProfileAutoStartService` (ApplicationReadyEvent; waits ≤300s for embedding model; profiles with `graphAutoStart=true`)
- CLI: `ProjectCrawlCommand` (kompile-cli-main) → HTTP POST to the running server; also embeds a local no-server crawl engine
- Main impl: `UnifiedCrawlGraphServiceImpl` (~2700 lines, kompile-crawl-graph) + `GraphExtractionOrchestrator` + `CrawlLlmDispatcher`

**Step dependency graph** (`CrawlPipelineStepRegistry`)
```
LOADING → DISCOVERING / CONVERTING → [PREPROCESSING] → ROUTING
   → GRAPH_PREP
   → CHUNKING (pivot, chunk-producer)
        ├─ GRAPH_EXTRACTION (LLM) → ENTITY_RESOLUTION → EDGE_COMPUTATION → ENRICHMENT
        ├─ SURFACING
        └─ VECTOR_INDEXING (EMBEDDING)   ← declared hard dep = CHUNKING ONLY
```
- VECTOR_INDEXING is *declared* independent of GRAPH_EXTRACTION but is serialized behind it at runtime
  by `HeavyMemoryCoordinatorImpl` (`serializedHeavyOps=true`, budgeted admission = MemAvailable×0.6).
  Declared graph and actual execution order diverge — top throughput lever for this run.
- Archivable/deferrable steps: GRAPH_EXTRACTION, ENTITY_RESOLUTION, EDGE_COMPUTATION, VECTOR_INDEXING
  (`CrawlStepArchiveService`; jobs park in `COMPLETED_PENDING_EMBEDDING` until deferred chunks drain).
- Post-crawl hydration: `GraphHydrationOrchestrator` → ENRICHMENT / DERIVATION / PRUNE_COMPACT / ONTOLOGY_CONFORMANCE.

**Backend dispatch chain** (per extraction wave)
`CrawlLlmDispatcher.dispatch`: capability filter `isCapableOf(backend,"llm")` (empty caps = accepts all) →
ResourceGovernor pressure check (skips LOCAL_MODEL/API_AGENT under memory pressure) → CLI-agent
availability/quota (`CliAgentQuotaLedger`) → per-backend CircuitBreaker (5 failures / 60s cooldown) →
`TokenBudgetTracker` → `ProcessingCapacityTrackerImpl.selectBackend` (priority sort; canAccept =
maxConcurrent + 60s sliding-window rate limit) → on failure `backupBackendId` → fallback chain.
CLI_AGENT `opencode-cli` round-robins models via `opencodeModelIndex`; LOCAL_MODEL `serving` lane is
gated on `LocalServingBackend.isAvailable()`.

**Resumability / durability**
In-memory `UnifiedCrawlJob` map + JPA `CrawlJobRecord` + `IndexingJobHistory` (full progress snapshot for
restart) + per-step disk archives + per-job JSONL log at `~/.kompile/logs/crawls/<jobId>.log`.
`retry(jobId, retryPhase, documentKeys)` re-processes only failed documents.

**Batching regimes (two, unshared)**
- Extraction: char-budget waves — `graphExtractionTargetCharsPerBatch` (48k), `chunksPerPrompt` (4),
  `maxItemsPerBatch` (64), parallelism 4 local / 4 remote, re-batch splitting on failure.
- Embedding: token-budget `InferenceBatchPlanner` (kompile-utils; maxBatchTokens + seqBuckets for DSP plan
  reuse) called from `AnseriniVectorStoreImpl`; AIMD feedback via `AdaptiveBatchingService` /
  `ContinuousBatcher` / `GpuAwareBatchSizer`. Do NOT hand-set embeddingBatchSize (bypasses AIMD;
  `VectorStorePopulationService:329`).

**Key knobs** (`CrawlRuntimeConfigManager`, hot-reload 5s TTL, kompile JSON config): crawlGraphExtraction
{Parallelism 4, RemoteParallelism 4, MaxItemsPerBatch 64, TargetCharsPerBatch 48000, ChunksPerPrompt 4,
BatchSize 10}, crawlVectorBatchSize 0(adaptive), llmCallTimeoutSeconds 300, batchTimeout 2700s,
circuitBreaker {5, 60s}, memoryWaitThresholdPercent 82 / critical 90, maxConcurrentJobs 1, queueCapacity 25,
crawlIncrementalByContentHash true. Resource side (`resource-scheduler-config.json`): serializedHeavyOps
true, heavyMemoryBudgetSafetyFraction 0.6, governorRamFloorMb 8192, op estimates {embedding 12G, kge 40G},
maxConcurrentByType {unifiedCrawl 1, embedding 2}, deferred-drain poll 60s.

## 2. Crawlers, loaders, extraction

**Crawlers**: `FileSystemCrawler` (glob/regex, mtime-incremental), `WebCrawler` (Jsoup BFS, robots,
same-domain, content-hash incremental, maxDepth from `UnifiedCrawlSource.maxDepth` default 3),
`EmailInboxCrawler` (Maildir/mbox/.sbd/PST/EMLX + `MailboxDiscoveryService`), `ImapPopDocumentLoader` (live),
Discord, Audio(Whisper). Source expansion: `UnifiedCrawlSource` {sourceType, pathOrUrl, maxDepth,
maxDocuments, include/excludePatterns}.

**Multi-route pipeline**: `ContentTypeRouter` + `PdfContentClassifierImpl` (PDFBox XObject inspection;
threshold 50 chars/page) → TEXT_ONLY→PDFBox+Tabula / IMAGE_BASED→VLM OCR (SmolDocling via
`VisionTextFusionStepRunner`) / MIXED→split. Office→POI/Tika, Excel→`ExcelFormulaGraphExtractor`,
HTML→Jsoup `WebHtmlLoaderImpl`, email→mime4j/libpst extractors. Config: `processing-route-config.json`
(pdfRoutingMode AUTO, fallbackEnabled, backends[] with priority/maxConcurrent/capabilities/backupBackendId,
servingLaneEnabled/localServingAutoParticipate true).

**LLM extraction**: `GraphExtractionConfig` {schemaPresetId e.g. `planning-cpg-channel-v1`, schemaMode LENIENT,
llmProvider, temperature 0.0, maxTokens 4096, entityResolution true (embedding threshold 0.88),
minConfidence 0.5, extractionContextBudgetFraction 0.5}. `LlmJsonExtractor` zero-yield guard: anchors on
`{"entities"` root key + skips `[kompile]`/log-prefixed lines (historical silent 0-entity cascade, fixed,
shared by orchestrator + `MatrixGraphConstructor`). CLI-agent path: `CliAgentExtractionLlmService`
(subprocess pool 8..32, stream-json/opencode-json/plain parsing). opencode serve path:
`DirectSubagentRunnerStdio.runOpencodeServe` (ephemeral `opencode serve`, POST /session/{id}/message,
text parts only; `KOMPILE_OPENCODE_MODEL` pin). Serving fallback: `LocalServingBackend` →
`POST /api/llm/generate` loopback. `deriveOntology` flag → schema derivation step
(UnifiedCrawlGraphServiceImpl:2321).

**Metadata**: `OccurredAtParser` (ISO/epoch, UTC-normalized, null-safe round-trip with
`GraphIOService.formatOccurredAt`), Tika Dublin-Core stamping, Obsidian frontmatter, email identity
(`EmailGraphExtractor`).

## 3. Knowledge graph (matrix store + subprocess + KGE/CSR)

**Store**: `AdjacencyMatrixGraph` (sparse maps edgeType→src→tgt for weights/relTypes/`EdgeMeta` metadata
bags; dense matrix built on demand; per-type AtomicInteger counters; reverse in-degree index; shell mode
for subprocess-resident graphs). `GraphNode`/`GraphEdge` pure POJOs — edges embed NO nodes;
`getSourceNode()` synthesizes hollow stubs; resolve via `resolvedSourceNode(byId)` map-first.
`MatrixKnowledgeGraphService` (@Primary seam) scopes graphs per fact sheet (`factsheet_<id>`);
`createEdgesBatch(EdgeSpec[])` single-RPC writes; `updateEdgeMetadataBatch` = GNN/KGE score write-back.

**Subprocess**: `GraphMatrixSubprocessMain` (:8094, persistent, owns the matrix + rehydration;
`POST /invoke` JSON-RPC-ish protocol) ← `GraphMatrixSubprocessLauncher` (DEFAULT_HEAP 32g; managed-config
override; always-on) ← `SubprocessMatrixGraphStore` (@Primary JDK HttpClient client, 120s/invoke;
createGraph/loadGraph return SHELLS — iterating getAllNodes() on them silently sees empty graph).

**HEAD commit ffc966086**: `SameDiffKgeModel` (RotatE `sd.graph().rotatE()` + TransE + marginRankingLoss,
ND4J Adam; parity RotatE MRR 0.84, TransE 0.58>0.55; `useSameDiffKge` default TRUE for fresh configs,
persisted old configs deserialize false), CSR cache (`getCsrForEdgeType` → `Nd4j.sparseFromEdges(...).toCsr()`,
mutation-evicted, consumed by `GraphToSameDiffDataset`), edge-metadata write-back path, KGE→PSL
(`KgeTripleScoreFunction` ExternalFunction + `KgePslBulkObserver`) and KGE→MEBN (`KgeOpinionStoreBridge`
tier-b evidence) bridges — NOT auto-registered post-training. `SameDiffEmbeddingTrainer` (Node2Vec) on Adam.

**Embeddings/alias**: KGE jobs (`KGEmbeddingJobService`, warm-start 10 epochs, model.sdz artifacts),
EMBEDDING_SIMILARITY edges → WP14b union-find alias unify (floor `kbAliasEmbeddingMinSimilarity` 0.95 +
same-type guard), WP16a epoch topology stamps (`topology.pagerank`, `community.id` via PageRank/Louvain).

**Retrieval**: SearchType LOCAL (BFS) / GLOBAL (community summaries top-K cosine) / HYBRID (embed →
NN seeds → sparse personalized PageRank → blend → PathRAG path augmentation always appended, line 588/731
`MatrixGraphRagService`); entityType-typed filtering incl. owlInferredTypes closure; community-scoped
candidate restriction (`kbCommunityScopedRetrieval` true, `kbSynthesisCommunities` 3). Wired into ReAct
`GraphRagTool`, `UnifiedKnowledgeTool`, lite chat.

## 4. Reasoning & learning stack (kompile-graph-reasoning + KB cascade)

**Engines** (infra-free lib `ai.kompile.graph.reasoning`): PSL (PslProgram/Rules, grounding via
`ConjunctiveQueryEngine`+`ConcurrentFactStore`+`JoinKernel`, MAP solvers: ADMM/SGD/SameDiff-SGD/scalar/
tensor HL-MRF, `PslMarginalInference`), weight learning (`StructuredPerceptronLearner`,
`PseudolikelihoodLearner`, `SameDiffPslWeightGradient`), MEBN (MTheory/MFrag/SSBNGenerator, OWL-RL reasoner
+ Turtle reader, `MebnWeightLearner` + SameDiff strength learner), Bayesian (VariableElimination, NoisyOr),
causal (AllenRelation, CausalHop, sensitivity/uncertainty), explain SPI (ReasoningTrail, OpinionTree,
ConfidenceBreakdown).

**Belief machinery**: `Opinion` subjective logic (cumulativeFuse), StrengthBand
ESTABLISHED/HIGH/PROBABLE/SPECULATIVE/SUPPRESSED, Beta-MAP calibration (PlattCalibrator; priors: evidence
2.0 / structural 0.1 / asserted 0.0), `ProbabilisticContradictionDetector` (minPosterior 0.55, joint 0.30),
`BeliefReviser.retract/retractAndRevise` under write lock, abstention gate (evidenceSufficiencyThreshold 0.3).

**The 17-step cascade** (`IncrementalReasoningOrchestrator.doReground`, per fact sheet):
PROJECTION (`GraphToFactStoreProjector` w/ alias unify + opinion soft-truth) → PROGRAM_BUILD →
ONTOLOGY_RULES (`OntologyToPslRuleCompiler`) → WEIGHT_RELOAD → MAP_SOLVE → MATERIALIZE (`EntailmentEngine`
→ InferredFactStore) → PROMOTION (`FactPromotionTracker`, Beta evidence, source-keyed) → AUDIT →
graph write-back (`InferredFactGraphMaterializer`, replace-mode, minConf 0.5, feedback-guard) →
WEIGHT_LEARN (perceptron, online 1 epoch, band-aware MAP priors 0.9/0.7/0.4/0.15) → TOPOLOGY stamps →
MEBN_LEARN (every 10 cascades; auto-enable when ontology bound). SHA-256 skip gate for unchanged content.
Full re-ground each time — incremental/semi-naive grounding NOT implemented (known).

**Learned layers**: answer-synthesis featurizer + `LogisticAnswerScorer` (beatsFold deploy gate — computed
but not runtime-enforced), `groundedConfidenceWeight` 0.2 RAG re-rank nudge, cascade materializer,
`topologyPriorWeight` 0.0 (WP19 pending — topology tier unfed into priors).

**Query path**: `KbVerifier.verify` → SUPPORTED/REFUTED/UNKNOWN; ask_graph MCP tools (query/verify/assert/
explain/mebn/subscribe/synthesize/fused) → `/api/kb-grounding/*`; chat trails
`GraphReasoningRetriever.retrieveWithTrail` (CAUSAL→EventAttribution, PROBABILISTIC→MEBN/BN) → SSE
`reasoning_trace` in AgentChatService.

**KbConfig** (managed JSON `kb-confidence-config.json`, never @Value): kbLearningEnabled true,
kbPslLearningRate 0.1, epochs 50/1-online, band prior means, kbMebnAutoEnableWhenOntologyBound true,
kbMebnLearningInterval 10, kbCascadeMaterializeInferredEnabled true (minConf 0.5), kbSkipUnchangedCascade
true, kbProjectionOpinionEnabled true, alias unify gates, kbGroundedConfidenceWeight 0.2, abstention/CoVe/
CRAG gates all true, kbOntologyGuidedExtractionEnabled true (constrains LLM extraction to bound ontology),
kbDerivationMaxAtoms/TimeBudgetMs 0 (UNCAPPED — cascade can run long).

## 5. Business-process layer (mining/entailment)

**Pipeline** (auto-discover on `GraphBuildCompletedEvent`, `kompile.process.mining.auto-discover=true`):
`EventLogExtractor` (object-centric via `miningAnchorEntityType`) → `ActivityAliasUnifier` (cosine ≥0.95
union-find, ALIAS chips) → `TaxonomyRollup` (owlInferredTypes fixpoint ≤5 passes; inert without prior OWL
classification) → `TraceClusterer` (Jaccard 0.2, ≤6 processes) → actor tally
(`ActorResourceObservations.tallyDetailed`, conflicts → CONFLICT chips) → per-cluster: InductiveMiner +
DfgBuilder + `ProcessCausalAnalyzer` (χ² PSL rules → `<dataDir>/rules/<fs>-mined.psl`) + DeclareMiner +
**ProcessEntailment** (PSL atoms Df/Resp/Prec/Chain/Nce; transitivity 0.8 skipped >40 activities;
`ActivityIntervals` → Allen ORDERED/REVERSED/OVERLAP; recency-decayed votes half-life 180d;
Pseudolikelihood 50 epochs when ≥4 labels; refuted = weightedReversed > weightedOrdered; concurrent pairs
withhold precedes) → fact promotion `assertFactsBatch` (Occurs unquoted for PSL unification, precedes
quoted, isA/partOf, performedBy/hasRole; gate ≥0.7) → `ProcessTreeToSuggestion.convertGrounded` (XOR SpEL
share-stubs + decision-stump guards ≥0.9 accuracy; AND annotations) → `RoleBindingExtractor` 3-tier
(OBSERVED → KB → keyword heuristic) → evidence chips (ALIAS/DRIFT/CONFLICT/RECONCILIATION) →
`ProcessIdentityResolver` (processKey lineage, supersede=MARK) + `ProcessDriftAnalyzer` +
`ChangePointDetector` → `PrecedenceMaterializer` (DIRECTLY_FOLLOWS instance / PRECEDES ≥0.7 /
PERFORMED_BY edges, EdgeType.TEMPORAL, provenance INFERRED, idempotent).

**Accept path**: `ProcessEngineServiceImpl.reviseProcess` version-bump `<id>_v<N>.json`, priors immutable.

**Config**: 18 tunables in `ProcessMiningConfig` (managed JSON; REST `/api/process-mining-config`;
Settings → Process Mining tab). Key gates: entailAssert/Materialize 0.7, aliasSimilarity 0.95,
guardMinAccuracy 0.9, identityJaccard 0.5, conflictMinorityShare 0.25.

## 6. Model staging & serving lane

**Shape**: standalone `@SpringBootApplication` (`ModelStagingApplication`/`StandaloneApp`), :8090, Angular
UI, exec jar by default. NEVER on app-main's classpath (enforced by pom comment; exec-classifier dep only
for uber co-bundling). Serving subprocess :8091 (`kompile.llm.serving.port`).

**Catalog** (`model-sources.yml` + live `RegistryService` merge): bge family/e5/arctic (onnx encoders),
ms-marco L6/L12 (cross-encoders), florence-2 base/large + smoldocling-256m (vlm), lfm2.5-1.2b-instruct
Q4_K_M (gguf llm, ram 3072 / disk 750). All entries carry ram_mb/vram_mb/disk_mb (vram 0 = CPU-capable).
Downloads via `HuggingFaceDownloader`.

**Flow**: `POST /api/staging/stage/catalog/{id}?autoPromote=true` → async download → GGUF metadata via
`GgmlImporter` / onnx validation → READY → promote (staging-dir → model-dir, registry.json entry, ACTIVE).
Registry: project `data/models/registry.json` or global `~/.kompile/models/registry.json`.
`StagingServingBridge` (app-main daemon, poll 15s): GET /active → llm_ggml id → download to
`~/.kompile/llm-cache/` → `ServingSubprocessLauncher.loadModel` → :8091 `POST /api/llm/generate`.
`ServingSubprocessBackend` implements `LocalServingBackend` (agentName "serving", isAvailable = subprocess
up + model loaded, 3s TTL) → quota-free LOCAL_MODEL extraction lane. OpenAI-compat `/v1/chat/completions`
behind `kompile.staging.app.enabled=true`.

**GGUF inference = SameDiff `GenerationPipeline`** (org.eclipse.deeplearning4j.llm.generation — NOT
llama.cpp): KvCacheStrategy, SamplingConfig, `InferenceBatchPlanner` KV-bucket sizing. Device placement is
implicit: ND4J backend env at subprocess launch (`SubprocessEnvironmentPropagator` + device-routing-config)
— no model-level CUDA assignment in staging.

**CLI client**: `autoStageProjectModels` (ProjectServiceCommand:655; serve/open-time; free-space guard 1GB
headroom + per-model diskMb; catalog-first, /convert fallback; REQUIRES staging already running).
`ModelAutoInitializationService` (embedding init poll, circuit-breaker on restart governor).

## 6b. ND4J multi-backend & failover layer (fork-side + kompile wiring)

Multi-GPU + failover machinery lives in the ND4J fork and IS configured by kompile (an earlier draft of
this map wrongly said none was found):
- `org.nd4j.linalg.factory.BackendManager` (nd4j-api): enumerates every device with per-device memory
  caps, priority order (default `CUDA_GPU > ROCM_GPU > METAL_GPU > TPU > CPU`), cross-device op execution
  with automatic data transfer, fluent per-device cap API (`setMemoryCap`, fraction-of-total). Boot
  banner shows the ratios applied: `cuda:cuda:0 - 24084 MB total, 21676 MB cap` (0.9), CPU 0.8.
- System properties (`ND4JSystemProperties`): `nd4j.backend.priority`,
  **`nd4j.backend.memory.fallback`** (the failover toggle), **`nd4j.backend.gpu.memory.fraction`** /
  **`nd4j.backend.cpu.memory.fraction`** (the ratios), `nd4j.backend.auto.transfer`,
  `nd4j.backend.auto.init` (+ `org.nd4j.backend.multi.auto` gate for MultiBackendNativeOpsHolder).
- `DeviceAwareOpExecutioner` — kompile-gated by `nd4j.multibackend.enabled`.
- libnd4j allocation-level failover: `CudaMemoryPool::allocateFailover` + non-peer
  `DataBuffer::allocateSpecial` (both observed live in planning-v3 embedding-subprocess logs).
- DSP capture OOM machinery (fields on `Nd4jEnvironmentConfig` → libnd4j env vars):
  `ND4J_DSP_CAPTURE_OOM_MAX_RETRIES/_RETRY_INTERVAL`, `ND4J_DSP_CUBLAS_WORKSPACE_MB`,
  `ND4J_DSP_GRAPH_METADATA_SAFETY_MB`, `ND4J_DSP_PROACTIVE_EVICT`, `ND4J_DSP_LRU_EVICTION`,
  `ND4J_DSP_CAPTURE_WORKSPACE_MB`.
- Kompile wiring: `Nd4jEnvironmentConfig` (kompile-app-core; ~100-field managed record — threads/BLAS,
  full CUDA allocator/pool/P2P/unified-memory block, Triton compiler incl. graph capture + TF32, DSP
  toggles + diagnostics, memory limits; on CUDA `Environment.applyOptimalLLMConfig()` is the single
  source of truth) applied pre-Spring by `MainApplication.main()` and synced by
  `Nd4jEnvironmentConfigService` (kompile-app-platform); `SubprocessEnvironmentPropagator` forwards
  parent `nd4j.*`/`org.nd4j.*` -D flags + `CUDA_VISIBLE_DEVICES` into every subprocess. Backend
  routing is handled by launcher preferences and scheduler placement; we no longer force multi-backend
  disable flags at launcher scope.
- ⚠️ Interplay with the CUDA-only dist variant (gap #4): the learning subprocess's CPU-safe defaults
  (`-Dnd4j.backend.priority=CPU` when parent unset) collide with a classpath that has NO nd4j-native
  backend. Verify the parent (ServiceManager launch) carries `nd4j.backend.priority=CUDA_GPU,CPU` — or
  KGE training in the learning subprocess crashes at Nd4j clinit on the cuda-only build.

## 7. Cross-cutting gap register (pre-run)

P0/high-interest for this run:
1. **VECTOR_INDEXING runtime serialization behind GRAPH_EXTRACTION** via HeavyMemoryCoordinator despite
   CHUNKING-only declared dep — the top declared-vs-actual throughput divergence.
2. **`kompile install model <id>` does not exist** — doctor's model-warning hint is broken; no headless
   model download without a running staging server (autoStage requires the server).
3. **RoleBindingExtractor KB tier queries predicates no extractor emits** (hasRole/performedBy with
   activity subjects) → roles silently fall to keyword heuristic unless observed-actor tally matches.
4. **CUDA-only exec jar (cuda variant)**: no nd4j-native backend bundled — any subprocess launched with
   `CUDA_VISIBLE_DEVICES=-1` fails ND4J init. Launchers must not force CPU-only env on this variant.
   Sharpest instance: `LearningSubprocessLauncher` defaults `-Dnd4j.backend.priority=CPU` when the
   parent doesn't set it (§6b) — on this build that means KGE training crashes unless the parent
   carries a CUDA-first priority.
5. **build-dist.sh jars-only mismatch — FIXED 2026-07-04**: the full-java-build path passed the dead flag
   `-Dkompile.dist.jar=true` (no pom consumed it) → app exec jar silently missing on that path;
   build-dist.sh:175 now passes `-Dkompile.uber`.
6. **KGE→PSL/MEBN bridges not auto-registered** post-training; GNN scoring not automatic post-crawl.
7. **useSameDiffKge=true only for fresh configs** — pre-existing persisted KGE config files deserialize
   false (v10 is fresh, so fine here).
8. **CSR cache node-count race** (volatile check-then-rebuild, narrow window under concurrent writers).
9. **DeferredWorkDrainer poll reuses 60s embedding-resume knob** for ALL deferred sources — coarse.
10. **AGENTS.md tool-instruction template missing from CLI native image** — FIXED 2026-07-04
    (`templates/.*` added to resource-config.json); verify in rebuilt binary.
11. Two batching regimes (char-based extraction vs token-based embedding) share no abstraction.
12. PathRAG is always-on inside HYBRID (not selectable/disable-able per query).
13. Process lineage: cluster-split mints new processKey silently (no SPLIT chip); AND-parallel instability
    computed but no dedicated chip; `assertFactsBatch` version = epoch-millis (same-ms collision).
14. Process-edge Opinion fusion is inline (not OpinionStore-backed); TaxonomyRollup inert without OWL run.
15. `SubprocessMatrixGraphStore.createGraph/loadGraph` return shells — any main-JVM code iterating them
    sees an empty graph silently.
16. Stale instance registrations (`~/.kompile/instances/default.json` pointed at a dead PID) — registry
    hygiene; doctor cross-ref exists but no GC.
17. `ClusterBackendHealth` NOOP on single node (fine here; real on multi-node).

## 8. Dogfood run plan & watchlist (on GO)

Sequence: clear `/tmp/embedding-subprocess-javacpp-*` (JavaCPP leak; app stopped) → `kompile project
service start` on kompile-planning-v10 (staging :8090 → autoStage 4 manifest models → StagingServingBridge
loads lfm2.5 GGUF → serving :8091 on cuda0 → app :8080 heap 8g → graph-matrix :8094 heap 32g) → doctor +
`/api/setup/status` → read `UnifiedCrawlRequest` and start the crawl (auto-ingest profile; graph extraction
schemaPresetId=planning-cpg-channel-v1, deriveOntology, opencode primary / serving fallback) → monitor.

| User concern | Concrete signals to watch |
|---|---|
| Multi-GPU memory overload | `nvidia-smi` per-GPU (cuda0=4090 24G, cuda1=3070Ti 8G); device routes have `maxDeviceMemory: null` (unbounded); graph-matrix 32g heap + serving 16g + VLM all contending; ND4JSystemProperties maxphysicalbytes |
| CLI-agent vs local-model balancing | backendStats / recentLlmCalls; circuit-breaker trips (5/60s); opencode maxConcurrent=2 + model alternation; serving-lane pickup when opencode saturates; quota ledger |
| Crawl throughput / batch dependencies | step timeline in `~/.kompile/logs/crawls/<jobId>.log`; VECTOR_INDEXING start vs GRAPH_EXTRACTION end (gap #1); COMPLETED_PENDING_EMBEDDING deferrals; DeferredWorkDrainer 60s latency; chunksPerPrompt=4, targetChars=48k wave sizes |
| Multi-GPU failover (SameDiff) | ND4J-level failover IS configured (§6b: BackendManager priority + `nd4j.backend.memory.fallback` + gpu/cpu memory fractions → 0.9/0.8 caps; `CudaMemoryPool::allocateFailover`; DSP capture-OOM retry/evict) — watch it FIRE under cuda0 pressure (fallback allocations / auto-transfer to cuda1/CPU in BackendManager + subprocess logs); kompile routing above it stays statically pinned (llm/vlm→cuda0, maxDeviceMemory null); learning-subprocess CPU-safe defaults vs CUDA-only jar collision (§6b ⚠️); embedding AIMD backoff vs OOM; GpuResourceManager decisions |
| CUDA crashes / DSP issues | fresh nd4j-cuda-12.9 (today's rebind fix) — hs_err/SIGSEGV in subprocess logs (`~/.kompile/logs/subprocesses/`); libnd4j op failures; `InferenceBatchPlanner` seqBuckets DSP plan reuse; CUDA-only jar + any CPU-forced lane (gap #4) |

Scaffold state (2026-07-04): kompile-planning-v10 initialized (SERVER tier, 9 configs, 4-model manifest,
6 agents, .mcp.json) with 22-doc domain-planning corpus; components refreshed from tree (app-main 882MB CUDA exec jar
+ staging 743MB exec jar, stale .boot-inf-extracted purged); CLI native rebuilt from tree (+ templates
resource fix). Nothing started.

## 9. Storage → metadata → surfacing → answer-trace preflight (2026-07-04, 4-agent sweep)

**9.1 How graphs are stored.** Everything lives in ONE Anserini/Lucene index (per-project path from
`vectorstore-anserini-config.json`): `graph:{graphId}:meta` (counts/edgeTypes/capacity/dim),
`graph:{graphId}:node:{nodeId}` (nodeType/title/description/full metadata map),
`graph:{graphId}:adj:{edgeType}` (serialized adjacency per edge type), `graph:{graphId}:embd`
(node-embedding matrix). Per-fact-sheet graphs (`factsheet_<id>`), rehydrated at boot by
`VectorStoreMatrixGraphStore.rehydrateGraphsOnStartup` (in subprocess mode, inside :8094 only).
Named-graph registry: `~/.kompile/named-graphs.json` (`NamedGraph` carries ontologySchemaId/version,
schemaJson, parentGraphId — the graph↔ontology binding anchor). KGE artifacts: `models/kge/<fs>-v<ts>/
model.sdz` + vocab sidecar + registry entry; node vectors written back as `_kge_emb/_kge_algo/_kge_ver`
metadata. Export formats via GraphIOService (JSON-LD/Turtle/GraphML/...); `KGE2` binary sidecar for clone.

**9.2 Metadata inventory (what retrieval/reasoning can key on).** Node: provenance
(`_source/_sourceDocumentId/_sourceChunkId/_crawlRunId/_extractionModel/_extractedAt`), belief
(`_opinion/_evidencePos/_evidenceNeg/_priorStrength/_sourceTrust/_basisType/_corroborationCount/
_validFrom/_validTo`), typing (`entity_type/entity_category/entity_subtype` + `owlInferredTypes`
closure — GraphNodeTypes multi-key fallback), KGE (`_kge_*`), GNN (`gnn.score`, `neural.*`), structure
(`parentNodeId/chunkIndex/sourceType/pathOrUrl`), format stamps (40+ `email.*`, `tika.*`, `pdf.*`,
`docx.*`, `ogTitle`, `sql.*`). Topology (`topology.pagerank`, `community.id`) is stamped by
`GraphTopologyStatsService` in cascade step 7 (the algorithms themselves return maps and persist
nothing). Edge: EdgeType (HIERARCHICAL/EXTRACTED_FROM/SHARED_ENTITY/EMBEDDING_SIMILARITY/TEMPORAL/
ALIAS_OF/RESOLVES_TO/...16) + relationType + weight + confidence + provenanceType
(EXTRACTED/INFERRED/AMBIGUOUS) + occurredAt/observedAt + stale/userPinned/validUntil + metadata bag
(GNN/KGE write-back). NodeLevel: SOURCE/DOCUMENT/SNIPPET/ENTITY/TABLE/ATTACHMENT/IDENTIFIER/ALIAS/CUSTOM.

**9.3 Ontology-cleaned data.** Chain: schema preset → prompt constraints (LENIENT logs, STRICT strips;
`kbOntologyGuidedExtractionEnabled` projects bound-ontology types into extraction) → binding
(`NamedGraph.ontologySchemaId`, fallback ProcessDefinition) → `deriveOntology` enrichment
(OWL classify → type induction → relation-schema resolution → re-classify; commits 809b6624a/c0e3eab76)
→ hydration: DERIVATION → PRUNE_COMPACT (entity conf ≥0.3, relation ≥0.2, component ≥3, compaction
Jaccard+embeddings, 500-candidate cap) → ONTOLOGY_CONFORMANCE (conformanceScore, unknownTypeCount;
`ontology.conformant` stamped) → HEALTH. Entity resolution: multi-signal (title/alias/Levenshtein/cosine
0.88) blocked by type, union-find, ALIAS_OF/RESOLVES_TO merge trace. Invariants that HOLD post-crawl:
typed nodes when ontology bound, confidence floors, alias-merged entities, materialized is-a/has-a
closure, conformance scored. Known leaks: untyped nodes under LENIENT with no binding; >500-pair
compaction truncation; EXTRACTED edge dupes on re-crawl (inflate retrieval weights); hollow-endpoint
conformance false-negatives.

**9.4 Learned artifacts (all warm-start loops closed).** PSL weights
`<dataDir>/data/graph/reasoning/<programId>.v<N>.json` (+Lucene mirror `fs:<id>:<programId>`, cascade
key `<fs>:cascade`, 10 backups); mined process rules `<dataDir>/rules/<fs>-mined.psl` (reloaded into next
cascade, ModelTrainedEvent to staging); MEBN `reasoning/<fs>/mebn-weights.json` (warm-start); KGE
model.sdz (query-time triple re-rank + node `_kge_emb` NN lookup); answer scorer at
`kbAnswerScorerModelPath`; process suggestions `~/.kompile/processes/suggestions/*.json` (XOR stump
`conditionExpression` inside); extraction checkpoints `data/graph/graph-extraction-checkpoints.json`
(SHA-256 chunk keys, skip-unchanged; cleared on config-fingerprint change). Business-process surfacing
into the graph proper: DIRECTLY_FOLLOWS (instance), PRECEDES ≥0.7, PERFORMED_BY edges (TEMPORAL carrier,
INFERRED provenance) + `Occurs/precedes/isA/partOf/performedBy/hasRole` KB atoms — i.e. process
structure IS reachable by hybrid retrieval and ask_graph conjunctive queries, not just the UI cards.

**9.5 Answer-trace verdict — can the hybrid reasoner feed an LLM a good trace?**
READY TODAY (title-based, verified in code): HYBRID/LOCAL/GLOBAL context strings
(`MatrixGraphRagService.formatNodeContext` → `Entity: <title> [<type>]`, neighbor lines
`title -> title (weight)`, PathRAG path text; raw nodeIds only in structured citation refs);
CAUSAL NL explanations + root-cause titles; FusedReasoner modality details (`entity:<title> [type]`);
ask_graph verify (verdict + confidence + strength band); explain naturalLanguageSummary when
TraceHumanizer is wired.
CONFIRMED LEAKS (the patch list): (1) `AskGraphQueryTool` renders raw binding values — no title
resolution on FOL args; (2) `SynthesizeResponse.Answer.answer` = raw `entityId` — the synthesize tool
literally answers with an id; (3) CAUSAL/PROBABILISTIC fall back to raw nodeId/variable keys when
titles are null; (4) `TraceHumanizer` is `@Autowired(required=false)` everywhere — silently degrades
to raw atom keys; (5) derivation-tree SSE DTO carries raw `atomKey()` (UI-side, still wrong).
MISSING GLUE: `ReasoningTrail` (evidence, activated rules, derivation tree, confidence breakdown) is
computed per retrieval but goes ONLY to the `reasoning_trace` SSE store — the LLM prompt receives just
the flat context string. ReferenceResolver / DisplayRef / IdLeakAssert from the reasoning-math plan:
NOT implemented (zero references in tree).
Bottom line: graph content + typing + provenance + process edges are trace-ready; the gaps are thin,
well-localized rendering/glue layers (bindings→titles, answer→title+evidence, trail→prompt section).
