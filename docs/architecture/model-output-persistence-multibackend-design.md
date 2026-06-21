# Learned Model + Library File Output — Persistence & Multi-Backend Architecture

**Status:** DESIGN — 2026-06-21
**Scope:** Where PSL/MEBN learned weights, SameDiff embedding models (RotatE/SGNS), embedding
tables, type registries, inferred-fact/audit/pin JSONL, OWL Turtle, and calibration models
are persisted — across multiple pluggable backends, possibly via the kompile-model-staging server.
**Complements:**
- `fact-store-audit-tuning-correction-design.md` — audit/pin record structure; references
  `FileBackedInferredFactStore` and `WeightStore` as the anchor SPIs
- `graph-serialization-storage-audit.md` — H-4 documents the KGE2 binary sidecar
- Plans: `plans/humming-zooming-llama.md` — 9-phase graph-as-asset roadmap (phases 1-9 done)

---

## 0. Context and Motivation

The graph-reasoning library (`kompile-graph-reasoning`) is deliberately infra-free.
Every persistence primitive it provides emits to a caller-supplied `Path` or returns a `String`
that the caller writes to disk. The library never touches Spring, JPA, or network I/O.
Meanwhile, `kompile-model-staging` is a live standalone server with a full REST API
(`@RequestMapping("/api/staging")`) for staging, converting, versioning, and serving large binary
model files. The embedding training pipeline (`KGEmbeddingJobService`, `RotatELearner`,
`SameDiffEmbeddingTrainer`) has all the serialization machinery it needs in `SameDiffModelIO` but
no production Spring code wires those SPIs to any durable path. The PSL/MEBN weight learners have
`FileWeightStore` implemented but no `@Bean` declaration anywhere in the app. Both families are
silently transient today. This design fixes that.

---

## 1. JOB 1 — Inventory

### 1.1 kompile-model-staging Server

**Modules:**
- Server: `kompile-app/kompile-models/kompile-model-staging/`
- MCP tool wrapper: `kompile-app/kompile-middleware/kompile-tools/kompile-tool-model-staging/`

**What it is:** A dedicated Spring Boot model-artifact registry and staging service.
It is NOT a general key-value store. It is designed around large binary models (ONNX, `.sdz`,
SameDiff `.fb` FlatBuffers, GGUF) that are downloaded from HuggingFace, converted, optimized,
registered in a `ModelRegistry`, and promoted to ACTIVE status. The registry associates each
`ModelEntry` with one or more physical files in a directory rooted at
`${kompile.staging.models-dir}` (default `~/.kompile/models`).

**REST surface** (`StagingController.java:61`, `@RequestMapping("/api/staging")`):

| Category | Key endpoints |
|---|---|
| Registry CRUD | `GET/PUT/DELETE /api/staging/registry/model/{modelId}` |
| Staging pipeline | `POST /stage`, `POST /stage/catalog/{modelId}`, SSE `/models/{id}/stream` |
| Promotion | `POST /promote/{modelId}`, `POST /models/{modelId}/activate` |
| File serving | `GET /registry/model/{modelId}/download/model`, `/download/vocab`, `/download/file/{name}` |
| Archive bundle | `POST /export`, `POST /import` |
| Upload | `POST /upload`, `POST /upload-and-stage` |
| Format conversion | `POST /convert` (ONNX/TF/Keras → SameDiff) |
| Training (PEFT/distillation) | `POST /training/*` (via `TrainingService`) |

**Storage layout:**
```
~/.kompile/models/
  <modelId>/           ← staged + promoted model dir
    model.fb or model.onnx
    vocab.json
    config.json
    <shard files>
~/.kompile/training-jobs/
  <jobId>/             ← per-job training outputs
```
Storage root: `${kompile.staging.project-dir}` (project-local, empty = global `~/.kompile`).
Training root: `${kompile.staging.training-jobs-dir}` (default `~/.kompile/training-jobs`).
Relevant file: `TrainingService.java:51,54`.

**SameDiff integration:** Via reflection — `Class.forName("org.nd4j.autodiff.samediff.SameDiff")`
— with simulation fallback when ND4J is not on the classpath (`TrainingService.java:180-193`).
Checkpoint saving is logged but not materialised outside the reflection block (`TrainingService.java:311-313`).

