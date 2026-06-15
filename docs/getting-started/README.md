# Getting Started

This guide walks you through four real scenarios, from simplest to most
involved. Pick the one that matches what you want to do.

## Scenario 1: Chat with your documents (fastest path)

You have a folder of PDFs, Word docs, or text files and you want to ask
questions about them.

```bash
# 1. Create a project
kompile project init --name my-docs

# 2. Start the server
kompile project open .
# Opens http://localhost:8080 with a setup wizard

# 3. Walk through the setup wizard
#    It confirms model-staging is running, picks an embedding model,
#    and verifies search works.

# 4. Ingest your documents
kompile crawl start /path/to/your/documents/

# 5. Chat
kompile chat --url=http://localhost:8080
```

Or skip the server entirely and use the CLI's built-in chat:

```bash
kompile configure chat          # One-time: pick an LLM provider
kompile chat                    # Direct LLM chat, no server
```

The server gives you the web UI, persistent document index, knowledge
graph, and the full retrieval pipeline. The direct CLI chat is lighter but
has no document retrieval — it's just you and the LLM.

**When to use the server:** You have documents to search. You want a web
UI. You need persistent state. Multiple people or agents will access the
same knowledge base.

**When to skip the server:** You just want to chat with an LLM. You're
running `kompile mcp-stdio` to provide tools to an agent. You're doing
model operations.

## Scenario 2: Give your AI agent access to your knowledge base

You use Claude Code, Codex, or another MCP-compatible agent and you want
it to search your indexed documents while it works.

```bash
# 1. Create a project and start the server (as above)
kompile project init --name my-project
kompile project open .

# 2. Ingest your documents
kompile crawl start /path/to/docs/

# 3. That's it. `project open` already wrote .mcp.json:
cat .mcp.json
```

`.mcp.json` tells your agent how to connect to Kompile's tools. Most
agents auto-discover it. The file contains two entries:

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

- **kompile** (stdio): The agent launches Kompile as a subprocess. Works
  without a running server. Provides file I/O, search, code tools.
- **kompile-app** (SSE): The agent connects to the running server. Provides
  RAG search, graph search, knowledge tools.

You can use both simultaneously — the agent gets tools from both sources.

**Tool profiles** control how many tools the agent sees:

| Profile | Tools | Good for |
|---------|-------|----------|
| `minimal` | 5 | Simple file navigation |
| `explore` | 10 | Read-only code exploration |
| `core` | 15 | Standard development work |
| `full` | ~44 | Everything: RAG, graphs, delegation, workflows |

```bash
kompile mcp-stdio --profile=core    # Limit exposed tools
```

**Or use passthrough mode** to wrap the agent with Kompile's tools
directly:

```bash
kompile chat --agent=claude-code --rag --role=architect
```

This launches Claude Code with Kompile's MCP tools injected, a system
prompt, and session persistence. The agent can search your documents,
query the knowledge graph, and use semantic memory — all transparently.

See [MCP Integration](../mcp/README.md) for the full tool catalog and
[Agents](../concepts/agents.md) for all agent modes.

## Scenario 3: Build a knowledge graph from your data

You want more than vector search — you want to understand how entities in
your documents relate to each other.

```bash
# 1. Set up a project with the server running (as in Scenario 1)

# 2. Crawl with graph extraction enabled
kompile crawl start /path/to/docs/ \
  --graph \
  --graph-model-provider=anthropic \
  --graph-schema-mode=LENIENT

# 3. Watch it work
kompile crawl status --watch
```

This does everything Scenario 1 does (chunk, embed, index) plus:
- An LLM reads each batch of chunks and extracts entities (people,
  organizations, technologies, concepts) and the relationships between them
- Entity resolution deduplicates across documents ("OpenAI" and "Open AI"
  become one node)
- The graph is stored and made available for Graph RAG queries

**When do you need a knowledge graph?**

