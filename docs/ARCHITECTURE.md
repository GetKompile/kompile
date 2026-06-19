# Kompile Architecture Overview

Kompile is an AI/ML platform that combines CLI tooling, a Spring Boot RAG framework, an ML pipeline execution engine, and native tokenizer bindings into a unified system for building, deploying, and operating AI applications on the JVM.

**Version:** 0.1.0-SNAPSHOT  
**Java:** 17 (21 for Anserini)  
**Build:** Maven 3.9+  

---

## Repository Structure

```
kompile/
├── kompile-cli/                        CLI application (Picocli)
├── kompile-cli-plugin-api/             CLI plugin framework
├── kompile-cli-common/                 Shared CLI utilities
├── kompile-app/                        RAG framework (Spring Boot, 60+ modules)
├── kompile-app-cli/                    Application management CLI
├── kompile-model-cli/                  Model management CLI
├── kompile-agent-cli/                  Agent framework CLI
├── kompile-component-cli/              Component management CLI
├── kompile-pipelines-framework/        Pipeline execution engine
├── kompile-model-importer-tensorflow/  TensorFlow model converter
├── kompile-model-importer-onnx/        ONNX model converter
├── kompile-model-importer-keras/       Keras model converter
├── kompile-sdk-serving/                Model serving SDK
├── tokenizers-rust/                    HuggingFace tokenizer bindings (Rust/JavaCPP)
├── anserini/                           Anserini IR toolkit fork (Lucene 9.9.1)
├── kompile-rag-builds/                 Sample RAG project templates
└── pom.xml                             Parent POM
```

---

## 1. kompile-cli

The main user-facing entry point. Built with Picocli, compilable to GraalVM native image.

**Entry point:** `ai.kompile.cli.main.MainCommand`

### Command Hierarchy

| Command | Subcommands | Purpose |
|---------|-------------|---------|
| `info` | -- | Display installation info (version, services, instances, config) |
| `bootstrap` | -- | Initialize `~/.kompile` directory structure |
| `build` | `app`, `pom-generate`, `rag-pom-generate`, `native-image-generate`, `clone-build`, `dl4j-build-generate`, `nd4j-backend`, `sync-sample` | Build applications and components |
| `install` | `graalvm`, `python`, `maven`, `all`, `native-tools`, `openblas`, `headers`, `kompile-app`, `kompile-model-staging`, `sdk-install`, `python-wrappers`, `program-indexer` | Install platform dependencies |
| `uninstall` | `graalvm`, `python`, `maven`, `all` | Remove installed components |
| `manage` | `start`, `stop`, `restart`, `status`, `list`, `logs` | Manage running Kompile services |
| `sdk` | `list`, `download`, `scaffold`, `serve` | Mobile SDK management and local LLM serving |
| `pipeline` | `exec`, `validate`, `list-steps`, `serve`, `new-step` | Pipeline operations |
| `chat` | -- | Interactive chat REPL (server or direct LLM) |
| `lite-chat` | -- | Chat with kompile-lite instance |
| `passthrough` | -- | Transparent passthrough to CLI agents (claude, codex, gemini) |
| `session` | `list`, `show`, `import`, `import-all`, `search`, `translate`, `merge` | Chat session management |
| `resume` | -- | Multi-tab TUI for browsing and resuming conversations |
| `mcp-stdio` | -- | MCP stdio server for tool integration |
| `config` | (dynamic) | Configuration management |
| `ingest` | `file`, `path`, `url`, `status`, `cancel`, `list` | Document ingestion to kompile-app |
| `index` | `status`, `rebuild`, `vector` | Index and vector population management |
| `jobs` | `list`, `show`, `logs`, `stats` | Indexing job history |
| `schedule` | `create`, `list`, `delete` | Scheduled job management |
| `subprocess` | `list`, `status`, `config`, `events`, `stats` | Subprocess monitoring |

### Build System (`build app`)

The `build app` command generates custom Kompile applications from presets or module selection:

**Presets:**
- `hosted-llm-rag` -- RAG with hosted LLM APIs (OpenAI, Anthropic, Gemini)
- `samediff-rag` -- RAG with local SameDiff embeddings
- `pipeline` -- Pipeline execution only
- `full` -- All modules enabled
- `minimal` -- Core only