**Verdict — is it the right home for learned KG models?**
YES, with a narrow scope: it is the right home for the **SameDiff `.fb` files** (RotatE/SGNS
embedding checkpoints) and for any PEFT/fine-tuned variants of an encoder model. It is **not**
the right home for small JSON artefacts (PSL weights, MEBN strengths, calibration JSON,
embedding tables, type registries) because its architecture targets large binary blobs with a
full download/staging lifecycle that is overkill for a 5 KB JSON file.

---

### 1.2 Library File-Output SPI Table

All SPIs live under `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/`.

| SPI | Class | What it writes | Format | Typical size | Current home | Gap |
|---|---|---|---|---|---|---|
| PSL rule weights | `learning/WeightStore.java:34` + `learning/FileWeightStore.java` | `<baseDir>/<programId>.v<N>.json` — monotonically versioned rule→weight map | Hand-rolled JSON | 1–50 KB | Nowhere (no `@Bean`; in-memory only in production) | No Spring wiring; no path convention |
| MEBN edge strengths | `learning/MebnWeightSerializer.java` | Returns `String`; caller writes `{ruleName→weight}` | Hand-rolled JSON | 1–20 KB | Nowhere | Caller must write; no path convention, no service |
| SGNS (Node2Vec) checkpoint | `embedding/learn/SameDiffModelIO.java:114` | Dir: `model.fb` (SameDiff FlatBuffers) + `mapping.json` | Binary + JSON | 10 MB – 2 GB | Nowhere in production (only in tests) | `KGEmbeddingJobService` writes to JPA byte[] only; `SameDiffModelIO.saveSgns` never called |
| RotatE checkpoint | `embedding/learn/SameDiffModelIO.java:249` | Dir: `model.fb` + `mapping.json` + optional `adam.json` | Binary + JSON | 10 MB – 2 GB | Nowhere in production | Same gap as SGNS |
| Embedding table | `embedding/learn/EmbeddingTableIO.java` | Returns `String`; caller writes `{dim, entityIds, vectors}` | Hand-rolled JSON | 1–200 MB | Nowhere | Pure in-memory; no path wiring |
| Type registry snapshot | `mebn/type/TypeRegistryIO.java` | Returns `String`; caller writes `{types:[...]}` | Hand-rolled JSON | < 1 MB | Nowhere | Pure in-memory |
| Inferred facts (latest) | `fol/InferredFactStore.java` + `knowledgegraph/grounding/FileBackedInferredFactStore.java` | `data/graph/inferred/factsheet-<id>-latest.jsonl` | JSONL | KB – 10 MB | FULLY WIRED via `KbGroundingService` | None — good |
| Inferred facts (history) | Same | `data/graph/inferred/factsheet-<id>-history.jsonl` | JSONL (append) | KB – 100 MB | FULLY WIRED | None — good |
| Audit log | `audit/FactAuditEvent.java` (new, per `fact-store-audit-tuning-correction-design.md`) | `data/graph/inferred/factsheet-<id>-audit.jsonl` | JSONL (append) | KB – 100 MB | NOT YET BUILT — design only | Entire SPI does not exist yet |
| PIN records | `fol/grounding/PinRecord.java` (new) | Lives in `factsheet-<id>-audit.jsonl` or separate pin file | JSONL | < 1 MB | NOT YET BUILT | Same |
| Bayesian calibration | — | CPTs derived per-request from live graph weights | None | — | Never persisted (recomputed on demand) | Full gap: no calibration model is ever saved |
| OWL Turtle | `io/format/TurtleGraphExporter.java` (Phase 9) | `<factSheetId>.ttl` via N-Triples + Turtle exporters | Text | KB – 50 MB | Written on demand via `GraphIOService` export endpoint | Not saved into the versioned data tree automatically |
| KG embedding vectors (JPA byte[]) | `knowledgegraph/io/GraphEmbeddingSidecar.java:74` | Binary sidecar `data/graph/<fsId>/embeddings.bin` | KGE2 binary | 1 MB – 500 MB | FULLY WIRED — travels on git-xet | None — good |
| Graph health snapshots | `maintenance/GraphHealthService.java` | `data/graph/health/<fsId>/<timestamp>.json` | JSON | < 1 MB each | FULLY WIRED | None — good |
| Graph snapshots | `maintenance/SnapshotManager.java` | `data/graph/snapshots/<id>.graph.json` | JSON | 1 MB – 500 MB | FULLY WIRED | None — good |

---

### 1.3 Current Persistence Scheme and Gaps

**What persists where today:**

