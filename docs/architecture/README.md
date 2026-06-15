# Architecture

## System overview

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

The kompile-server binary is self-contained. For compute-heavy work it re-launches itself as an isolated subprocess with `--subprocess=TYPE` so the web server stays responsive. Model-staging notifies the server to hot-reload models after promotion.

## RAG data flow

### Indexing pipeline

```
Documents -> Loaders -> Chunks -> Embeddings -> Vector Index
```

1. Load documents (PDF, Office, email via specialized loaders)
2. Parse and extract text/metadata
3. Chunk using token/sentence/recursive/markdown/table-aware splitters
4. Generate embeddings (batch processing with interrupt checks and adaptive sizing)
5. Index to vector store (Lucene HNSW, PostgreSQL, Chroma, or Vespa)

### Query pipeline

```
Query -> Embed Query -> Vector Search -> Retrieved Context -> LLM -> Response
```

1. Embed query using same model as indexing
2. Vector similarity search (COSINE, EUCLIDEAN, DOT\_PRODUCT)
3. Retrieve top-k documents with metadata
4. Optional: reranking, filter chain, graph RAG augmentation
5. Pass context to LLM for generation

## Design patterns

### Plugin architecture

- Spring Boot conditional beans: `@ConditionalOnProperty`
- Picocli command discovery via ClassGraph
- Interface-based pluggability (swap embeddings, vector stores, LLMs)

### Factory patterns

- `AnseriniEncoderFactory`: Maps model IDs to encoder instances
- `SameDiffEncoderFactory`: Creates tokenizer-aware encoders
- `KompileModelManager`: Model download/cache factory

### Subprocess isolation

Compute-heavy operations (embedding, indexing, model loading, training) run as isolated subprocesses. The same binary handles all subprocess types via a `--subprocess=TYPE` flag. This keeps the web server responsive and provides memory isolation.

Model-staging is a separate orchestrator process that uses `nd4j-native` only and launches subprocesses for CUDA workloads. It never has CUDA loaded itself.

### Resource management

- ND4J workspace cleanup on shutdown
- OpenBLAS thread pool termination via reflection
- Native library extraction to temp directories
- Interrupt handling in embeddings (check `Thread.isInterrupted()`)
- Adaptive batch sizing based on available GPU/CPU memory

### Native image support

GraalVM native image compilation produces a single unified binary. Class initialization is split between build-time (logging, static configs) and run-time (ND4J, JNI, JavaCPP, JGit, Netty).

Platform-specific native libraries are included/excluded via Maven profiles that auto-detect the build platform.
