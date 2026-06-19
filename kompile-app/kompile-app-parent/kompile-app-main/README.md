# kompile-app-main

The web application server for the Kompile platform. Provides the RAG Console UI, 120+ REST API controllers, MCP server endpoints, WebSocket real-time updates, and subprocess orchestration for document ingestion, embedding, and vector indexing. This is the primary user-facing application.

## Building

```bash
# JVM JAR (with Angular frontend)
mvn clean package

# Without frontend (faster backend-only iteration)
mvn clean package -Dskip.ui

# Install/update frontend dependencies (first time or after package.json changes)
mvn clean install -Dui.deps

# GraalVM native image
mvn clean package -Pnative -DskipTests
```

Main class: `ai.kompile.app.MainApplication`

## Running

```bash
# Standard startup on port 8080
java -jar kompile-app-main-*-exec.jar

# Custom port
java -jar kompile-app-main-*-exec.jar --server.port=9090

# With increased heap for large document processing
java -Xmx8g -jar kompile-app-main-*-exec.jar
```

You usually don't run this manually -- `kompile web` launches it automatically along with `kompile-model-staging` on port 8090.

## Default Configuration

| Setting | Default |
|---------|---------|
| Port | `8080` |
| Data directory | `~/.kompile` |
| Orchestrator DB | H2 at `~/.kompile/data/orchestrator-db` |
| Chat history DB | H2 at `~/.kompile/data/chat-history` |
| Document uploads | `./data/input_documents/uploads` |
| Max upload size | 500 MB |
| Default encoder | `bge-base-en-v1.5` |
| Default reranker | `ms-marco-MiniLM-L-6-v2` |
| Vector similarity | COSINE |
| HNSW M | 16 |
| HNSW efConstruction | 100 |
| Chunk size | 2000 chars |
| Chunk overlap | 200 chars |
| Chunking strategy | `table-aware` |
| Backup interval | 6 hours |
| Backup retention | 7 days |
| Staging server URL | `http://localhost:8090` |

Override via `application.properties`, command-line `--property=value`, or environment variables.

## Frontend

Angular 19.2.11 single-page application served as static resources. The `SpaForwardController` handles client-side routing by forwarding non-API, non-static requests to `index.html`.

Key UI features:
- RAG Console with conversational chat
- Document management and upload
- Index browser and search testing
- Knowledge graph visualization (D3)
- Model registry and staging controls
- System diagnostics and ND4J environment configuration
- Crawler management
- KV cache monitoring

The frontend uses dynamic API URL detection via `window.location` -- no hard-coded ports.

## Architecture

### Subprocess Isolation

Heavy processing runs in isolated subprocesses to prevent crashes from affecting the main application. The `--subprocess=TYPE` flag routes to the appropriate subprocess main class, enabling a single native binary to serve all roles:

| Type | Purpose |
|------|---------|
| `ingest` | Document parsing and chunking |
| `vector-population` | Embedding generation and vector indexing |
| `embedding` | Standalone embedding operations |
| `model-init` | Model download and initialization |
| `vlm-test` | Vision-language model testing |
| `training` | Model training jobs |
| `graph` | Knowledge graph operations |
| `serving` | Model serving |
| `pipeline-serving` | Pipeline serving |

Subprocesses communicate back to the main app via HTTP callbacks and STDOUT JSON. Heartbeat monitoring detects stuck processes.

### Model Staging Integration

On startup, `kompile-app-main` auto-discovers and launches `kompile-model-staging` as a child process on port 8090. Promoted models from staging are automatically wired into the RAG pipeline via `AnseriniEncoderFactory` and `CrossEncoderRerankerAdapter`.

### Real-Time Updates

WebSocket/STOMP endpoint at `/ws` provides real-time progress updates for:
- Document ingestion progress
- Vector population status
- Crawl job progress (also via SSE at `/api/crawl-progress/stream`)

### MCP Server

Two MCP server implementations run simultaneously:

| Implementation | Endpoint | Protocol |
|----------------|----------|----------|
| Spring AI MCP | `/sse`, `/mcp/message` | SSE (2024-11-05) |
| Custom MCP | `/mcp/sse` (GET + POST) | Streamable HTTP (2025-03-26) |

