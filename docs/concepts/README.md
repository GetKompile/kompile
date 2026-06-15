# How It All Fits Together

This page explains how Kompile's pieces connect. The individual pages in
this section go deep on each topic — this page gives you the map.

## The core loop

Everything in Kompile revolves around one loop:

```
Data in  ->  Structure it  ->  Ask questions  ->  Get grounded answers
```

**Data in**: [Crawl jobs](crawl-jobs.md) pull documents from 25+ source
types — files, web pages, email, Slack, Confluence, Google Drive, databases,
and more. Each source has a dedicated loader that understands the format.

**Structure it**: The [ingestion pipeline](ingestion-pipeline.md) chunks
documents, embeds them into vectors, and optionally extracts a
[knowledge graph](knowledge-graphs.md) of entities and relationships. The
result is a searchable index of your data.

**Ask questions**: The [retrieval system](information-retrieval.md) finds
relevant content through vector search, keyword search, graph traversal,
or all three combined.

**Get grounded answers**: An LLM generates a response using the retrieved
context, not its training data. Source attribution shows which documents
contributed to the answer.

## What connects to what

```
                    +------------------+
                    |  Your data       |
                    |  (files, web,    |
                    |   email, cloud)  |
                    +--------+---------+
                             |
                     [Crawl jobs]
                             |
              +--------------+--------------+
              |                             |
     +--------v--------+          +--------v--------+
     |  Vector index   |          | Knowledge graph |
     |  (chunks +      |          | (entities +     |
     |   embeddings)   |          |  relationships) |
     +--------+--------+          +--------+--------+
              |                             |
              +-------+  [Query]  +---------+
                      |           |
               +------v-----------v------+
               |  Retrieved context      |
               |  (chunks + graph nodes  |
               |   + fact sheet data)    |
               +------------+------------+
                            |
                     +------v------+
                     |  LLM        |
                     |  (generate  |
                     |   answer)   |
                     +------+------+
                            |
                     +------v------+
                     |  Response   |
                     |  with source|
                     |  attribution|
                     +-------------+
```

The **vector index** and **knowledge graph** are independent. You can use
either alone or both together:

- **Vector index only** (default): Good for content questions. "What does
  the docs say about X?" Fast and effective for most use cases.

- **Knowledge graph only**: Good for structural questions when you've
  already extracted entities. "How do these teams relate?" Less common
  as a standalone mode.

- **Both** (Graph RAG): Best for complex questions that need both content
  and context. "How does the authentication change affect downstream
  services?" The graph adds relationship context that vector search alone
  would miss.

## The three ways to interact

### 1. Web UI (http://localhost:8080)

The server's Angular frontend gives you:

| Screen | What you do there |
|--------|------------------|
| **Chat** | Ask questions, get RAG-augmented answers, switch between RAG and agent mode |
| **Knowledge** | Create crawl jobs, manage the knowledge graph, organize fact sheets |
| **Code Projects** | Register code repos, trigger indexing, browse the code graph |
| **Tools** | Configure MCP servers, invoke tools, view audit logs |
| **KClaw** | Run AI agents in the browser with live tool injection |
| **Settings** | Configure everything: vector store, chunking, embeddings, LLM, guardrails |
| **Developer** | ND4J status, subprocess logs, benchmarks, model debug |

See [Web UI](../server/web-ui.md) for details.

### 2. CLI

The command line for everything else:

```bash
kompile chat                     # Chat with an LLM
kompile crawl start <source>     # Ingest documents
kompile graph search "query"     # Query the knowledge graph
kompile code-index search "fn"   # Search indexed code
kompile mcp-stdio                # Serve MCP tools to an agent
kompile build app                # Generate a custom application
kompile model download <repo>    # Download a model
```

See [CLI Reference](../cli/README.md) for the full command set.

### 3. MCP tools (for AI agents)

When you run `kompile project open`, it writes `.mcp.json` so agents
auto-discover Kompile's tools. An agent using Kompile's MCP tools can:

- Search your documents (`rag_search`, `knowledge_search`)
- Query the knowledge graph (`graph_search`, `knowledge_graph`)
- Search code (`code_search`, `code_graph`)
- Read and write files (`read`, `write`, `edit`, `patch`)
- Run commands (`bash`, `process`)
- Fetch web pages (`webfetch`, `websearch`, `browser`)
- Delegate to sub-agents (`task`, `multi_task`, `quorum_task`)
- Manage persistent memory (`semantic_memory`, `memory`)

