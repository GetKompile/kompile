# App persona boundary

The contract for splitting `kompile-app-main` into three processes. Every `@RestController` /
`@Controller` in `kompile-app-main` is assigned to exactly one web-layer module. The persona apps
depend on a subset of those modules, so an endpoint a persona does not own is **not on its
classpath** and returns 404 — process separation, not UI hiding.

There is no Spring Security in `kompile-app-parent`, so this is the only isolation mechanism
available. Anything mis-filed as end-user is genuinely reachable by end users.

## Packages are preserved

Controllers keep their existing packages (`ai.kompile.app.web.controllers`, `ai.kompile.app.project`,
…) and only change Maven module. This follows `kompile-app-diff`, which holds
`ai.kompile.app.services.diffindex|diffpolicy|difftracker` in its own module with the note *"Package
preserved"* (`kompile-app-diff/pom.xml:34`). Consequences:

- No `package` or `import` edits anywhere — the move is `git mv` plus pom wiring.
- Component scanning is unaffected: every app still scans `ai.kompile`.
- Isolation comes from **classpath presence**, not package naming. A controller an app does not depend
  on is not in its jar set, so its mapping is never registered.

Split packages across jars are fine here — these are classpath Boot apps, not JPMS modules.

## Service-layer prerequisite (done)

Controllers cannot move on their own: the crawl controllers alone needed 21 service classes that
were still resident in `kompile-app-main`. Three modules were carved out first, all with packages
preserved and verified zero back-edges into app-main:

| Module | Files | What |
|---|--:|---|
| `kompile-app-ingest-svc` | 40 | Step 3 of `services-decomposition-spec.md`. `DocumentIngestService` (2367 LOC), `VectorStorePopulationService`, staged pipeline, chunk dedup/enrichment, contextual RAG, cross-document relations, cross-index tracking, index status/sync, freshness, job resume, progress trackers, adaptive batching + audit, Confluence/YouTube/markdown conversion, and the subprocess launch machinery those drive |
| `kompile-app-ontology` | 12 | The whole `ai.kompile.app.ontology` package — OWL classification + Jena bridge, derivation, graph↔ontology binding, type induction, relation-schema resolution, schema enrichment |
| `kompile-app-crawl-svc` | 11 | Single-source crawl start/preview, crawl-job persistence, graph schema presets, processing route + capacity, and the Quartz scheduling jobs |

Layering — `kompile-app-ontology` and `kompile-app-crawl-svc` are siblings above `-ingest-svc`;
neither references the other:

```
core / config / dto / ingest / facts / platform / mcp / crawl
                        ↓
              kompile-app-ingest-svc
                   ↓          ↓
   kompile-app-ontology   kompile-app-crawl-svc
```

`kompile-app-crawl-svc` could **not** join the existing `kompile-app-crawl`: it reaches into
`-ingest-svc` (`YouTubeTranscriptService`, `IndexSyncService`, `DocumentFreshnessService`) and
`kompile-app-crawl` is a *dependency* of `-ingest-svc`, so folding it in would close a cycle.

Three back-edges into app-main were inverted rather than dragged along:

- `SubprocessHeartbeatBroadcaster` moved **down** into `kompile-app-platform` (its only kompile
  dependency was `SubprocessMessage` in app-core, and platform already has spring-messaging) —
  no code change, and `VlmTestSubprocessLauncher` still reaches it from app-main.
- `MonitorService` is now reached through the `SubprocessTaskCompletionListener` SPI
  (`kompile-app-platform`), implemented by `MonitorService` in app-main. Injection stays
  `@Autowired(required = false)`, so a persona app without the monitor subsystem simply skips the
  notification.
- `CrawlJobPersistenceService` switched from `PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER`
  to the identical constant on `IngestTransactionManagers` (`kompile-app-ingest`) — the pattern that
  module already established for exactly this problem. Bean name unchanged, so runtime resolution is
  identical.

`kompile-app-main` went from 423 to 360 source files and still compiles main + tests.

## Modules → apps

| Web module | `kompile-app-chat` :8081 | `kompile-app-crawl-manager` :8082 | `kompile-app-main` :8080 |
|---|:--:|:--:|:--:|
| `kompile-app-web-shared` | ✅ | ✅ | ✅ |
| `kompile-app-web-chat` | ✅ | — | — |
| `kompile-app-web-crawl` | — | ✅ | — |
| `kompile-app-web-graph` | ✅ | ✅ | — |
| `kompile-app-web-admin` | — | — | ✅ |

Counts: shared 25, chat 18, crawl 24, graph 2, admin 85 — **154 total**.