Both expose RAG tools, filesystem tools, and knowledge graph tools to MCP clients.

## REST API

### Document Management (`/api/documents`)

Upload, list, delete, and re-index documents. Supports PDF, Office, email, and text formats. Large documents (>10MB or >50 pages) automatically use streaming processing with resume support.

### RAG & Search

- `/api/rag` -- Conversational RAG with multi-turn context
- `/api/retriever` -- Direct document retrieval
- `/api/knowledge-search` -- Unified search across vector and keyword indexes
- `/api/index-browser` -- Browse and inspect index contents
- `/api/cross-index` -- Cross-index search and comparison
- `/api/diff-index` -- Index diff and change tracking

### Indexing & Ingestion

- `/api/indexer` -- Trigger indexing operations
- `/api/vector-population` -- Vector store population from Lucene indexes
- `/api/ingest-events` -- Ingest event log and history
- `/api/job-log` -- Per-job log viewer
- `/api/indexing-jobs` -- Job history with filtering
- `/api/ingest/resume` -- Resume interrupted jobs

### Knowledge Graph

- `/api/graph-rag` -- GraphRAG queries (local, global, hybrid search)
- `/api/graph-extraction` -- Entity/relationship extraction
- `/api/graph-eval` -- Graph quality evaluation

### Crawlers

- `/api/crawlers` -- Manage web, Confluence, Notion, Slack, SharePoint crawlers
- `/api/unified-crawl` -- Unified multi-source crawl orchestration
- `/api/distributed-crawl` -- Distributed crawling across nodes
- `/api/crawl-progress/stream` -- SSE progress streaming

### Chat & Agents

- `/api/agent-chat` -- Agentic chat with tool use
- `/api/conversational-rag` -- Multi-turn RAG conversations
- `/api/passthrough-chat` -- Passthrough to external LLM agents
- `/api/chat-sessions` -- Chat session management and context
- `/api/react-agent/config` -- ReAct agent configuration

### Models & Embeddings

- `/api/model-registry` -- Model catalog and registry
- `/api/model-debug` -- Model inspection and debugging
- `/api/model-warmup` -- Pre-warm models on startup
- `/api/model-weight-cache` -- Weight caching configuration
- `/api/model-scheduler` -- Model loading/unloading schedules
- `/api/model-admission` -- Model admission control
- `/api/local-model` -- Local model management (proxies to staging server)

### MCP Tools & Configuration

- `/api/mcp/tools` -- MCP tool listing and invocation
- `/api/mcp/server-builder` -- Build custom MCP server configurations
- `/api/mcp/clients` -- External MCP server connections
- `/api/mcp/cli-injection` -- CLI MCP tool injection
- `/api/mcp/bridge` -- REST-to-MCP bridge
- `/api/mcp/action-log` -- MCP action audit log
- `/api/mcp/optimization` -- Schema optimization settings

### Configuration

- `/api/config` -- Application configuration
- `/api/nd4j/environment` -- ND4J runtime settings (threads, memory, CUDA, Triton)
- `/api/processing-settings` -- Chunking, embedding, and indexing settings
- `/api/batch-size-config` -- Batch size tuning
- `/api/subprocess/config` -- Subprocess settings
- `/api/guardrails/config` -- Input/output guardrails
- `/api/query-transform/config` -- Query transformation settings
- `/api/evaluation/config` -- RAG evaluation metrics
- `/api/llm-pipeline/config` -- LLM pipeline settings
- `/api/vlm-pipeline/config` -- VLM pipeline settings
- `/api/contextual-rag/config` -- Contextual RAG settings

### System & Diagnostics

- `/api/system-info` -- System information
- `/api/system-resources` -- CPU, memory, disk, GPU stats
- `/api/diagnostics` -- Comprehensive system diagnostics
- `/api/lifecycle-tracking` -- ND4J lifecycle tracking
- `/api/memory-pool` -- Native memory pool stats
- `/api/op-timing` -- Operation timing profiler
- `/api/gpu-lifecycle` -- GPU memory management
- `/api/triton-cache` -- Triton compilation cache
- `/api/monitor` -- Health monitoring
- `/api/service-state` -- Service state machine
- `/api/backup` -- Backup management
- `/api/log-config` -- Runtime log level control

