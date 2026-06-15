# Chat

Kompile supports three chat modes: direct LLM, server-connected RAG, and agent passthrough.

## Direct LLM chat

No server needed. A setup wizard runs on first use to configure your LLM provider.

```bash
kompile chat
```

## Server-connected RAG chat

Connects to a running kompile-server and augments queries with retrieved documents.

```bash
kompile chat --url=http://localhost:8080
```

Queries flow through: optional query rewriting, embedding, vector search, optional reranking, optional filter chain, LLM generation with retrieved context.

## Agent passthrough

Wraps an AI agent (Claude Code, Codex, etc.) with Kompile's MCP tools for RAG search, graph RAG, file I/O, code search, and memory.

```bash
kompile chat --agent=claude-code --rag --role=architect
```

Kompile injects its MCP tools into the agent, adds a system prompt, and manages session persistence.

```bash
# Resume a previous session
kompile chat --continue

# List sessions
kompile session list
```

## Running a local LLM

```bash
# Downloads from HuggingFace, starts an OpenAI-compatible server
kompile run Qwen/Qwen3-0.6B --serve --port=8000

# Interactive chat with a local model
kompile run Qwen/Qwen3-0.6B --backend=cuda
```