Graph *viewing* is an end-user surface, so `web-graph` ships with the two end-user apps. The admin
console does not mount it: graph **maintenance** — `/api/graph-eval`, `/api/graph-ontology`,
`/api/graph-sim` — lives in `web-admin` and is a different surface from the aggregate/forecast read
paths. This is a change from the pre-split arrangement, where app-main mounted everything.

## shared — `kompile-app-web-shared` (25)

Mounted by all three apps: project browsing, fact sheets, documents, sources, config, setup, and the
SPA forward.

| Controller | Base path |
|---|---|
| `ProjectController` | `/api/projects` |
| `ProjectPortabilityController` | `/api/projects/current/portability` |
| `ProjectStoreController` | `/api/project-store` |
| `FactSheetController` | `/api/fact-sheets` |
| `NoteController` | `/api/fact-sheets/{id}/notes`, `/api/notes/{id}` (method-level paths) |
| `DocumentManagementController` | `/api/documents` |
| `DocumentUploadController` | `/api/documents` |
| `SourceViewerController` | `{"/api/facts", "/api/sources"}` |
| `SourceProviderController` | `/api/source-providers` |
| `IndexBrowserController` | `/api/index-browser` |
| `TableBrowserController` | `/api/tables` |
| `KnowledgeSearchController` | `/api/knowledge` |
| `NoteSyncController` | `/api/sync` |
| `NoteSyncConfigController` | `/api/sync/config` |
| `AppConfigController` | `/api/config/k-app` |
| `FrontendConfigController` | `/api/config` |
| `SetupStatusController` | `/api/setup` |
| `ModelStatusController` | `/api/models` — `/status`, `/init-status`, `/subprocess/logs` |
| `ModelDiscoveryController` | `/api/models` — `/list`, `/embeddings/info` |
| `ModelDebugController` | (empty stub, no endpoints — kept beside the model controllers) |
| `ServiceStateController` | `/api/services` |
| `SystemResourceController` | `/api/system` |
| `SystemInfoController` | `/api/system` |
| `SpaForwardController` | `/` → `forward:/index.html` |
| `SdkController` | `/api/sdk` |

`SetupStatusController` must stay shared: `ProjectCrawlCommand` probes `/api/setup/status` on every
generated app to decide whether the backend is ready.

## chat — `kompile-app-web-chat` (18)

| Controller | Base path |
|---|---|
| `AgentChatController` | `/api/agents/chat` |
| `PassthroughChatController` | `/api/agents/passthrough` |
| `CliAgentModelController` | `/api/agents/models` |
| `KompileLocalModelController` | `/api/agents/kompile-local` |
| `AgentRuntimeStatusController` | `/api/agents/runtime` |
| `AgentDiagnosticController` | `/api/agents` |
| `ChatSessionContextController` | `/api/chat-sessions/{sessionId}/context` |
| `ConversationalRagController` | `/api/chat` |
| `RagController` | `/api/rag` |
| `GraphRagController` | `/api/graph-rag` |
| `SystemPromptController` | `/api/system-prompts` |
| `SkillController` | `/api/skills` |
| `SessionMetricsController` | `/api/session-metrics` |
| `ToolCallCatalogController` | `/api/tool-calls` |
| `KbGroundingController` | `/api/kb-grounding` |
| `ExplainController` | `/api/explain` |
| `VerifyElementController` | `/api/grounding` |
| `PromptTemplateController` | `/api/prompts` |

## crawl — `kompile-app-web-crawl` (24)

| Controller | Base path |
|---|---|
| `UnifiedCrawlController` | `/api/unified-crawl` |
| `CrawlerController` | `/api/crawlers` |
| `CrawlProgressSseController` | `/api/crawl-events` |
| `DistributedCrawlController` | `/api/distributed-crawl` |
| `CrawlClusterController` | `/api/cluster` — `/workers*`, `/capabilities`, `/local/drain` |
| `ClusterJobController` | `/api/cluster` — `/jobs*` (worker-facing delegated jobs) |
| `GraphExtractionController` | `/api/graph-extraction` |
| `GraphExtractionModelController` | `/api/graph/extraction-models` |
| `GraphHydrationController` | `/api/graph/hydration` |
| `IngestEventController` | `/api/ingest/events` |
| `IngestJobResumeController` | `/api/ingest/resume` |
| `InternalIngestCallbackController` | `/api/internal/ingest` |
| `ExternalSourceIngestController` | `/api/documents` (ingest sub-paths) |
| `IndexerController` | `/api/indexer` |
| `IndexingAliasController` | `/api/indexing` |
| `IndexingJobHistoryController` | `/api/indexing/history` |
| `JobLogController` | `/api/indexing/jobs` |
| `ScheduleController` | `/api/schedules` |
| `ConfluenceController` | `/api/confluence` |
| `EmailValueExtractionController` | `/api/email/extract-values` |
| `ChunkManagerController` | `/api/chunk-manager` |
| `CrossIndexController` | `/api/cross-index` |
| `VectorPopulationController` | `/api/vector-population` |
| `EnrichmentAgentLabelController` | `/api/enrichment` |

