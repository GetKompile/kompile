# Incremental crawl-backed knowledge graphs

## Goal

Kompile should support a Graphify-style loop in which a stable set of sources becomes a durable
knowledge graph, later crawls update only changed contributions, deleted local sources are removed,
and agents reason over the resulting fact sheet with provenance and bounded evidence.

## Graphify reference model

Graphify v8 combines deterministic AST extraction for code with semantic extraction for documents.
Every emitted node and edge retains a `source_file`, enabling one source's contribution to be
replaced without rebuilding the rest of the graph. Its incremental pipeline:

1. discovers source files;
2. compares each source against a manifest, using modification time as a shortcut and SHA-256 as the
   durable identity;
3. reuses cached per-file fragments for unchanged content;
4. rebuilds changed fragments and prunes deleted-source fragments;
5. deduplicates before clustering; and
6. publishes a portable `graph.json` queried through bounded BFS/DFS-style context retrieval.

Primary references:

- [Graphify repository](https://github.com/Graphify-Labs/graphify)
- [How it works](https://github.com/Graphify-Labs/graphify/blob/v8/docs/how-it-works.md)
- [Incremental update design](https://github.com/Graphify-Labs/graphify/blob/v8/docs/superpowers/specs/2026-05-04-incremental-updates-dedup-design.md)
- [Detection and manifest implementation](https://github.com/Graphify-Labs/graphify/blob/v8/graphify/detect.py)
- [Cache implementation](https://github.com/Graphify-Labs/graphify/blob/v8/graphify/cache.py)
- [Graph build implementation](https://github.com/Graphify-Labs/graphify/blob/v8/graphify/build.py)

## Kompile mapping

Kompile already has the larger persistence and reasoning surface:

| Graphify concept | Kompile implementation |
|---|---|
| portable graph | fact sheet plus `.kgraph` export |
| `source_file` provenance | source document IDs on graph entities and relations |
| manifest/content identity | per-fact-sheet `DocumentHashStore` |
| changed-source replacement | purge source contribution, then extract and persist replacement |
| deleted-source pruning | scope-aware reconciliation after complete local discovery |
| graph query context | `graph_reasoning_query` with trace and evidence |
| interactive refresh | `crawl_discover` → `crawl_documents` → `crawl_control` |
| project-owned code | `KompileCodingProject.factSheetId` → incremental AST index and crawl selector |
| graph embeddings | `graph_embeddings` with TransE/RotatE training, warm starts, scoring, prediction, and similarity |
| graph mutation | `knowledge_graph` and `ask_graph_assert`/`ask_graph_retract` |
| graph algorithms | search, reason, aggregate, centrality, simulation, forecast, and Bayesian tools |
| snapshots/versioning | existing knowledge-graph snapshots and fact-sheet versioning |

## Kompile project integration

The active `kompile.project.json` remains authoritative for code ownership. Project auto-detection
creates a `KompileCodingProject` with a stable code-project ID, root path, include/exclude patterns,
lifecycle, and `autoIndex`. `crawl_discover` exposes that registry as `codeProjects`, so a code root
does not need to be registered again as an unrelated crawl source.

`crawl_documents` accepts code-project IDs/names, with `"*"` meaning every active discovered project.
Each selection becomes a managed directory source carrying the code-project ID as provenance. The
tool merges project filters with generated/build-directory exclusions, explicitly enables
content-hash incremental updates, and supplies a code-aware pipeline/routing rule while retaining a
standard graph-extraction path for README files and build metadata. The resulting facts share the
same target fact sheet, reasoning query, snapshots, and `.kgraph` export as document sources.

The fact-sheet binding is persisted on `KompileCodingProject` and mirrored to `CodeProject`.
`CodebaseIndexer` therefore writes deterministic AST nodes and dependency edges into the bound fact
sheet. The index persists the graph store's native deterministic node IDs. Incremental indexing
reuses IDs for unchanged files, prunes nodes owned by changed or deleted files, rebuilds their
relations, and reconnects incoming edges and hierarchy links owned by unchanged files. A managed `crawl_documents` run binds a
selected code project to the resulting fact sheet; when that binding changes, the old structural
projection is pruned and a full code-index refresh is launched asynchronously into the new scope.
Current and later code discoveries are therefore available through the same graph tools and snapshot
format.

## Complete kgraph lifecycle

The fact sheet is the live graph identity. A `.kgraph` file is its portable, full-fidelity
checkpoint—not a second database. The unified graph bundle can carry structural entities and
relations, multiple vector layers, entity/relation/global embeddings, facts, opinions and weights,
metadata and byte artifacts, plus serialized learned models and their references.

After enrichment, the crawl can train a TransE or RotatE model synchronously in the tracked
`LEARNING` step. `embeddingTraining` controls enablement, algorithm, dimension, cold-start epochs,
batch size, and warm-start epochs per crawl. The resulting persisted model is immediately available
to `graph_embeddings` scoring/prediction tools and is included by `graph_export(format=kgraph)`.

## Correctness invariants

The crawl implementation follows these invariants:

- A content hash becomes durable only after `GraphBuildCompletedEvent`. A failed extraction or graph
  persistence cannot make a later run incorrectly skip the source.
- Manifest updates for one crawl run are staged in memory and committed per fact sheet with atomic
  file replacement.
- A source scope is a stable identity derived from the normalized local file/directory root.
- Deletion is inferred only after complete, error-free discovery of a local file or directory scope.
  Existing-but-filtered paths and remote URLs are retained.
- Clearing a graph implies a full recrawl; incremental skipping is unsafe against an empty graph.
- Legacy manifest entries without a scope remain valid for change detection. They become eligible for
  scope deletion reconciliation after they are next reprocessed and receive scope metadata.

## Agent workflow

For routine refreshes, an agent resolves a fact sheet, discovers registered code projects, and crawls
selected IDs (or `"*"`), refreshes `local_code_index`/`code_graph`, and crawls the same explicit
source roots with `incrementalByContentHash=true`. It waits through the optional `LEARNING` step,
then uses `graph_embeddings`, `graph_reason`, `graph_reasoning_query`, or the `ask_graph_*`
primitives. The answer should report the project, fact sheet, source evidence, model, and trace rather
than relying on an unbounded dump. `graph_export` produces a portable `.kgraph` checkpoint for
transfer or archival; `graph_import` restores that lifecycle.

A destructive graph clear or explicit `forceFullRecrawl=true` is an operator decision. Remote
connectors should eventually provide authoritative tombstones or complete-snapshot markers so remote
deletion can use the same reconciliation contract without guessing from a partial crawl.
