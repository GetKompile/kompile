# Kompile

Kompile is a self-hosted AI platform for building retrieval-augmented
generation (RAG), agentic chat, knowledge graph, and model inference
applications. It ships as native binaries and runs entirely on your
hardware. Embeddings, vector search, and model inference are computed
locally using [ND4J/SameDiff](https://github.com/deeplearning4j/deeplearning4j)
with CUDA or CPU backends. Hosted LLMs (OpenAI, Anthropic, Gemini) can be
plugged in for generation while keeping all retrieval and data processing
local.

## Who is this for?

**You want to search your own documents with AI.** You have PDFs, email
archives, Confluence spaces, Slack history, code repos, or other data
sitting on your machines. You want to ask questions about them and get
answers grounded in the actual content — not hallucinations. Kompile
ingests your data, builds vector indices and knowledge graphs, and serves
a RAG pipeline that ties it all together.

**You want AI agents that know your data.** Claude Code, Codex, and
similar agents are powerful but have no context about your project's
documents. Kompile injects its MCP tools (RAG search, graph search, code
search, memory) into those agents so they can query your indexed knowledge
while they work.

**You want to run models locally.** You want to embed documents, run
inference, and serve models without sending data to a third party. Kompile
runs ND4J/SameDiff and ONNX models on your GPU or CPU, and exposes an
OpenAI-compatible API for local LLM serving.

## What it does

Kompile has three main components. You can use them independently or
together:

| Component | What it does | When you need it |
|-----------|-------------|-----------------|
| **kompile** (CLI) | Project management, document ingestion, chat, model operations, agent tooling, pipeline execution | Always — it's the entry point for everything |
| **kompile-server** | Web UI + REST API for RAG, knowledge graphs, agent hub, document management | When you want a persistent server with a browser interface, or when agents need SSE-based MCP tools |
| **kompile-model-staging** | Model download, conversion, training, local LLM inference | When you need to manage model lifecycle or serve local LLMs |

**You don't have to use all three.** The CLI works standalone for chat,
model operations, and MCP tool serving. The server adds the web UI, REST
API, and persistent state. Model-staging adds model lifecycle management.
Start with what you need and add pieces as your use case grows.

## How to read these docs

### Starting out

1. **[Installation](getting-started/installation.md)** — download, extract,
   configure
2. **[Your first project](getting-started/README.md)** — create a project,
   ingest documents, chat with your data, connect an AI agent
3. **[Projects](getting-started/projects.md)** — what a project is and how
   it organizes your work

### Understanding the system

4. **[How it all fits together](concepts/README.md)** — the big picture:
   how crawls, graphs, retrieval, and agents connect
5. **[Crawl jobs](concepts/crawl-jobs.md)** — how data gets into the system
6. **[Knowledge graphs](concepts/knowledge-graphs.md)** — entity extraction,
   Bayesian networks, graph algorithms, Graph RAG
7. **[Information retrieval](concepts/information-retrieval.md)** — how data
   comes back out: vector search, BM25, reranking, query transformation
8. **[Agents](concepts/agents.md)** — agent modes, passthrough, KClaw, A2A
9. **[Fact sheets](concepts/fact-sheets.md)** — curated knowledge notebooks

### Reference

10. **[CLI Reference](cli/README.md)** — every command and its options
11. **[Server](server/README.md)** — web UI screens and REST API endpoints
12. **[MCP Integration](mcp/README.md)** — connecting AI agents via MCP
13. **[Configuration](configuration/README.md)** — JSON config files, wizards
14. **[Architecture](architecture/README.md)** — system design, data flow,
    subprocess model

### Building from source

15. **[Development Guide](development/README.md)** — building each component

## Download

Pre-built native binaries are published to
[GitHub Releases](https://github.com/GetKompile/kompile/releases):

| Platform | Archive |
|----------|---------|
| Linux x86\_64 (CUDA) | `kompile-<version>-linux-x86_64-cuda12.9.tar.gz` |
| Linux x86\_64 (CPU) | `kompile-<version>-linux-x86_64-cpu.tar.gz` |
| Linux ARM64 | `kompile-<version>-linux-arm64-cpu.tar.gz` |
| macOS Apple Silicon | `kompile-<version>-macosx-arm64-cpu.tar.gz` |
| Windows x86\_64 | `kompile-<version>-windows-x86_64-cuda12.9.zip` |

Extract and run. Native libraries auto-resolve from the adjacent `lib/`
directory.

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
