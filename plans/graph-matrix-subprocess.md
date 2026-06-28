# Move the matrix graph store to a managed subprocess

## Why (evidence, 2026-06-26)
Main app OOM'd at `-Xmx32g`: `jstat` showed Old=99.97%, 127 full GCs, then `OutOfMemoryError`.
Root cause = the in-heap `AdjacencyMatrixGraph` (17,059 nodes / 1.27M edges) rehydrated into the
**main** heap by `GraphRehydrationListener` -> `VectorStoreMatrixGraphStore.rehydrateGraphsOnStartup()`.
Lucene is already off-heap (mmap, verified). The bloat is Java-object overhead, NOT raw numbers:
`adjacencyData`, `relationTypeData`, `edgeMetaData`, `reverseIndex` are four parallel
`Map<String,Map<Integer,Map<Integer,…>>>` over 1.27M edges (boxed Integer/Float + HashMap.Node), plus
per-edge `String` descriptions (703k clique edges each carry "Name-based cross-doc resolution: …").
~24GB at rest. An `INDArray` alone can't fix the String payloads -> the whole subsystem must move.

## Decision (user, confirmed twice)
Move the matrix subsystem into a **managed subprocess**; main app = thin client. Matrix never in main.
Seam = **service-layer**, NOT a transparent 32-method `MatrixGraphStore` proxy, because
`loadGraph`/`createGraph` RETURN `AdjacencyMatrixGraph` (a transparent proxy would re-ship 1.27M edges
into main on every call) and 3 methods pass nd4j `INDArray`. The matrix-returning + INDArray methods
become subprocess-internal; the client exposes result-returning ops only.

## Blast radius (contained)
All `AdjacencyMatrixGraph`-touching classes are in ONE module, `kompile-knowledge-graph`:
`VectorStoreMatrixGraphStore`, `MatrixGraphConstructor`, `MatrixGraphAlgorithms`, `MatrixGraphRagService`,
`MatrixKgEmbeddingGraphAdapter`, `MatrixKnowledgeGraphService` (13 files). External callers are few:
`GraphOntologyBindingService` (app-main), `NamedGraphController`, `NamedGraphTool`, the crawl
(`GraphExtractionOrchestrator`), RAG.

## Architecture
- **Subprocess** `GraphMatrixSubprocessMain` (app-main/subprocess): boot a Spring context with the REAL
  matrix beans + the anserini vector store (same data dir/config as main) + run rehydration HERE; expose
  a JDK `com.sun.net.httpserver.HttpServer` `POST /invoke` -> reflective dispatch on the real
  `MatrixGraphStore`/services. `loadGraph`/`createGraph` return a lightweight ack (graphId + counts), never
  the matrix. `INDArray` <-> `float[][]`/base64 on the wire.
- **Launcher** `GraphMatrixSubprocessLauncher` extends `ManagedSubprocessLauncher` (id `graph-matrix`,
  mainClass `GraphMatrixSubprocessMain`, heap configurable e.g. 48g — the matrix lives here). Base gives
  launch/supervise/native-cap/live-logs/restart for free. Wire into MainApplication `--subprocess=` dispatch.
- **Client** (main app): `@Primary` `MatrixGraphStore` (+ the delegated services) become JDK `HttpClient`
  delegates -> subprocess. JSON DTOs. No nd4j matrix in main.
- **Toggle** `kompile.graph.subprocess.enabled` (managed config, NOT @Value). On -> client+launcher, and
  `GraphRehydrationListener` is GATED in main (rehydration happens in the subprocess). Off -> current
  in-process behavior (dev/small graphs).

## Phases
1. Launcher + `GraphMatrixSubprocessMain` skeleton (boot matrix subsystem + rehydrate + HttpServer /invoke). Compile + boot.
2. Client delegate + DTOs + INDArray encoding + matrix-return handling. Compile.
3. Wiring + toggle + gate main rehydration + refactor the few external callers. Compile.
4. Integrated build + deploy + verify.

## Invariant to verify (objective)
After deploy with the toggle ON: `jmap -histo` on the MAIN pid shows ~0 `AdjacencyMatrixGraph` /
`MatrixGraphNode` instances; main Old-gen stays low; the FP&A crawl writes + RAG reads succeed through
the subprocess; the matrix lives only in the `graph-matrix` subprocess (its OOM is contained, app survives).