## graph — `kompile-app-web-graph` (2)

Read-only exploration only. Graph *maintenance* — rules, health, versioning, OWL reasoning, the
simulator, eval — stays admin.

| Controller | Base path |
|---|---|
| `GraphAggregateController` | `/api/graph` |
| `GraphForecastController` | `/api/graph` |

## admin — `kompile-app-web-admin` (85)

Everything else. Mounted only by `kompile-app-main`.

`ApiAgentConfigController` `/api/agents/api-config` ·
`AutoConfigureController` `/api/auto-configure` ·
`BackupController` `/api/backup` ·
`BatchSizeConfigController` `/api/embeddings/batch-config` ·
`BuildAppController` `/api/build` ·
`CliLlmConfigController` `/api/agents/cli-config` ·
`ComprehensiveDiagnosticsController` `/api/diagnostics` ·
`ConfigArchiveController` `/api/config-archives` ·
`ContextualRagConfigController` `/api/contextual-rag` ·
`DeviceRoutingController` `/api/device-routing` ·
`DiffIndexController` `/api/diff-index` ·
`DiffPolicyController` `/api/diff-policy` ·
`DocumentDebuggerController` `/api/documents/debug` ·
`DocumentIngestDebugController` `/api/documents` (debug sub-paths) ·
`EmbeddingRestartController` `/api/embedding-restart` ·
`EnforcerSessionController` `/api/enforcer` ·
`EnvironmentConfigController` `/api/environment` ·
`EvalDebuggerController` `/api/eval-debugger` ·
`EvaluationConfigController` `/api/evaluation` ·
`ExperimentController` `/api/experiments` ·
`ExternalMcpServerController` `/api/mcp` ·
`FilterChainConfigController` `/api/filterchain` ·
`GitDiffController` `/api/git` ·
`GpuLifecycleController` `/api/gpu-lifecycle` ·
`GraphEvalController` `/api/graph-eval` ·
`GraphSimulatorController` `/api/graph-sim` ·
`GuardrailsConfigController` `/api/guardrails` ·
`InstallManagerController` `/api/install` ·
`KarchArchiveController` `/api/archives` ·
`KbConfigController` `/api/kb-config` ·
`KbWeightsController` `/api/kb/weights` ·
`BatchVerifyController` `/api/kb/verify` ·
`LifecycleTrackingController` `/api/lifecycle` ·
`LlmPipelineConfigController` `/api/llm/config` ·
`LogConfigController` `/api/config/logs` ·
`ManagedEvalController` `/api/eval-sets` ·
`McpActionLogController` `/api/mcp/action-log` ·
`McpCliInjectionController` `/api/mcp/cli-injection` ·
`McpClientController` `/api/mcp/client` ·
`McpOptimizationController` `/api/config/mcp-optimization` ·
`McpServerBuilderController` `/api/mcp/servers` ·
`McpSseController` `/mcp` ·
`McpToolController` `/api/mcp/tools` ·
`MemoryPoolController` `/api/memory-pools` ·
`ModelAdmissionRestController` `/api/model-admission` ·
`ModelFallbackController` `/api/model-fallback` ·
`ModelRegistryController` `/api/models` (registry sub-paths) ·
`ModelSchedulerController` `/api/model-scheduler` ·
`ModelWarmupController` `/api/model-warmup` ·
`ModelWeightCacheController` `/api/weight-cache` ·
`MonitorController` `/api/monitor` ·
`MultiBackendTestController` `/api/multi-backend` ·
`Nd4jEnvironmentController` `/api/nd4j/environment` ·
`Nd4jProfilingController` `/api/models` (nd4j sub-paths) ·
`OntologyConformanceController` `/api/process/ontology` ·
`OntologyDerivationController` `/api/process/ontology` ·
`OpTimingController` `/api/op-timing` ·
`OwlReasoningController` `/api/graph-ontology` ·
`ProcessDiagramController` `/api/process/diagrams` ·
`ProcessMiningConfigController` `/api/process-mining-config` ·
`ProcessNarrationController` `/api/process/narration` ·
`ProcessSynthesisController` `/api/process/synthesis` ·
`ProcessWritebackController` `/api/process/writeback` ·
`ProcessingSettingsAliasController` `/api/settings/processing`, `/api/processing-settings` ·
`ProcessingSettingsController` `/api/processing` ·
`QueryTransformerConfigController` `/api/query-transformer` ·
`RagTestController` `/api/rag/test` ·
`ReActAgentConfigController` `/api/react-agent` ·
`ResourceSchedulerController` `/api/scheduler` ·
`RestMcpBridgeController` `/api/mcp/bridges` ·
`RetrieverController` `/api/retriever` ·
`SamediffBenchmarkController` `/api/benchmark` ·
`SdxServingController` `/api/sdx` ·
`StagingConfigController` `/api/staging-config` ·
`SubprocessConfigController` `/api/subprocess-config` ·
`SubprocessEventHistoryController` `/api/subprocess-events` ·
`TestMilestoneController` `/api/test-milestones` ·
`ToolDefinitionController` `/api/tools` ·
`ToolGatewayConfigController` `/api/tool-gateway` ·
`ToolPermissionController` `/api/tool-permissions` ·
`TritonCacheController` `/api/triton-cache` ·
`VlmModelController` `/api/vlm` ·
`VlmOrchestrationController` `/api/vlm-orchestration` ·
`VlmPipelineConfigController` `/api/vlm/config` ·
`VlmTestWorkflowController` `/api/vlm/test`