**Module categories for `--include`/`--exclude`:**
- LLM providers (openai, anthropic, gemini, springai)
- Embedding models (openai, anserini, samediff, sentence-transformer, postgresml)
- Vector stores (pgvector, chroma, vespa, anserini)
- Document loaders (tika, microsoft, mail, slack, pdf-extended, pdf-tables, web, email-imap)
- Text chunkers (token, sentence, recursive-character, markdown, table-aware)
- Tools (rag, filesystem, model-staging)
- Source providers (confluence, jira, gdrive, notion, slack, reddit, email)
- Advanced features (knowledge-graph, neo4j, guardrails, evaluation, react-agent, kvcache, ocr)

The build system generates a Maven POM, invokes Maven to compile, and optionally builds a GraalVM native image.

### Chat System

The CLI includes a full-featured chat system with:
- **Multi-agent support:** Claude, Codex, Gemini, custom agents
- **Tool integration:** Bash, file read/write/edit, glob, grep, web fetch/search, RAG search, graph RAG search, memory, task management
- **MCP support:** Model Context Protocol tool injection
- **Session persistence:** Import/export across agents, search, merge, translate formats
- **Role system:** Architect, reviewer, devops roles with customizable prompts
- **Setup wizard:** Interactive LLM provider configuration

### CLI Delegation

For external binaries installed alongside kompile:
```
kompile app <args>    --> kompile-app binary
kompile model <args>  --> kompile-model binary
kompile agent <args>  --> kompile-agent binary
kompile lite <args>   --> kompile-lite binary
```

### Plugin Architecture

Plugins implement `CliCommandRegistrar` (ServiceLoader discovery) to register additional commands at startup.

### Native Image

GraalVM native image build configured in `kompile-cli/pom.xml` (`-Pnative` profile):
- 18GB heap for compilation
- Runtime initialization for: Netty, JavaCPP/Bytedeco, DL4J/ND4J, JNA, JLine, JGit
- Resource inclusion for META-INF configs, ND4J resources, schema files

---

## 2. kompile-app (RAG Framework)

A Spring Boot application framework with 60+ pluggable modules for building RAG systems.

### Core Interfaces (`kompile-app-core`)

```java
// Embedding generation
interface EmbeddingModel extends AutoCloseable {
    INDArray embed(String text);
    INDArray embed(List<String> texts);
    int dimensions();
    int getOptimalBatchSize();
    boolean isInitialized();
}

// Vector storage and similarity search
interface VectorStore {
    int add(List<Document> documents);
    int addWithEmbeddings(List<Document> documents, INDArray embeddings);
    List<Document> similaritySearch(String query, int k);
    List<ScoredDocument> similaritySearchWithScores(INDArray queryEmbedding, int k, double threshold);
    boolean delete(List<String> ids);
    boolean flushAndCommit();
}

// Document retrieval
interface DocumentRetriever {
    List<String> retrieve(String query, int maxResults);
    List<RetrievedDoc> retrieveWithDetails(String query, int maxResults);
}

// LLM interaction
interface LanguageModel {
    String generateResponse(String userQuery, List<String> context);
    ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context);
}

// Conversational LLM with memory
interface ConversationalLanguageModel extends LanguageModel {
    String generateConversationalResponse(String conversationId, String userQuery, List<String> context);
    void clearConversation(String conversationId);
    KompileChatMemory getChatMemory();
}
```

Additional core interfaces: `TextChunker`, `ContentExtractor`, `LLMEntityExtractor`, `LLMFactExtractor`, `McpServerConfig`, `McpToolDefinition`, `KGEmbeddingModel`.

### Module Catalog

#### LLM Providers
| Module | Provider |
|--------|----------|
| `kompile-app-openai-llm` | OpenAI API |
| `kompile-app-anthropic-llm` | Anthropic Claude |
| `kompile-app-gemini-llm` | Google Gemini |
| `kompile-app-springai-llm` | Spring AI multi-provider |
| `kompile-pipelines-app-llm` | Pipeline-based LLM |

#### Embedding Models
| Module | Implementation |
|--------|---------------|
| `kompile-embedding-openai` | OpenAI embeddings API |
| `kompile-embedding-anserini` | SameDiff encoders (BGE, Arctic, DPR, SPLADE) |
| `kompile-embedding-samediff` | Local SameDiff models |
| `kompile-embedding-sentence-transformer` | Python subprocess wrapper |
| `kompile-embedding-postgresml` | PostgresML server-side |

#### Vector Stores
| Module | Backend |
|--------|---------|
| `kompile-vectorstore-anserini` | Lucene HNSW (primary) |
| `kompile-vectorstore-pgvector` | PostgreSQL pgvector |
| `kompile-vectorstore-chroma` | Chroma DB |
| `kompile-vectorstore-vespa` | Vespa search |

