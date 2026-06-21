# Graph Serialization and Storage Audit

**Date**: 2026-06-21
**Scope**: Knowledge-graph serialization, persistence, export/import, versioning, and rehydration
**Status**: Read-only audit — no code was modified

---

## 1. Inventory of Serialization / Storage Paths

### 1.1 JPA / H2 Store (secondary — legacy path, not @Primary)

| Class | File |
|---|---|
| `GraphNode` | `domain/GraphNode.java` |
| `GraphEdge` | `domain/GraphEdge.java` |
| `NamedGraph` | `domain/NamedGraph.java` |
| `EntityMention` | `domain/EntityMention.java` |
| `SourceWeight` | `domain/SourceWeight.java` |
| `KnowledgeGraphServiceImpl` | `impl/KnowledgeGraphServiceImpl.java` |

Persists to H2 file at `./data/orchestrator-db` (via `spring.datasource.url`). JPA entity annotations drive schema via `ddl-auto=update`. Embeddings are stored as `BLOB` columns (`kg_embedding`, `kg_relation_embedding`) using `INDArrayConverter`. The H2 file is **not version-controlled** (not under `data/graph/`).

### 1.2 Matrix/Vector Store (@Primary live store)

| Class | File |
|---|---|
| `MatrixKnowledgeGraphService` | `matrix/service/MatrixKnowledgeGraphService.java` |
| `VectorStoreMatrixGraphStore` | `matrix/store/VectorStoreMatrixGraphStore.java` |
| `AdjacencyMatrixGraph` | `matrix/model/AdjacencyMatrixGraph.java` |
| `MatrixGraphNode` | `matrix/model/MatrixGraphNode.java` |

The `@Primary` implementation. Stores nodes as Spring AI `Document` records in a vector store (Lucene-backed). Graph metadata and adjacency matrices are also stored as vector-store documents. An in-memory `graphCache` sits in front. The vector-store index lives under the app's data directory (not explicitly versioned).

### 1.3 Portable Export/Import (JSON — canonical portability format)

| Class | File |
|---|---|
| `GraphIOService` | `io/GraphIOService.java` |
| `PortableNode` | `io/model/PortableNode.java` |
| `PortableEdge` | `io/model/PortableEdge.java` |
| `PortableNamedGraph` | `io/model/PortableNamedGraph.java` |
| `PortableGraph` | `io/format/PortableGraph.java` |
| `JsonGraphExporter` | `io/format/JsonGraphExporter.java` |
| `JsonGraphImporter` | `io/format/JsonGraphImporter.java` |

Serializes to/from `{nodes:[...], edges:[...]}` JSON. This is the canonical round-trip format used by the graph-as-asset portability service and snapshots.

### 1.4 Graph-as-Asset Portability (project tree persistence)

| Class | File |
|---|---|
| `ProjectGraphPortabilityService` | `kompile-app-main/src/main/java/ai/kompile/app/project/ProjectGraphPortabilityService.java` |
| `NamedGraphPortability` | `io/NamedGraphPortability.java` |

Writes `data/graph/factsheet-<id>.json`, `data/graph/global.json`, `data/graph/named-graphs.json` and `data/graph/embeddings/factsheet-<id>.bin`. Triggered on `commit` and `GraphChangesetCompletedEvent`. Rehydrates on `open` (empty-graph guard).

### 1.5 Embedding Sidecar (binary)

| Class | File |
|---|---|
| `GraphEmbeddingSidecar` | `io/GraphEmbeddingSidecar.java` |
| `INDArrayConverter` | `embedding/util/INDArrayConverter.java` |

Binary format with magic header `KGE1`. Stores node embeddings keyed by `(nodeType, externalId)` and relation embeddings keyed by `edgeType`. Reads/writes JPA repositories directly (not the @Primary vector store).

### 1.6 Snapshots

| Class | File |
|---|---|
| `SnapshotManager` | `maintenance/SnapshotManager.java` |

Writes `<snapshotId>.json` (lightweight manifest) + `<snapshotId>.graph.json` (full portable JSON dump) under `<dataDir>/data/graph/snapshots/<factSheetId>/`. Restore: delete current fact sheet, re-import dump via `GraphIOService.importGraph("json", ...)`.

### 1.7 Graph Health Time Series

| Class | File |
|---|---|
| `GraphHealthService` | `maintenance/GraphHealthService.java` |

Writes `GraphHealthSnapshot` records as JSON under `<dataDir>/data/graph/health/<factSheetId>/<epochMillis>.json`. Travels with git clone.

### 1.8 Additional Export Formats (export-only, no round-trip import)

| Format | Exporter | Importer |
|---|---|---|
| CSV | `CsvGraphExporter` | `CsvGraphImporter` |
| GraphML | `GraphMLExporter` | none |
| Cypher (Neo4j) | `CypherDumpExporter` | `CypherDumpImporter` |
| JSON-LD | `JsonLdGraphExporter` | `JsonLdGraphImporter` |
| N-Triples (RDF) | `NTriplesGraphExporter` | none |
| Turtle (RDF) | `TurtleGraphExporter` | none |
| Obsidian Vault | `ObsidianVaultExporter` | none |
| Wiki (Markdown) | `WikiExporter` | none |
| SVG | `SvgGraphExporter` | none |
| HTML | `HtmlGraphExporter` | none |

---

## 2. Round-Trip Fidelity Matrix

Legend: **Y** = preserved, **N** = dropped/lost, **P** = partial, **n/a** = not applicable

### 2.1 GraphNode Fields