```
~/.kompile/  (or ${kompile.data.dir})
  config/                                  ← configDirectory() — FIXED pattern
  data/
    graph/
      <factSheetId>/                       ← per-fact-sheet portable graph (Phase 1)
        embeddings.bin                     ← GraphEmbeddingSidecar KGE2 (git-xet)
      global.json
      inferred/
        factsheet-<id>-latest.jsonl        ← FileBackedInferredFactStore (WIRED)
        factsheet-<id>-history.jsonl       ← FileBackedInferredFactStore (WIRED)
      snapshots/
        <snapshotId>.graph.json            ← SnapshotManager (WIRED)
      health/
        <factSheetId>/<ts>.json            ← GraphHealthService (WIRED)
  models/                                  ← KompileHome.modelsDirectory()
  sessions/                                ← KompileHome.sessionsDirectory()

~/.kompile/models/<modelId>/               ← model-staging server (separate process)
~/.kompile/training-jobs/<jobId>/          ← training service outputs
```

**KNOWN BUG — KompileHome.dataDir():**
`KompileHome.dataDir()` (line 128) returns `new File(homeDirectory(), "data")`, using the
hardcoded `~/.kompile` root, NOT `resolvedHomeDirectory()`. Spring `@Value` injections correctly
pick up `${kompile.data.dir}` but static callers of `KompileHome.dataDir()` always write to
`~/.kompile/data`. All new persistence adapters must use Spring `@Value("${kompile.data.dir:}")`,
not `KompileHome.dataDir()`, mirroring the FIXED pattern in `FileBackedInferredFactStore` and
`SnapshotManager`.

**Dual-store @Primary:**
`MatrixKnowledgeGraphService` is `@Primary` (line 49).
`MatrixGraphRagService` and `MatrixGraphConstructor` are also `@Primary` (lines 59, 58).
JPA repositories are still injected directly by `SnapshotManager`, `GraphEmbeddingSidecar`, and
`ContradictionDetector` (which is dead on the matrix path — known gap from memory).

**Gaps summary:**
1. PSL/MEBN learned weights — `FileWeightStore` + `MebnWeightSerializer` implemented, never wired.
2. SameDiff embedding checkpoints — `SameDiffModelIO` implemented, `SameDiffModelIO.saveRotatE()` never called from production code; embeddings stored only as JPA `byte[]` rows (TransE/RotatE numbers), not as a reloadable SameDiff graph.
3. Embedding table + type registry — serializers return `String`; no caller writes them to disk.
4. Bayesian calibration — entirely absent; CPTs are recomputed per request.
5. Audit log + PIN records — not yet built (pending `fact-store-audit-tuning-correction-design.md`).

---

## 2. JOB 2 — Design Decisions

### 2.1 Artifact-Placement Matrix

| Artifact | Recommended backend | Rationale |
|---|---|---|
| **Inferred facts (latest + history JSONL)** | Versioned data tree (`data/graph/inferred/`) | ALREADY DONE. Small JSONL, text-diffable, travels on git clone. |
| **Audit log + PIN records JSONL** | Versioned data tree (`data/graph/inferred/factsheet-<id>-audit.jsonl`) | Same family as history.jsonl; same size profile; must travel with the graph for reproducible audits. |
| **PSL rule weights (JSON, versioned)** | Versioned data tree (`data/graph/reasoning/factsheet-<id>/psl-weights/`) | Small JSON (< 50 KB per version). Text-diffable. Monotonically versioned by `FileWeightStore`. HUMAN_CORRECTION labels travel with the data. Must co-locate with the fact sheet so a cloned project can resume inference with the same weights. |
| **MEBN edge strengths (JSON)** | Versioned data tree (`data/graph/reasoning/factsheet-<id>/mebn-weights.json`) | Same reasoning as PSL weights: small, text-diffable, must travel. |
| **Bayesian calibration (JSON)** | Versioned data tree (`data/graph/reasoning/factsheet-<id>/bayes-calibration.json`) | CPTs derived from the graph + possibly a few learned priors. If persisted (rather than recomputed), store alongside the graph so the derivation is reproducible. Small JSON. |
| **Type registry snapshot (JSON)** | Versioned data tree (`data/graph/reasoning/factsheet-<id>/type-registry.json`) | < 1 MB, structural metadata, travels on clone for reproducible reasoning. |
| **Embedding table (dense JSON, 1-200 MB)** | Git-xet sidecar OR model-staging (see Fork i) | Dense `double[][]`; too large for text-diff. Git-xet handles BLOBs natively. Alternative: model-staging + a small pointer file in the data tree. |
| **SameDiff SGNS/RotatE checkpoint (`model.fb`, 10 MB – 2 GB)** | kompile-model-staging server | This is exactly what the server was built for: large binary model artifacts with versioning, activation, and download endpoints. The checkpoint is registered as `modelType=kge_rotate` or `kge_sgns` in the `ModelEntry`. A small pointer file (`data/graph/reasoning/factsheet-<id>/kge-model-ref.json`) in the versioned tree records the `modelId + stagingUrl` so the pointer travels on clone. |
| **JPA KGE byte[] (TransE/RotatE vectors, 1-500 MB)** | GraphEmbeddingSidecar binary (`data/graph/<fsId>/embeddings.bin`) via git-xet | ALREADY DONE (Phase 1 + Phase 9). The sidecar format (KGE2 magic `0x4B474532`) is the canonical carrier for JPA structural embeddings traveling on clone. |
| **OWL Turtle export** | On-demand file, optionally copied to versioned tree | Already exportable on demand. Auto-snapshot on crawl-complete: `data/graph/<fsId>/ontology.ttl`. Size varies; not always needed. |
| **Graph health snapshots** | Versioned data tree (`data/graph/health/`) | ALREADY DONE. |

