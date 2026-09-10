# Code Projects

Register source code repositories and index them for semantic code search. The code indexer parses source files into a graph of entities and relationships, enabling structural queries beyond simple text search.

## Indexing a codebase

```bash
# Index a directory (no server required)
kompile code-index --path=/path/to/project

# Watch for file changes and re-index
kompile code-index watch --path=/path/to/project

# Guided setup
kompile configure code-index
```

## Supported languages

Java, Python, Go, Rust, C++, plus configuration and script files.

## Entity types

The indexer extracts these entity types from source code:

`FILE`, `PACKAGE`, `CLASS`, `INTERFACE`, `METHOD`, `FUNCTION`, `FIELD`, `IMPORT`, `TYPE_ALIAS`, `RATIONALE`

**Rationale entities** are extracted from tagged comments (`NOTE:`, `WHY:`, `HACK:`, `TODO:`, etc.) to capture the reasoning behind code decisions.

## Search

```bash
# Search by keyword
kompile code-index search --query="authentication" --type=METHOD

# Find symbol paths
kompile code-index spath --symbol=UserService

# Find by regex
kompile code-index find --regex=".*Controller"

# Find usages of a symbol
kompile code-index usages --symbol=authenticate

# Replace by regex
kompile code-index replace --regex="oldName" --replacement="newName"

# List indexed projects
kompile code-index list

# Index statistics
kompile code-index stats
```

## Code graph

The code graph provides structural analysis:

```bash
# Build the code graph
kompile code-index graph build

# Find callers of a function
kompile code-index graph callers --symbol=processRequest

# Show relationships for a symbol
kompile code-index graph relations --symbol=UserService

# File-level analysis
kompile code-index graph file --path=src/main/java/Auth.java

# Impact analysis
kompile code-index graph impact --symbol=authenticate

# Dependency analysis
kompile code-index graph deps --symbol=UserService

# Component detection
kompile code-index graph component

# Generate a symbol dossier
kompile code-index graph dossier --symbol=UserService

# Export the graph
kompile code-index graph export --output=graph.json

# Test framework detection
kompile code-index graph test-frameworks

# Find tests for a symbol
kompile code-index graph tests-for --symbol=processRequest

# Code path analysis
kompile code-index graph code-paths --from=A --to=B
```

## Web UI

Code Projects screen in the web UI. Register repositories, trigger indexing, browse the code graph, and use code search to inform agent sessions.

## MCP tools

Code search is exposed as MCP tools (`code_search`, `code_graph`, `local_code_index`) so agents can query your codebase during chat sessions.

Folder-local indexing schedules projection into the owning project's portable
`data/crawls/<knowledge-base>/graph.kgraph`. Index completion, projection completion, and optional
learning completion are separate phases; inspect `local_code_index action=index_status` before
assuming graph consumers see the latest generation. The projection result reports the exact knowledge-base id.
`code_search` and `code_graph` default to local operation. An explicitly configured server selects
its separate dataset: failures do not silently fall back locally, and local-only actions reject
that routing choice. Managed indexing does not publish the folder-local graph.
Use that id to move from source lookup to relationship-aware graph retrieval:

```text
local_code_index action=index directory=/path/to/project project_id=my-project
graph_search query="request authentication flow" knowledgeBase=my-project-knowledge code_project_id=my-project search_type=hybrid
graph_reasoning_query operation=SEARCH question="request authentication flow" knowledgeBase=my-project-knowledge
```

Folder-local `graph_search` supports ranked `local`, one-hop `hybrid`, and two-hop `global`
search. Managed Graph RAG may additionally generate community summaries.

When documents are crawled into a knowledge base that already contains an indexed code projection,
the crawl runs extraction and learning on its bounded document graph, then preserves unselected
code slices from the previous archive before a single atomic graph publication. It does not need
the old SQLite index to restore those slices, and does not label a newer index with an old generation.
The crawl's terminal status and `finishedAt` remain authoritative. Preserved code learning is marked
`STALE` until retrained; stale learned vectors/posteriors are not used by the guarded local consumers.

### Evidence quality, temporal queries, and bounded work

The structural index is a heuristic parser, not a compiler. Projected `CALLS` carry
`evidenceKind=heuristic-source-pattern` plus source locations. Local verification does not turn
these observations into authoritative calls; local pattern-query rows identify heuristic evidence.
Ambiguous names require exact entity ids or an unambiguous fully-qualified name.

Local `ask_graph_verify` honors `minConfidence`; `ask_graph_query` combines recorded edge
confidences using the Lukasiewicz conjunction and excludes zero-confidence edges. Its local-only
`maxWork` defaults to 100000 relation examinations (maximum 1000000). Exceeding the budget returns
an error rather than incomplete intermediate joins. Local requests allow at most 64 conjuncts.
`maxResults` bounds output, not join work.