| Field | JPA persists | PortableNode field | JSON export | JSON import | Notes |
|---|---|---|---|---|---|
| `nodeId` (UUID) | Y | — | — | assigned fresh | **N** — nodeId is regenerated on import; externalId is the stable key |
| `nodeType` / `NodeLevel` | Y | `nodeType` (String) | Y | Y | Defaults to ENTITY on unknown value |
| `externalId` | Y | `externalId` | Y | Y | Primary reconciliation key |
| `title` | Y | `title` | Y | Y | |
| `description` | Y | `description` | Y | Y | |
| `contentPreview` | Y | **missing** | **N** | **N** | Dropped on export; lost across clone |
| `parent` (hierarchy) | Y | **missing** | **N** | **N** | Parent/child JPA relationships not exported |
| `sourceNode` | Y | **missing** | **N** | **N** | Dropped |
| `metadataJson` | Y | `metadata` (Map) | Y | Y | Serialized via `getMetadata()` |
| `vectorId` | Y | **missing** | **N** | **N** | Not exported; irrelevant post-import (new vectorId assigned) |
| `sourceType` | Y | **missing** | **N** | **N** | Stored in metadata on @Primary store; lost from JPA path |
| `pathOrUrl` | Y | **missing** | **N** | **N** | Same issue |
| `childCount` / `edgeCount` | Y | **missing** | **N** | **N** | Derived counters; acceptable to drop |
| `factSheetId` | Y | `factSheetId` | Y | Y | |
| `confidence` | Y | `confidence` | Y | Y | |
| `namedGraphId` | Y | `namedGraphId` | Y | Y | Travels via named-graphs.json |
| `stale` / `staleAt` | Y | **missing** | **N** | **N** | Stale nodes are included in export (no stale filter in `collect()`) |
| `userPinned` | Y | **missing** | **N** | **N** | Dropped |
| `validUntil` | Y | **missing** | **N** | **N** | TTL metadata lost |
| `lastVerifiedAt` | Y | **missing** | **N** | **N** | Dropped |
| `observedAt` | Y | **missing** | **N** | **N** | Dropped |
| `occurredAt` | Y | `occurredAt` (String) | Y | Y | Serialized as `toString()` — see issue #4 |
| `kgEmbedding` (INDArray) | Y (BLOB) | — | **N** | — | Not in portable JSON; handled by sidecar |
| `kgEmbeddingAlgorithm` | Y | — | **N** | — | Carried only in sidecar |
| `kgEmbeddingVersion` | Y | — | **N** | — | Carried only in sidecar |
| `kgEmbeddingUpdatedAt` | Y | — | **N** | — | **Not in sidecar either — permanently lost** |
| `createdAt` / `updatedAt` | Y | **missing** | **N** | **N** | Timestamps not exported |

### 2.2 GraphEdge Fields

| Field | JPA persists | PortableEdge field | JSON export | JSON import | Notes |
|---|---|---|---|---|---|
| `edgeId` (UUID) | Y | — | — | assigned fresh | **N** — regenerated on import |
| `edgeType` | Y | `edgeType` (String) | Y | Y | |
| `relationType` | Y | `relationType` | Y | Y | |
| `weight` | Y | `weight` | Y | Y | |
| `description` | Y | `description` | Y | Y | |
| `label` | Y | **missing** | **N** | **N** | Dropped |
| `sharedEntitiesJson` | Y | **missing** | **N** | **N** | Dropped — breaks SHARED_ENTITY round-trips |
| `similarityScore` | Y | **missing** | **N** | **N** | Dropped |
| `bidirectional` | Y | **missing** | **N** | **N** | Dropped; defaults to false on import |
| `metadataJson` | Y | **missing** | **N** | **N** | Edge metadata not exported |
| `factSheetId` | Y | **missing** | **N** | **N** | Not in PortableEdge; factSheetId not preserved per-edge |
| `confidence` | Y | `confidence` | Y | Y | |
| `provenance` (String) | Y | `provenance` | Y | Y | |
| `kgRelationEmbedding` | Y (BLOB) | — | **N** | — | Binary sidecar only |
| `kgEmbeddingAlgorithm` | Y | — | **N** | — | Binary sidecar only |
| `kgEmbeddingVersion` | Y | — | **N** | — | Binary sidecar only |
| `stale` / `staleAt` | Y | **missing** | **N** | **N** | Dropped |
| `userPinned` | Y | **missing** | **N** | **N** | Dropped |
| `validUntil` | Y | **missing** | **N** | **N** | |
| `lastVerifiedAt` | Y | **missing** | **N** | **N** | |
| `observedAt` | Y | **missing** | **N** | **N** | |
| `occurredAt` | Y | `occurredAt` (String) | Y | Y | |
| `computedAt` | Y | **missing** | **N** | **N** | |
| `createdAt` | Y | **missing** | **N** | **N** | |

### 2.3 NamedGraph Fields

| Field | JPA persists | PortableNamedGraph | JSON export | JSON import | Notes |
|---|---|---|---|---|---|
| `graphId` | Y | `graphId` | Y | Y | |
| `name` | Y | `name` | Y | Y | |
| `description` | Y | `description` | Y | Y | |
| `parentGraph` | Y | `parentGraphId` | Y | Y | Two-pass import restores hierarchy |
| `factSheetId` | Y | `factSheetId` | Y | Y | |
| `ontologyType` | Y | `ontologyType` | Y | Y | |
| `schemaJson` | Y | `schemaJson` | Y | Y | |
| `metadataJson` | Y | `metadataJson` | Y | Y | |
| `ontologySchemaId` | Y | `ontologySchemaId` | Y | Y | |
| `ontologyVersion` | Y | `ontologyVersion` | Y | Y | |
| `nodeCount` / `edgeCount` | Y | **missing** | **N** | **N** | Derived counters; acceptable |
| `createdAt` / `updatedAt` | Y | **missing** | **N** | **N** | Dropped |

