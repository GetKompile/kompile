# Kompile

Kompile is a self-hosted AI platform for building retrieval-augmented generation (RAG), agentic chat, knowledge graph, and model inference applications. It ships as native binaries and runs entirely on your hardware. Embeddings, vector search, and model inference are computed locally using [ND4J/SameDiff](https://github.com/deeplearning4j/deeplearning4j) with CUDA or CPU backends. Hosted LLMs (OpenAI, Anthropic, Gemini) can be plugged in for generation while keeping all retrieval and data processing local.

## Three pillars

### Compile Models

Multi-GPU auto-scheduling, graph optimizations (fusion, hardware targeting), training and fine-tuning (LoRA, DPO, PPO, distillation), and a model registry with `.karch` air-gapped archives.

### Compile Knowledge

RAG pipeline with query transformers, contextual enrichment, guardrails, and an evaluation harness. GraphRAG with entity and relationship extraction, Neo4j or native storage, and community detection. Automated knowledge graph construction with data crawlers for 20+ sources.

### Compile Applications

LLM provider integrations (OpenAI, Anthropic, Gemini, local SameDiff), embeddings and vector stores (BGE, Arctic Embed, Anserini, pgvector, Chroma, Vespa), data source crawlers (Confluence, Jira, Notion, Slack, Discord, Google Workspace, Gmail, Reddit), orchestration engines (Apache Camel, n8n), and Agent-to-Agent Protocol (A2A) for multi-agent coordination.

## The three components

| Component | What it is | Default port |
|-----------|-----------|-------------|
| **kompile** (CLI) | Command-line tool for project management, building apps, chatting, ingesting documents, running models, and connecting AI agents | N/A |
| **kompile-server** | Spring Boot + Angular web application for document-powered AI | 8080 |
| **kompile-model-staging** | Model lifecycle service: download, convert, validate, promote, serve | 8090 |

## Download

Pre-built native binaries are published to [GitHub Releases](https://github.com/GetKompile/kompile/releases):

| Platform | Archive |
|----------|---------|
| Linux x86\_64 (CUDA) | `kompile-<version>-linux-x86_64-cuda12.9.tar.gz` |
| Linux x86\_64 (CPU) | `kompile-<version>-linux-x86_64-cpu.tar.gz` |
| Linux ARM64 | `kompile-<version>-linux-arm64-cpu.tar.gz` |
| macOS Apple Silicon | `kompile-<version>-macosx-arm64-cpu.tar.gz` |
| Windows x86\_64 | `kompile-<version>-windows-x86_64-cuda12.9.zip` |

Extract and run. Native libraries auto-resolve from the adjacent `lib/` directory.

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
