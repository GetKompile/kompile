# kompile-cpu-graph-test

A kompile RAG application generated with `kompile init-project`.

## Quick Start

```bash
# Build with CUDA (default)
mvn clean package -DskipTests

# Build with CPU only
mvn clean package -DskipTests -Pcpu

# Launch with the kompile CLI (recommended)
kompile web

# Or use the generated scripts
./scripts/start-all.sh
```

The application will start on port 8080. Open http://localhost:8080/ in your browser.

## Architecture

This project runs as three cooperating services:

| Service | Port | Role |
|---------|------|------|
| **Staging Server** | 8090 | Model registry — downloads, caches, and serves model files |
| **LLM Serving** | 8091 | Inference subprocess — runs SameDiff/ONNX models on nd4j-native |
| **Main App** | 8080 | Orchestrator + Web UI — RAG pipeline, REST API, Angular frontend |

The main app is a pure orchestrator and does **not** load ND4J or GPU libraries.
All ML inference happens in the serving subprocess, which has the `nd4j-native` backend
baked into this project's POM.

```
User -> Main App (:8080) -> LLM Serving (:8091) -> GPU/CPU
                          |                      |
                          v                      v
                   Staging (:8090) <--- Model download/cache
```

## Starting Services

### Using the kompile CLI (recommended)

`kompile web` is the managed way to launch. It handles the staging server,
lifecycle management, and is visible to `kompile manage list/stop`.

```bash
cd kompile-cpu-graph-test

# Launch app + staging server (foreground, Ctrl+C to stop)
kompile web

# Custom port
kompile web --port=9090

# Rebuild before starting
kompile web --build

# Check status / stop from another terminal
kompile web status
kompile web stop
```

### Using the CLI to interact

Once the app is running, you can interact with it from the terminal:

```bash
# Interactive chat (connects to the running app)
kompile chat

# Ingest documents
kompile app ingest file ./data/input_documents/my-rulebook.pdf
kompile app ingest path ./data/input_documents/

# Crawl a URL
kompile app crawl url https://example.com/rules

# Check indexing status
kompile app index status
```

### Project Repository

This directory contains a unified Kompile project manifest at `kompile.project.json`.
Use `kompile project status` to inspect lifecycle, tags, repository backend, and registered components.
Use `kompile project serve` to start the services implied by the registered scripts, workflows, models, and pipelines.
Use `kompile project crawl` to select and run the managed crawl workflow or crawl profile for this project.

```bash
kompile project status
kompile project model-list
kompile project pipeline-list
kompile project serve --dry-run
kompile project crawl --dry-run
```

Project model and pipeline registry snapshots are written to `data/models/project-models.json` and `data/pipelines/project-pipelines.json`.
The model-staging registry consumed by `kompile-model-staging` lives at `data/models/registry.json`.

Local Kompile metadata lives under `.kompile/`. `kompile project open` records the active project in `.kompile/project/open.json`, while cache, session, and runtime state remain local.

### Initial Crawl

This project includes a managed initial crawl profile in `kompile.project.json`.

```bash
# Start the app first, then run the managed crawl profile
./scripts/init-crawl.sh

# Let the project command choose the crawl profile and optional services
kompile project crawl --dry-run

# Inspect or dry-run the generated crawl command
kompile project crawl-list
kompile project crawl-run --id initial-crawl --dry-run
```

### Using shell scripts

The `scripts/` directory contains standalone scripts that work without the
kompile CLI installed.

```bash
./scripts/start-all.sh     # Start all (background)
./scripts/stop-all.sh      # Stop all
```

### Running from JAR directly

```bash
java -jar target/kompile-cpu-graph-test-0.1.0-SNAPSHOT.jar
```

## Compute Backend

This project was initialised with **`nd4j-native`** as the primary backend.
The backend is selected through Maven profiles in `pom.xml`. CUDA is active by default for CUDA-capable presets; use `-Pcpu` to build a CPU-only JAR.