**Reasoning directory convention:**
```
data/graph/reasoning/<factSheetId>/
  psl-weights/
    <programId>.v1.json
    <programId>.v2.json
    ...
  mebn-weights.json
  bayes-calibration.json
  type-registry.json
  kge-model-ref.json          ← pointer to model-staging entry (modelId + url)
```

---

### 2.2 Infra-Free Seam Preserved — Client-Side Persistence Adapters

The library SPIs take a `Path` or return a `String`. No Spring code enters the lib.
The adapters live in `kompile-knowledge-graph` (the existing client module that already
implements `InferredFactStore` via `FileBackedInferredFactStore`).

**New adapter: `FileBackedWeightStore`** — a Spring `@Component` that wraps
`FileWeightStore` and injects a `kompile.data.dir`-rooted `baseDir`.

```
// kompile-knowledge-graph/.../grounding/FileBackedWeightStore.java
// @Component — wraps the infra-free FileWeightStore
@Component
public class FileBackedWeightStore implements WeightStore {

    private final FileWeightStore delegate;

    public FileBackedWeightStore(
            @Value("${kompile.data.dir:}") String dataDir) {
        // Mirror the FIXED pattern from FileBackedInferredFactStore
        Path base = dataDir.isBlank()
            ? Path.of(System.getProperty("user.home"), ".kompile")
            : Path.of(dataDir);
        this.delegate = new FileWeightStore(
            base.resolve("data/graph/reasoning"));
    }
    // delegate all WeightStore methods
}
```

**New service: `EmbeddingModelPersistenceService`** — a Spring `@Service` in
`kompile-knowledge-graph` that:
1. After `KGEmbeddingJobService` completes a RotatE/SGNS job, calls
   `SameDiffModelIO.saveRotatE(model, checkpointDir)`.
2. Registers the checkpoint with model-staging via `POST /api/staging/upload-and-stage`.
3. Writes `kge-model-ref.json` into `data/graph/reasoning/<factSheetId>/`.

**New service: `MebnWeightPersistenceAdapter`** — a Spring `@Component` that:
1. Receives the `String` from `MebnWeightSerializer.strengthsToJson()`.
2. Writes it to `data/graph/reasoning/<factSheetId>/mebn-weights.json`.

**Embedding table:** `EmbeddingTableIO.toJson(EmbeddingTable)` returns a `String` that the
embedding job completes — a new `EmbeddingTablePersistenceAdapter` writes it to
`data/graph/reasoning/<factSheetId>/embedding-table.json` if < 50 MB, else registers
it as a model-staging artifact and writes a pointer file.

**Calibration:** `BayesianNetworkService` currently recomputes from live graph weights on every
call (`BayesianNetworkService.java:51`). The calibration adapter writes the learned CPT JSON to
`data/graph/reasoning/<factSheetId>/bayes-calibration.json` after an explicit calibration run;
on the next call, if the file exists and the graph version matches (via the graph snapshot
`snapshotId` pointer), it is loaded rather than recomputed.

