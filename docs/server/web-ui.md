# Web UI

The kompile-server web UI is a full-stack Angular application accessible at `http://localhost:8080`.

## Screens

### Chat (default)

Conversational RAG with streaming responses, source attribution, multi-turn history, and token metrics. Two modes:

- **RAG mode**: Retrieved documents + LLM generation
- **Agent mode**: CLI agent (Claude Code, Codex, etc.) with MCP tool injection

### Knowledge

- **Document ingestion**: Create crawl jobs from 20+ sources
- **Knowledge graph**: Browse entities and relationships, visualize the graph
- **Fact sheets**: Notebook-style knowledge organization with key-value facts
- **Graph visualization**: Interactive graph explorer

### Code Projects

- Register source code repositories
- Trigger semantic indexing
- Browse the code graph (entities, relationships, callers, dependencies)
- Manage project context for agent sessions

### Tools

- Configure MCP servers
- Browse and invoke tools
- View tool call audit logs
- Manage prompt template skills

### KClaw (Agent Hub)

- Run CLI agents interactively in the browser
- MCP tool injection with permission management
- Heartbeat monitoring
- Session history and replay

### Settings

| Section | What you configure |
|---------|-------------------|
| Vector store | Backend (Anserini, pgvector, Chroma, Vespa), index paths |
| Chunking | Strategy, chunk size, overlap |
| Embedding | Model, provider, batch size |
| LLM | Provider, model, API key, base URL |
| Query | Rewriting, reranking, filter chains |
| Guardrails | Input/output filters, individual toggles |
| System prompts | Default prompts for chat and agent modes |
| Tool gateway | LLM judge rules for tool call approval |
| ND4J | Threads, BLAS, CUDA, Triton, memory limits |
| Config archive | Export/import all settings as a zip |

### Developer

- **ND4J framework**: Runtime status, GPU lifecycle, memory pools
- **Subprocess logs**: Aggregated logs from all subprocesses
- **Operation timing**: Performance metrics for embeddings, indexing, search
- **Benchmarks**: Run and compare performance benchmarks
- **VLM orchestration**: Vision-language model pipeline status
- **SameDiff graph**: Visualize and debug SameDiff computation graphs
- **Model debug**: Inspect loaded models and their configuration

## Connected Services

Manage OAuth connections for cloud data sources. Each provider shows connection status, token expiry, required scopes, and connect/disconnect/refresh actions.

Supported providers: Google Drive, OneDrive, Gmail, Slack, Discord, Confluence, Jira, Notion, Google Workspace.