## Shared base paths that split across personas

These need sub-path-level care — the class-level `@RequestMapping` is the same but the persona is not.
They coexist safely because Spring matches on the full path.

| Base path | shared | chat | crawl | admin |
|---|---|---|---|---|
| `/api/models` | `ModelStatusController`, `ModelDiscoveryController` | — | — | `ModelRegistryController`, `Nd4jProfilingController` |
| `/api/documents` | `DocumentManagementController`, `DocumentUploadController` | — | `ExternalSourceIngestController` | `DocumentDebuggerController`, `DocumentIngestDebugController` |
| `/api/system` | `SystemResourceController`, `SystemInfoController` | — | — | — |
| `/api/agents` | — | `AgentDiagnosticController`, `AgentChatController`, `AgentRuntimeStatusController`, `CliAgentModelController`, `KompileLocalModelController`, `PassthroughChatController` | — | `ApiAgentConfigController`, `CliLlmConfigController` |
| `/api/cluster` | — | — | `CrawlClusterController`, `ClusterJobController` | — |
| `/api/graph` | — | — | `GraphExtractionModelController`, `GraphHydrationController` | — |
| `/api/graph` (graph module) | — | `GraphAggregateController`, `GraphForecastController` | `GraphAggregateController`, `GraphForecastController` | — |
| `/api/indexing` | — | — | `IndexingAliasController`, `IndexingJobHistoryController`, `JobLogController` | — |
| `/api/process/ontology` | — | — | — | `OntologyConformanceController`, `OntologyDerivationController` |
| `/api/rag` | — | `RagController` | — | `RagTestController` |

`/api/cluster` is split by method path, not by class: `ClusterJobController` owns `/jobs*` and
`CrawlClusterController` owns `/workers*`, `/capabilities`, `/local/drain`. Per
`docs/architecture/distributed-crawl-cluster.md:141-147` both are worker-facing halves of the same
distributed-crawl protocol, so **both go to crawl** — filing `ClusterJobController` under admin would
break job delegation once `kompile-app-main` stops mounting crawl APIs. Neither is called from the
frontend.

### Splits the web modules cannot fence

The table above splits paths between *web* modules, which the persona poms control. A second kind of
split is not controllable that way: **library** modules carry their own `@RestController`s, and those
modules are dependencies of business logic all three personas need. Their endpoints mount on all
three ports by construction — `/api/knowledge-graph` and `/api/process` answer 200 on :8080, :8081
and :8082 alike.

That is normally invisible, because library paths do not collide with persona ones. Two do, and both
were found by the boundary tests rather than by reading the poms:

| Path | Persona-module owner | Library owner | Kind |
|---|---|---|---|
| `/api/kb-grounding/{factSheetId}` | — (parent `/api/kb-grounding` is `KbGroundingController`, web-chat) | `KbGroundingAuditController`, `KbOpinionBrowserController` (`kompile-knowledge-graph`) | nests under a chat path |
| `/api/enrichment` | `EnrichmentAgentLabelController` (web-crawl) | `DataEnrichmentController` (`kompile-data-enrichment`) | exact collision |

The consequence for `/api/enrichment` is worth stating plainly: the base path is on all three ports
with a different method set on each, so it cannot mark the crawl persona no matter how the test is
written. Moving the crawl half to its own base path is the only change that would alter that. Both
paths are declared in `PersonaSurfaces.LIBRARY_OVERLAPS` so the tests fence what they can and say out
loud what they cannot.

### The library surface, fully enumerated