See [MCP Integration](../mcp/README.md) for tool profiles and transport
modes.

## How crawls feed everything

A crawl job is where data enters the system. Everything downstream depends
on what the crawl produces:

```
Crawl job
  |
  +-> Vector index      (always)
  |     Used by: vector search, hybrid search, RAG queries
  |
  +-> Knowledge graph   (when --graph is enabled)
  |     Used by: Graph RAG, graph algorithms, Cypher queries,
  |              Bayesian inference, community detection
  |
  +-> Fact sheet scope   (when --fact-sheet is set)
  |     Used by: scoped retrieval, fact-grounded answers
  |
  +-> Document metadata  (always)
        Used by: filters, freshness scoring, source attribution
```

You control the trade-off between speed and richness:

| What you enable | Crawl time | What you get |
|-----------------|-----------|-------------|
| Nothing extra | Fast | Vector search + keyword search |
| `--graph` | Slower (LLM calls per chunk batch) | + entity/relationship graph, Graph RAG, algorithms |
| `--graph --multimodal` | Slowest | + VLM OCR for image-heavy PDFs, table extraction |

You can always re-crawl the same sources with new options. Incremental
crawls skip already-processed documents.

See [Crawl Jobs](crawl-jobs.md) for the full crawl system.

## How the knowledge graph adds value

Without a graph, you search *chunks*. With a graph, you also search
*entities and their connections*.