#### Document Loaders
| Module | Formats |
|--------|---------|
| `kompile-loader-tika` | Multi-format via Apache Tika |
| `kompile-loader-microsoft` | Office 365 / OneDrive / SharePoint |
| `kompile-loader-mail` | SMTP email |
| `kompile-loader-email-imap` | IMAP email |
| `kompile-loader-slack` | Slack exports |
| `kompile-loader-pdf-extended` | Advanced PDF processing |
| `kompile-loader-pdf-tables` | PDF table extraction |
| `kompile-loader-web` | Web scraping |
| `kompile-app-loaders-orchestrator` | Loader coordination |

#### Source Providers (OAuth-based)
`kompile-source-confluence`, `kompile-source-jira`, `kompile-source-gdrive`, `kompile-source-notion`, `kompile-source-slack`, `kompile-source-reddit`, `kompile-source-email`

Shared OAuth2 utilities in `kompile-oauth2-client`.

#### Text Chunkers
`kompile-chunker-token`, `kompile-chunker-sentence`, `kompile-chunker-recursivecharacter`, `kompile-chunker-markdown`, `kompile-chunker-table-aware`

#### Tools
| Module | Purpose |
|--------|---------|
| `kompile-tool-rag` | RAG query tool (Spring AI `@Tool`) |
| `kompile-tool-filesystem` | File system operations |
| `kompile-tool-model-staging` | Model staging tools |

#### Advanced Features
| Module | Purpose |
|--------|---------|
| `kompile-knowledge-graph` | Knowledge graph construction and querying |
| `kompile-graph-neo4j` | Neo4j graph database backend |
| `kompile-query-transformer` | Query reformulation and augmentation |
| `kompile-guardrails` | Safety and content filtering |
| `kompile-filter-chain` | Pluggable pre/post-retrieval filters |
| `kompile-evaluation` | RAG evaluation metrics (RAGAS) |
| `kompile-react-agent` | ReAct multi-step reasoning agent |
| `kompile-kvcache` | LLM KV cache management (paged, evictable, prefix) |
| `kompile-chat-history` | Conversation persistence |
| `kompile-orchestrator` | Orchestration engine |
| `kompile-pipeline-management` | Pipeline lifecycle management |

#### OCR Pipeline
`kompile-ocr-core`, `kompile-ocr-models`, `kompile-ocr-datapipeline`, `kompile-ocr-integration`, `kompile-ocr-postprocess`

### RAG Data Flow

```
INGESTION
=========
Documents ──> Loaders ──> Text Conversion ──> [OCR] ──> Preprocessing
    ──> Parallel Ingest Pipeline:
        1. Tokenization
        2. Chunking (token/sentence/recursive/markdown/table-aware)
        3. Metadata enrichment
        4. Embedding generation (batch, interrupt-aware)
        5. Vector store indexing
    ──> [Optional subprocess scaling]
    ──> WebSocket progress updates

QUERY
=====
User Query ──> Query Processing (intent detection, rewriting)
    ──> [Query Transformation]
    ──> [Pre-retrieval Filter Chain]
    ──> Embedding Generation
    ──> Hybrid Retrieval:
        ├── Semantic: Vector similarity (HNSW/pgvector/Chroma/Vespa)
        └── Keyword: BM25 via Anserini
    ──> Result merging + deduplication
    ──> [Reranking]
    ──> Context Building (token budget, conversation history, KG context)
    ──> LLM Generation (with MCP tool calling support)
    ──> [Post-retrieval Filter Chain]
    ──> [Guardrails]
    ──> Update chat memory
    ──> Response (REST or streaming WebSocket)
```

### Subprocess Architecture

For scaling, the main application can offload work to subprocesses:

**Subprocess types:** `ingest`, `vector-population`, `embedding`, `model-init`, `training`, `vlm-test`

**Launch modes:**
1. **Auto** (default): Detects native image vs JVM automatically
2. **JVM**: `java -cp <classpath> <MainClass>`
3. **Native**: `/path/to/kompile-app --subprocess=TYPE`

The unified native executable routes subprocess types in `MainApplication.main()` before Spring Boot starts. Each subprocess type has a dedicated launcher and main class.

`SubprocessMemoryWatchdog` monitors GPU and system memory with velocity tracking across all subprocess types.

### Main Application Services

**Key service areas in `kompile-app-main`:**