Collisions are the visible tip. Counting every base path the admin console mounts turns up **170**
`/api/*` families, of which only **89 are admin-only** and **26 are the shared surface**. The
remaining **53 are library-owned and belong to no persona** — they are in
`PersonaSurfaces.LIBRARY_UNSCOPED`, and the name is chosen over "shared" on purpose. `SHARED` is a
decision each persona test re-asserts; these are a consequence of the dependency graph that nothing
re-evaluates when a pom changes.

41 of the 53 land on all three ports, which is unremarkable — chat history, orchestrator, code index,
OCR, OAuth, the knowledge-graph read surface. **12 do not**, and those splits are arbitrary rather
than designed:

| Library module | Paths | :8081 chat | :8082 crawl | :8080 admin |
|---|---|:--:|:--:|:--:|
| `kompile-graph-change-tracking` | `/api/graph/changes`, `/api/graph/hooks`, `/api/graph/pipelines`, `/api/graph/rules` | ✅ | — | ✅ |
| `kompile-kclaw` | `/api/kclaw`, `/api/kclaw/channels`, `/api/kclaw/oauth`, `/api/kclaw/tasks` | — | ✅ | ✅ |
| `kompile-compute-graph-core` | `/api/compute-graph`, `/api/workflows` | — | ✅ | ✅ |
| `kompile-process-discovery` | `/api/process/discovery`, `/api/process/mining` | — | ✅ | ✅ |

Live probe, not inference: `/api/kclaw/tasks` returns 200 on :8080 and :8082 and 404 on :8081. Two of
these read wrong on their face — graph *change tracking* is off the one persona that writes to the
graph, and the agent-task gateway with its OAuth surface is on the crawl manager, which has no agent
UI. Neither is a leak the tests can call, because neither path is admin-only; both are consequences of
which library each web module happened to pull in. Fixing one means moving the controller into a
persona web module, which takes it out of `LIBRARY_UNSCOPED`.

Eight further library paths *are* admin-only today and are filed in `PersonaSurfaces.ADMIN` for that
reason, not because they live in `kompile-app-web-admin`: `/api/a2a`, `/api/events/observation`,
`/api/metrics`, `/api/pipelines`, `/api/process/attribution`, `/api/process/lineage`,
`/api/rag-pipelines`, `/api/samediff-llm`. `/api/rag-pipelines` is the one that motivated the
completeness check below — it was mounted on :8080 alone and classified nowhere, so adding
`kompile-rag-pipeline` to chat's pom would have exposed pipeline create and delete on :8081 with every
boundary test still green. (`/api/metrics` is `@ConditionalOnBean(MeterRegistry.class)` and registers
on no port today; the classpath scan sees the class, Spring declines the bean.)

## Boundary tests

`kompile-app-web-shared` publishes a test-jar carrying `PersonaApiSurface` (the classpath scan) and
`PersonaSurfaces` (which module owns which base path). Each app consumes it and asserts its own
surface:

| Test | Module |
|---|---|
| `MainPersonaBoundaryTest` | `kompile-app-main` |
| `ChatPersonaBoundaryTest` | `kompile-app-chat` |
| `CrawlManagerPersonaBoundaryTest` | `kompile-app-crawl-manager` |

```bash
$MVN -pl :kompile-app-main,:kompile-app-chat,:kompile-app-crawl-manager \
     -Dtest='*PersonaBoundaryTest' test
```

They scan `ai.kompile` with Spring's own `ClassPathScanningCandidateComponentProvider` rather than
booting a context. All three apps declare `@SpringBootApplication(scanBasePackages = "ai.kompile")`
and every web module shares the package `ai.kompile.app.web.controllers`, so reproducing that scan
gives exactly the controller set the app will register — while a `@SpringBootTest` would cost a 30-50s
refresh dragging in JPA, Lucene, ND4J and the model runtime, none of which affects which controllers
are present.

Each test asserts in both directions: the families it must serve are present, and the other personas'
families are absent. The absence half is the load-bearing one. There is no Spring Security anywhere in
`kompile-app-parent`, so anything a process mounts is reachable by anyone who can reach its port — a
missing chat API is a visibly broken app, while a stray admin API is an invisible hole.

### The completeness assertion

Both directions above are still blind to a path that is listed nowhere. Chat and the crawl manager
fence themselves by subtracting `PersonaSurfaces.ADMIN`, so an admin path missing from that set is a
path their forbidden assertions cannot see — which is exactly how `/api/rag-pipelines` sat unfenced.
`MainPersonaBoundaryTest.classifiesEverythingItMounts` closes it: every `/api/*` base path the admin
console mounts must appear in `ADMIN`, `SHARED`, `LIBRARY_UNSCOPED` or `LIBRARY_OVERLAPS`. A new
controller cannot be added without saying which persona owns its path.

