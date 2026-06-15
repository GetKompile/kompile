# CLI Reference

The `kompile` CLI is the main entry point for everything: project management, building applications, chatting, ingesting documents, running models, and connecting AI agents to your data.

## Command groups

| Command | Description |
|---------|------------|
| `kompile project` | Create, open, manage projects |
| `kompile build app` | Generate RAG applications from presets or custom module selection |
| `kompile build dist` | Build distribution tarballs with all three binaries |
| `kompile chat` | Direct LLM chat, server-connected RAG chat, or agent passthrough |
| `kompile ingest` | Upload files, register directories, add URL sources |
| `kompile run` | Download and run a local LLM with OpenAI-compatible API |
| `kompile model` | Download, convert, list, export, import models |
| `kompile configure` | Interactive configuration wizards |
| `kompile mcp-stdio` | Expose MCP tools to AI agents via stdio transport |
| `kompile serve` | Run a shared MCP daemon over Unix socket |
| `kompile enforcer` | Policy-governed agent execution |
| `kompile agent` | Agent workflows, tasks, channels |
| `kompile app` | Manage a running server (ingest, index, crawl, jobs, graph) |
| `kompile graph` | Knowledge graph operations (nodes, edges, traverse, search) |
| `kompile code-index` | Local code search with live re-indexing |
| `kompile perf` | Model performance analytics and leaderboards |
| `kompile install` / `uninstall` | Manage dependencies (GraalVM, Maven, Python) |
| `kompile bootstrap` | Initialize `~/.kompile` directory |
| `kompile info` | Show system and environment information |