### Projects

- `/api/projects` -- Project CRUD, status, and configuration

### Tools & Skills

- `/api/tool-definitions` -- Custom tool definitions
- `/api/tool-gateway/config` -- Tool gateway rules
- `/api/tool-permissions` -- Tool access control
- `/api/prompt-templates` -- Prompt template management
- `/api/skills` -- Skill management and invocation
- `/api/enforcer-sessions` -- Policy enforcement sessions
- `/api/tool-call-catalog` -- Tool call audit history

### OAuth & Integrations

- `/api/oauth` -- OAuth2 token management (Google, Microsoft, Atlassian, Notion, Slack)
- `/api/source-providers` -- Data source provider configuration
- `/api/confluence` -- Confluence integration

### VLM (Vision-Language Models)

- `/api/vlm/models` -- VLM model management
- `/api/vlm/orchestration` -- VLM workflow orchestration
- `/api/vlm/test-workflow` -- VLM testing workflows

### Other

- `/api/notes` -- Note management and sync
- `/api/experiments` -- A/B experiment tracking
- `/api/fact-sheets` -- Generated document fact sheets
- `/api/schedules` -- Scheduled task management
- `/api/build-app` -- RAG application builder
- `/api/samediff/benchmark` -- SameDiff benchmarking
- `/api/sdk` -- SDK operations
- `/api/install-manager` -- Component install management
- `/api/config-archive` -- Configuration snapshot/restore
- `/api/test-milestones` -- Test milestone tracking

## Subprocess Configuration

Document ingestion and vector population run in isolated subprocesses by default:

```properties
# Ingest subprocess
kompile.ingest.subprocess.enabled=true
kompile.ingest.subprocess.heap-size=4g
kompile.ingest.subprocess.timeout-minutes=60

# Vector population subprocess
kompile.vectorpopulation.subprocess.enabled=true
kompile.vectorpopulation.subprocess.heap-size=4g
kompile.vectorpopulation.subprocess.timeout-minutes=120
kompile.vectorpopulation.subprocess.embedding-batch-size=32
```

## Databases

Two H2 embedded databases:

| Database | Path | Purpose |
|----------|------|---------|
| Orchestrator | `~/.kompile/data/orchestrator-db` | Task orchestration, workflows, ingest events, job logs |
| Chat History | `~/.kompile/data/chat-history` | Conversation sessions and messages |

H2 Console available at `/h2-console` (disabled for remote access by default).

## Backup Service

Periodic backup of H2 databases and Lucene indexes:

```properties
kompile.backup.enabled=true
kompile.backup.backup-path=${user.home}/.kompile/backups
kompile.backup.fixed-rate-ms=21600000   # 6 hours
kompile.backup.retention-days=7
kompile.backup.format=COMPRESSED        # tar.gz
```

## OAuth2 Providers

Centralized OAuth2 token management with encrypted storage:

| Provider | Scopes |
|----------|--------|
| Google | Drive read, email, profile |
| Microsoft | Files read, user read |
| Atlassian | Confluence content, Jira |
| Notion | Integration access |
| Slack | Channel history, users |

Set credentials via environment variables (`GOOGLE_CLIENT_ID`, `MICROSOFT_CLIENT_ID`, etc.) or `application.properties`.

## Native Image Profiles

Nine GraalVM native image profiles for building specialized binaries:

| Profile | Description |
|---------|-------------|
| `native` | Main web application |
| `native-ingest` | Ingest subprocess |
| `native-vector` | Vector population subprocess |
| `native-embedding` | Embedding subprocess |
| `native-model-init` | Model initialization subprocess |
| `native-vlm-test` | VLM testing subprocess |
| `native-training` | Training subprocess |
| `native-graph` | Graph subprocess |
| `native-serving` | Model serving subprocess |

The unified executable approach uses a single binary with `--subprocess=TYPE` routing instead of separate binaries.

## Observability

- **Actuator**: `/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`
- **Metrics**: Micrometer with Prometheus export, tagged with `application=kompile`
- **Logging**: Runtime log level control via `/api/log-config`
- **Session metrics**: `/api/session-metrics`