Membership is matched **exactly**, unlike the required and forbidden assertions, which match on
segment boundaries. Under prefix matching a listed family would silently adopt anything mounted
beneath it, so a new admin-only `/api/process/secrets` would be waved through by `/api/process`
already being classified as library-unscoped — the same hole one segment deeper. The cost is one
entry per endpoint family; the return is a decision per endpoint family.

## Shipping the three processes

A dist has to carry all three, because the split means no single process serves everything: a bundle
with only `kompile-server` in it has no chat and no crawl manager.

| Artifact | Chat | Crawl manager |
|---|---|---|
| Exec jar | `lib/kompile-chat.jar` | `lib/kompile-crawl-manager.jar` |
| Launcher | `bin/kompile-chat.sh` | `bin/kompile-crawl-manager.sh` |
| JBang alias | `kompile-chat` | `kompile-crawl-manager` |
| Port / env | 8081, `KOMPILE_CHAT_PORT` | 8082, `KOMPILE_CRAWL_PORT` |
| Heap env | `KOMPILE_CHAT_HEAP` | `KOMPILE_CRAWL_HEAP` |

Both apps follow the `kompile-model-staging` shape rather than app-main's: the `-exec.jar` classifier
is produced on every build, so no `-Dkompile.uber` is needed. The env vars use the service id (`chat`,
`crawl`) so they line up with the `server.port` placeholders, `KompileService` in the CLI router, and
the `chatHeap` / `crawlHeap` keys in `project-runtime.json`; only the artifact and script are named
"crawl-manager". The launchers accept a heap as either `8g` or `-Xmx8g`, because the JSON config uses
the bare form and `java 8g` is a confusing failure.

There are **two** dist builders and they must agree: `build-dist.sh` (hand-rolled) and
`kompile-dist/src/main/assembly/dist.xml` (Maven assembly, plus the `copy-persona-exec-jars`
dependency-plugin execution that stages the jars into `target/persona-deps`). Neither persona declares
a native-maven-plugin profile today, so both ship the exec jar. When one is added, set `imageName` to
the **dist** name (`kompile-chat`), not the artifactId: an assembly fileSet cannot rename, so a binary
built as `kompile-app-chat` would land under one name via the assembly and another via `build-dist.sh`,
and the launcher would find it on only one of the two paths.

## Support closure (what moves with the controllers)

Each persona's controllers were expanded to a fixpoint over the top-level types still resident in
`kompile-app-main`, counting three reference forms — explicit imports, bare same-package references,
and fully-qualified names — with `//` **and** `/* */` comments stripped. Stripping block comments
matters: `DocumentManagementController` javadoc-`{@link}`s three sibling controllers across persona
lines, and counting those inflated the closures by 33 types (admin alone went 138 → 105).

| Persona | Controllers | Support types | Shape of the support set |
|---|---|---|---|
| shared | 25 | 20 | `app/project` (5), `app/projectstore` (4), `app/staging/*` (4), `app/services` (4), `app/scaffold` (2), `app/subprocess/model` (1) |
| chat | 18 | 29 | 24 are request/response records already colocated in `web/controllers/{explain,grounding}`; 5 real services |
| crawl | 24 | 14 | 13 are `web/dto/crossindex` records; 1 is a cross-persona controller reference |
| graph | 2 | 0 | self-contained |
| admin | 85 | 105 | the remainder |

154 controllers parsed, matching each section's declared count. Every name resolves to a real file;
153 carry `@RestController`/`@Controller`. The 154th, `ModelDebugController`, is an endpoint-less stub
whose javadoc claims it is "retained to avoid breaking any compile-time references" — there are none.
It mounts nothing wherever it lands; it is a deletion candidate, not a boundary concern.

### Cross-persona edges

Exactly **one** controller→controller edge crosses a persona line:

```
crawl:ExternalSourceIngestController  ->  shared:DocumentManagementController.DocumentProcessingResult
```

Six call sites, all using that nested record. `web-shared` is on every app's classpath, so the edge
points downward and is legal as-is — but a nested type inside a controller is a poor shared contract,
so lift `DocumentProcessingResult` into a `web-shared` DTO when the controllers move.

Ten support types are needed by two personas each. Eight are **admin+shared** (`ProjectResponse`,
`ProjectBackendService`, `ProjectGraphPortabilityService`, `EmbeddingStatusBroadcaster`,
`StagingClientService`, `StagingServiceConfig`, `StagingServiceConfigRepository`,
`StagingServiceConfigService`) and resolve for free — admin depends on `web-shared`.