When your questions are about *relationships* rather than *content*:
- "How does team X relate to project Y?"
- "What technologies does department Z use?"
- "Show me the dependency chain from A to B"

Vector search finds relevant text chunks. Graph RAG finds relevant text
chunks *and* tells you how the entities in those chunks connect to each
other. The LLM gets both the text and the relationship context.

**Schema modes** control extraction quality vs. flexibility:

| Mode | Trade-off |
|------|-----------|
| `NONE` | Maximum recall — the LLM extracts whatever it finds. Noisy. |
| `LENIENT` | Guided — the LLM sees your schema but can create new types. Good default. |
| `STRICT` | Maximum precision — only your defined types are accepted. Clean but may miss things. |

**Schema presets** give you ready-made type sets for common domains. Use
`kompile graph config presets` to list them, or define your own.

After the crawl finishes, you can explore the graph:

```bash
kompile graph stats                           # Overview
kompile graph search "machine learning"       # Find entities
kompile graph traverse --start-node=<id>      # Explore neighborhoods
kompile graph communities --summarize         # Detect and summarize clusters
kompile graph shell                           # Interactive Cypher REPL
kompile graph browse                          # Interactive TUI browser
```

See [Knowledge Graphs](../concepts/knowledge-graphs.md) for the full
graph system including Bayesian networks, KG embeddings, and graph
algorithms.

## Scenario 4: Build a custom application

You want to generate a self-contained RAG application tailored to your
needs — maybe for deployment, maybe for a specific use case.

```bash
# Interactive wizard
kompile build app --wizard

# Or from a preset
kompile build app --configName=myapp --preset=hosted-llm-rag
```

This generates a complete Maven project at
`kompile-rag-builds/myapp/project/` with exactly the modules you selected.

**Choosing a preset:**

| If you want... | Use this preset | What you get |
|----------------|----------------|-------------|
| Quick start with a hosted LLM | `hosted-llm-rag` | OpenAI LLM + local embeddings + PDF loader |
| AI agents that know your code | `cli-agent-rag` | Claude/Codex passthrough + local embeddings + file tools |
| Fully local, no API keys | `samediff-rag` | Local SameDiff embeddings + local LLM, nothing external |
| Small self-contained app | `lite` | Chat + RAG + Graph RAG in a minimal footprint |
| Everything | `full` | All modules — LLMs, embeddings, vector stores, OCR, graphs, training |
| Inference pipelines only | `pipeline` | Pipeline executor with SameDiff, ONNX, Python steps |
| Minimal hosted setup | `minimal` | OpenAI embeddings + OpenAI LLM + Anserini vector store |

After generating, the project has `start-all.sh` and `stop-all.sh` scripts,
or you can build a native image:

```bash
cd kompile-rag-builds/myapp/project/
./scripts/start-all.sh              # Start everything

# Or compile to a native binary
mvn clean package -DskipTests -Pnative
```

See [Build Applications](../cli/build-app.md) for full build options.

---

## What happens under the hood

When you ingest documents and then query them, here's the full flow:

### Ingestion

```
Your documents
  |
  v
[Crawl] Discover documents at source (files, web, email, cloud)
  |
  v
[Load] Parse each document using the appropriate loader
       (PDF, Office, email, audio, HTML, etc.)
  |
  v
[Preprocess] Language detection, PII redaction, boilerplate removal,
             deduplication (optional, configurable)
  |
  v
[Route] Classify content and route to the right pipeline
        (text, VLM for image-heavy PDFs, table-aware, code)
  |
  v
[Chunk] Split into chunks using the configured strategy
        (recursive, sentence, markdown, table-aware)
  |
  v
[Extract] (if graph enabled) LLM extracts entities + relationships
  |         from each batch of chunks
  v
[Resolve] (if graph enabled) Deduplicate entities across documents
  |         using string similarity + embedding similarity
  |
  v
[Embed] Convert each chunk to a vector using the embedding model
        (adaptive batch sizes based on available memory)
  |
  v
[Index] Write embeddings + text + metadata to the vector store
        (Lucene HNSW, pgvector, Chroma, or Vespa)
```