Local `validAt` filters **currently retained** entities and relationships by validity:

```text
ask_graph_verify atom="CALLS(entity-a, entity-b)" validAt=2026-09-01T00:00:00Z
```

Declared `validFrom`/`validUntil` use a half-open interval; timestamps imply validity from that
instant, and timeless records remain included as temporally unspecified. Both endpoints must be
valid. Full-graph learned posteriors are not used for the filtered topology. This is **not historical
reconstruction**: deleted/overwritten facts are not recovered. Local `asOf` remains rejected;
`validAt` and `maxWork` are rejected on managed routes rather than silently ignored.

### Opt-in language-server call hierarchy

For stronger static call analysis at an exact declaration, use an installed server that advertises
LSP call-hierarchy support:

```text
lsp action=call_hierarchy file_path=src/main/java/example/OrderService.java line=12 column=17 direction=incoming max_results=20 timeout_ms=10000
```

Input positions are 1-based. Returned declaration/call-site ranges use LSP's 0-based UTF-16,
end-exclusive coordinates. Incoming and outgoing analysis is one hop only; multiple prepared
declarations return explicit ambiguity choices, never an arbitrary overload. Results preserve
call-site ranges but are not persisted into KGraph. Missing capability, timeout, and protocol
errors are explicit. Initialization failure stops the unregistered server child.

Limits bound retained results and protocol wait, not remote server computation/response size,
startup, synchronization, or connection-lock wait. Cancellation is best-effort. Language-server
static results are neither guaranteed complete nor proof of runtime dynamic dispatch. A CLI/MCP
rebuild and restart is required to advertise newly added tool parameters to external clients.

### File context and durable notes

Agents can retrieve the context attached to the exact file they are reading without running a
separate fuzzy search:

```text
read file_path=src/main/java/example/OrderService.java include_context=true
file_context file_path=src/main/java/example/OrderService.java
file_note action=add file_path=src/main/java/example/OrderService.java content="Preserve idempotency when changing retry behavior."
```

`read` keeps its normal line-numbered output unless `include_context=true` is requested.
`file_context` returns the same context without rereading the file: durable notes, bounded indexed
declarations and structural relations, plus an exact one-hop neighborhood from the projected
KGraph. A missing or stale index/graph is reported explicitly; lookup never starts indexing,
learning, a model, or a crawl. `read_batch` supports the same flag globally or per object entry and
caps both the number and rendered size of contextual files.

When MCP stdio explicitly uses the shared daemon, host-native single-file `read` remains omitted to
avoid registering a duplicate tool. In that mode call `file_context` after the host's read;
Kompile's contextual `read_batch` remains available for batched reads.

`file_note` adds notes or deletes one by its returned `note_id`. Notes are stored in the owning
Kompile project's code-project metadata (`data/code-projects/<id>/metadata/file-notes.json`), not in
the generated SQLite or KGraph artifacts, so they survive reindexing and graph replacement. For an
external registered checkout, the active owner project's metadata remains authoritative. Up to twelve
notes are retained per file so every note id remains discoverable. Writes use cross-process locking
and directory-handle-relative atomic rename so concurrent agents do not erase one another's notes;
filesystems without secure directory handles fail closed.

### Optional learned code-graph layers

Structural indexing and graph lookup are always available. KGE, PSL, and MEBN learning is
opt-in because it may launch a large subprocess and take minutes. Inspect or update the
project-local configuration through `code_graph`:

```text
code_graph action=learning_config_get
code_graph action=learning_config_update config_json='{"enabled":true,"kgeTraining":true,"triggers":["build"]}'
code_graph action=learn
```

A changed code projection is marked `phase.codeLearning.<projectId>=STALE`. A successful
learning pass records the matching `codeLearningGeneration.<projectId>` and changes the phase
to `COMPLETED`, so graph status cannot imply that newly indexed code already has vectors.

## When to use code projects

Use code projects when agents need structural understanding of a codebase:
- Finding where a function is defined and who calls it
- Impact analysis before refactoring
- Understanding dependencies between modules
- Navigating unfamiliar code during review

Code search works independently of document search. An agent can call
`code_search` to find a function and `rag_search` to find documentation
about that function — both from the same MCP tool set.

## Related concepts

- **[Information Retrieval](information-retrieval.md)** — code search is
  one of several retrieval mechanisms, alongside vector search and Graph RAG
- **[Agents](agents.md)** — agents access code search through MCP tools
- **[MCP Integration](../mcp/README.md)** — tool profiles control which
  code tools are exposed to agents