The remaining two are **admin+chat** (`ExplainOrchestrator`, `PromptTemplateService`), which would be
a genuine problem — admin must never depend on `web-chat`, or app-main re-mounts the chat API. It is
not a problem: no admin *controller* touches them. They are reached only through `KbVerifyExplainTool`
and `PromptTemplateTool`, and both tools follow their controllers to the chat app. `PromptTemplateTool`
was not in the original tool-split list; it belongs with chat for the same reason `KbVerifyExplainTool`
does — `PromptTemplateController` is a chat controller.

### MCP tools → controllers

No controller references any MCP tool; the coupling runs the other way. Every reference is a
constructor parameter annotated `@Autowired(required = false)`, so a tool already degrades gracefully
when its controller bean is absent — but the compile-time edge still dictates module placement.

| Tool | Controllers it injects | Placement |
|---|---|---|
| `GraphConfigTool` | crawl | crawl |
| `IntegrationsTool` | crawl | crawl |
| `IndexManagementTool` | crawl ×2, shared ×1 | crawl (shared is mounted everywhere) |
| `SourceManagementTool` | shared ×2, admin ×1 | admin |
| `SystemConfigTool` | admin ×3, shared ×1 | admin |
| `AgentConfigTool` | admin ×2, **chat ×1** | **split** — `SystemPromptController` half → chat |
| `JobHistoryTool` | crawl ×3, **admin ×1** | **split** — `SubprocessEventHistoryController` half → admin |
| `ArchiveTool`, `EvalDebugTool`, `McpServerTool`, `ModelRegistryTool`, `RagTestTool`, `VlmPipelineTool`, `VlmTestTool` | admin only | admin |

Two tools straddle the boundary and must be split, not moved. `JobHistoryTool` is the newly found one:
three of its four injections are crawl, but `SubprocessEventHistoryController` is admin.

#### The registries were the blocker (resolved)

`McpToolRegistry` declared ~75 typed `@Autowired(required = false)` fields and named them all in
`collectToolBeans()`, so it hard-referenced every tool class in the build. Measured on the web-admin
closure, it was the **sole** reason all 49 remaining tools were pulled in: carving web-admin without
fixing it would have dragged the entire MCP surface into the admin module and reversed decision 8.
`McpSseServerConfiguration` held a second copy of the same list for Spring AI's
`ToolCallbackProvider`.

Both now call `McpToolBeanDiscovery.discoverToolBeans(ApplicationContext)` (kompile-app-mcp), which
returns every bean whose user class declares a Spring AI `@Tool` method. A tool is exposed exactly
when it is a bean on that app's classpath — the same "absent means not registered" behaviour the
optional injection gave, with no compile-time edge. After the change the admin closure dropped from
168 types to 117 and **all 55 tools became free to move** (0 remained pinned).

Two behaviour changes came out of it, both deliberate:

- **The SSE list had drifted.** It named ~34 tools against the stdio registry's 68, so an SSE client
  saw roughly half the tools a stdio client saw against the same server. Discovery makes the two
  paths agree by construction.
- **16 tool classes were built but never registered anywhere** and are now exposed:
  `A2ADelegationTool`, `BusinessRulesTool`, `CamelRouteTool`, `ComputeGraphTool`,
  `ConfigArchiveMcpTool`, `CrawlerToolImpl`, `EntityResolutionTool`, `ExcelComputeTool`,
  `ModelStagingTool`, `ProcessAttributionTool`, `SdxInferenceTool`, `ServingInfrastructureTool`,
  `TestMilestoneTool`, `ToolCallCatalogMcpTool`, `UnifiedCrawlGraphTool`, `WorkflowTool`.
  That is the point of discovery — a tool on the classpath is a tool the app offers.

##### The old exclusion list, and why it does not survive

`AllToolBeansRegisteredSweepTest` carried a `KNOWN_EXCLUSIONS` map naming those sixteen with reasons,
which reads as deliberate policy. It splits in two on inspection:

- **Six were classpath facts, not policy.** `A2ADelegationTool`, `BusinessRulesTool`, `CamelRouteTool`,
  `ExcelComputeTool`, `ModelStagingTool` and `WorkflowTool` live in modules that are not compile
  dependencies of `kompile-app-main` ("optional module" in the map). Discovery reproduces that exactly:
  absent from the classpath, absent from the surface. Nothing changes for app-main; an app that *does*
  depend on those modules now gets their tools, which is the correct reading of "optional".
