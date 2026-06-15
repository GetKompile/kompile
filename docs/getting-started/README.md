# Getting Started

This guide walks you through creating your first Kompile project, ingesting documents, and chatting with your data.

## Prerequisites

Download the Kompile distribution for your platform from [GitHub Releases](https://github.com/GetKompile/kompile/releases) and extract it. Add the `bin/` directory to your `PATH`.

## 1. Create a project

A **project** is the central organizing concept in Kompile. It is a directory with a `kompile.project.json` manifest that ties together your documents, models, code repositories, crawl profiles, pipelines, prompt templates, and chat sessions.

```bash
# Initialize a project in the current directory
kompile project init --name my-rag-project

# Or start from a pre-built app template
kompile build app --configName=myapp --preset=hosted-llm-rag
```

`kompile project init` scans your directory for existing assets (build files, model files, documents) and auto-detects what kind of project it is. It creates the standard directory structure:

```
my-rag-project/
  kompile.project.json          # Project manifest
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
```

## 2. Open the project and start the server

```bash
kompile project open .
```

This writes `.kompile/project/open.json`, starts the server on port 8080, starts model-staging on port 8090, and writes `.mcp.json` for AI agents.

Open your browser to **http://localhost:8080**. The setup wizard walks you through:

1. **Staging server** -- confirms kompile-model-staging is running
2. **Model source** -- connects to the staging service or loads local models
3. **Embedding model** -- downloads and initializes an embedding model (e.g., BGE, Arctic Embed)
4. **Document indexing** -- ingest your first documents
5. **Search readiness** -- verifies end-to-end retrieval works

## 3. Ingest documents

**From the web UI** (Knowledge tab > New Crawl Job):

Configure sources, graph extraction, vector indexing, and PDF routing. Submit and watch progress in real-time with pipeline counters, memory meters, and per-document status.

**From the CLI:**

```bash
kompile ingest file /path/to/document.pdf
kompile ingest path /path/to/documents/
kompile ingest url https://docs.example.com
kompile ingest status
```

## 4. Chat with your data

```bash
# Web UI
# http://localhost:8080 -> Chat tab

# CLI: connect to the running server for RAG-augmented chat
kompile chat --url=http://localhost:8080

# CLI: direct LLM chat (no server needed)
kompile chat

# CLI: wrap an AI agent with kompile's RAG tools
kompile chat --agent=claude-code --rag
```