- **RAG orchestration:** `RagServiceImpl`, `KompileRagOrchestratorImpl`, `HybridOptimizedRetriever`, `DefaultQueryProcessor`, `DefaultContextBuilder`
- **Document ingestion:** `DocumentIngestService` (async, WebSocket progress, subprocess scaling, cancellation)
- **MCP (Model Context Protocol):** `McpServerManagerImpl`, `RestMcpBridgeManagerImpl`, `StdioMcpClientRuntime`, `ExternalMcpServerManager`, `ToolDefinitionService`, `ToolPermissionService`
- **Agent system:** `AgentChatService`, `AgentRegistryService`, `CliAgentLlmProvider`, `ApiAgentChatExecutor`
- **Indexing:** `IndexingJobHistoryService`, `IngestEventService`, `IngestCheckpointService`, `IndexSyncService`
- **Pipeline:** `ParallelIngestPipeline`, `AdaptivePipelineConfig`, `ScheduledPipelineService`

**50+ REST controllers** covering RAG queries, indexing, MCP management, agent chat, configuration, monitoring, backup, knowledge graph, and more.

### Frontend (Angular)

The Angular frontend provides:
- **Document management:** Upload, browse, debug, folder navigation
- **Index browser:** Search indexed content, chunk management
- **MCP hub:** Server builder, debugger, config manager, tools viewer
- **Knowledge graph:** Builder, 3D visualizer, entity browser, KG embeddings
- **KV Cache:** Browser, config, stats, checkpoint manager, prefix cache viewer
- **Unified chat:** Multi-agent chat interface with history
- **Developer hub:** System configuration, device routing, subprocess config
- **Evaluation:** Debugger, managed eval, benchmark runner
- **Administration:** Job history, connections, backup, archive management, model staging

---

## 3. kompile-pipelines-framework

A modular pipeline execution engine for composing ML workflows.

### API Layer (`kompile-pipelines-framework-api`)

Core abstractions:
- `Pipeline` -- Sequence or graph of executable steps
- `PipelineStepRunner` -- Step execution with lifecycle: `init()`, `exec()`, `close()`
- `PipelineStepRunnerFactory` -- ServiceLoader-discovered factory for creating runners
- `PipelineExecutor` -- Manages runner lifecycle and data flow
- `StepConfig` -- Configuration per step (parameters, runner class name)
- `Context` -- Execution context with key-value storage, metrics, profiling, KV cache
- `Data` -- Generic map-like container with typed values (`ValueType`: FLOAT32, FLOAT64, INT8, INT32, INT64, BOOL, STRING, NDARRAY, IMAGE, etc.)
- `NDArray` -- N-dimensional array abstraction (name, shape, element type, ByteBuffer access)

### Core Layer (`kompile-pipelines-framework-core`)

Implementations: `GenericStepConfig`, `JDataFactory`, `SchemaRegistry`, `ConfigAccessor`, `DefaultContext`, JSON/YAML ObjectMappers.

### Runtime Layer (`kompile-pipelines-framework-runtime`)

**Execution models:**
- `SequencePipeline` + `SequencePipelineExecutor` -- Linear step execution
- Graph pipeline support via `GraphNodeConfig`, `SwitchFn` (conditional routing), `CombineFn` (branch merging)
- `PipelineToolCallOrchestrator` -- LLM tool calling within pipelines

### Pipeline Steps

| Module | Purpose | Key Tech |
|--------|---------|----------|
| `kompile-pipelines-steps-deeplearning4j` | DL4J model inference and training | deeplearning4j-nn, MultiLayerNetwork, ComputationGraph |
| `kompile-pipelines-steps-samediff` | SameDiff model inference, LLM generation | samediff-pipeline-core/ggml/onnx/safetensors, samediff-llm, samediff-vlm |
| `kompile-pipelines-steps-onnx` | ONNX Runtime model inference | onnxruntime-platform (Bytedeco) |
| `kompile-pipelines-steps-python` | Python subprocess execution | python4j-numpy, commons-exec |
| `kompile-pipeline-steps-nd4j-common` | Shared ND4J utilities, image processing, training config | Image normalization, aspect ratio, updater/schedule generation |
| `kompile-pipline-steps-document-parser` | Document parsing | Apache Tika 2.9.1 |
| `kompile-pipelines-steps-vlm` | Vision/multimodal model support | VLM pipeline integration |

---

## 4. tokenizers-rust

High-performance HuggingFace tokenizer bindings for Java via Rust and JavaCPP.

### Architecture

```
Rust (HuggingFace tokenizers) --> C wrapper --> JavaCPP JNI bindings --> Java API
```

