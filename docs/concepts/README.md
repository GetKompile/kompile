# Core Concepts

This section explains the key concepts in Kompile and how they relate to each other.

## Projects

A **project** is the central organizing unit. It is a directory backed by a `kompile.project.json` manifest that ties together documents, models, code repositories, crawl profiles, pipelines, prompt templates, fact sheets, and chat sessions. Projects move through lifecycle states: `DRAFT -> ACTIVE -> PAUSED -> ARCHIVED | DEPRECATED`. They can be version-controlled with Git or Git-XET.

See [Projects](../getting-started/projects.md) for setup details.

## Crawl jobs

A **crawl job** discovers and ingests documents from one or more sources into the vector index. Each job has a source type (file, web, email, cloud service), depth limits, document limits, and pattern filters. Crawl jobs track their progress through phases and support pause, resume, cancel, and incremental re-crawl via checkpoints.

See [Crawl Jobs](crawl-jobs.md) for the full lifecycle.

## The ingestion pipeline

When a crawl job discovers documents, they pass through a multi-phase pipeline:

```
QUEUED -> DISCOVERING -> LOADING -> CHUNKING -> GRAPH_EXTRACTION ->
ENTITY_RESOLUTION -> EMBEDDING -> VECTOR_INDEXING -> COMPLETED
```

Each phase runs as an isolated subprocess (the same binary re-launched with `--subprocess=TYPE`). This keeps the web server responsive and provides memory isolation.

See [Ingestion Pipeline](ingestion-pipeline.md) for details on each phase.

## Knowledge graphs

A **knowledge graph** stores entities and relationships extracted from your documents. During ingestion, an LLM reads each chunk and extracts structured entities (people, organizations, concepts, technologies) and the relationships between them. Entity resolution deduplicates entries using embedding similarity and string matching.

Graphs support hierarchy (named graphs can be nested), community detection, traversal, Cypher queries, import/export, and maintenance operations.

See [Knowledge Graphs](knowledge-graphs.md) for usage.

## Fact sheets

A **fact sheet** is a named collection of key-value assertions that ground agent responses. Facts can be manually curated or auto-derived from indexed content. One fact sheet is "active" at a time, and its facts are injected into the RAG context during queries. Fact sheets support per-sheet embedding model config and reindexing.

See [Fact Sheets](fact-sheets.md) for details.

## Skills and prompt templates

A **skill** is a named, reusable agent capability (like a slash command). Skills have categories, descriptions, associated tools, and can be auto-expanded into sub-skills. They are exposed as MCP prompts.

A **prompt template** is a Jinja-style text template with named variables. Templates can be rendered with specific variable values, duplicated, and organized by category. They are used to compose structured prompts for LLM interactions.

See [Skills and Prompt Templates](skills-and-templates.md) for usage.

## Sessions

A **session** is a persistent chat conversation. Sessions can be created from direct LLM chat, server-connected RAG chat, or agent passthrough mode. They store the full message history and can be resumed, searched, imported from external AI providers (Claude, ChatGPT, Gemini), merged, and translated between formats.

## Agents

Kompile supports multiple agent execution modes:

- **Direct chat**: Kompile itself acts as the chat interface to an LLM
- **Passthrough**: Kompile wraps an external CLI agent (Claude Code, Codex, Gemini) and injects MCP tools
- **KClaw**: Run CLI agents interactively in the browser with tool injection and permission management
- **ReAct agents**: Server-side agents with tool-calling loops
- **A2A**: Agent-to-Agent protocol for multi-agent coordination across services

## Code projects

A **code project** is a registered source code repository indexed for semantic search. Kompile parses source files into a graph of entities (files, packages, classes, methods, functions, fields, imports) and relationships (calls, extends, implements, contains). The index supports search by entity type, caller graphs, unused function detection, and impact analysis.

## Vector stores

A **vector store** holds document embeddings for similarity search. Kompile supports multiple backends: Anserini (Lucene HNSW, the default), pgvector (PostgreSQL), Chroma, and Vespa. All backends implement the same interface, so they are interchangeable via configuration.

## Embedding models

**Embedding models** convert text into numerical vectors for similarity search. Kompile supports local models (Anserini SameDiff encoders like BGE, Arctic Embed, E5), OpenAI API, PostgresML, sentence-transformers (Python subprocess), and direct SameDiff models. The same model must be used for both indexing and querying.

## Subprocesses

Compute-heavy operations (embedding, indexing, model loading, training, VLM inference) run as **subprocesses** -- the same binary re-launched with `--subprocess=TYPE`. This provides memory isolation and keeps the web server responsive. Subprocess types: `ingest`, `vector-population`, `embedding`, `model-init`, `vlm-test`, `training`.

## Guardrails

**Guardrails** are input and output filters that protect against prompt injection, PII leakage, toxicity, hallucination, off-topic responses, and other risks. Individual guardrails can be toggled on/off via configuration.

## Evaluation

The **evaluation harness** runs test suites against your RAG pipeline to measure retrieval quality, answer relevance, and other metrics. Experiments track runs over time for model comparison and regression detection.
