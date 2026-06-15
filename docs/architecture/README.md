# Architecture

## System overview

Kompile has three processes that work together. You can run all three or
just the ones you need.

```
                              +-----------------------+
                              |   kompile (CLI)       |
                              |                       |
                              |  project init/open    |
                              |  build app            |
                              |  chat / ingest        |
                              |  mcp-stdio            |
                              |  run <model>          |
                              |  enforcer             |
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

### Why three processes?

**kompile (CLI)** is always present. It's the entry point for everything:
creating projects, starting servers, running commands, serving MCP tools.
It works standalone — you don't need the server for direct chat, model
operations, or MCP stdio.

**kompile-server** adds persistence and a web interface. It maintains the
document index, knowledge graph, and chat sessions across restarts. It
provides the REST API that the CLI and agents connect to. If you're just
doing model operations or CLI chat, you don't need it.

**kompile-model-staging** handles model lifecycle. It downloads models
from HuggingFace, converts formats (ONNX, GGUF, TensorFlow -> SameDiff),
serves them via an OpenAI-compatible API, and notifies the server to
hot-reload after promotion. It's separate because it manages CPU-only
orchestration while delegating GPU work to subprocesses. If you're using
hosted LLMs and the built-in embedding models, you don't need it.

## Data flow

### Indexing

```
Your documents
  |
  [Crawl] -> [Load] -> [Preprocess] -> [Route]
                                          |
                         +----------------+----------------+
                         |                                 |
                    [Text pipeline]                  [VLM pipeline]
                         |                                 |
                     [Chunk]                           [OCR]
                         |                                 |
                         +----------------+----------------+
                                          |
                                   [Graph extract]  (optional)
                                          |
                                   [Entity resolve] (optional)
                                          |
                                      [Embed]
                                          |
                                      [Index]
                                          |
                               +----------+----------+
                               |                     |
                        Vector store          Knowledge graph
```

See [Crawl Jobs](../concepts/crawl-jobs.md) and
[Ingestion Pipeline](../concepts/ingestion-pipeline.md) for details.

### Querying

```
User question
  |
  [Query transform]  (optional: HyDE, multi-query, step-back)
  |
  [Embed query]
  |
  +---[Vector search]---+---[BM25 search]---+---[Graph RAG]---+
  |                     |                   |                  |
  +---------------------+-------------------+------------------+
  |
  [Merge + dedup + rerank]
  |
  [Assemble context]  (+ fact sheet facts)
  |
  [LLM generation]
  |
  Response with source attribution
```

See [Information Retrieval](../concepts/information-retrieval.md) for the
full retrieval architecture.

## Subprocess model

Compute-heavy work runs as isolated subprocesses — the same binary
re-launched with `--subprocess=TYPE`. This provides:

- **Memory isolation**: A subprocess OOM doesn't crash the web server
- **Responsiveness**: The web server stays fast while embedding runs
- **Resource control**: Each subprocess has its own heap size, timeout, and
  worker count

| Subprocess type | What it does |
|----------------|-------------|
| `ingest` | Load and chunk documents |
| `vector-population` | Embed chunks and write to vector store |
| `embedding` | Standalone embedding computation |
| `model-init` | Model download and initialization |
| `vlm-test` | VLM OCR processing |
| `training` | Model training jobs |

Subprocesses are launched with `--subprocess=TYPE`. Configuration lives in
`subprocess-ingest-config.json`. The web server monitors subprocess health
via RSS tracking.

**Model-staging** is a separate orchestrator that uses `nd4j-native` only
(CPU). It never loads CUDA itself — it launches subprocesses with CUDA
backends for GPU work. This separation prevents GPU memory conflicts.

## Plugin architecture

The server's behavior changes based on which modules are on the classpath:

```java
@Component
@ConditionalOnProperty(name = "kompile.vectorstore.type", havingValue = "pgvector")
public class PgVectorStoreImpl implements VectorStore { ... }
```

Every major component (embeddings, vector stores, LLMs, loaders, chunkers,
graph backends) is an interface with pluggable implementations. Spring
Boot's conditional bean loading activates only the implementations that
match your configuration.

This means:
- The full binary contains all modules, but only configured ones are active
- Custom builds (`kompile build app`) can exclude unused modules entirely
- Swapping a component (e.g., Anserini -> pgvector) is a config change,
  not a code change

## Configuration flow

All runtime configuration lives in JSON files at `~/.kompile/config/`:

```
CLI wizards  ---+
                +--->  ~/.kompile/config/*.json  --->  Server reads on
Web UI       ---+                                     startup (and reloads
REST API     ---+                                     on change)
```

Spring properties (`application.properties`) exist only for bootstrap
defaults — port numbers, paths, which providers are enabled/disabled. The
JSON config files always take precedence at runtime. This is intentional:
config should be manageable through the UI and CLI, not buried in properties
files.

See [Configuration](../configuration/README.md) for the full config
reference.

## Native image compilation

GraalVM native image compilation produces a single binary with no JVM
dependency. Class initialization is split:

- **Build-time**: Logging (SLF4J, Logback), static configs, Picocli
  command metadata
- **Run-time**: ND4J, JNI (JavaCPP, Bytedeco), JGit, Netty Epoll,
  dynamic proxy generation

Platform-specific native libraries are included/excluded via Maven
profiles that auto-detect the build platform. The `kompile build dist`
command builds all three binaries (CLI, server, model-staging) into a
distribution tarball.

## Key design decisions

**Why subprocess isolation instead of thread pools?** Native memory (ND4J,
CUDA) is hard to reclaim once allocated. Subprocess isolation guarantees
cleanup — when the subprocess exits, the OS reclaims all memory. Thread-pool
approaches risk native memory leaks and GPU memory fragmentation.

**Why JSON config instead of Spring properties?** Properties files are
developer-facing. JSON config files can be read and written by the web UI,
CLI wizards, and the REST API. The same config is editable through three
interfaces. Properties files would require server restarts; JSON files
are hot-reloaded.

**Why separate model-staging?** Model operations (download, convert,
train) need CPU-only orchestration with GPU work delegated to subprocesses.
If the server loaded models directly, a model conversion OOM could crash
the web UI. Separation also enables hot-reload: staging promotes a model
and sends an HTTP callback to the server, which loads the new model
without restarting.

**Why a single binary for subprocesses?** Instead of separate binaries for
ingestion, embedding, and indexing, one binary handles all subprocess types
via `--subprocess=TYPE`. This simplifies deployment (one binary per
component), ensures version consistency (subprocess always matches server),
and works naturally with native image compilation.

## Related pages

- **[How It All Fits Together](../concepts/README.md)** — the big picture
  from a user's perspective
- **[Getting Started](../getting-started/README.md)** — practical
  walkthroughs for common scenarios
- **[Configuration](../configuration/README.md)** — the JSON config system
- **[Crawl Jobs](../concepts/crawl-jobs.md)** — the ingestion system
  in detail
- **[Knowledge Graphs](../concepts/knowledge-graphs.md)** — the graph
  system including Bayesian networks
- **[Information Retrieval](../concepts/information-retrieval.md)** — the
  query pipeline