**Modules:**
- `libtokenizers/` -- Rust source (BPE, WordPiece, Unigram, Word-level models) with pre-tokenizers, normalizers, decoders, and post-processors
- `tokenizers-native-preset/` -- JavaCPP preset definitions
- `tokenizers-native/` -- Generated Java bindings with platform-specific natives

**Platforms:** linux-x86_64, macosx-x86_64, macosx-arm64, windows-x86_64

---

## 5. anserini

Fork of the Anserini information retrieval toolkit, extended with SameDiff encoders for dense and sparse retrieval.

### Encoders (`io.anserini.encoder.samediff`)

**Dense encoders:** `BgeSameDiffEncoder`, `ArcticEmbedSameDiffEncoder`, `CosDprDistilSameDiffEncoder`, `VlmImageEncoder`

**Sparse encoders:** `SpladePlusPlusSameDiffEncoder`, `SpladePlusPlusEnsembleDistilSameDiffEncoder`, `UniCoilSameDiffEncoder`

**Base classes:** `GenericDenseSameDiffEncoder`, `SameDiffEncoder`, `SameDiffSparseEncoder`

Built on Lucene 9.9.1. Includes a Next.js frontend for search UI.

---

## 6. Model Management

### kompile-model-manager

Centralized model registry, download, and caching:
- `ModelConstants` -- Catalog of supported models with URLs, SHA256 checksums, dimensions
- `ModelDescriptor` -- Model metadata (ID, URL, checksum, dimensions, type)
- `ModelBundle` -- Container for model + vocabulary + tokenizer config
- Downloads to `~/.kompile/models/`, verifies SHA256, extracts tar.gz archives

### Model Importers

Three standalone converter tools (CLI + REST API each):

| Module | Input | Output |
|--------|-------|--------|
| `kompile-model-importer-tensorflow` | TensorFlow (.pb, checkpoints) | SameDiff (.fb) |
| `kompile-model-importer-onnx` | ONNX (.onnx) | SameDiff (.fb) |
| `kompile-model-importer-keras` | Keras (.h5, .keras) | DL4J (.zip) |

---

## 7. Native Image & Deployment

### GraalVM Native Image

Both `kompile-cli` and `kompile-app-main` support native image compilation.

**kompile-app-main unified executable:**
- Single binary handles main app + all subprocess types via `--subprocess=TYPE` flag
- Separate native-image Maven profiles: `native` (main), `native-ingest`, `native-vector`, `native-embedding`, `native-model-init`
- Per-target configs at `META-INF/native-image/{main,ingest,vector,embedding,model-init}/`

**Key initialization constraints:**
- Build-time: SLF4J, Logback, ND4J deallocation
- Run-time: JavaCPP, ND4J, DL4J, Lucene, Netty epoll, JGit

### Docker

Rocky Linux 8 base image. Supports both CLI and application deployments with configurable memory limits and file descriptor settings.

---

## 8. Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Spring Boot | 3.2.5 | Application framework |
| Spring AI | 1.0.0 | LLM/embedding abstraction |
| Picocli | 4.7.7 | CLI framework |
| ND4J | 1.0.0-SNAPSHOT | Tensor computation |
| DL4J | 1.0.0-SNAPSHOT | Deep learning |
| Lucene | 9.9.1 | Text and vector search (via Anserini) |
| JavaCPP | 1.5.11 | JNI bindings |
| GraalVM SDK | 24.0.1 | Native compilation |
| Jackson | 2.15.3 | JSON/YAML serialization |
| Lombok | 1.18.42 | Code generation |
| Netty | 4.1.100.Final | Network transport |
| FlatBuffers | 23.5.26 | Model serialization |

---

## 9. Design Principles

1. **Plugin architecture:** Spring Boot conditional beans (`@ConditionalOnProperty`), Picocli command discovery via ClassGraph, ServiceLoader for pipeline steps and CLI plugins
2. **Interface-based pluggability:** Swap any component (embeddings, vector stores, LLMs, chunkers, loaders) by changing configuration
3. **Subprocess scaling:** Heavy work (ingestion, embedding, vector population) can be offloaded to separate JVM or native processes
4. **MCP integration:** Model Context Protocol for tool interoperability with external AI systems
5. **Hybrid retrieval:** Combines semantic (vector similarity) and keyword (BM25) search with optional reranking
6. **Resource management:** ND4J workspace cleanup, OpenBLAS thread termination, native library extraction, GPU memory watchdog
7. **Multi-platform:** Maven profiles for OS/architecture detection, platform-specific native library bundles
8. **Progressive complexity:** From `kompile chat` (zero-config chat) to full RAG deployment with knowledge graphs, guardrails, and evaluation