Each compute-heavy step runs as an isolated subprocess — the same binary
re-launched with `--subprocess=TYPE`. This keeps the web server responsive
and provides memory isolation.

See [Crawl Jobs](../concepts/crawl-jobs.md) and
[Ingestion Pipeline](../concepts/ingestion-pipeline.md) for full details.

### Query

```
Your question
  |
  v
[Transform] Optionally rewrite the query (HyDE, multi-query, step-back)
  |
  v
[Embed] Convert to a vector using the same embedding model
  |
  v
[Search] Vector similarity search (dense) + BM25 keyword search
  |         run in parallel, results merged
  |
  v
[Rerank] (optional) Re-score results using RM3, cross-encoder, MMR, etc.
  |
  v
[Graph RAG] (if enabled) Find matching graph entities, expand
  |           neighborhoods, add community summaries
  |
  v
[Assemble] Combine retrieved chunks + graph context + fact sheet data
  |          into a prompt, respecting the token budget
  |
  v
[Generate] LLM produces the answer with source attribution
```

See [Information Retrieval](../concepts/information-retrieval.md) for
the full retrieval architecture.

---

## Choosing what pieces you need

Not every project needs every feature. Here's a decision guide:

### Do I need the server?

**Yes** if you want:
- A web UI for managing documents, graphs, agents
- Persistent document index that survives restarts
- Multiple users or agents accessing the same knowledge base
- The full REST API
- WebSocket-based real-time monitoring

**No** if you're only:
- Chatting with an LLM directly (`kompile chat`)
- Serving MCP tools to an agent (`kompile mcp-stdio`)
- Running model operations (`kompile model`)
- Building pipelines (`kompile pipeline`)

### Do I need a knowledge graph?

**Yes** if your questions are about *relationships*:
- "Who works on what?"
- "How does A depend on B?"
- "What's the chain of events that led to X?"

**No** if your questions are about *content*:
- "What does the documentation say about configuration?"
- "Summarize this report."
- "Find all mentions of Y."

Vector search handles content questions well. Graph RAG adds value when
the relationships between entities matter.

You can always add graph extraction later by re-crawling with `--graph`.

### Do I need model-staging?

**Yes** if you want to:
- Download and convert models from HuggingFace
- Serve a local LLM with an OpenAI-compatible API
- Train or fine-tune models
- Manage model lifecycle (staging, promotion, hot-reload)

**No** if you're using hosted LLMs (OpenAI, Anthropic, Gemini) and
the built-in embedding models are sufficient.

### Do I need a custom build?

**Yes** if you want to:
- Deploy a specific combination of modules
- Build a native binary for production
- Reduce the footprint by excluding unused modules
- Include specific data source connectors

**No** if the default `kompile-server` binary works for your use case.
The default build includes all modules — you just configure which ones
are active at runtime.

### Do I need MCP tools?

**Yes** if you use Claude Code, Codex, Gemini Code Assist, or any other
MCP-compatible agent. Kompile gives the agent access to your documents,
graphs, code index, and more.

**No** if you only interact through the web UI or CLI chat.

---

## Next steps

- **Deep dive into crawls**: [Crawl Jobs](../concepts/crawl-jobs.md) covers
  source types, pipeline routing, adaptive batching, and real-time monitoring
- **Understand knowledge graphs**: [Knowledge Graphs](../concepts/knowledge-graphs.md)
  covers extraction, Bayesian networks, graph algorithms, and Graph RAG
- **Learn retrieval**: [Information Retrieval](../concepts/information-retrieval.md)
  covers vector search, query transformation, reranking, and all the ways
  data comes back out
- **Connect agents**: [MCP Integration](../mcp/README.md) covers tool
  profiles, transport modes, and auto-discovery
- **Configure the system**: [Configuration](../configuration/README.md)
  covers JSON config files, CLI wizards, and auto-detection