### 2.4 Import-side field restoration (GraphIOService.apply())

On import, `graphService.createNode(...)` is called without setting: `confidence`, `namedGraphId`, `occurredAt`. The 5-arg `createNode` overload used for new nodes does **not** pass these fields even though `PortableNode` carries them. Only `factSheetId` is conditionally passed via the 6-arg overload. Result: **confidence, namedGraphId, and occurredAt are silently discarded on import** even though they were exported.

On edge import, `graphService.createEdge(...)` is called without `confidence` or `provenance`. Both fields are in `PortableEdge` but are not passed to the store on import — they are silently discarded.

---

## 3. Concrete Issues and Risks

### [H-1] Import silently discards confidence, namedGraphId, occurredAt on nodes
**Severity**: HIGH
**File**: `io/GraphIOService.java`, lines 141–153 (apply method, node branch)

The `PortableNode` record carries `confidence`, `namedGraphId`, and `occurredAt`, and `GraphIOService.toPortable()` populates them. However, `apply()` calls `graphService.createNode(level, n.externalId(), n.title(), n.description(), n.metadata(), n.factSheetId())` which does not accept or set these fields. After a restore/rehydrate, all confidence scores are null, all named-graph assignments are null, and all occurred-at timestamps are null. This breaks ontology conformance (namedGraphId required for binding), TTL sweeps (occurredAt required), and confidence-based pruning.

### [H-2] Import silently discards confidence and provenance on edges
**Severity**: HIGH
**File**: `io/GraphIOService.java`, lines 162–179 (apply method, edge branch)

`PortableEdge` carries `confidence` and `provenance`. The import path calls `graphService.createEdge(fromUuid, toUuid, parseEdgeType(...), e.relationType(), weight, e.description())` — provenance and confidence are not passed and are lost. After a snapshot restore, all edges lose their provenance attribution, breaking the provenance subsystem.

### [H-3] Stale nodes included in export without filtering
**Severity**: HIGH
**File**: `io/GraphIOService.java`, lines 209–228 (collect method)

`collect()` iterates all nodes via `getNodesByType(level)` without filtering `stale=true` nodes. Stale nodes are then imported back on restore/rehydrate, resurrecting soft-deleted content. The `stale` flag itself is not in `PortableNode`, so imported nodes cannot even be re-staled.

### [H-4] Embedding sidecar reads JPA — RE-CLASSIFIED: not data loss (investigated 2026-06-21)
**Severity**: ~~HIGH~~ → **INFORMATIONAL** — the original diagnosis was wrong.
**File**: `io/GraphEmbeddingSidecar.java`, lines 95–98

The original finding read the sidecar's JPA calls and concluded "the @Primary store is matrix/vector, therefore the sidecar exports zero and KG embeddings are lost on clone." Tracing where the embeddings the sidecar actually serves are *produced and stored* shows this conflated two independent subsystems:

1. **Graph structure** (nodes/edges) lives in the matrix/vector store (`@Primary MatrixKnowledgeGraphService`). Its per-node "embeddings" (`AdjacencyMatrixGraph.nodeEmbeddings`, written via `storeNodeEmbeddings`) are **text embeddings** — `MatrixGraphConstructor` computes `embeddingModel.embed(title + " " + description)` (lines 138, 213). These are *recomputable* from node content, and `title`/`description` already travel in the portable structure JSON (`data/graph/*.json`). The Anserini index that backs them is git-ignored (`.gitignore`: `anserini-vector-index/`) **by design** — it is rebuilt, not cloned.

