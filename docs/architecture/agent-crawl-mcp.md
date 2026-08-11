# Agent-driven crawl MCP tools

Kompile exposes one crawl contract to interactive agents, external agents using the stdio MCP server,
and the headless crawl worker:

| Tool | Purpose |
|---|---|
| `crawl_discover` | Discover source types, pipelines, loaders, chunkers, runtime limits, code projects, and knowledge bases. |
| `crawl_documents` | Add selected files, directories, or code projects to a knowledge base. |
| `crawl_source` | Add one path or inline text source, or preview it with `dryRun`. |
| `crawl_control` | Inspect crawl state and use the lifecycle operations supported by the active backend. |
| `knowledge_search` | Search indexed knowledge, including project-local indexes. |
| `knowledge_status` | List knowledge bases and their document/chunk counts. |
| `local_code_index` / `code_graph` | Incrementally index project code and inspect its structural dependency graph. |
| `graph_embeddings` | Train or inspect TransE/RotatE models; score and predict graph links. |
| `knowledge_graph` / `ask_graph_*` | Query, assert, retract, explain, synthesize, and verify graph knowledge. |
| `graph_reason*` / graph algorithms | Reason, search, aggregate, simulate, forecast, rank centrality, and run Bayesian analysis. |
| `graph_import` / `graph_export` | Restore or checkpoint the complete graph, vectors, and learned models as `.kgraph`. |

The tools select their executor from configuration; callers do not need a separate offline API.

| Configuration | Executor | Intended use |
|---|---|---|
| No crawl URL | Project-local crawl subprocess | A code project, one agent, no Kompile instance |
| Explicit `--url`, `--crawl-url`, or configured server URL | Crawl manager | Distributed workload, remote coordination, shared services |

The stdio server deliberately leaves the crawl URL unset when `kompile mcp-stdio` is started without
`--url`. In that case the stdio process validates the request and launches an isolated child JVM to
load, transform, and chunk documents. The resulting knowledge artifacts are stored in the active
project. A native-image distribution executes the same local engine inline; discovery reports the
effective execution mode. Supplying `--url` opts into the managed crawl service.

## Discover before selecting a pipeline

Call `crawl_discover` with one of these sections:

- `sources`: source types available to the active executor.
- `pipelines`: pipeline kinds, step IDs, installed loaders and chunkers, and processing routes.
- `runtime`: execution mode, capacity, and effective runtime configuration.
- `knowledge_bases`: available local knowledge indexes or remote fact sheets.
- `code_projects`: code projects registered in `kompile.project.json`, or the implicit current
  project when working locally without a manifest.
- `all`: every group.

Discovery is executor-specific. The project-local response advertises executable `auto`, `text`,
`markdown`, `html`, `pdf`, and `code` loaders; `recursive-character`, `sentence`, and `no-op`
chunkers; and the built-in `standard-text` and `code` pipeline templates. The names in discovery are
the names accepted by `crawl_documents`, not descriptive metadata. It also marks distributed-only
features such as URL connectors and OCR/VLM/table-aware processing as unavailable.
A connected crawl manager reports its live pipeline catalog, including any installed
`STANDARD_TEXT`, `VLM`, `OCR`, `CODE`, `TABLE_AWARE`, `KEYWORD_ONLY`, or custom pipelines.
This lets an agent configure a supported request instead of guessing component names.

## Add selected documents

A project-local request can be as small as:

```json
{
  "knowledgeBase": {"name": "project-notes"},
  "documents": [
    {"path": "docs/architecture.md"},
    {"path": "decisions/adr-001.md"}
  ]
}
```

Paths may be files or directories. Each document can include filesystem filters and executable
loader/chunker selections:

```json
{
  "name": "project knowledge refresh",
  "knowledgeBase": {"name": "project-notes"},
  "documents": [
    {
      "path": "docs",
      "sourceType": "DIRECTORY",
      "includePatterns": ["**/*.md", "**/*.pdf"],
      "excludePatterns": ["**/generated/**"],
      "loaderName": "auto",
      "chunkerName": "recursive-character",
      "chunkSize": 1200,
      "chunkOverlap": 120
    }
  ],
  "codeProjects": ["*"],
  "embeddingTraining": {
    "enabled": true,
    "algorithm": "ROTATE",
    "embeddingDim": 128,
    "epochs": 25,
    "batchSize": 512,
    "warmStartEpochs": 5
  }
}
```

Named pipelines can be declared and selected for one document, routed by file characteristics, or
used as the request default:

```json
{
  "knowledgeBase": {"name": "mixed-project-knowledge"},
  "documents": [
    {"path": "README.md", "pipelineId": "whole-note"},
    {"path": "src"}
  ],
  "pipelines": [
    {
      "pipelineId": "whole-note",
      "pipelineType": "STANDARD_TEXT",
      "loaderName": "markdown",
      "chunkerName": "no-op"
    },
    {
      "pipelineId": "project-code",
      "pipelineType": "CODE",
      "loaderName": "code",
      "chunkerName": "recursive-character",
      "chunkSize": 1800,
      "chunkOverlap": 180
    }
  ],
  "routeRules": [
    {
      "pipelineId": "project-code",
      "fileExtensions": [".java", ".kt", ".py", ".ts"],
      "priority": 10
    }
  ],
  "defaultPipelineId": "standard-text"
}
```

Resolution order is: a document's `pipelineId`, the first matching route rule by priority,
`defaultPipelineId`, then the automatic built-in pipeline. Document-level loader, chunker, size,
overlap, and `chunkerOptions` values override the selected pipeline's defaults. Unsupported names or
local-only-incompatible pipeline types are rejected before the subprocess starts; call
`crawl_discover(section="pipelines")` to get the current executable catalog.

With the local executor, `codeProjects: ["*"]` selects every active registered code project. If no
project manifest or code-project registry exists, it indexes the current project as an implicit code
project. Common source, build, configuration, markup, data, and PDF files are converted into markdown
and chunks. Generated/build directories and the local `data` artifact tree are excluded by default.

For a managed crawl, the returned fact-sheet ID is persisted back onto every selected
`KompileCodingProject` and its `CodeProject` index record. A changed binding prunes the old AST graph
projection and asynchronously forces a deterministic code-index rebuild into the new fact sheet.
Current and future AST discoveries, source crawling, embedding/model training, reasoning, mutation,
and `.kgraph` export therefore share that fact-sheet ID. If a project already has a binding,
`crawl_documents` reuses it and rejects an explicitly conflicting `knowledgeBase.id`.

Repeated calls targeting the same `knowledgeBase` are additive. The executor merges the stored source
set with the newly selected sources and synchronously rebuilds that knowledge base. It returns
`status: "COMPLETED"` only after the artifacts are ready for `knowledge_search`. The artifact layout is:

```text
<project>/data/crawls/<knowledge-base-id>/
  crawl-result.json
  analysis.json
  documents.jsonl
  chunks.jsonl
  mcp-request.json
<project>/data/markdown/<knowledge-base-id>/
  <document-id>.md
```

`crawl_source` uses the same local path for a single file or inline text. Inline text is persisted as
a stable project source under `data/knowledge-sources/<knowledge-base-id>/` before indexing. A dry run
writes neither the source nor index artifacts.

A URL document or `crawl_source.url` requires the managed crawl service. With a configured manager,
the same tool contract accepts URL sources and the full `UnifiedCrawlRequest` surface:

```json
{
  "name": "finance policy refresh",
  "knowledgeBase": {"name": "finance-kb"},
  "documents": [
    {"path": "/shared/reports/q1.pdf", "loaderName": "pdf"},
    {"url": "https://example.test/policy.html"}
  ],
  "steps": ["GRAPH_EXTRACTION", "VECTOR_INDEXING"],
  "strictSteps": true,
  "pipelines": [
    {
      "pipelineId": "finance-pdf",
      "pipelineType": "TABLE_AWARE",
      "enableGraphExtraction": true,
      "collectionName": "finance-documents"
    }
  ],
  "defaultPipelineId": "finance-pdf",
  "runtimeConfig": {
    "sourceLoadParallelism": 4,
    "graphExtractionParallelism": 8
  }
}
```

