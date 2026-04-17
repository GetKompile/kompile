# Kompile Overview

Kompile is an AI/ML platform for building, deploying, and operating retrieval-augmented
generation (RAG) applications, agent workflows, and ML pipelines on the JVM. It bundles
several cooperating components into a single repository so developers can move from a raw
model artifact to a serving endpoint without leaving the toolchain.

## Main Components

- **kompile-cli**: A Picocli-based command line interface that drives model conversion,
  ingestion, query, and end-to-end RAG application generation. Subcommands include
  `bootstrap`, `install`, `model convert`, `ingest`, and `build`. Federated subcommands
  delegate to companion CLIs (`kompile-app`, `kompile-model`, `kompile-agent`).

- **kompile-app**: A Spring Boot RAG application framework split into 40+ modules. It
  defines core interfaces (`EmbeddingModel`, `VectorStore`, `LanguageModel`,
  `DocumentRetriever`) and provides pluggable implementations for embeddings (Anserini,
  OpenAI, Postgres ML), vector stores (Lucene HNSW, pgvector, Chroma), and document
  loaders (PDF, Office, Tika).

- **kompile-model-staging**: A standalone staging server (port 8090 by default) that
  downloads, converts, validates, and promotes models. It is the bridge between raw
  artifacts on HuggingFace, GitHub, or HTTP and the runtime model registry consumed by
  kompile-app instances.

- **kompile-pipelines-framework**: A pipeline execution engine that composes Python,
  SameDiff, DL4J, and ONNX steps into reusable workflows.

- **tokenizers-rust**: HuggingFace tokenizer bindings exposed through a C++ wrapper and
  JavaCPP for high-throughput, language-aware text processing.

## Runtime Modes

Kompile supports two deployment modes: a JVM mode (`mvn spring-boot:run` or a fat jar)
and a GraalVM native image mode that produces a single binary for the main app and all
subprocess types (`--subprocess=ingest`, `--subprocess=embedding`, etc.). Both modes
share the same configuration surface in `application.properties`.