```bash
# Build with CUDA (default — requires CUDA toolkit on the build machine)
mvn clean package -DskipTests

# Build with CPU only
mvn clean package -DskipTests -Pcpu
```

All subprocesses launched from this project's JAR automatically use the backend
that was compiled in — no `-D` flags or environment variables needed.

To create a project with a different default backend, re-run `kompile init-project --backend=nd4j-native`.

## Subprocess Types

The main JAR supports subprocess routing via `--subprocess=TYPE`:

| Type | Description |
|------|-------------|
| `serving` | LLM inference HTTP server (load/generate/status endpoints) |
| `ingest` | Document ingestion (parsing, chunking, embedding, indexing) |
| `vector-population` | Bulk vector index population |
| `embedding` | Long-running embedding subprocess (stdin/stdout protocol) |
| `model-init` | One-shot model initialization/download |
| `vlm-test` | Vision-language model testing |
| `training` | Model fine-tuning |

Most subprocesses are launched automatically by the main app. The serving
subprocess is started separately via `scripts/start-serving.sh` so it can
run with full GPU access independently of the orchestrator.

### Manual subprocess launch

```bash
java -jar target/kompile-cpu-graph-test-0.1.0-SNAPSHOT.jar --subprocess=serving --port=8091 --staging-url=http://localhost:8090
java -jar target/kompile-cpu-graph-test-0.1.0-SNAPSHOT.jar --subprocess=ingest /path/to/args.json
```

## Preset

Generated with the `cli-agent-rag` preset.

## Enabled Modules

- **CORE**: app-main, app-core, loaders-orchestrator, app-anserini, chat-history, pipelines-llm
- **LLM**: llm-cli-agent
- **EMBEDDING**: embedding-anserini
- **VECTORSTORE**: vectorstore-anserini
- **LOADER**: loader-pdf-extended, loader-tika
- **CHUNKER**: chunker-sentence
- **TOOL**: tool-filesystem, tool-rag, tool-crawler
- **ENTERPRISE**: kvcache, model-manager, code-indexer
- **ADVANCED**: crawler-core, crawl-graph

## Project Structure

```
kompile-cpu-graph-test/
  pom.xml                              # Maven project (backend=nd4j-native)
  kompile.project.json                 # Unified project manifest, lifecycle, tags, components
  scripts/
    start-all.sh / .bat                # Start all services
    stop-all.sh / .bat                 # Stop all services
    start-app.sh / .bat                # Main app only
    start-serving.sh / .bat            # LLM serving subprocess only
    start-staging.sh                   # Staging server only
    init-crawl.sh                      # Initial managed crawl profile, when configured
    init-pdf-vlm.sh                    # PDF/VLM workflow, when configured
  src/main/resources/
    application.properties             # Application configuration
  staging/                             # Staging server executable
  data/
    input_documents/                   # Documents for RAG ingestion
    markdown/                          # Markdown note storage
    sources/                           # Source repository metadata and staged source assets
    chats/                             # Project-scoped chat exports
    code-projects/                     # Context, AGENTS.md, and chats for external indexed code projects
    crawls/                            # Crawl profile state and ingestion initialization metadata
    workflows/                         # Workflow run metadata and orchestration outputs
    artifacts/                         # Generated project artifacts
    logs/                              # Service log files
    pids/                              # PID files for service management
    models/                            # Model files and descriptors; Git Xet-ready component
    shared_files/                      # MCP shared filesystem root
    prompt-templates/                  # Prompt template definitions
  AGENTS.md                            # AI agent guide to this project
```

## Configuration

Runtime configuration is managed through the **web UI** (Developer Hub),
not by editing `application.properties` directly.

Settings are persisted as JSON files under `~/.kompile/config/`.

The only settings in `application.properties` that you may want to edit:
- LLM API keys (OpenAI, Anthropic, Gemini) — or set as environment variables
- `server.port` — default 8080

## Logs

```bash
# All logs
tail -f data/logs/*.log

# Specific service
tail -f data/logs/app.log
tail -f data/logs/serving-subprocess.log
tail -f data/logs/staging-server.log
```