- **Ten were annotated `@Component` + `@Tool` and are now live** — `ComputeGraphTool`,
  `ConfigArchiveMcpTool`, `CrawlerToolImpl`, `EntityResolutionTool`, `ProcessAttributionTool`,
  `SdxInferenceTool`, `ServingInfrastructureTool`, `TestMilestoneTool`, `ToolCallCatalogMcpTool`,
  `UnifiedCrawlGraphTool`. Their stated reasons ("internal", "managed through the crawl pipeline",
  "exposed via a dedicated CLI command") describe how the tool is *usually* reached, not a decision to
  withhold it: a class annotated `@Component` + `@Tool` has already declared itself an MCP tool, and
  nothing but the absent registry field kept it dark. Exposing them matches the registry's own contract
  ("all MCP Tools exposed by Kompile") and the ship-features-enabled rule. If any one of them should
  genuinely be withheld, the mechanism is to drop its `@Tool` annotations — not to omit a field and
  leave the annotation lying.

**`VlmConfigTool` was deleted.** It declared `list_vlm_pipelines`, `get_vlm_pipeline` and
`list_vlm_stages` — the same three names as `VlmPipelineTool` — and both were registered, so the stdio
registry has been resolving the collision by map-overwrite, non-deterministically, for as long as both
existed. Spring AI's `MethodToolCallbackProvider` does not tolerate that: with the SSE path now
discovering both, it is a startup failure. `VlmConfigTool` was the one to go — it reached
`VlmPipelineRegistry.getInstance()` directly rather than through a controller, and `VlmPipelineTool`
covers all four of its operations against the same singleton. The one name lost is
`remove_vlm_pipeline`; `delete_vlm_pipeline` on `VlmPipelineTool` calls the identical
`registry.deletePipeline(...)`.

`McpToolRegistryParityTest` and `AllToolBeansRegisteredSweepTest` asserted the old field lists and were
replaced by `McpToolDiscoveryContractTest`, which asserts what discovery actually depends on: every
`@Tool` class carries a Spring stereotype, no two tool names collide (Spring AI rejects duplicates at
startup), and neither registry has grown a per-tool field again. The collision above is precisely what
that second assertion is for — it was found by the test, not by a boot failure in the field.

### Reference closure does not see component-scan beans

299 of app-main's 358 types are reachable from some controller. The other 59 are reached by Spring, not
by a reference: `MainApplication`, 15 `@Configuration` classes, the SPI implementations
(`*CallbackImpl`, `*Bridge`, `*Hook`), the subprocess launchers, 2 diagnostics, and 6 MCP tools with no
controller injection. Almost all are app-level wiring that correctly stays in `kompile-app-main`.

Two consequences for the move:

- **`GlobalExceptionHandler` belongs in `web-shared`.** Nothing references it, so the closure misses it
  entirely. Because packages are preserved, its `@ControllerAdvice(basePackages = {...})` list keeps
  matching without edits — but the bean is only registered by the module that ships it. Left in
  `kompile-app-main`, the chat and crawl apps boot with no advice at all once app-main goes admin-only,
  and return opaque 500s instead of `{error, message, type}`. It moves with the shared controllers.
- **Runtime-only beans need their own pass.** `@Service` beans such as `CrawlProfileAutoStartService`,
  `SourceSyncGraphMaintenanceService`, and `CrossIndexIdResolverImpl` are event listeners and SPI
  implementations that no controller names. The optional-injection guards make a missing one non-fatal,
  which is exactly the failure mode to avoid: the crawl app would boot and quietly skip work. Placement
  for these is decided per persona from runtime wiring, not from the closure.

## Enforcement

Shipped, and described in full under [Boundary tests](#boundary-tests). Each app carries a
`*PersonaBoundaryTest` asserting the mounted path set against this document, failing on **extra**
mappings as well as missing ones. A widened `scanBasePackages` or a new controller dropped into the
wrong module breaks the build rather than silently re-exposing an admin API to end users.

Two deliberate deviations from the original sketch:

- **Classpath scan, not `@SpringBootTest`.** The plan was to read
  `RequestMappingHandlerMapping.getHandlerMethods()` from a live context, reusing the traversal in
  `ai.kompile.app.diagnostics.RequestMappingDiagnostic`. Booting costs 30-50s per app and drags in
  JPA, Lucene, ND4J and the model runtime, none of which affects which controllers are present, so
  `PersonaApiSurface` reproduces Spring's component scan instead. The one thing this cannot see is a
  `@Conditional` guard that declines a bean at runtime — it over-reports rather than under-reports,
  which is the safe direction for a fence. `/api/metrics` is the live example.
- **"Extra mappings" means unclassified, not un-allowlisted.** Asserting an exact path-set equality
  per app would have meant three parallel allowlists drifting against each other. Instead the admin
  console — the only app that mounts a superset — asserts that every family it mounts is classified
  somewhere, and the other two subtract those classifications. One list, three consumers.