---

### 2.3 kompile-model-staging Integration for Learned KG Models

**Registration flow (write path):**
```
KGEmbeddingJobService.runJob()
  → RotatELearner.train() → TrainedRotatE
  → EmbeddingModelPersistenceService.persist(fsId, model)
      → SameDiffModelIO.saveRotatECheckpoint(model, tmpDir)       // lib SPI
      → POST /api/staging/upload-and-stage (multipart: model.fb)  // model-staging
      → model-staging assigns modelId = "kge-rotate-<fsId>-<ts>"
      → model-staging sets modelType = "kge_rotate"
      → POST /api/staging/promote/<modelId>                        // activate
      → write data/graph/reasoning/<fsId>/kge-model-ref.json:
          { "modelId": "kge-rotate-<fsId>-<ts>",
            "stagingUrl": "http://localhost:<port>/api/staging",
            "algorithm": "RotatE",
            "dim": 32,
            "snapshotId": "<graphSnapshotId>",
            "trainedAt": "<iso8601>",
            "origin": "LOCAL | HUMAN_CORRECTION" }
```

**Retrieval flow (read path — inference time):**
```
GraphPslProgramBuilder / PslReasoningService
  → reads kge-model-ref.json from data/graph/reasoning/<fsId>/
  → GET /api/staging/registry/model/{modelId}/download/model     // stream .fb
  → SameDiffModelIO.loadRotatE(tmpDir)                           // lib SPI
  → TrainedRotatE injected into link-prediction scorer
```

**Version lineage for HUMAN_CORRECTION weights:**
When a human correction fires `PslWeightLearningService.updateOnBatch()` and a new version
is saved to `FileWeightStore`, the `WeightStore.save()` call returns the new version number.
The `kge-model-ref.json` pointer and the PSL weight filename (`.v<N>.json`) both carry the
version number so the full lineage is auditable via the existing `factsheet-<id>-audit.jsonl`.
Model-staging `ModelEntry` metadata can carry `origin: HUMAN_CORRECTION` as a custom field.

---

### 2.4 Multi-Backend Strategy — No Hard @Primary for Persistence

The current dual-store setup uses `@Primary` on `MatrixKnowledgeGraphService` for the live
graph store. Persistence backends for learned models must NOT replicate this single-qualifier
pattern because the right backend depends on artifact size, lifecycle, and portability:

**Backend taxonomy:**

| Backend | Best for | Config trigger |
|---|---|---|
| Versioned data tree (file, git-tracked) | Small text/JSON artifacts (< 5 MB), must travel on clone | Default; always available |
| Git-xet sidecar (binary, large-file tracked) | Dense embedding vectors (JPA byte[]), must travel | `${kompile.kge.xet.enabled}=true` |
| kompile-model-staging server | Large SameDiff `.fb` checkpoints (10 MB – 2 GB), versioned/served | `${kompile.staging.url}` configured |
| JPA byte[] columns | In-graph embedding vectors for query-time similarity; not a persistence target for models | Always (via `@Primary` matrix path) |

**Plug-in contract — `ModelArtifactBackend` SPI (new, app-core):**
```java
// kompile-app-core/.../model/ModelArtifactBackend.java
public interface ModelArtifactBackend {
    boolean supports(ModelArtifactType type);
    void store(ModelArtifactRef ref, Path sourceDir) throws IOException;
    Path retrieve(ModelArtifactRef ref, Path targetDir) throws IOException;
    List<ModelArtifactRef> list(String factSheetId);
}
```
Implementations:
- `FileModelArtifactBackend` — writes/reads from versioned data tree (default; no config required)
- `GitXetModelArtifactBackend` — delegates to `GraphEmbeddingSidecar` pattern for large blobs
- `StagingModelArtifactBackend` — calls model-staging REST API; activated when `${kompile.staging.url}` is set

`EmbeddingModelPersistenceService` iterates registered backends in priority order (staging >
git-xet > file) and uses the first that `supports()` the artifact type. No `@Primary` qualifier;
registration order is explicit.

**Graph-as-asset extended to models:**
The `kge-model-ref.json` pointer file in `data/graph/reasoning/<fsId>/` is the seam that lets
a model reference travel on git clone without embedding the binary. On a fresh clone:
1. The pointer file is present in git (small JSON).
2. If `stagingUrl` is reachable: `StagingModelArtifactBackend.retrieve()` downloads the `.fb`.
3. If not reachable: the embedding job re-runs locally and re-registers with model-staging.