## CORRECTION (2026-06-26, proven by code review) — store-delegate does NOT fit; use SERVICE-layer
Code review after the Phase 2-3 store-delegate build found the seam is wrong. EVERY matrix service
mutates the loaded graph OBJECT, never leaf store ops: `MatrixGraphConstructor` = 6 `graph.addNode/addEdge`,
0 `store.*`; `MatrixKnowledgeGraphService` = 13 / 0; all do `createGraph()/loadGraph()` → mutate object →
`saveGraph()`. `MatrixGraphRagService` does `loadGraph()` → PPR over the object. So a store client that
returns empty `AdjacencyMatrixGraph` shells means the crawl mutates a throwaway in main and builds NOTHING,
and PPR over a delegated store would be per-node HTTP (unusable). => The services must run WHERE the matrix
is (the subprocess); main delegates at the SERVICE layer.

NEW seam: subprocess hosts the matrix + the services (already component-scanned by SubprocessGraphConfiguration);
expose service ops over the existing `/invoke` HttpServer; main gets HTTP-client delegates for
`MatrixKnowledgeGraphService` (crawl write + node/edge/stats) and `MatrixGraphRagService` (PPR/retrieve).
REUSE from the store-delegate build: GraphMatrixSubprocessMain (boot+rehydrate+HttpServer+reflective dispatch),
GraphMatrixSubprocessLauncher, the gate on GraphRehydrationListener, the data-dir propagation, INDArray codec.
The store client (`SubprocessMatrixGraphStore`) becomes unused on the main path (subprocess services use the
real store locally). Bug already fixed during review: subprocess had `eager-rehydration-enabled=false` →
rehydrate no-op'd (would load empty); now true.

## Deploy debug (2026-06-26) — three boot failures found by ACTUALLY running it (subagents never booted it)
Built service-layer (subagent), deployed at 20g + `--kompile.graph.subprocess.enabled=true` (in run-cpu.sh). No OOM ever. Failures, in order:
1. **@Primary conflict**: two @Primary `KnowledgeGraphService` beans (`matrixKnowledgeGraphService` + my `knowledgeGraphServiceSubprocessProxy`). First tried @ConditionalOnProperty-gating the 3 real Matrix impls (havingValue=false matchIfMissing=true) — compiled but caused #2.
2. **Main-context AOP destabilization**: `graphMutationRecordingListener.setGroundingCascadeHook` wanted the concrete `GroundingCascadeHook` but got a JDK `$Proxy` (despite `@EnableAsync(proxyTargetClass=true)` in AppConfig). The @ConditionalOnProperty gating reordered conditional-bean eval and perturbed @Async/AOP proxying. FIX: reverted the gating; instead just **removed @Primary** from the 3 Matrix impls (`MatrixKnowledgeGraphService/Constructor/RagService` → plain @Service). Safe because the OTHER impls are conditional-off in matrix mode (KnowledgeGraphServiceImpl/JpaGraphRagService need `kompile.jpa.graph.enabled`; EventPublishing needs `knowledgeGraphDelegate`; Neo4j gated) → Matrix is the SOLE active impl, so @Primary was redundant. Toggle-off: Matrix sole bean. Toggle-on: proxy is sole @Primary.
3. **Subprocess can't boot the VectorStore**: `NoSuchBeanDefinitionException VectorStore` — it's a Spring Boot AUTO-CONFIG (`AnseriniVectorStoreAutoConfiguration`) which a bare AnnotationConfigApplicationContext never applies. FIX in progress (subagent): make the subprocess boot the auto-config (import it OR SpringApplication WebApplicationType.NONE) WITHOUT web/H2-datasource/nested-launchers (H2 lock is held by main). Lazy embedding OK (rehydration only reads).

GOTCHA: app daemon launch (`run-cpu.sh` nohup java) is blocked by the tool sandbox → use dangerouslyDisableSandbox for launches; foreground `sleep` blocked → background the poll.

## Follow-ups (reduce TOTAL ram, after the move isolates it)
- #28 collapse 703k clique -> star (built, deployed; run it in the subprocess).
- Optional: store adjacency as an mmap'd nd4j INDArray (CSR) inside the subprocess (user's idea) — numbers
  off-heap; Strings (descriptions/relationType) still need handling (intern / vector-store-backed).
