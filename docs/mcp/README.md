# MCP Integration

Kompile exposes its full tool set to any MCP-compatible agent (Claude Code, Codex, Gemini Code Assist, Qwen, OpenCode) via the Model Context Protocol. Two transport modes are supported.

## Transport modes

### Stdio mode

The agent launches kompile as a subprocess:

```bash
kompile mcp-stdio --profile=full
```

### SSE mode

The agent connects to a running kompile-server:

```
http://localhost:8080/mcp/sse
```

Auto-configured in `.mcp.json` when you run `kompile project open`.

## Auto-discovery

When a project is opened, Kompile writes a `.mcp.json` in the project directory so agents auto-discover the tools:

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

Kompile also auto-configures hooks in agent settings files (`.claude/settings.local.json`, `.codex/config.toml`, `.opencode/plugins/`, `.gemini/settings.json`).

## Tool profiles

| Profile | Tools | Use case |
|---------|-------|----------|
| `minimal` | 5 | read, grep, glob, list, bash |
| `explore` | 10 | Read-only + code intelligence |
| `core` | 15 | File I/O + search + workflow |
| `full` | ~44 | Everything below |

## Full tool set

| Category | Tools |
|----------|-------|
| File I/O | `read`, `write`, `edit`, `patch` |
| Search | `grep`, `glob`, `list`, `explore` |
| Execution | `bash`, `process` |
| Network | `webfetch`, `websearch`, `browser` (CDP-based) |
| Workflow | `todowrite`, `todoread` |
| Knowledge | `rag_search`, `graph_rag_search`, `semantic_memory`, `memory`, `transcript_search` |
| Code | `code_search`, `code_graph`, `local_code_index`, `tool_call_catalog` |
| Delegation | `task` (single subagent), `multi_task` (parallel), `quorum_task` (consensus voting) |
| Coordination | `edit_coordinator`, `file_activity` (file watcher for multi-agent) |
| Config | `project_config`, `enforcer_config`, `role_manager`, `skill_manager`, `config_archive` |

Any tool can run asynchronously with `_background: true` -- returns a task ID immediately, use `poll` to check status later.

## Shared daemon

`kompile serve` runs a shared daemon that multiplexes MCP sessions over a Unix socket at `~/.kompile/runtime/kompile.sock`. One process serves N agent sessions instead of N separate JVMs.

## When to use each transport

**Stdio** — agent launches Kompile as a subprocess. No running server
needed. Best for: single-agent sessions, development, offline use. The
agent gets tools immediately when it starts.

**SSE** — agent connects to a running server. Requires `kompile project
open` or `kompile web`. Best for: multiple agents sharing the same
knowledge base, persistent state, web UI alongside agent work.

You can use both simultaneously. The `.mcp.json` file written by
`kompile project open` configures both.

## Related concepts

- **[Agents](../concepts/agents.md)** — agent modes that use MCP tools,
  including passthrough, KClaw, and A2A
- **[Information Retrieval](../concepts/information-retrieval.md)** — the
  retrieval mechanisms behind `rag_search`, `knowledge_search`, and
  `graph_search` tools
- **[Knowledge Graphs](../concepts/knowledge-graphs.md)** — the graph
  system behind the `knowledge_graph` and graph tools
- **[Code Projects](../concepts/code-projects.md)** — the code indexing
  system behind `code_search` and `code_graph` tools
- **[Configuration](../configuration/README.md)** — `kompile configure mcp`
  sets tool profiles and schema levels
