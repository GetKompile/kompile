# Kompile

[![Build Native Images - Linux x86_64](https://github.com/GetKompile/kompile/actions/workflows/build-native-linux-x86_64.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/build-native-linux-x86_64.yml)
[![Build Native Images - Linux ARM64](https://github.com/GetKompile/kompile/actions/workflows/build-native-linux-arm64.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/build-native-linux-arm64.yml)
[![Build Native Images - macOS ARM64](https://github.com/GetKompile/kompile/actions/workflows/build-native-mac-arm64.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/build-native-mac-arm64.yml)
[![Build Native Images - Windows x86_64](https://github.com/GetKompile/kompile/actions/workflows/build-native-windows-x86_64.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/build-native-windows-x86_64.yml)
[![Build SDK - Linux CUDA](https://github.com/GetKompile/kompile/actions/workflows/build-native-linux-cuda.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/build-native-linux-cuda.yml)
[![Build SDK - Windows CUDA](https://github.com/GetKompile/kompile/actions/workflows/build-native-windows-cuda.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/build-native-windows-cuda.yml)
[![Publish Release](https://github.com/GetKompile/kompile/actions/workflows/publish-release.yml/badge.svg)](https://github.com/GetKompile/kompile/actions/workflows/publish-release.yml)

Kompile is a self-hosted AI platform for building retrieval-augmented generation (RAG),
agentic chat, knowledge graph, and model inference applications. It ships as native
binaries — no JVM required — and runs entirely on your hardware. Embeddings, vector search,
and model inference are computed locally using
[ND4J/SameDiff](https://github.com/deeplearning4j/deeplearning4j) with CUDA or CPU backends.
Hosted LLMs (OpenAI, Anthropic, Gemini) can be plugged in for generation while keeping
all retrieval and data processing local.

## Download

Pre-built native binaries are published to
[GitHub Releases](https://github.com/GetKompile/kompile/releases):

| Platform | Archive |
|----------|---------|
| Linux x86_64 (CUDA) | `kompile-<version>-linux-x86_64-cuda12.9.tar.gz` |
| Linux x86_64 (CPU) | `kompile-<version>-linux-x86_64-cpu.tar.gz` |
| Linux ARM64 | `kompile-<version>-linux-arm64-cpu.tar.gz` |
| macOS Apple Silicon | `kompile-<version>-macosx-arm64-cpu.tar.gz` |
| Windows x86_64 | `kompile-<version>-windows-x86_64-cuda12.9.zip` |

Extract and run. Native libraries auto-resolve from the adjacent `lib/` directory — no
environment variables or setup needed.

```
kompile-<version>-<platform>/
  bin/
    kompile                # CLI
    kompile-server         # RAG server with web UI
    kompile-model-staging  # Model operations service
  lib/                     # Native libraries (ND4J, CUDA, JavaCPP)
  conf/
  data/
  models/
```

---

## Quick start

### 1. Create a project

A **project** is the central organizing concept in Kompile. It's a directory with a
`kompile.project.json` manifest that ties together your documents, models, code repositories,
crawl profiles, pipelines, prompt templates, and chat sessions.

```bash
# Initialize a project in the current directory
kompile project init --name my-rag-project

# Or start from a pre-built app template
kompile build app --configName=myapp --preset=hosted-llm-rag
```

`kompile project init` scans your directory for existing assets — build files, model files,
documents — and auto-detects what kind of project it is (data-only, code-only, models-only,
or a combination). It creates the standard directory structure:

```
my-rag-project/
  kompile.project.json          # Project manifest (ID, name, lifecycle, components)
  .kompile/                     # Local runtime state
  scripts/                      # Lifecycle scripts (start-all, stop-all)
  data/
    input_documents/            # Raw documents for ingestion
    models/                     # Model artifacts
    indices/                    # Lucene keyword + HNSW vector indices
    code-projects/              # Registered code repos for code search
    crawls/                     # Crawl output
    pipelines/                  # Inference pipelines
    prompt-templates/           # Reusable prompt templates
    fact-sheets/                # Notebook-style knowledge organization
    markdown/                   # Synced notes
    workflows/                  # Automation workflows
```

### 2. Build a generated application (alternative to project init)

`kompile build app` generates a complete, self-contained application as a Maven project.
This is different from `project init` — it produces a compiled artifact (JAR or native
binary) with exactly the modules you selected baked in:

```bash
kompile build app --configName=myapp --preset=hosted-llm-rag
```

The output lives at `kompile-rag-builds/myapp/project/` and includes:

```
kompile-rag-builds/myapp/project/
  kompile.project.json            # Project manifest (auto-generated)
  pom.xml                         # Maven POM with your selected modules
  src/main/resources/
    application.properties        # Structural config (ports, paths, provider flags)
  scripts/
    start-all.sh                  # Starts staging → serving → app in order
    stop-all.sh
    start-app.sh
    start-staging.sh
  data/
    input_documents/              # Drop documents here
    models/                       # Model artifacts
    indices/                      # Built during ingestion
    prompt-templates/             # Pre-seeded: rag_query, code_review, extract_entities, etc.
    crawls/                       # Crawl profile definitions
    fact-sheets/
    ...
  .kompile/
    project/open.json             # Tracks which project is active, ports, PID state
  target/
    myapp-0.1.0-SNAPSHOT-exec.jar # The compiled application (or native binary with -Pnative)
```

The generated `application.properties` handles structural wiring (ports, paths, which
Spring AI providers are enabled/disabled). All runtime configuration — vector store type,
batch sizes, ND4J settings, feature flags, LLM provider — lives in JSON files under
`~/.kompile/config/` and is managed through the web UI or CLI wizards, not properties files.

### 3. Open the project and start the server

```bash
# Start the web application for this project
kompile project open .

# This writes .kompile/project/open.json, starts the server on port 8080,
# starts model-staging on port 8090, and writes .mcp.json for AI agents
```

Open your browser to **http://localhost:8080**. The setup wizard walks you through:

1. **Staging server** — confirms kompile-model-staging is running
2. **Model source** — connects to the staging service or loads local models
3. **Embedding model** — downloads and initializes an embedding model (e.g., BGE, Arctic Embed)
4. **Document indexing** — ingest your first documents
5. **Search readiness** — verifies end-to-end retrieval works

### 4. Ingest documents

**From the web UI** (Knowledge tab → New Crawl Job):

The crawl job form lets you add one or more sources, each with its own type, path/URL,
max depth, and max document count. Configure graph extraction (LLM provider, entity types,
schema mode, entity resolution with embedding similarity), vector indexing (collection name,
batch size, adaptive batching), and PDF routing (auto/force-VLM/force-text). Submit and
watch progress in real-time — the UI streams pipeline counters (Found → Loaded → Chunks →
Entities → Embedded → Indexed), current phase, memory meters (heap, native, subprocess RSS),
adaptive batch sizing, and per-document status.

**From the CLI:**

```bash
kompile ingest file /path/to/document.pdf          # Upload a local file
kompile ingest path /path/to/documents/             # Register a directory
kompile ingest url https://docs.example.com         # Add a URL source
kompile ingest status                               # Check job progress
```

**Supported source types** (20+): local files and directories, web crawl (recursive with
configurable depth), S3, SFTP, SQL databases, email (IMAP, POP3, Gmail, Outlook PST, MBOX,
Maildir), Confluence, Jira, Notion, Slack, Discord, Google Drive, OneDrive, Google
Workspace, and SMB shares. Cloud sources use OAuth connections managed through the
**Connected Services** screen — each provider shows connection status, token expiry, required
scopes, and connect/disconnect/refresh actions.

**The ingestion pipeline** runs as isolated subprocesses (the same binary re-launched with
`--subprocess=TYPE`):

```
Documents → Loader → Chunker → (Graph Extraction) → Embedder → Vector Index
```

- **PDF routing**: Auto-classifies image-heavy PDFs and routes them through VLM OCR
- **Graph extraction**: LLM extracts entities and relationships per chunk, entity
  resolution deduplicates via embedding similarity + string matching
- **Adaptive batching**: Embedding batch sizes adjust based on ND4J memory pressure

### 5. Chat with your data

```bash
# Web UI: http://localhost:8080 → Chat tab

# CLI: connect to the running server for RAG-augmented chat
kompile chat --url=http://localhost:8080

# CLI: direct LLM chat (no server needed)
kompile chat

# CLI: wrap Claude Code / Codex with kompile's RAG tools
kompile chat --agent=claude-code --rag
```

Queries flow through: optional query rewriting → embedding → vector search → optional
reranking → optional filter chain → LLM generation with retrieved context. If graph RAG
is enabled, entity/relationship/community context from the knowledge graph augments the
vector results.

---

## The three components

### kompile (CLI)

The command-line tool for everything: project management, building applications, chatting,
ingesting documents, running models, and connecting AI agents to your data.

**Project management:**

```bash
kompile project init --name myproject          # Initialize a project
kompile project open .                         # Start the server for this project
kompile project status                         # Show manifest, components, Git state
kompile project add-model --path=model.onnx    # Register a model
kompile project add-crawl-profile              # Add an ingestion profile
kompile project add-code-project --dir=./src   # Register code for semantic search
kompile project index-code-project <id>        # Index code for search
kompile project lifecycle --state=ACTIVE       # Transition project state
kompile project commit / pull / push           # Git operations on the project
```

Projects move through lifecycle states: `DRAFT → ACTIVE → PAUSED → ARCHIVED | DEPRECATED`.
They can be backed by Git or Git-XET for version control with optional auto-commit.

**Build applications:**

```bash
# Interactive wizard
kompile build app --wizard

# From a preset
kompile build app --configName=myapp --preset=hosted-llm-rag

# Fine-tune modules
kompile build app --configName=myapp \
  --preset=full \
  --exclude=graph-neo4j,ocr \
  --llm=anthropic \
  --embedding=anserini \
  --vectorstore=pgvector \
  --native                    # Compile to GraalVM native image
  --container                 # Or build an OCI container (Jib, no Docker needed)
```

`build app` generates a complete Maven project under `kompile-rag-builds/<configName>/project/`
with a POM assembled from your selected modules, downloads required ML models (embeddings,
sentence tokenizers), and compiles it. Use `--skipMavenBuild` to generate project files only.

| Preset | Includes | API keys needed? |
|--------|----------|-----------------|
| `hosted-llm-rag` | OpenAI LLM + Anserini embeddings + PDF loader | Yes (OpenAI) |
| `cli-agent-rag` | CLI agent (Claude/Codex) + Anserini + filesystem tools | No |
| `samediff-rag` | Fully local — SameDiff embeddings, no hosted LLM | No |
| `lite` | Self-contained chat + RAG + Graph RAG, minimal footprint | No |
| `full` | All LLMs, embeddings, vector stores, OCR, crawler, graph, training | Mixed |
| `pipeline` | Pipeline executor only (SameDiff, ONNX, Python steps) | No |
| `minimal` | OpenAI embeddings + OpenAI LLM + Anserini vector store | Yes (OpenAI) |

**Chat — three modes:**

```bash
# 1. Direct LLM chat (no server, setup wizard on first use)
kompile chat

# 2. Server-connected RAG chat
kompile chat --url=http://localhost:8080

# 3. Agent passthrough — wrap an AI agent with kompile's tools
kompile chat --agent=claude-code --rag --role=architect
```

In passthrough mode, Kompile injects its MCP tools (RAG search, graph RAG, file I/O, code
search, memory) into the agent, adds a system prompt, and manages session persistence.
Sessions are resumed with `kompile chat --continue` or `kompile session list`.

**Policy enforcement:**

```bash
kompile enforcer --agent=claude-code \
  --rules="STOP_CMD: git push --force" \
  --rules="BAN_DIFF_REGEX: password\s*=\s*\"[^\"]+\"" \
  --max-corrections=3
```

The enforcer evaluates every agent response against rules (keyword patterns or LLM judge),
can interrupt mid-stream, auto-rollback file changes on violations, and retry with
correction prompts. `--diff-patterns` catches banned code patterns in file diffs.

**Run a local LLM:**

```bash
# Downloads from HuggingFace, starts an OpenAI-compatible server
kompile run Qwen/Qwen3-0.6B --serve --port=8000

# Interactive chat with a local model
kompile run Qwen/Qwen3-0.6B --backend=cuda
```

**MCP server for AI agents:**

Kompile exposes its full tool set to any MCP-compatible agent (Claude Code, Codex, Gemini
Code Assist, Qwen, OpenCode) via the Model Context Protocol. Two transport modes:

```bash
# Stdio mode — agent launches kompile as a subprocess
kompile mcp-stdio --profile=full

# SSE mode — agent connects to a running kompile-server
# (auto-configured in .mcp.json when you run `kompile project open`)
```

When a project is opened, Kompile writes a `.mcp.json` in the project directory so agents
auto-discover the tools:

```json
{
  "mcpServers": {
    "kompile": {
      "command": "kompile",
      "args": ["mcp-stdio", "--work-dir", "/path/to/project"]
    },
    "kompile-app": {
      "url": "http://localhost:8080/mcp/sse",
      "transport": "sse"
    }
  }
}
```

**Tool profiles** control how many tools are exposed:

| Profile | Tools | Use case |
|---------|-------|----------|
| `minimal` | 5 | read, grep, glob, list, bash |
| `explore` | 10 | Read-only + code intelligence |
| `core` | 15 | File I/O + search + workflow |
| `full` | ~44 | Everything below |

**Full tool set by category:**

| Category | Tools |
|----------|-------|
| File I/O | `read`, `write`, `edit`, `patch` |
| Search | `grep`, `glob`, `list`, `explore` |
| Execution | `bash`, `process` |
| Network | `webfetch`, `websearch`, `browser` (CDP-based) |
| Workflow | `todowrite`, `todoread` |
| Knowledge | `rag_search`, `graph_rag_search`, `semantic_memory`, `memory`, `transcript_search` |
| Code | `code_search`, `code_graph`, `local_code_index`, `tool_call_catalog` |
| Delegation | `task` (single subagent), `multi_task` (parallel), `quorum_task` (consensus voting) |
| Coordination | `edit_coordinator`, `file_activity` (file watcher for multi-agent) |
| Config | `project_config`, `enforcer_config`, `role_manager`, `skill_manager`, `config_archive` |

Any tool can run asynchronously with `_background: true` — returns a task ID immediately,
use `poll` to check status later. Schema compression (`--schema-level=compact`) reduces the
token footprint of tool definitions by thousands of tokens.

Kompile also auto-configures hooks in agent settings files (`.claude/settings.local.json`,
`.codex/config.toml`, `.opencode/plugins/`, `.gemini/settings.json`) to display tool name,
parameters, and timing for every call.

`kompile serve` runs a shared daemon that multiplexes MCP sessions over a Unix socket at
`~/.kompile/runtime/kompile.sock` — one process serves N agent sessions instead of N
separate JVMs.

**Other commands:**

| Command | Description |
|---------|------------|
| `kompile model` | Download, convert, list, export, import models (federated binary: `kompile-model`) |
| `kompile agent` | Workflows, tasks, channels, logs, process discovery (federated binary: `kompile-agent`) |
| `kompile lite` | Self-contained chat + RAG + Graph RAG app (federated binary: `kompile-lite`) |
| `kompile app` | Manage a running server: ingest, index, crawl, jobs, graph, a2a, setup, train, schedule |
| `kompile graph` | Knowledge graph: nodes, edges, traverse, search, communities, Cypher shell, import/export, extraction, proposals, maintenance |
| `kompile code-index` | Local code search with `search`, `find`, `usages`, `watch`, plus code graph analysis (callers, impact, deps, components) |
| `kompile knowledge` | Manage Markdown notes synced from local folders, Git repos, or Obsidian vaults |
| `kompile skills` | Manage prompt template skills (list, create, delete, generate docs) |
| `kompile eval` | Run agent evaluation suites, compare runs, track regressions |
| `kompile perf` | Agent performance harness: reports, recommendations, leaderboards |
| `kompile sdk` | SDX Runtime SDK: list, download, scaffold mobile apps, serve locally |
| `kompile cloud` | Manage Kompile Cloud account, instances, apps, and build jobs |
| `kompile manage` | Start, stop, restart, status, and logs for Kompile components |
| `kompile web` | Launch the full web application (server + staging + UI) |
| `kompile deploy` | Deploy a built project to `~/.kompile/instances/` |
| `kompile init-project` | Initialize a new project with wizard, presets, and optional auto-start |
| `kompile a2a` | Agent-to-Agent protocol: discover, ping, send tasks to remote agents |
| `kompile resume` | Browse and resume previous conversations across agents |
| `kompile resume-all` | Resume all tracked agent sessions in new terminal windows |
| `kompile edit-coordinator` | Inspect and manage multi-agent edit locks and coordination state |
| `kompile daemon` | Observe the MCP daemon: status, stop, view logs |
| `kompile build dist` | Build all three native binaries into a distribution tarball |

> **Federated CLI:** `kompile model`, `kompile agent`, and `kompile lite` are separate
> binaries (`kompile-model`, `kompile-agent`, `kompile-lite`) resolved from PATH or
> `~/.kompile/bin/` at runtime. Each is built from its own Maven module
> (`kompile-model-cli`, `kompile-agent-cli`, `kompile-app-cli`).

---

### kompile-server (RAG application)

The web application generated by `kompile build app`. It's a full-stack Spring Boot +
Angular application that serves as the primary interface for document-powered AI.

**What you see at http://localhost:8080:**

| Screen | What it does |
|--------|-------------|
| **Chat** (default) | Conversational RAG with streaming, source attribution, multi-turn history, token metrics. Supports both RAG mode (retrieved documents + LLM) and agent mode (Claude Code, Codex, etc.) |
| **Knowledge** | Document ingestion from 20+ sources, knowledge graph builder with entity/relationship browsing, fact sheets (notebook-style knowledge organization), graph visualization |
| **Code Projects** | Register code repositories, trigger semantic indexing, browse code graphs, manage project context for agent sessions |
| **Tools** | Configure MCP servers, browse and invoke tools, view tool call audit logs, manage prompt template skills |
| **KClaw** (Agent Hub) | Run CLI agents interactively in the browser with MCP tool injection, permission management, heartbeat monitoring, session history |
| **Settings** | Vector store backend, chunking strategy, embedding config, LLM provider, query rewriting, reranking, filter chains, guardrails, system prompts, tool gateway rules, ND4J environment tuning |
| **Developer** | ND4J framework status, GPU lifecycle, subprocess logs, operation timing, benchmarks, VLM orchestration, SameDiff graph visualization, model debug |

**The ingestion pipeline in detail:**

Documents pass through phases tracked in real-time via SSE:
`QUEUED → DISCOVERING → LOADING → CHUNKING → GRAPH_EXTRACTION → ENTITY_RESOLUTION →
EMBEDDING → VECTOR_INDEXING → COMPLETED`

- **Graph extraction** (optional): An LLM extracts entities and relationships from each
  chunk, then entity resolution deduplicates using embedding similarity + string matching
- **PDF routing**: Auto-classifies PDFs as text-heavy or image-heavy, routes image-heavy
  ones through VLM OCR
- **Adaptive batching**: Embedding batch sizes adjust based on available ND4J memory
- **Memory monitoring**: Each job reports heap, native memory, direct buffers, and
  subprocess RSS

Subprocesses (`ingest`, `vector-population`, `embedding`, `model-init`, `vlm-test`,
`training`) are the same binary re-launched with `--subprocess=TYPE`. No separate process
management needed.

**REST API highlights** (~100+ endpoints):

- `/api/chat`, `/api/chat/stream` — conversational RAG (streaming SSE)
- `/api/graph-rag/search` — graph-augmented retrieval (local or global)
- `/api/unified-crawl/start` — multi-source ingestion with graph extraction
- `/api/agents/passthrough/*` — interactive agent terminal sessions over HTTP
- `/api/agents/chat/stream` — structured agent chat with RAG augmentation
- `/api/retriever/search` — direct vector search (bypass LLM)
- `/api/skills` — prompt template CRUD (exposed as MCP prompts)
- `/api/mcp/*` — MCP server config, tool invocation, audit log
- `/api/nd4j/environment` — full ND4J/CUDA runtime tuning
- `/api/config/k-app` — vector store, subprocess, and pipeline config
- `/api/projects/current` — project manifest and component management
- `/api/setup/status` — setup wizard state and staging server management
- `/api/fact-sheets` — notebook-style knowledge organization

**Pluggable modules** — the same binary reshapes behavior based on which modules are on
the classpath:

| Category | Options |
|----------|---------|
| Embeddings | Anserini SameDiff (BGE, Arctic Embed, E5), OpenAI, PostgresML, sentence-transformers, SameDiff |
| Vector stores | Anserini (Lucene HNSW), pgvector, Chroma, Vespa |
| LLM providers | OpenAI, Anthropic, Gemini, local SameDiff, CLI agent passthrough, Spring AI |
| Document loaders | PDF (extended + tables), Office, Tika, email (IMAP/POP3/Gmail/PST), web crawler |
| Data sources | Slack, Confluence, Jira, Notion, Reddit, Google Drive, OneDrive, S3, SFTP, SQL, Discord |
| Chunkers | Token, sentence, recursive-character, markdown, table-aware |
| Graph | Neo4j knowledge graph, entity extraction, community detection |
| Compute graphs | Camel, Drools, n8n, Excel, Xircuits, scripting workflow engines |
| Other | KV cache, OCR pipeline, A2A protocol, filter chain, training |

**Guardrails** — input filters (prompt injection, jailbreak, PII, toxicity, off-topic,
business rules, competitor mention, copyright) and output filters (hallucination, relevancy,
format). Each guardrail is individually toggleable via Settings or the REST API.

**Evaluation harness** — run structured test suites against your RAG pipeline with
`kompile eval`. Track experiments, compare runs, and schedule recurring evaluations to
detect regressions. The tool gateway uses an LLM judge to approve/deny tool calls at
runtime.

**Agent-to-Agent (A2A)** — discover and communicate with remote agents via the A2A
protocol. Each agent exposes a card at `/.well-known/agent-card.json`. Enable, discover,
ping, and send tasks from the CLI (`kompile a2a`) or the REST API (`/api/a2a`).

**Connected Services** — OAuth connection management for cloud data sources. Each provider
(Google Drive, OneDrive, Gmail, Slack, Discord, Confluence, Jira, Notion, Google Workspace)
shows connection status, token expiry, required scopes, and connect/disconnect/refresh
actions.

---

### kompile-model-staging (model operations)

A model lifecycle service that handles the path from raw HuggingFace weights to
production-ready deployment. Runs as a REST API on port 8090 with its own Angular UI,
or as a CLI tool.

**The staging pipeline:**

Models pass through managed states: `DOWNLOADING → CONVERTING → VALIDATING → READY →
PROMOTING → COMPLETED`. Failed models land in `.staging/failed/` with diagnostics.

```bash
# Download from HuggingFace
kompile-model-staging download \
  --source=huggingface \
  --repo=BAAI/bge-base-en-v1.5 \
  --format=onnx

# Conversions: ONNX, TensorFlow, GGUF/GGML → SameDiff (sharded .sdnb)
kompile-model-staging convert --input=model.onnx --output=model.sdz

# List registered models
kompile-model-staging list

# Promote a staged model to production (notifies live server to hot-reload)
kompile-model-staging promote <modelId>
```

After promotion, the staging service sends an HTTP callback to the running kompile-server
telling it to hot-swap the model in memory — no server restart needed. The server can also
pull model files directly from staging over HTTP.

**Local LLM inference with OpenAI-compatible API:**

The staging service includes a full inference engine. Load a converted model and query it
from any OpenAI client:

```bash
curl http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "qwen3-0.6b", "messages": [{"role": "user", "content": "Hello"}]}'
```

Supports streaming, speculative decoding, prompt templates, text processing pipelines,
and generation cancellation.

**Training and fine-tuning:**

Training jobs with PEFT (LoRA, etc.), knowledge distillation, and alignment are managed
through REST endpoints with SSE log streaming and metrics tracking. Dataset management is
built in.

**Graph compiler:**

SameDiff graph optimization with Triton GPU compilation, caching, and async compilation
jobs. Compare optimized vs. unoptimized graph performance.

**Kompile Archives (.karch):**

Versioned bundles of pre-converted models for redistribution and offline installation.
Each archive contains a manifest with checksums, compatibility ranges, and model metadata.

```bash
kompile-model-staging archive export --output=models-v1.karch
kompile-model-staging archive import --input=models-v1.karch
```

Remote catalogs at GitHub Releases and kompile.ai are checked every 24 hours for updates.

**Built-in model catalog:**

| Category | Models |
|----------|--------|
| Dense encoders | BGE base/small/large, BGE-M3 (multilingual, 8K context), Arctic Embed L, E5-base-v2 |
| Cross-encoder rerankers | MS MARCO MiniLM L-6, MS MARCO MiniLM L-12 |
| Vision-language | Florence-2 base, Florence-2 large |

---

## Configuration

Kompile uses a **JSON config file system** rooted at `~/.kompile/config/`. All three
entry points — CLI wizards, the web UI, and the REST API — read and write the same files.
Spring properties exist only for bootstrap defaults; the JSON files always take precedence
at runtime.

### First-time setup

```bash
kompile configure init          # Creates ~/.kompile/ directory tree + default config files

kompile configure app           # Interactive 9-section config wizard
kompile configure chat          # Chat session mode, LLM provider, agent preferences
kompile configure mcp           # MCP profile and schema level
kompile configure enforcer      # Per-project policy rules
kompile configure judge         # LLM judge mode, model, scoring
```

### Config files

All configs are JSON files under `~/.kompile/config/`. They're created with sensible
defaults by `kompile configure init` and can be edited by the CLI wizards, the web UI
settings screens, the REST API, or by hand:

| File | What it controls |
|------|-----------------|
| `app-index-config.json` | Vector store type (Anserini/pgvector/Chroma/Vespa), index paths, subprocess settings, batch sizes |
| `pipeline-config.json` | Batch sizes, thread counts (embedding, chunking, indexing), chunking strategy and parameters |
| `subprocess-ingest-config.json` | Subprocess JVM heap, timeout, parallel workers, queue capacity |
| `nd4j-environment-config.json` | ND4J threads, BLAS settings, CUDA config, Triton compiler, SameDiff optimizer, memory limits |
| `feature-flags-config.json` | Toggle: guardrails, query transformation, contextual RAG, tool gateway, KV cache, graph RAG, multi-modal |
| `model-roles-config.json` | Dense/sparse retrieval models, reranking model, hybrid search weights |
| `llm-provider-config.json` | LLM provider, model, API key, base URL |
| `tool-gateway-config.json` | Model source, fail-open, evaluation timeout, judge scoring |
| `backup-config.json` | Backup schedule, retention, format |

### Auto-configuration

```bash
# Detect hardware and apply recommended settings
curl -X POST http://localhost:8080/api/auto-configure/apply

# Preview what would change
curl http://localhost:8080/api/auto-configure/detect
```

This probes your hardware (GPU count, VRAM, CPU cores, RAM) and sets subprocess, ND4J,
and pipeline configs simultaneously.

### Config archives

Export and import all configs as a `.zip` bundle for portability:

```bash
# From the web UI: Settings → Config Archive Manager
# From the API:
curl -X POST http://localhost:8080/api/config-archives/export -o config-backup.zip
curl -X POST http://localhost:8080/api/config-archives/import -F file=@config-backup.zip
```

---

## How the pieces fit together

```
                              +-----------------------+
                              |   kompile (CLI)       |
                              |                       |
                              |  project init/open    |
                              |  build app            |  generates + compiles
                              |  chat / ingest        |  talks to server
                              |  mcp-stdio            |  exposes tools to agents
                              |  run <model>          |  local LLM serving
                              |  enforcer             |  policy-governed agents
                              +-----------+-----------+
                                          |
                          +---------------+---------------+
                          |                               |
               +----------v----------+         +----------v----------+
               |  kompile-server     |         | kompile-model-      |
               |  (port 8080)        |         | staging (port 8090) |
               |                     |         |                     |
               |  Web UI + REST API  |<------->|  Download + convert |
               |  Document ingestion |  hot    |  LLM inference      |
               |  Vector search      | reload  |  Training / PEFT    |
               |  Knowledge graph    |  notify |  OpenAI-compat API  |
               |  Agent hub          |         |  Archive management |
               |  Chat + RAG         |         |  Graph compiler     |
               +-----+---------+----+         +---------------------+
                     |         |
          subprocess |         | subprocess
          (same      |         | (same binary)
           binary)   |         |
               +-----v--+ +---v--------+
               | ingest  | | vector-    |
               | (load + | | population |
               | chunk)  | | (embed +   |
               |         | | index)     |
               +---------+ +------------+
```

The kompile-server binary is self-contained. For compute-heavy work it re-launches itself
as an isolated subprocess with `--subprocess=TYPE` so the web server stays responsive.
Model-staging notifies the server to hot-reload models after promotion — no restart needed.

---

## For developers

### Building from source

Requires Java 17, Maven 3.9+, 10+ GB RAM. GraalVM 17 for native image builds (18-32 GB
heap).

```bash
# Full build
mvn clean install -DskipTests

# CLI only
cd kompile-cli && mvn clean package

# RAG application
cd kompile-app/kompile-app-main && mvn clean package

# Native image (requires GraalVM 17)
cd kompile-rag-builds/kompile-sample/project
mvn clean package -DskipTests -Pnative

# Full distribution tarball
kompile build dist
```

### Repository structure

```
kompile/
  kompile-cli/                       CLI entry point (Picocli)
  kompile-cli-common/                Shared CLI utilities
  kompile-cli-plugin-api/            Plugin SPI for CLI extensions
  kompile-app-cli/                   Federated CLI: kompile app start/stop/ingest/query
  kompile-model-cli/                 Federated CLI: kompile model download/convert/serve
  kompile-agent-cli/                 Federated CLI: kompile agent chat/workflow/logs
  kompile-component-cli/             Federated CLI: kompile component list/config/status
  kompile-project-store/             Project manifest read/write
  kompile-sdk-serving/               SDX Runtime SDK serving layer
  kompile-app/                       Spring Boot RAG framework (40+ modules)
    kompile-app-core/                  Core interfaces
    kompile-app-main/                  Main application + Angular web UI
    kompile-app-lite/                  Lightweight self-contained RAG app
    kompile-model-manager/             Model download and cache
    kompile-model-staging/             Model lifecycle service
    kompile-embedding-*/               Embedding implementations
    kompile-vectorstore-*/             Vector store implementations
    kompile-loader-*/                  Document loaders
    kompile-source-*/                  Data source connectors (20+)
    kompile-chunker-*/                 Chunking strategies
    kompile-tool-*/                    Spring AI / MCP tools
    kompile-kvcache/                   Paged KV cache for local LLMs
    kompile-graph-neo4j/               Graph RAG with Neo4j
    kompile-knowledge-graph/           Knowledge graph construction
    kompile-ocr-*/                     OCR pipeline
    kompile-guardrails/                Input/output guardrails
    kompile-evaluation/                RAG evaluation harness
    kompile-a2a/                       Agent-to-Agent protocol
    kompile-kclaw/                     Agent hub (browser-based agent runner)
    kompile-code-indexer/              Semantic code search and indexing
    kompile-compute-graph-*/           Workflow engines (Camel, Drools, n8n, Excel, Xircuits)
    kompile-oauth2-client/             OAuth connections for cloud sources
  kompile-pipelines-framework/       Pipeline execution engine
  anserini/                          Lucene IR toolkit + SameDiff dense encoders
  tokenizers-rust/                   HuggingFace tokenizers -> JavaCPP JNI bindings
  kompile-model-importer-*/          Model importers (TensorFlow, ONNX, Keras)
  kompile-e2e-tests/                 End-to-end test suite
  kompile-dist/                      Distribution assembly (native binaries + tarball)
```

### Key dependencies

Java 17 . Spring Boot 3.2.5 . Spring AI 1.0.0 . Picocli 4.7.7 .
ND4J 1.0.0-SNAPSHOT . Lucene (via Anserini) . JavaCPP 1.5.13 .
Lombok 1.18.42 . GraalVM 17

### Links

- Documentation: https://kompile.gitbook.io/kompile-docs
- Issues: https://github.com/GetKompile/kompile/issues
- Eclipse DeepLearning4j: https://github.com/deeplearning4j/deeplearning4j

## License

See [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
