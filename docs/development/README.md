# Development Guide

This section covers building Kompile from source and contributing to its major libraries.

## Prerequisites

- Java 17
- Maven 3.9+
- 10 GB RAM minimum (16 GB+ recommended for native builds)
- Docker (optional, for containerized builds)
- GraalVM 17 (optional, for native image builds -- needs 18-32 GB heap)

## Building from source

```bash
# Full build
mvn clean install -DskipTests

# CLI only
cd kompile-cli && mvn clean package

# RAG application (kompile-app-main only, not the entire parent)
cd kompile-app/kompile-app-main && mvn clean package

# Native image (requires GraalVM 17)
cd kompile-rag-builds/kompile-sample/project
mvn clean package -DskipTests -Pnative

# Full distribution tarball
kompile build dist
```

## Key dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Java | 17 | Runtime |
| Spring Boot | 3.2.5 | Web framework |
| Spring AI | 1.0.0 | LLM abstraction |
| Picocli | 4.7.7 | CLI framework |
| ND4J | 1.0.0-SNAPSHOT | BLAS compute |
| Lucene | via Anserini | Text + vector search |
| JavaCPP | 1.5.13 | JNI bindings |
| GraalVM SDK | 24.0.1 | Native compilation |
| Jackson | 2.15.3 | JSON/YAML |
| Lombok | 1.18.42 | Boilerplate reduction |

## Repository structure

```
kompile/
  kompile-cli/                       CLI (Picocli)
  kompile-project-store/             Project manifest read/write
  kompile-app/                       Spring Boot RAG framework (40+ modules)
    kompile-app-core/                  Core interfaces
    kompile-app-main/                  Main application + Angular web UI
    kompile-model-manager/             Model download and cache
    kompile-model-staging/             Model lifecycle service
    kompile-embedding-*/               Embedding implementations
    kompile-vectorstore-*/             Vector store implementations
    kompile-loader-*/                  Document loaders
    kompile-source-*/                  Data sources
    kompile-chunker-*/                 Chunking strategies
    kompile-tool-*/                    Spring AI / MCP tools
    kompile-kvcache/                   Paged KV cache for local LLMs
    kompile-graph-neo4j/               Graph RAG with Neo4j
    kompile-ocr-*/                     OCR pipeline
  kompile-pipelines-framework/       Pipeline execution engine
  anserini/                          Lucene IR toolkit + SameDiff dense encoders
  tokenizers-rust/                   HuggingFace tokenizers -> JavaCPP JNI bindings
  kompile-model-importer-*/          Model importers (TensorFlow, ONNX, Keras)
```
