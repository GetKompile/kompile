# kompile-app

The Spring Boot RAG framework with 40+ pluggable modules. This is the largest part of the codebase.

## Core interfaces

Defined in `kompile-app-core`:

| Interface | Method | Description |
|-----------|--------|------------|
| `EmbeddingModel` | `embed(text)` | Returns `INDArray` embedding vector |
| `VectorStore` | `add/search/delete` | Document storage and similarity search |
| `DocumentRetriever` | `retrieve(query, k)` | Returns top-k documents |
| `LanguageModel` | `generate(prompt)` | LLM text generation |

## Module categories

### Embeddings (`kompile-embedding-*`)

| Module | Backend |
|--------|---------|
| `kompile-embedding-anserini` | SameDiff encoders (BGE, Arctic Embed, E5) |
| `kompile-embedding-openai` | OpenAI API |
| `kompile-embedding-postgresml` | PostgreSQL ML |
| `kompile-embedding-samediff` | Direct SameDiff |
| `kompile-embedding-sentence-transformer` | Python subprocess |

### Vector stores (`kompile-vectorstore-*`)

| Module | Backend |
|--------|---------|
| `kompile-vectorstore-anserini` | Lucene HNSW (primary) |
| `kompile-vectorstore-pgvector` | PostgreSQL pgvector |
| `kompile-vectorstore-chroma` | Chroma client |
| `kompile-vectorstore-vespa` | Vespa |

### Document loaders (`kompile-loader-*`)

PDF (extended + tables), Office, Tika, web crawler, audio, Excel, email (IMAP, inbox, mail), Slack, Discord, Google Docs/Drive/Gmail/Workspace, Microsoft, OneDrive.

### Data sources (`kompile-source-*`)

Confluence, Discord, email, Google Docs/Drive/Gmail/Workspace, Jira, Notion, OneDrive, Reddit, Slack.

### Chunkers (`kompile-chunker-*`)

Markdown, recursive-character, sentence, token, table-aware.

### LLM providers

OpenAI, Anthropic, Gemini, CLI agent passthrough, Spring AI, local SameDiff.

### Other notable modules

| Module | Purpose |
|--------|---------|
| `kompile-model-manager` | Model download/cache to `~/.kompile/models/` with SHA256 verification |
| `kompile-model-staging` | Model lifecycle service (download, convert, validate, promote, serve) |
| `kompile-graph-neo4j` | Graph RAG with Neo4j |
| `kompile-knowledge-graph` | Knowledge graph construction |
| `kompile-kvcache` | Paged KV cache for local LLM inference |
| `kompile-kclaw` | Agent hub for running CLI agents in the browser |
| `kompile-code-indexer` | Semantic code search and indexing |
| `kompile-evaluation` | RAG evaluation harness |
| `kompile-guardrails` | Input/output guardrails |
| `kompile-ocr-*` | OCR pipeline (core, data pipeline, integration, models, post-process) |
| `kompile-a2a` | Agent-to-Agent protocol |
| `kompile-tool-*` | MCP/Spring AI tools (RAG, filesystem, graph, crawler, workflow, etc.) |

## Plugin architecture

Modules are activated via Spring conditional beans:

```java
@Component
@ConditionalOnProperty(name = "kompile.vectorstore.type", havingValue = "myvectorstore")
public class MyVectorStoreImpl implements VectorStore { ... }
```

## Building

```bash
# Build kompile-app-main only (never build the entire parent)
cd kompile-app/kompile-app-main
mvn clean package

# With UI (default)
mvn clean package

# Backend-only (faster iteration)
mvn clean package -Dskip.ui
```

## Adding a new module

When adding a new `kompile-app` sub-module referenced by `kompile-app-main`:

1. **Parent POM** (`kompile-app/pom.xml`): Add `<module>` and `<dependency>` in `<dependencyManagement>`
2. **Main app POM** (`kompile-app/kompile-app-main/pom.xml`): Add dependency
3. **Sample project POM** (`kompile-rag-builds/kompile-sample/project/pom.xml`): Add dependency
4. **RagPomGenerator** (`kompile-cli/.../RagPomGenerator.java`): Add `@CommandLine.Option` field and `addDependency()` call
