# Agents

Kompile supports multiple ways to run AI agents, from simple chat to multi-agent coordination.

## Agent modes

### Direct chat

Kompile itself acts as the chat interface to an LLM. No external agent needed.

```bash
kompile chat
```

### Server-connected RAG chat

Connect to a running kompile-server for retrieval-augmented generation.

```bash
kompile chat --url=http://localhost:8080
```

### Agent passthrough

Wrap an external CLI agent (Claude Code, Codex, Gemini Code Assist) with Kompile's MCP tools. Kompile injects tools, adds a system prompt, and manages session persistence.

```bash
kompile passthrough --agent=claude-code --inject-tools
kompile chat --agent=claude-code --rag --role=architect
```

### Emulated passthrough

Run agent passthrough with Kompile's own TUI rendering.

```bash
kompile emulated-passthrough --agent=claude-code
```

### KClaw (Agent Hub)

Run CLI agents interactively in the browser via the web UI. Provides tool injection, permission management, heartbeat monitoring, and session history.

Access from the web UI: KClaw tab.

### ReAct agents

Server-side agents with tool-calling loops, configurable per-agent API configs, test suites, and evaluation metrics.

```bash
# Via REST API
curl -X POST http://localhost:8080/api/agents/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Find all files related to authentication"}'
```

## Agent-to-Agent Protocol (A2A)

Multi-agent coordination across services. Remote agents expose an agent card at `/.well-known/agent-card.json` and communicate via the A2A protocol.

```bash
# Enable A2A
kompile a2a enable

# Discover a remote agent
kompile a2a discover http://remote-agent:8080

# List registered agents
kompile a2a list

# Send a task to a remote agent
kompile a2a send --agent=<id> --message="Analyze this dataset"

# Ping connectivity
kompile a2a ping --agent=<id>
```

## Policy enforcement

The enforcer wraps agent execution with rule-based compliance. See [Policy Enforcer](../cli/enforcer.md).

## Performance harness

Evaluate agent performance across tasks with the harness:

```bash
kompile perf report                    # Performance report
kompile perf recommend                 # Get optimization recommendations
kompile perf config                    # Configure harness settings
```

## Agent evaluation

Run structured evaluation suites:

```bash
kompile eval run --suite=my-suite --agent=claude-code
kompile eval report --suite=my-suite
kompile eval compare --suite=my-suite  # Compare across runs
kompile eval list                      # List available suites
kompile eval create --name=my-suite    # Create a new suite
```

## Session management

All agent interactions are tracked as sessions:

```bash
# List sessions
kompile session list

# Show a session
kompile session show --id=<id>

# Search across sessions
kompile session search --query="authentication"

# Import sessions from external providers
kompile session import --source=claude
kompile session import-all

# Resume a session
kompile chat --resume --session-id=<id>

# Resume all tracked sessions
kompile resume-all --agent=claude-code
```

## Agent workflows

Manage orchestrator workflows (via the agent CLI):

```bash
kompile agent workflow list
kompile agent workflow create --name=my-workflow
kompile agent workflow run --id=<id>
kompile agent workflow stop --id=<id>
```

## Agent logs

```bash
# View agent run logs
kompile agent logs list --agent=claude-code
kompile agent logs tail --instance=<id>
kompile agent logs aggregate --since=24h

# View subprocess logs
kompile agent subprocess-logs list --type=ingest
kompile agent subprocess-logs tail --run-id=<id>
```

## Edit coordination

When multiple agents work on the same codebase, the edit coordinator manages file locks:

```bash
kompile edit-coordinator status        # Overall coordination status
kompile edit-coordinator edits         # Active edit locks
kompile edit-coordinator agents        # Active agents
kompile edit-coordinator release --file=path/to/file  # Release a lock
kompile edit-coordinator clean         # Clean up stale state
```

## Related concepts

- **[MCP Integration](../mcp/README.md)** — how agents connect to
  Kompile's tools via stdio or SSE transport, tool profiles, auto-discovery
- **[Information Retrieval](information-retrieval.md)** — the retrieval
  mechanisms agents use through `rag_search`, `knowledge_search`, and
  `graph_search` MCP tools
- **[Knowledge Graphs](knowledge-graphs.md)** — the graph system agents
  query through the `knowledge_graph` MCP tool, including Graph RAG,
  entity search, and Cypher queries
- **[Fact Sheets](fact-sheets.md)** — how agents activate and query
  scoped knowledge partitions
- **[Code Projects](code-projects.md)** — the code indexing system agents
  use through `code_search` and `code_graph` MCP tools
- **[Guardrails and Evaluation](guardrails-and-evaluation.md)** — input/output
  filters and evaluation harness for agent quality