Top-level fields cover the common unified-crawl controls. `config` remains an advanced pass-through
for current or future distributed request fields. Explicit `documents`, top-level settings, and
`knowledgeBase` take precedence over their pass-through equivalents.

## Use and inspect local knowledge

Use `knowledge_status` to list all project indexes or select one with `knowledgeBase`. Use
`knowledge_search` with `query`, optional `knowledgeBase`, and optional `limit` to run attributed
lexical retrieval over the stored chunks.

Local `crawl_control` supports synchronous operations that make sense without a coordinator:
`preflight`, `start`, `status`, `list`, `transcript`, `source_types`, `runtime_config`, and
`graph_stats`. There is no queued local job after a tool call returns. Cancellation, pause/resume,
retry, step scheduling, archive, and graph mutation therefore return an explicit message that a
distributed crawl manager is required.

The project knowledge base and Kompile memory are complementary stores:

- Crawl tools own project documents and searchable chunks under `data/crawls`.
- `memory` owns durable user, feedback, project, and reference memories.
- `semantic_memory` provides semantic memory operations when configured.

All are registered in the same stdio MCP session, so an offline agent can recall prior Kompile
memories while building or querying the current code project's knowledge base. A Kompile application
instance is not required for this combination.

## Managed and distributed lifecycle

When a crawl-manager URL is configured, `crawl_documents` returns a queued job ID. Pass it to
`crawl_control` for status, cancellation, retry, transcript inspection, or individual step execution.
The manager owns incremental content-hash manifests, deletion reconciliation, shared fact sheets,
graph/vector persistence, connectors, and workload distribution.

Post-enrichment KGE runs inside the crawl's tracked `LEARNING` step. Per-crawl
`embeddingTraining` values override service defaults; omitted values keep the configured behavior.
The crawl remains running until training completes or is explicitly skipped, making model readiness
observable before reasoning or snapshot export.

A successful managed crawl commits its per-document SHA-256 manifest only after persistence completes.
If extraction or persistence fails, the previous manifest remains authoritative. Complete local
file/directory discovery can remove contributions for files no longer in that source scope; remote
URL absence is not inferred from a partial crawl.

`clearGraphBeforeRun` is destructive and implies `forceFullRecrawl`, because an empty graph cannot
be rebuilt from a manifest that marks every source unchanged.

## Offline agent worker

`kompile crawl --offline` runs the direct/headless agent as the indexing worker. It does not connect
to the chat server or open the TUI. Without `--crawl-url`, the agent discovers and invokes the
project-local MCP executor, which launches the same isolated crawl subprocess used by stdio:

```bash
kompile crawl --offline \
  --document docs/architecture.md \
  --document decisions/adr-001.md \
  --knowledge-base project-notes
```

The model may call `crawl_discover`, add sources, check local status, search the result, and use
`memory` in the same run. The full normal tool surface remains available; “worker” describes the
agent's job, not a restricted tool profile.

Specify `--crawl-url` only when opting into distributed/remote execution:

```bash
kompile crawl --offline \
  --crawl-url http://localhost:8082 \
  --model local-tool-model \
  --pipeline-id finance-pdf \
  --document /shared/reports/q1.pdf \
  --knowledge-base 42
```

A complete advanced request can also be supplied:

```bash
kompile crawl --offline --request-file unified-crawl-request.json
```

The headless run retains its normal step/tool budgets, transcript, and append-only checkpoint ledger
under `~/.kompile/crawl-runs/<session-id>/`. Only the explicit crawl URL changes execution from the
agent-owned project index to the remotely coordinated crawl system.