This is intentionally analogous to how `GraphEmbeddingSidecar` works for JPA vectors — the
sidecar is optional; the system re-fits if absent, but portability is greatly improved when it
travels.

---

### 2.5 Forks — Decide + Tradeoffs

#### Fork (i): Learned KG models in model-staging vs git-xet-sidecar

**Option A — model-staging (RECOMMENDED):**
- Pro: versioning, activation, download endpoint, conversion pipeline already exist.
- Pro: separation of large binary blobs from git history (git history stays clean).
- Pro: the staging server already handles SameDiff `.fb` format via reflection.
- Pro: HUMAN_CORRECTION weight lineage can be surfaced via staging `ModelEntry` metadata.
- Con: requires model-staging server to be running; fresh clone cannot load the model without
  network access (mitigated by re-fit fallback).
- Con: the reflection-based SameDiff integration in `TrainingService.java:180-193` is fragile;
  a direct ND4J dependency in the staging module would be cleaner (separate concern).

**Option B — git-xet sidecar:**
- Pro: fully offline; model travels on `git clone` exactly like the embedding vectors do today.
- Pro: no additional server required.
- Con: git-xet binary sidecars grow linearly with training iterations (every checkpoint = new blob).
  RotatE models at 2 GB each make this impractical for more than a few checkpoints.
- Con: git-xet is not a model registry; there is no activation/promote/version-list API.

**Decision: Option A (model-staging) for SameDiff `.fb` checkpoints above 10 MB.**
Use Option B (git-xet embedding sidecar) only for the JPA vector byte[] blobs, which already
use this mechanism. The pointer file in the data tree bridges both: if staging is unreachable,
the re-fit path kicks in.

#### Fork (ii): Do models travel on clone or get re-fit on the target?

**Option A — models travel (pointer + staging download):**
- Best for: production deployments where training is expensive (large graphs, GPU training).
- Risk: SameDiff `.fb` files are bit-exact on a given ND4J version but not across version upgrades.
  A clone running a different ND4J version may not be able to load the checkpoint.
- Mitigation: record the ND4J version in `kge-model-ref.json`; fail fast with a clear error.

**Option B — re-fit on target (no model travel):**
- Best for: small graphs where RotatE training takes < 10 min; development environments.
- Risk: re-fit does NOT produce bit-identical results (Adam step counter resets; different
  random seeds for negative sampling). Results are statistically equivalent but not reproducible.
- Mitigation: treat the data tree weights (PSL/MEBN JSON) as the authoritative durable state;
  re-fit only populates the embedding vectors, which are a derived quantity.

**Decision: BOTH, based on artifact type.**
PSL/MEBN weights (small JSON, text-diffable) ALWAYS travel on clone — they are the authoritative
learned state. SameDiff `.fb` checkpoints are staged and have a pointer; the re-fit fallback
activates when the staging server is unreachable or the ND4J version mismatches.

#### Fork (iii): Model versioning granularity

**Option A — per-fact-sheet versioning:**
Each fact sheet has its own model set (one RotatE model per fact sheet). Version counter is
the `FileWeightStore` monotonic version + the model-staging `ModelEntry` timestamp.

**Option B — global model versioning:**
One global RotatE model trained across all fact sheets. Simpler registry; harder to partition.

**Decision: Option A (per-fact-sheet).** This is already the pattern for inferred facts,
graph snapshots, and health metrics. It is the correct granularity for a graph-as-asset system
where each fact sheet is independently cloneable and reproducible.

---

## 3. Phased Implementation Plan

### Phase A — Wire the small-JSON SPIs (no new deps, low risk)

1. Add `FileBackedWeightStore` (`@Component`) in `kompile-knowledge-graph/.../grounding/`.
   - Path: `${kompile.data.dir:~/.kompile}/data/graph/reasoning`.
   - Mirror `FileBackedInferredFactStore` constructor pattern exactly (Spring `@Value`, null guard).
   - Wire into `PslReasoningService` and `MebnReasoningService` via constructor injection.

2. Add `MebnWeightPersistenceAdapter` (`@Component`) — writes `MebnWeightSerializer.strengthsToJson()`
   output to `data/graph/reasoning/<fsId>/mebn-weights.json`.

3. Add `EmbeddingTablePersistenceAdapter` — writes `EmbeddingTableIO.toJson()` string to
   `data/graph/reasoning/<fsId>/embedding-table.json`.