2. **Structural KGE** (`kgEmbedding` — TransE/RotatE; expensive; **not** recomputable from a single node's text) is produced by `KGEmbeddingStorageService.storeEmbeddings()`, which reads `nodeRepository.findByFactSheetId()` and writes `nodeRepository.saveAll()` — i.e. it computes into and persists to the **JPA `GraphNode` tables**, independent of which `KnowledgeGraphService` is `@Primary`. The sidecar reads JPA (`findByFactSheetIdAndKgEmbeddingNotNull`) — *exactly where this data lives.*

So the sidecar targets the correct store. Both cases are internally consistent:
- JPA `GraphNode` rows present → KGE computes into JPA **and** the sidecar exports from JPA. ✓
- Pure-matrix (no JPA rows) → `KGEmbeddingStorageService.storeEmbeddings()` finds no nodes to attach to (its own `findByFactSheetId` is empty), so structural KGE is **never produced**; the sidecar correctly exports zero. Nothing irreplaceable to lose. ✓

**Conclusion**: no data-loss bug — the genuinely valuable structural KGE is already handled by the JPA path.

**UPDATE (2026-06-21): matrix/vector path now also carried, by request.** Although the matrix store's node vectors are *recomputable* (so this is an index-warming optimization, not a correctness fix), the sidecar now serializes them too so a freshly cloned project's similarity index is warm without re-embedding. Implementation:
- `KnowledgeGraphService` gained a store-agnostic seam — `exportNodeEmbeddings(factSheetId)` / `applyNodeEmbeddings(byNodeId)` (default no-op), overridden by `MatrixKnowledgeGraphService` (reads `AdjacencyMatrixGraph.getNodeEmbedding`, skips all-zero rows; writes via `storeNodeEmbeddings`, which re-indexes into the vector store), and delegated by the `EventPublishingKnowledgeGraphService` @Primary decorator.
- `GraphEmbeddingSidecar` binary format bumped **KGE1 → KGE2** with a third section: live-store node vectors keyed by `(nodeType, externalId)` (UUIDs regenerate on import). Legacy KGE1 files still read. JPA structural-KGE node/edge sections are unchanged, so both families travel and route to the correct store on import. Covered by `MatrixKnowledgeGraphServiceEmbeddingTest` + `GraphEmbeddingSidecarTest` (6 tests).

**Separate, still-open finding (out of scope for serialization)**: structural KGE is a **JPA-island** — neither produced nor consumed on the @Primary matrix read path (`kgEmbedding` is referenced nowhere in the `matrix` package; `getNodesInFactSheet` returns nodes without it populated). That is an architectural disconnect in how KGE is *consumed*, not a portability/sidecar defect; track it separately if KGE-on-the-matrix-path is desired.

### [H-5] `kgEmbeddingUpdatedAt` is never written to or read from the sidecar
**Severity**: HIGH
**File**: `io/GraphEmbeddingSidecar.java` (export/import methods), `domain/GraphNode.java` line 253

`kgEmbeddingUpdatedAt` exists on `GraphNode` and is persisted to JPA. The sidecar binary format (per the class Javadoc) stores `[UTF nodeType][UTF externalId][UTF algorithm][long version][int len][bytes]` — there is no slot for `kgEmbeddingUpdatedAt`. The timestamp is permanently lost on every export/import cycle.

### [H-6] The H2 database is not version-controlled
**Severity**: HIGH
**File**: `application.properties` lines 566–571 (`spring.datasource.url=jdbc:h2:file:./data/orchestrator-db`)

The H2 file (`./data/orchestrator-db.*`) is the persistence layer for JPA entities including `NamedGraph`, `GraphEdge`, `GraphNode`. It lives in `./data/` but is not exported to `data/graph/` and is not in the gitignore/git-tracked asset tree. After a `git clone`, the H2 database is absent. Graph rehydration from portable JSON populates the vector store (@Primary), not H2; the JPA store diverges from the live store immediately after clone.

### [M-1] `factSheetId` not exported per edge — cross-edge scoping breaks
**Severity**: MEDIUM
**File**: `io/model/PortableEdge.java`

`PortableEdge` has no `factSheetId` field. Edges are scoped in the collect loop by their source node's factSheetId, but upon import, the edge's fact-sheet scope is not set. `MatrixKnowledgeGraphService.createEdge()` does not accept a factSheetId parameter. After a round-trip, edge-level fact-sheet queries return wrong results.

### [M-2] `contentPreview`, `pathOrUrl`, `sourceType` lost on JSON round-trip (JPA path)
**Severity**: MEDIUM
**File**: `io/GraphIOService.java`, `io/model/PortableNode.java`

These SOURCE-node fields are first-class JPA columns but are absent from `PortableNode`. On the @Primary store they are stored inside `metadata` (see `MatrixKnowledgeGraphService.createOrUpdateSourceNode()` lines 138–140), so they survive the round-trip via `metadataJson`. On the JPA path they are lost. This is a JPA-vs-matrix divergence.

### [M-3] `bidirectional` field dropped on edge export/import
**Severity**: MEDIUM
**File**: `io/model/PortableEdge.java`, `domain/GraphEdge.java`

`bidirectional` is persisted on `GraphEdge` but absent from `PortableEdge`. On import, all edges default to `bidirectional=false` (the `@Builder.Default`). This changes traversal semantics for edges that were originally bidirectional (e.g., EMBEDDING_SIMILARITY edges).

### [M-4] `sharedEntitiesJson` and `similarityScore` lost on edge export
**Severity**: MEDIUM
**File**: `io/model/PortableEdge.java`, `domain/GraphEdge.java`

`sharedEntitiesJson` (the JSON array of co-occurring entities on SHARED_ENTITY edges) and `similarityScore` are persisted on `GraphEdge` but are not in `PortableEdge`. After a round-trip, SHARED_ENTITY edges lose their shared-entity payload and similarity context.

### [M-5] INDArrayConverter converts float→double→float with precision loss
**Severity**: MEDIUM
**File**: `embedding/util/INDArrayConverter.java`, lines 123–128 (`toDoubleArray`)

The `toDoubleArray()` static helper calls `array.toDoubleVector()`, which upcasts `float32` to `float64`. When this result is stored in a DB column that expects `double[]` and then read back via `fromDoubleArray(double[])`, the reconstituted `INDArray` is `FLOAT64` while the model trained on `FLOAT32`. The primary JPA store serialization (`convertToDatabaseColumn` / `convertToEntityAttribute`) correctly roundtrips via `float` — the precision risk is in callers using `toDoubleArray`/`fromDoubleArray` (e.g., Neo4j storage path mentioned in the Javadoc comment).

### [M-6] `occurredAt` serialized as `LocalDateTime.toString()` — no explicit ISO format
**Severity**: MEDIUM
**File**: `io/GraphIOService.java`, lines 258, 271

`node.getOccurredAt().toString()` outputs ISO-8601 local date-time (e.g., `2025-06-21T10:30:00`) but `toString()` on `LocalDateTime` is implementation-dependent and does not guarantee microsecond precision or timezone context. Imported `occurredAt` is never parsed back into the store (issue H-1 above). If parsing were added, timezone ambiguity would cause ordering errors for events that cross DST boundaries.

### [M-7] Vector store adjacency matrix serialization does not carry `bidirectional` or `confidence`
**Severity**: MEDIUM
**File**: `matrix/store/VectorStoreMatrixGraphStore.java`, lines 557–585 (`saveAdjacencyMatrix`)

The adjacency-matrix serialization stores `{source, target, weight, relationType}` per edge. Edge-level `confidence`, `bidirectional`, edge `description`, and `provenance` are not stored in the matrix adjacency document. They exist only if the edge was also written to H2 (JPA path), but on the @Primary store they are never written to JPA. These fields are permanently inaccessible from the live store.

### [M-8] `GraphEmbeddingSidecar.importInto()` reads JPA repositories, not live store
**Severity**: MEDIUM
**File**: `io/GraphEmbeddingSidecar.java`, lines 165–179

On import, the sidecar calls `nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(...)` (JPA), then `nodeRepository.save(node)` to write the embedding back to H2. If the graph was rehydrated into the @Primary vector store (not H2), the node does not exist in H2, the find returns empty, and no embeddings are reattached. The embedding rehydration silently no-ops on fresh clones.

### [M-9] Snapshot restore does not re-import KG embeddings
**Severity**: MEDIUM
**File**: `maintenance/SnapshotManager.java`, lines 220–263 (restoreSnapshot comment, line 215)

The `restoreSnapshot` Javadoc explicitly states: "KG embeddings (the binary sidecar) are not [restored], and would be recomputed." But there is no mechanism in `restoreSnapshot` to trigger or schedule recomputation. After a restore, embeddings are silently missing with no warning to the caller.

### [M-10] `EdgeProvenance` enum not persisted anywhere in the graph domain
**Severity**: MEDIUM
**File**: `domain/EdgeProvenance.java`, `domain/GraphEdge.java`

`EdgeProvenance` is a well-defined enum (`EXTRACTED`, `INFERRED`, `AMBIGUOUS`) in the domain, but `GraphEdge` has no `EdgeProvenance`-typed field — only a free-text `String provenance`. The rich epistemological classification is never actually stored or exported; all provenance is freeform text.

### [L-1] CSV export drops factSheetId, namedGraphId, confidence, occurredAt
**Severity**: LOW
**File**: `io/format/CsvGraphExporter.java`

The CSV format exports only `externalId, title, description, nodeType` for nodes and `fromExternalId, toExternalId, edgeType, weight, description` for edges. All scoping and quality fields are dropped. CSV is marked as a lossy interop format, but this is not documented in the format.

### [L-2] RDF exporters (N-Triples, Turtle) drop edge attributes entirely
**Severity**: LOW
**File**: `io/format/NTriplesGraphExporter.java` line 64–68, `io/format/TurtleGraphExporter.java`

Per the `NTriplesGraphExporter` Javadoc: "Edge attributes (weight/confidence) are intentionally not represented (that needs reification)." Weight and confidence are not in the exported RDF. There is no RDF importer, so round-trip is not supported for these formats.

### [L-3] GraphML export drops confidence, factSheetId, occurredAt, metadata
**Severity**: LOW
**File**: `io/format/GraphMLExporter.java`

GraphML exports only `title`, `description`, `nodeType` for nodes and `weight`, `edgeType` for edges. No GraphML importer exists.

### [L-4] Cypher export drops confidence, namedGraphId, factSheetId, metadata, relationType
**Severity**: LOW
**File**: `io/format/CypherDumpExporter.java`

Cypher dumps only `externalId`, `title`, `description`, `nodeType` for nodes and `weight`, `description` for edges. `relationType` is in `PortableEdge` but absent from Cypher output.

### [L-5] JSON-LD exporter uses schema.org context, not real kompile IRIs
**Severity**: LOW
**File**: `io/format/JsonLdGraphExporter.java`, lines 66–69

The JSON-LD output uses `@context: {title: "http://schema.org/name", description: "http://schema.org/description"}` while the N-Triples/Turtle exporters mint kompile-namespaced IRIs (`https://kompile.ai/kg/`). The two RDF-family formats are semantically inconsistent — a consumer processing both would see different predicate URIs for the same fields.

### [L-6] PSL/MEBN/FOL inference results are not persisted back to the graph
**Severity**: LOW (noted as deferred in psl-mebn-knowledge-base-gaps.md)
**File**: `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/`

PSL, MEBN, and FOL inference services compute probabilistic scores (`PslInferenceResult`, `HybridReasoner`) but do not write inferred edges or findings back to the knowledge graph store as `INFERRED` provenance edges. Inference is ephemeral — results live only for the duration of the HTTP request. This means the graph cannot accumulate learned facts across sessions (gap #6 in `psl-mebn-knowledge-base-gaps.md`).

### [L-7] Large-graph scalability: full-dump serialization is O(N+E) in memory
**Severity**: LOW
**File**: `io/GraphIOService.java` (collect method), `maintenance/SnapshotManager.java`

Both snapshot creation and portability export load all nodes and edges into memory via `getNodesByType(level)` / `getEdgesForNode()` before writing. On graphs with millions of nodes/edges this will OOM. There is no streaming export path.

### [L-8] No schema/version field in the PortableGraph JSON
**Severity**: LOW
**File**: `io/format/PortableGraph.java`, `io/model/PortableNode.java`, `io/model/PortableEdge.java`

The portable JSON has no version envelope. As new fields are added to `PortableNode`/`PortableEdge` (e.g., `relationType` was added in a backward-compatible way via multiple compact constructors), there is no way for an importer to detect which schema version a file was written against or to refuse/warn on incompatible files.

---

## 4. Store Consistency Analysis

### 4.1 JPA vs @Primary matrix/vector store

The two stores are **not synchronized**. `MatrixKnowledgeGraphService` (`@Primary`) never writes to H2 JPA repositories. `KnowledgeGraphServiceImpl` (JPA) is a non-primary fallback. In production, all node/edge creation goes to the vector store. The JPA tables (`graph_nodes`, `graph_edges`) are either empty or stale unless code explicitly calls the JPA service.

This dual-store divergence has cascading effects:

- `GraphEmbeddingSidecar` reads JPA repositories — this is **correct**, not a defect: the structural KGE it serves (`kgEmbedding`) is JPA-resident, written by `KGEmbeddingStorageService` (see re-classified H-4)
- `ContradictionDetector` (mentioned in MEMORY.md) is "JPA-only (dead on matrix)" — contradiction detection does not run
- `ProvenanceValidator` may query JPA for provenance; results depend on which store was written
- `SnapshotManager.createSnapshot()` reads JPA `nodeRepository.findActiveEntities()` — if the live store is the matrix store, the snapshot node list may be empty or incomplete

### 4.2 Export sourced from which store

`GraphIOService.collect()` calls `graphService.getNodesByType(level)` and `graphService.getEdgesForNode(nodeId)`. Since `graphService` is injected as `KnowledgeGraphService` and `@Primary` is `MatrixKnowledgeGraphService`, **exports should source from the live vector store**. This is correct. The embedding sidecar reads from JPA — also correct, because the structural KGE it serves is JPA-resident (see re-classified H-4); the matrix store only holds recomputable text vectors.

---

## 5. Versioning and Durability Assessment

### 5.1 What survives `git clone`

| Artifact | Path | Versioned? | Notes |
|---|---|---|---|
| Graph structure (nodes/edges) | `data/graph/factsheet-<id>.json` | Yes (if commit ran) | Requires `exportAllGraphs()` to have been called |
| Global graph | `data/graph/global.json` | Yes | Same trigger |
| Named graph registry | `data/graph/named-graphs.json` | Yes | |
| KG embeddings (structural KGE) | `data/graph/embeddings/factsheet-<id>.bin` | Yes (git-xet) | Sidecar reads JPA — correct: structural KGE is JPA-resident (re-classified H-4). Matrix text vectors are recomputable & travel as content |
| Graph health time series | `data/graph/health/<id>/*.json` | Yes | |
| Snapshots | `data/graph/snapshots/<id>/*.json` | Yes (if dataDir set) | |
| H2 database | `./data/orchestrator-db.*` | **No** | Not in git-tracked asset tree |
| Vector store index | varies (e.g., Lucene dir) | **No** | Not exported to versioned tree |

### 5.2 Named versions / semantic diff

There are no named graph versions (e.g., `v1.0`, `v2.0`). Versioning is snapshot-based (opaque UUID filenames). The semantic entity-level diff (`compareGraphs` in `GraphHealthService`) compares live fact sheets, not historical snapshots.

### 5.3 Inference results and findings

PSL, MEBN, and FOL inference results are not persisted. There is no `Finding` entity in the domain model (per `psl-mebn-knowledge-base-gaps.md`). Findings/inferred facts are ephemeral and disappear after each request.

---

## 6. Embedding Persistence Deep-Dive

### INDArrayConverter binary format

- Shape: stored as `[int numDims][int dim0][int dim1]...` (32-bit per dimension — will overflow for dim > 2^31)
- Data: stored as float32 (little-endian). The primary JPA path is float-accurate.
- `toDoubleArray()` static method upcasts to float64 — any caller using this for persistence creates a type mismatch (M-5).
- No format version field. If the shape encoding changes, existing BLOB data silently misparses.

### Sidecar binary format

- Magic header `0x4B474531` ("KGE1") catches truncated/foreign files — good.
- Per node: `[UTF nodeType][UTF externalId][UTF algorithm][long version][int len][bytes]` — uses `INDArrayConverter.convertToDatabaseColumn()` (float32 bytes), consistent with JPA.
- Per edge type: `[UTF edgeType][UTF algorithm][long version][int len][bytes]`.
- Missing: `kgEmbeddingUpdatedAt` (H-5). No magic footer or checksum — partial writes are silently accepted if the length field is correct.

### Live store (matrix) embedding path

`VectorStoreMatrixGraphStore.storeNodeEmbeddings()` stores node embeddings in the vector store via `vectorStore.addWithEmbeddings(documents, embeddings)`. These are NOT written to H2, so the sidecar (which reads H2) cannot export them. The sidecar export is broken for the live store.

---

## 7. Format Coverage Summary

| Format | Export | Import | Fields preserved | Round-trip | Notes |
|---|---|---|---|---|---|
| JSON (portable) | Y | Y | Most (see matrix above) | Partial (H-1, H-2) | Canonical format; use for portability |
| JSON-LD | Y | Y | Basic fields only | Partial | Confidence/provenance missing |
| CSV | Y | Y | Core 4 fields only | Lossy | No metadata/confidence/factSheetId |
| GraphML | Y | N | Core fields, weight | Export-only | No importer |
| Cypher | Y | Y | Core fields, weight | Partial | No confidence/metadata |
| N-Triples (RDF) | Y | N | Labels + confidence + metadata | Export-only | No edge attributes (weight/confidence) |
| Turtle (RDF) | Y | N | Same as N-Triples | Export-only | Consistent with N-Triples |
| Obsidian Vault | Y | N | Titles + edges | Export-only | Human-readable only |
| Wiki/Markdown | Y | N | Titles + edges | Export-only | |
| SVG | Y | N | Visual | Export-only | |
| HTML | Y | N | Visual | Export-only | |
| Parquet | N | N | — | Deferred | |
| RDF import | N | N | — | Deferred | |

---

## 8. Prioritized Recommendations

> **Status (2026-06-21):** P0 #1–#4 done in prior sessions (H-1/H-2/H-3 wired via the metadata seam; H-4 sidecar carries both families). Then closed the **PortableEdge completeness cluster** — **M-1** (factSheetId), **M-3** (bidirectional, now honored by the matrix store instead of being derived from edge type), **M-4** (sharedEntitiesJson + similarityScore), **M-7** (edge metadata) — routed through a new **typed `EdgeMetadata` POJO** (replacing hand-built map + key-by-key `extractMeta*` extraction). Then **H-5**: the sidecar's KGE2 node section now carries `kgEmbeddingUpdatedAt` (epoch-milli slot; KGE1 reads stay timestamp-less). Then **L-8**: `PortableGraph` carries a `schemaVersion` envelope (stamped on export; null = pre-versioning file; `apply()` warns on an incompatible version). Then **M-9**: snapshots now persist the embedding sidecar (`<id>.embeddings.bin`) next to the dump and `restoreSnapshot` reattaches it — so restore preserves KG embeddings instead of forcing recomputation (reuses the H-4/KGE2 sidecar; better than the original "publish a recompute event" idea). Covered by `GraphIOServiceTest`, `GraphEmbeddingSidecarTest`, `MatrixKnowledgeGraphServiceEdgeTest`, `SnapshotManagerTest`.
>
> **Then (2026-06-21, parallel subagent batch, all verified in main tree + full build green, +33 tests):**
> **M-5** (`fromDoubleArray` now casts to FLOAT32; lossy `toDoubleArray`/`fromDoubleArray` `@Deprecated`), **L-1** (CSV nodes 4→8 / edges 5→9 columns, header-name-keyed importer for back-compat), **L-3** (GraphML typed `<key>`/`<data>` for the dropped node/edge fields), **L-4** (Cypher emits + re-parses confidence/namedGraphId/factSheetId/relationType/provenance), **L-2** (N-Triples + Turtle now carry edge weight/confidence via RDF reification with minted kompile IRIs), **L-5** (JSON-LD `@context` switched schema.org → `https://kompile.ai/kg/`, consistent with the RDF exporters).
>
> **Then (inline, sequential — both touch `GraphIOService`, verified, full build green):** **M-6** (`occurredAt` now serialized via an explicit `DateTimeFormatter.ISO_LOCAL_DATE_TIME` helper rather than `toString()`); **M-10** (new typed `GraphEdge.provenanceType` `@Enumerated` field + `PortableEdge.provenanceType` + `EdgeMetadata.provenanceType`, surfaced by the matrix store from metaJson or the `EdgeProvenance` param — the epistemological classification now round-trips as an enum, distinct from the freetext *source* `provenance`).
>
> **Then L-7** (streaming export): `GraphIOService.collect` factored into a shared `traverse()` visitor; new `exportGraphStreaming(format, factSheetId, OutputStream)` writes nodes then edges in two passes via Jackson `JsonGenerator` (no full `byte[]`/list held); `SnapshotManager` streams its dump to disk.
>
> **Then L-6** (Finding/inferred-fact persistence — psl-mebn gap #6): inferences now persist as `INFERRED` edges/attributes that travel on clone. The materialization **logic** (atom grammar, binary→relation / unary→attribute routing, accounting) lives in the **infra-free lib** (`InferredFactMaterializer`, writing through an `InferredGraphSink`); the knowledge-graph side is only the store sink (`InferredFactGraphMaterializer` → INFERRED edges via `createEdgeWithMetadata`) + a REST endpoint. The lib also ships a sink over its own `MutableReasoningGraph`.
>
> **✅ Audit complete — every H/M/L finding is resolved or consciously closed.** (`EdgeMetadata` is a Lombok `@Builder` record; the materialization core is library-owned per the infra-free design.)

### P0 — Critical (data loss on every restore/clone)

1. **Fix import to restore confidence, namedGraphId, occurredAt on nodes** (H-1).
   Add these fields to `KnowledgeGraphService.createNode()` or apply them post-creation in `GraphIOService.apply()` by calling `graphService.updateNode()` with the extended fields.

2. **Fix import to restore confidence and provenance on edges** (H-2).
   `KnowledgeGraphService.createEdge()` needs `confidence` and `provenance` parameters, or `GraphIOService.apply()` must call a setter after creation.

3. **Embedding sidecar now carries BOTH JPA structural KGE and the @Primary store's node vectors** (H-4 — DONE).
   The sidecar reads JPA for structural KGE (`kgEmbedding`, written by `KGEmbeddingStorageService` — JPA-resident, the correct source) AND, via the store-agnostic `KnowledgeGraphService.exportNodeEmbeddings`/`applyNodeEmbeddings` seam, the matrix/vector store's per-node text vectors (KGE2 format, third section). The matrix store re-indexes them on import, warming similarity search after a clone. (KGE *consumption* on the matrix read path remains a separate architectural item — KGE is currently a JPA-island.)

4. **Filter stale nodes from export** (H-3).
   In `GraphIOService.collect()`, skip nodes where `node.getStale() == true` before adding to `nodes`. Optionally also add a `stale` field to `PortableNode` for explicit control.

### P1 — High (significant behavior degradation after restore)

5. **Add `kgEmbeddingUpdatedAt` to the sidecar format** (H-5).
   Bump the magic header version and add the timestamp slot. Treat older files as missing the field (backward-compatible read).

6. **Clarify and document H2 vs vector-store boundary** (H-6).
   The H2 database (`./data/orchestrator-db`) must either be exported as a versioned asset or the JPA path must be eliminated as the source of truth. At minimum, `SnapshotManager.createSnapshot()` must read from the @Primary store for node enumeration, not JPA repositories.

7. **Add `factSheetId` to `PortableEdge`** (M-1).
   Edges need scope restoration on import just as nodes do. Add the field to `PortableEdge` and pass it through `createEdge()`.

### P2 — Medium (functional gaps or interop degradation)

8. **Fix snapshot restore to schedule embedding recomputation** (M-9).
   After `restoreSnapshot()` re-imports nodes, publish a `KGEmbeddingRecomputationRequestedEvent` or return a flag so callers can trigger embedding jobs.

9. **Add `bidirectional` to `PortableEdge`** (M-3).
   EMBEDDING_SIMILARITY edges are bidirectional by design; losing this on restore changes traversal semantics.

10. **Add `sharedEntitiesJson` to `PortableEdge`** (M-4).
    SHARED_ENTITY edges lose their payload on round-trip.

11. **Add edge metadata (`metadataJson`) to `PortableEdge`** (M-7 partial).
    Edge metadata is persisted in JPA but not exported. Add to `PortableEdge` and populate in `GraphIOService.toPortable(edge)`.

12. **Add a format version envelope to PortableGraph JSON** (L-8).
    Add `schemaVersion` (e.g., `"1"`) to `PortableGraph` so future readers can detect and handle version skew.

13. **Replace `EdgeProvenance` freetext with typed enum storage** (M-10).
    Add an `@Enumerated(EnumType.STRING) EdgeProvenance provenance` column to `GraphEdge` alongside or replacing the freetext `provenance` String. Export in `PortableEdge`.

### P3 — Low / deferred

14. **Harmonize JSON-LD and N-Triples/Turtle IRI namespaces** (L-5).
    Both should use `https://kompile.ai/kg/` as base or both schema.org — pick one.

15. **Add streaming export for large graphs** (L-7).
    Replace in-memory collect with a streaming approach (Jackson streaming API, chunked writes) to avoid OOM on large graphs.

16. **Design a Finding/inferred-fact persistence model** (L-6).
    Per `psl-mebn-knowledge-base-gaps.md` gap #6: create an `InferredFact` or `Finding` entity that persists PSL/MEBN/FOL inference results as `INFERRED` provenance edges back into the graph after each inference session. Include in the portable export.

---

## 9. File Reference Index

| File (relative to `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/`) | Role |
|---|---|
| `ai/kompile/knowledgegraph/domain/GraphNode.java` | JPA entity — all node fields |
| `ai/kompile/knowledgegraph/domain/GraphEdge.java` | JPA entity — all edge fields |
| `ai/kompile/knowledgegraph/domain/NamedGraph.java` | JPA entity — named graph fields |
| `ai/kompile/knowledgegraph/domain/EdgeProvenance.java` | Enum (unused in field storage) |
| `ai/kompile/knowledgegraph/embedding/util/INDArrayConverter.java` | JPA BLOB ↔ INDArray; float32 |
| `ai/kompile/knowledgegraph/io/GraphIOService.java` | Export/import dispatcher + toPortable() |
| `ai/kompile/knowledgegraph/io/model/PortableNode.java` | DTO for node round-trip |
| `ai/kompile/knowledgegraph/io/model/PortableEdge.java` | DTO for edge round-trip |
| `ai/kompile/knowledgegraph/io/model/PortableNamedGraph.java` | DTO for named graph round-trip |
| `ai/kompile/knowledgegraph/io/GraphEmbeddingSidecar.java` | Binary embedding sidecar (reads JPA!) |
| `ai/kompile/knowledgegraph/io/NamedGraphPortability.java` | Named graph JSON export/import |
| `ai/kompile/knowledgegraph/io/format/JsonGraphExporter.java` | JSON exporter |
| `ai/kompile/knowledgegraph/io/format/JsonGraphImporter.java` | JSON importer |
| `ai/kompile/knowledgegraph/io/format/NTriplesGraphExporter.java` | RDF N-Triples exporter |
| `ai/kompile/knowledgegraph/io/format/TurtleGraphExporter.java` | RDF Turtle exporter |
| `ai/kompile/knowledgegraph/io/format/RdfSupport.java` | IRI minting utilities |
| `ai/kompile/knowledgegraph/io/format/CsvGraphExporter.java` | CSV exporter (4 fields only) |
| `ai/kompile/knowledgegraph/io/format/CypherDumpExporter.java` | Cypher exporter |
| `ai/kompile/knowledgegraph/io/format/GraphMLExporter.java` | GraphML exporter |
| `ai/kompile/knowledgegraph/io/format/JsonLdGraphExporter.java` | JSON-LD exporter (schema.org context) |
| `ai/kompile/knowledgegraph/matrix/store/VectorStoreMatrixGraphStore.java` | @Primary live store |
| `ai/kompile/knowledgegraph/matrix/service/MatrixKnowledgeGraphService.java` | @Primary service |
| `ai/kompile/knowledgegraph/maintenance/SnapshotManager.java` | Snapshot create/restore |
| `ai/kompile/knowledgegraph/maintenance/GraphHealthService.java` | Health time series |
| `ai/kompile/knowledgegraph/impl/KnowledgeGraphServiceImpl.java` | JPA service (non-primary) |
| `kompile-app-main/.../project/ProjectGraphPortabilityService.java` | Git-clone portability orchestrator |