**Example without graph:**
> Q: "What is OpenAI's relationship with Microsoft?"
> A: (returns chunks that mention both, but doesn't connect them)

**Example with graph:**
> Q: "What is OpenAI's relationship with Microsoft?"
> A: (returns chunks + a graph path: OpenAI --INVESTED_BY--> Microsoft,
>    OpenAI --PARTNERSHIP--> Microsoft Azure, plus community summaries
>    about the broader AI ecosystem)

The graph also enables:

- **Community detection**: Louvain algorithm finds clusters of related
  entities. Each cluster gets an LLM-generated summary. Graph RAG global
  search uses these for big-picture questions.

- **Graph algorithms**: PageRank for importance, betweenness for
  bottlenecks, shortest path for connection chains.

- **Bayesian inference**: Build probabilistic models over the graph for
  causal reasoning, event attribution, and risk analysis.

- **Temporal queries**: Graph change tracking records every mutation.
  You can reconstruct the graph at any past point in time.

See [Knowledge Graphs](knowledge-graphs.md) for the full system.

## How retrieval composes

When a query arrives, multiple retrieval mechanisms can fire in parallel:

| Mechanism | What it finds | When it fires |
|-----------|--------------|---------------|
| Dense vector search | Semantically similar chunks | Always (unless disabled) |
| BM25 keyword search | Exact keyword matches | Always (unless disabled) |
| Graph RAG local | Entity neighborhoods | When graph is available |
| Graph RAG global | Community summaries | When graph + communities are available |
| Fact sheet injection | Curated facts | When a fact sheet is active |
| Freshness scoring | Recent documents boosted | When configured |

Results are merged, deduplicated (highest score wins), and assembled into
a context prompt. The LLM sees all of it together.

**Query transformation** runs before retrieval to improve results:

| Strategy | When to use it |
|----------|---------------|
| `passthrough` | Simple, direct questions |
| `hyde` | Questions where the answer would look different from the question |
| `multi-query` | Complex questions with multiple parts |
| `step-back` | Specific questions that need broader context |

**Reranking** runs after retrieval to re-score results. Cross-encoder
reranking is the most effective but slowest. RM3 and BM25 feedback are
faster alternatives.

See [Information Retrieval](information-retrieval.md) for the full
architecture.

## How fact sheets scope your queries

A [fact sheet](fact-sheets.md) is a named partition of your knowledge base.
When a fact sheet is active, queries are scoped to its documents and its
curated facts are injected into the context.

This matters when you have multiple distinct knowledge domains:

```bash
# Create fact sheets for different domains
# (via web UI: Knowledge > Fact Sheets)

# Crawl with fact sheet scoping
kompile crawl start /data/engineering/ --fact-sheet=engineering
kompile crawl start /data/finance/ --fact-sheet=finance

# Queries hit only the active fact sheet's documents
kompile fact-sheets activate engineering
kompile chat --url=http://localhost:8080
# -> Only retrieves from engineering documents
```

Fact sheets also connect to the knowledge graph: `kompile graph build
--fact-sheet-id=<id>` builds a graph from a specific fact sheet's
documents.

## How agents use all of this

Agents interact with Kompile through MCP tools. The tools abstract away
the complexity — the agent calls `rag_search` and gets back relevant
chunks, or calls `knowledge_graph` and gets back entity relationships.

The typical agent flow:

1. Agent receives a task from the user
2. Agent calls `rag_search` to find relevant documents
3. Agent calls `code_search` if the task involves code
4. Agent calls `knowledge_graph` if it needs relationship context
5. Agent uses the retrieved context to complete the task
6. Conversation turns are indexed into `semantic_memory` for next time

**Policy enforcement** wraps all of this: the enforcer watches agent
output against rules (banned commands, diff patterns, LLM judge) and can
interrupt, rollback, and retry.

See [Agents](agents.md) for all agent modes and
[MCP Integration](../mcp/README.md) for the tool catalog.

## How code projects integrate

[Code projects](code-projects.md) are source code repositories indexed
for semantic search. The indexer (ANTLR4-based) parses source files into
entities (classes, methods, functions, interfaces, fields, imports) and
relationships (calls, extends, implements, contains).

Code search runs independently of document search. An agent can call
`code_search` to find a function and `rag_search` to find documentation
about that function — both from the same tool set.

```bash
# Register and index a code project
kompile project add-code-project --dir=./src
kompile project index-code-project <id>

# Search
kompile code-index search "handleRequest"
kompile code-index callers "processDocument"    # Who calls this?
kompile code-index impact "VectorStore"          # What breaks if this changes?
```

## How configuration flows

All runtime configuration lives in JSON files at `~/.kompile/config/`.
Three interfaces read and write the same files:

```
CLI wizards  ──┐
               ├──>  ~/.kompile/config/*.json  ──>  Server reads on startup
Web UI       ──┤                                    (and reloads on change)
REST API     ──┘
```

This means you can configure via whichever interface you prefer, and the
changes are visible everywhere. `kompile configure app` runs a 9-section
interactive wizard. The web UI Settings screen gives you the same options
with a GUI. The REST API gives you programmatic access.

**Feature flags** (`feature-flags-config.json`) toggle major features:
guardrails, query transformation, contextual RAG, tool gateway, KV cache,
Graph RAG, multi-modal. Enable what you need.

**Auto-configuration** (`POST /api/auto-configure/apply`) probes your
hardware and sets optimal values for subprocess counts, ND4J threads,
batch sizes, and memory limits.

See [Configuration](../configuration/README.md) for the full config
reference.

## What's in each concept page

| Page | What you'll learn |
|------|------------------|
| [Crawl Jobs](crawl-jobs.md) | Source types, pipeline routing, adaptive batching, PDF routing, memory monitoring, distributed crawls, lifecycle management |
| [Ingestion Pipeline](ingestion-pipeline.md) | Pipeline phases, subprocess architecture, chunking strategies, monitoring |
| [Knowledge Graphs](knowledge-graphs.md) | Entity extraction, entity resolution, graph algorithms, Bayesian networks, MEBN, KG embeddings, Graph RAG, change tracking, maintenance, import/export |
| [Information Retrieval](information-retrieval.md) | Vector search, BM25, hybrid search, query transformation, reranking, contextual RAG, semantic memory, transcript search, code search |
| [Fact Sheets](fact-sheets.md) | Knowledge partitioning, curated facts, auto-derivation, graph building |
| [Agents](agents.md) | Agent modes, passthrough, KClaw, A2A protocol, policy enforcement, evaluation, session management |
| [Code Projects](code-projects.md) | Code indexing, entity types, search, code graph analysis |
| [Skills and Templates](skills-and-templates.md) | Reusable agent skills, Jinja-style prompt templates |
| [Guardrails and Evaluation](guardrails-and-evaluation.md) | Input/output filters, evaluation harness, experiments |