4. Add `TypeRegistryPersistenceAdapter` — writes `TypeRegistryIO.toJson()` to
   `data/graph/reasoning/<fsId>/type-registry.json`.

5. Add `BayesianCalibrationPersistenceService` — explicit calibration run writes
   `data/graph/reasoning/<fsId>/bayes-calibration.json`; load on next inference if graph version matches.

Files to create/modify:
- `kompile-knowledge-graph/.../grounding/FileBackedWeightStore.java` (new)
- `kompile-knowledge-graph/.../reasoning/MebnWeightPersistenceAdapter.java` (new)
- `kompile-knowledge-graph/.../grounding/EmbeddingTablePersistenceAdapter.java` (new)
- `kompile-knowledge-graph/.../grounding/TypeRegistryPersistenceAdapter.java` (new)
- Existing: `kompile-event-attribution/.../psl/PslReasoningService.java` — inject `WeightStore`

### Phase B — SameDiff checkpoint → model-staging

1. Add `ModelArtifactBackend` SPI + 3 implementations to `kompile-app-core`.
2. Add `EmbeddingModelPersistenceService` in `kompile-knowledge-graph`.
   - Called from `KGEmbeddingJobService` on job completion.
   - Calls `SameDiffModelIO.saveRotatECheckpoint(model, tmpDir)`.
   - Delegates to `StagingModelArtifactBackend.store()` when `${kompile.staging.url}` is set,
     else falls back to `FileModelArtifactBackend`.
   - Writes `kge-model-ref.json` to versioned data tree.
3. Wire model-staging SameDiff integration properly: replace reflection in `TrainingService` with
   a direct optional dependency on `nd4j-api` (the API jar, not the backend) to avoid the
   fragile `Class.forName` pattern for `.fb` save/load.

### Phase C — Audit log + PIN records (pending `fact-store-audit-tuning-correction-design.md`)

Implement `FactAuditEvent` SPI + `FileBackedAuditLog` in `kompile-graph-reasoning` (infra-free),
then wire into `FileBackedInferredFactStore` as described in the audit design doc.
HUMAN_CORRECTION audit events link to the `WeightStore` version number (from Phase A).

### Phase D — Graph-as-asset portability for models

1. Extend `GraphIOService` clone/export to include the `data/graph/reasoning/<fsId>/` subtree.
2. On import: if `kge-model-ref.json` is present, attempt staging download; if it fails, schedule
   a background re-fit job via `KGEmbeddingJobService`.
3. Add `ND4J_VERSION` field to `kge-model-ref.json`; fail-fast with a clear log message on version
   mismatch rather than silently producing wrong embeddings.

---

## 4. Open Questions

1. **TrainingService reflection removal** — the reflection-based SameDiff integration in
   `TrainingService.java:180-193` should be replaced with a proper optional dependency.
   Is `kompile-model-staging` allowed to take a direct `nd4j-api` dependency, or must it
   remain reflection-only for native-image reasons?

2. **Embedding table size threshold** — `EmbeddingTableIO` produces dense `double[][]` JSON.
   At what dimension × entity-count does this exceed the git-tree threshold?
   Recommend measuring at 128 dim × 100K entities ≈ 100 MB; above that, route to model-staging.

3. **Calibration persistence granularity** — Bayesian CPTs can be computed globally or
   per-fact-sheet. If a fact sheet grows incrementally via channel messages (Phase 2), does
   the calibration become stale? Define a calibration-validity policy (snapshot-version-pinned
   vs. time-to-live).

4. **Multi-tenant model-staging** — `${kompile.staging.project-dir}` scopes the staging server
   to a project. If multiple fact sheets share one staging server, how are their model entries
   namespaced? Recommend prefix convention: `kge-rotate-<fsId>-<ts>` already achieves this.

5. **KompileHome.dataDir() bug** — the static `KompileHome.dataDir()` always returns
   `~/.kompile/data`, ignoring `kompile.data.dir`. All new adapters must use Spring `@Value`
   injection. Decide whether to fix the static method or leave it as a known footgun with
   a Javadoc warning.

6. **ND4J version pinning across clones** — a RotatE `.fb` written on one machine may not load
   on a machine with a different ND4J minor version (FlatBuffers schema changes). Define a
   compatibility window (same major.minor = compatible) and document it in `kge-model-ref.json`.
