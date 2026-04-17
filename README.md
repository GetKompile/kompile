# Kompile

Kompile is a comprehensive AI/ML platform for building, deploying, and operating retrieval-augmented
generation (RAG), agentic chat, and model inference workloads on the JVM. It combines a
Picocli-based developer CLI, a pluggable Spring Boot RAG framework, a pipeline execution engine
(the former Konduit Serving, now merged into `kompile-pipelines-framework`), Rust-backed
tokenizers, and GraalVM native-image packaging into a single toolchain built on top of the
[Eclipse DeepLearning4j / ND4J ecosystem](https://github.com/deeplearning4j/deeplearning4j).

> Status: README is actively maintained. For questions, join the community at
> <https://community.konduit.ai>.

## What's in the box

Kompile is a monorepo of several cooperating subsystems:

- **`kompile-cli`** — The main developer CLI (`kompile`). Subcommands cover bootstrap, install,
  config generation, build (RAG apps, native images, ND4J backends), model conversion, pipeline
  management, ingest/index, chat/REPL, MCP stdio server, and job scheduling. Additional federated
  CLIs (`kompile-app-cli`, `kompile-agent-cli`, `kompile-model-cli`, `kompile-component-cli`) are
  discoverable through the `kompile app|agent|model` delegating subcommands.
- **`kompile-app`** — A 40+ module Spring Boot RAG framework with pluggable embeddings, vector
  stores, document loaders, data sources, chunkers, LLM providers, tools, chat history, guardrails,
  and an evaluation harness. See [Architecture](#architecture) below.
- **`kompile-pipelines-framework`** — A pipeline execution engine (`api`, `core`, `runtime`) with
  reusable steps for Python, SameDiff, DL4J, ONNX, and VLM workloads.
- **`kompile-model-importer-{tensorflow,onnx,keras}`** — Model importers that convert TensorFlow,
  ONNX, and Keras models into DL4J / SameDiff formats for execution on the JVM.
- **`anserini`** — A vendored fork of Anserini (Lucene-based IR toolkit) with custom SameDiff
  dense encoders (BGE, Arctic Embed, etc.) and an `AnseriniModelDownloader`.
- **`tokenizers-rust`** — HuggingFace tokenizers compiled as a Rust static lib with a C++ wrapper
  and JavaCPP bindings (`tokenizers-native`, `tokenizers-native-preset`) for high-performance
  tokenization on the JVM.
- **`kompile-c-library` / `kompile-python` / `kompile-sdk-serving`** — C shim, Python SDK, and
  serving SDK used by generated pipelines.
- **`kompile-rag-builds`** — Pre-generated sample RAG projects used during development so you do
  not have to regenerate every time.

## Prerequisites

- Java 17 (GraalVM 21 required for native-image builds)
- Maven 3.9+
- 10 GB RAM minimum; 16 GB+ recommended (native-image builds want 18–32 GB heap)
- Optional: Docker, Python (Anaconda/Miniconda), CUDA toolkit for GPU workloads

## Building

### Standard JVM build

```bash
# Full build
mvn clean install

# Faster — skip tests
mvn clean install -DskipTests

# CLI only
cd kompile-cli && mvn clean package

# RAG application (UI builds by default)
cd kompile-app/kompile-app-main && mvn clean package

# RAG application, backend only (~5× faster)
cd kompile-app/kompile-app-main && mvn clean package -Dskip.ui

# Install or refresh frontend node_modules (first checkout or after package.json changes)
cd kompile-app/kompile-app-main && mvn clean install -Dui.deps
```

### GraalVM native image

```bash
# CLI
cd kompile-cli && mvn clean package -Pnative

# RAG app (unified executable — routes subprocess types via --subprocess=TYPE)
JAVA_HOME=~/.sdkman/candidates/java/21.0.10-graal \
  mvn clean package -DskipTests -Dskip.ui \
      -pl kompile-app/kompile-app-main -am -Pnative
```

Subprocess-specific profiles (`native-ingest`, `native-vector`, `native-embedding`,
`native-model-init`) are available for building standalone subprocess binaries.

### Rust tokenizers

```bash
cd tokenizers-rust/libtokenizers
./buildnativetokenizers.sh                    # current platform
JAVACPP_PLATFORM=linux-x86_64 ./buildnativetokenizers.sh   # cross target
```

### Docker

```bash
# Build image
docker build -f Dockerfile.rockylinux8 --ulimit nofile=98304:98304 \
  -t konduitai/kompile:latest .

# Pull prebuilt image
docker pull ghcr.io/konduitai/kompile

# Run the CLI
docker run --rm -it konduitai/kompile

# Interactive shell with the working dir mounted
docker run --ulimit nofile=98304:98304 --rm -it \
  -v "$(pwd):/mnt/:Z" --entrypoint /bin/bash konduitai/kompile
```

## Testing

```bash
mvn test                                    # all modules
cd kompile-cli && mvn test                  # one module
mvn test -Dtest=YourTestClass               # one class
mvn test -Dtest=YourTestClass#testMethod    # one method

# Integration tests with Spring profile
cd kompile-app/kompile-app-main && mvn verify -Dspring.profiles.active=test
```

## CLI quick start

```bash
# First-time setup: create ~/.kompile and install dependencies
./kompile bootstrap
./kompile install all                  # graalvm + maven + python (miniconda)
./kompile install graalvm              # or install components individually
./kompile install python

# Ask the CLI what it can do
./kompile --help
./kompile <command> --help
```

Top-level subcommands include:

| Command      | Purpose                                                                      |
| ------------ | ---------------------------------------------------------------------------- |
| `bootstrap`  | Initialize `~/.kompile` directory layout                                     |
| `install`    | Install GraalVM, Maven, Anaconda/Python, native tools, headers               |
| `uninstall`  | Remove managed components                                                    |
| `info`       | Report installed versions and environment info                               |
| `config`     | Generate pipeline, python, server, and variable configs                      |
| `build`      | Build RAG apps, native images, ND4J backends, serving binaries               |
| `build-rag-app` | Generate a custom RAG Spring Boot project with chosen modules             |
| `sdk`        | Operate on the generated Python SDK                                          |
| `pipeline`   | Manage and run pipelines                                                     |
| `ingest`     | Load documents into a vector store                                           |
| `index`      | Build / update a search index                                                |
| `chat`       | Interactive agentic chat REPL with tools, roles, MCP, and session history    |
| `lite`       | Self-contained Kompile Lite chat + RAG + Graph RAG app                       |
| `session`    | List, resume, export, import chat sessions                                   |
| `passthrough`| Stream a single prompt through the chat runtime                              |
| `resume`     | Resume a previous chat session                                               |
| `mcp-stdio`  | Run the MCP (Model Context Protocol) stdio server                            |
| `jobs`       | Inspect/manage background jobs                                               |
| `schedule`   | Create and manage scheduled tasks                                            |
| `subprocess` | Entry point used by the app when spawning helper processes                   |
| `app`        | Delegates to `kompile-app-cli` (start/stop/status/ingest/query)              |
| `agent`      | Delegates to `kompile-agent-cli` (workflow, task, channel, session, chat)    |
| `model`      | Delegates to `kompile-model-cli` (list, download, convert, export, import)   |

Examples:

```bash
# Convert a TensorFlow model to SameDiff flatbuffers
./kompile model convert \
  --inputFile=model.pb \
  --outputFile=model.fb

# Convert a Keras model to DL4J zip
./kompile model convert \
  --inputFile=model.h5 \
  --outputFile=model.zip \
  --kerasNetworkType=sequential

# Generate a custom RAG application with only the modules you want
./kompile build-rag-app \
  --instanceId=myapp \
  --enableAnserini=true \
  --enableOpenAi=true \
  --enablePgvector=false

# Simpler hosted-LLM RAG app
./kompile build-hosted-llm-rag-app --instanceId=myapp

# SameDiff-embedding RAG app
./kompile build-samediff-app --instanceId=myapp
```

## Architecture

### `kompile-app` — RAG framework modules

**Core interfaces** (`kompile-app-core`):

- `EmbeddingModel#embed(text) → INDArray`
- `VectorStore` — `add`/`search`/`delete` documents
- `DocumentRetriever#retrieve(query, k) → List<String>`
- `LanguageModel#generate(prompt)`

**Centralized model management** (`kompile-model-manager`):

- Downloads to `~/.kompile/models/` with SHA256 verification
- Registry in `ModelConstants`, descriptor in `ModelDescriptor`

**Embeddings**: `kompile-embedding-anserini` (SameDiff: bge-base-en-v1.5, arctic-embed, …),
`kompile-embedding-openai`, `kompile-embedding-postgresml`,
`kompile-embedding-sentence-transformer` (Python subprocess), `kompile-embedding-samediff`.

**Vector stores**: `kompile-vectorstore-anserini` (Lucene HNSW, primary),
`kompile-vectorstore-pgvector`, `kompile-vectorstore-chroma`, `kompile-vectorstore-vespa`.

**Loaders & sources**: PDF (extended, tables), Office, Tika, email (IMAP, mail), web, Slack,
Confluence, Jira, Notion, Reddit, Google Drive, plus `kompile-app-loaders-orchestrator` to
coordinate them.

**Chunkers**: token, sentence, recursive-character, markdown, table-aware.

**LLM providers**: `kompile-app-openai-llm`, `kompile-app-anthropic-llm`, `kompile-app-gemini-llm`,
`kompile-app-springai-llm`, `kompile-pipelines-app-llm` (local SameDiff LLMs).

**Tools & agents**: `kompile-tool-rag`, `kompile-tool-filesystem`, `kompile-tool-model-staging`,
`kompile-react-agent`, `kompile-orchestrator`, `kompile-query-transformer`, `kompile-guardrails`,
`kompile-filter-chain`.

**Other subsystems**: `kompile-kvcache` (paged/evictable KV cache for local LLMs),
`kompile-chat-history`, `kompile-evaluation`, `kompile-graph-neo4j` and `kompile-knowledge-graph`
(Graph RAG), `kompile-ocr-*` (document OCR pipeline), `kompile-model-staging`,
`kompile-pipeline-management`, `kompile-oauth2-client`, `kompile-postgres-common`.

**Plugin activation** uses Spring Boot `@ConditionalOnProperty` with `kompile.embedding.type`,
`kompile.vectorstore.type`, `kompile.llm.type`, etc., so the same binary can be reshaped via
`application.properties` or generated POMs from `RagPomGenerator`.

### RAG data flow

```
Documents → Loaders → Chunks → Embeddings → Vector Index

Query → Embed Query → Vector Search → Retrieved Context → LLM → Response
```

### `kompile-pipelines-framework`

`api` defines `Configuration`, `StepConfig`, `Data`; `core` is the execution engine; `runtime`
provides runtime support. Step implementations live under `kompile-pipeline-steps-parent`
(`samediff`, `python`, `onnx`, `vlm`, …).

### Native-image subprocess model

The main application ships as a single native executable that routes subprocess types before
Spring Boot starts via `--subprocess=TYPE` (`ingest`, `vector-population`, `embedding`,
`model-init`, `vlm-test`, `training`). `NativeImageInfo` detects the runtime mode and
`SubprocessExecutableConfig` (`kompile.subprocess.executable.*` properties) configures how
helper processes are launched in auto / jvm / native modes.

## Repository layout

```
kompile/
├── kompile-cli/                    # Main developer CLI (Picocli)
├── kompile-app/                    # Spring Boot RAG framework (40+ modules)
│   ├── kompile-app-core/           # Core interfaces
│   ├── kompile-app-main/           # Main app + web UI (Angular)
│   ├── kompile-app-lite/           # Self-contained chat + RAG + Graph RAG
│   ├── kompile-model-manager/      # Model download + cache
│   ├── kompile-embedding-*/        # Embedding implementations
│   ├── kompile-vectorstore-*/      # Vector store implementations
│   ├── kompile-loader-*/           # Document loaders
│   ├── kompile-source-*/           # Data sources (Slack, Jira, Notion, …)
│   ├── kompile-chunker-*/          # Chunking strategies
│   ├── kompile-tool-*/             # Spring AI / MCP tools
│   ├── kompile-kvcache/            # Paged KV cache for local LLMs
│   ├── kompile-graph-neo4j/        # Graph RAG
│   └── kompile-ocr-*/              # OCR pipeline modules
├── kompile-agent-cli/              # Federated agent CLI
├── kompile-app-cli/                # Federated app-management CLI
├── kompile-model-cli/              # Federated model CLI
├── kompile-component-cli/          # Component management CLI
├── kompile-pipelines-framework/    # Pipeline execution engine
│   ├── kompile-pipelines-framework-api/
│   ├── kompile-pipelines-framework-core/
│   ├── kompile-pipelines-framework-runtime/
│   └── kompile-pipeline-steps-parent/ (samediff, python, onnx, vlm, …)
├── kompile-model-importer-tensorflow/
├── kompile-model-importer-onnx/
├── kompile-model-importer-keras/
├── anserini/                       # Lucene IR toolkit + SameDiff dense encoders
├── tokenizers-rust/                # HuggingFace tokenizers → JavaCPP bindings
│   ├── libtokenizers/
│   ├── cpp-wrapper/
│   ├── tokenizers-native/
│   └── tokenizers-native-preset/
├── kompile-c-library/              # C shim for generated pipelines
├── kompile-python/                 # Python SDK
├── kompile-sdk-serving/            # Serving SDK
├── kompile-rag-builds/             # Pre-generated sample RAG projects
├── docs/                           # AsciiDoc / HTML docs
└── pom.xml                         # Parent POM
```

## Memory management

**Build**

- Standard build: ~4 GB heap
- Native-image build: 18–32 GB heap
- Docker: `--memory=16g` recommended

```bash
docker run --ulimit nofile=98304:98304 --memory=16g --rm -it \
  --entrypoint /bin/bash konduitai/kompile
```

**Runtime**

```bash
java -Xmx4g  -jar kompile-cli.jar
java -Xmx8g  -jar kompile-app-main.jar
```

ND4J cleans up workspaces on shutdown, caps OpenBLAS threads via `ND4J_NUM_BLAS_THREADS`, and
runs with `-Dorg.bytedeco.javacpp.nopointergc=true` in production configurations.

## Common pitfalls

- **Out of memory during build** — raise `MAVEN_OPTS=-Xmx…`.
- **Native library not found** — verify `JAVACPP_PLATFORM` matches your OS/arch.
- **Model download fails** — check network access and SHA256 in `ModelConstants`.
- **Vector search returns nothing** — index and query must use the same embedding model.
- **Spring bean not found** — double-check `kompile.*.type` in `application.properties` matches a
  module that is on the classpath.
- **Frontend changes not visible** — Spring Boot caches static resources at startup; restart the
  app after rebuilding the Angular UI, and clean `target/classes/static/` if stale hashed
  bundles linger.

## Key dependencies

Java 17 · Spring Boot 3.2.5 · Spring AI 1.0.0 · Picocli 4.7.6 · ND4J 1.0.0-SNAPSHOT ·
Lucene (via Anserini) · JavaCPP 1.5.11 · GraalVM SDK 24.0.1 · Jackson 2.15.3 · Lombok 1.18.38

## Links

- Community: <https://community.konduit.ai>
- Eclipse DeepLearning4j: <https://github.com/deeplearning4j/deeplearning4j>
- HTML docs: [`./docs/`](./docs)
- License: see [`LICENSE`](./LICENSE) and [`NOTICE`](./NOTICE)
