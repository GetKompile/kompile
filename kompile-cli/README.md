# kompile-cli

The main developer CLI for the Kompile platform. This is the primary entry point — it manages projects, launches servers, runs chat sessions, converts models, and acts as an MCP server.

## Building

```bash
# JVM JAR
mvn clean package

# GraalVM native image
mvn clean package -Pnative
```

The build produces a shaded JAR (`kompile-cli-*-shaded.jar`) with main class `ai.kompile.cli.main.MainCommand`. The native image profile compiles this to a standalone binary named `kompile`.

## Usage

```bash
kompile --help
kompile <command> --help
```

## First-Time Setup

```bash
kompile bootstrap       # Create ~/.kompile directory layout and default configs
kompile install all      # Install GraalVM, Maven, Python (Miniconda)
```

`bootstrap` creates the `~/.kompile/` home directory with:
- `bin/`, `config/`, `components/`, `data/`, `logs/`
- `data/input_documents/uploads/`, `data/models/.staging/`
- Default config files: `app-index-config.json`, `pipeline-config.json`, `subprocess-ingest-config.json`, `nd4j-environment-config.json`, `feature-flags-config.json`, `tool-gateway-config.json`

## Commands

### Project Management

```bash
kompile project init --name=my-project         # Scaffold a new project
kompile project init --preset=vlm-ocr          # Scaffold with VLM/OCR preset
kompile project status                         # Show project state
kompile project serve                          # Start the project's web server
kompile project crawl                          # Run configured crawlers
kompile project crawl-add --type=confluence    # Add a data source
kompile project model-add --model=bge-base     # Register a model
kompile project code-index                     # Index code for semantic search
kompile project fact-sheet-list                # Browse generated fact sheets
```

A project is a self-contained directory with `kompile.project.json`, its own config, model assignments, vector indexes, and knowledge graph. Projects support `local`, `git`, and `git-xet` (large file) backends.

Init options include `--detect-sources` (auto-scan for documents), `--detect-models` (auto-register models in `data/models/`), `--auto-crawl` (auto-ingest after startup).

### Web Server

```bash
kompile web                                    # Start app + staging server
kompile web --port=9090                        # Custom app port
kompile web --staging-port=8091                # Custom staging port
kompile web --no-staging                       # Skip model staging server
kompile web --build                            # Rebuild project before starting
kompile web --no-open                          # Don't auto-open browser
kompile web stop                               # Stop running instances
kompile web status                             # Check what's running
```

Operates in two modes:
- **Project mode** (run from a project directory or with `--project-dir`): starts the project's built JAR plus `kompile-model-staging`
- **Global mode** (run from anywhere else): starts the globally installed `kompile-app-main` from `~/.kompile/components/`, auto-installing from GitHub Releases if needed

The web command launches `kompile-app-main` on port 8080 and `kompile-model-staging` on port 8090 as child processes. Running instances are tracked in `~/.kompile/data/pids/` and visible via `kompile info`, `kompile web status`, and `kompile manage list`.

### Chat

```bash
kompile chat                                   # Interactive agentic chat REPL
kompile chat --mode passthrough --agent claude  # Wrap Claude CLI as agent
kompile chat --url http://localhost:8080        # Connect to running app server
kompile chat --list                            # List saved conversations
kompile chat --continue                        # Resume most recent session
kompile chat --resume cli-abc123               # Resume specific session
kompile passthrough                            # Single prompt passthrough
kompile session list                           # List sessions
kompile resume                                 # Resume previous session
```

Three chat modes:
- **Passthrough**: wraps an external CLI agent (Claude, Codex, Gemini, Qwen) as a subprocess with MCP tool injection
- **Server**: connects to a running `kompile-app-main` via MCP SSE
- **Local**: direct LLM API calls (OpenAI, Anthropic, Gemini)

### Enforcer (Policy Enforcement)

```bash
kompile enforcer run --agent=claude                     # Launch enforced session
kompile enforcer run --judge-mode=subprocess             # Agent-to-agent enforcement
kompile enforcer run --judge-mode=subprocess --judge-agent=codex  # Different judge agent
kompile enforcer init                                    # Full enforcer setup wizard
kompile enforcer quick                                   # Quick preset-based setup
```

The enforcer wraps an agent session with policy enforcement. Every agent response is evaluated against a set of rules before being accepted. Three evaluator modes are available:

- **Keyword** (`--judge-mode=keyword`): instant pattern matching against banned tools, commands, keywords, and diff patterns. No LLM needed. Runs inline in the chat loop.
- **LLM judge** (`--judge-mode=local` or `--judge-mode=remote`): a separate LLM evaluates agent output against the rules via direct API call.
- **Subprocess judge** (`--judge-mode=subprocess`): a second CLI agent (Claude, Codex, Gemini, etc.) runs as a full managed subprocess with MCP tools, skills, and streaming — the same treatment as the worker agent. The judge can read files, run commands, and verify claims before issuing a verdict.

#### Enforcement Flow

```
User prompt
    │
    ▼
┌──────────────────┐
│   Worker Agent    │  (SubprocessAgentRunner — full CLI agent with MCP tools)
│  claude/codex/…  │
└────────┬─────────┘
         │ agent output
         ▼
┌──────────────────┐
│    Evaluator      │  keyword / LLM judge / subprocess judge
│                   │
│  Keyword:         │  pattern match → pass/fail
│  LLM judge:       │  API call → JSON verdict
│  Subprocess judge:│  full agent subprocess → reads files, runs tests → JSON verdict
└────────┬─────────┘
         │
    ┌────┴────┐
    │ PASS?   │
    └────┬────┘
     yes │    no
         │     │
         ▼     ▼
      Accept  Generate correction prompt
              → feed back to worker agent
              → retry (up to maxCorrections)
              → if still failing → STOP
```

The `EnforcerService` orchestrates this loop: worker generates → evaluator judges → if violation, a correction prompt is built and sent back → the worker retries up to `maxCorrections` times.

#### Subprocess Judge (Agent-to-Agent)

When `judgeMode=subprocess`, both the worker and judge are full `SubprocessAgentRunner` instances:

```
┌─────────────────────────────────┐
│         EnforcerService         │
│                                 │
│  ┌───────────┐  ┌────────────┐  │
│  │  Worker    │  │   Judge    │  │
│  │  Agent     │  │   Agent    │  │
│  │ (claude)   │  │  (codex)  │  │
│  │            │  │            │  │
│  │ MCP tools  │  │ MCP tools  │  │
│  │ Skills     │  │ Skills     │  │
│  │ Streaming  │  │ (silent)   │  │
│  └───────────┘  └────────────┘  │
│                                 │
│  Worker output → Judge prompt   │
│  Judge reads files, checks git  │
│  Judge returns JSON verdict     │
└─────────────────────────────────┘
```

The judge agent's output is captured silently (not displayed to the user) and parsed as an `EnforcerDecision` JSON response with `action` (PASS/RETRY/STOP), `violations`, and `guidance`.

#### Setup

Three ways to configure enforcement:

1. **Setup Wizard** (`kompile chat` first run): after selecting passthrough mode and agent, the wizard offers enforcer preset selection (standard/strict/minimal) and then judge mode upgrade (keyword-only or subprocess judge with agent selection).

2. **Enforcer Wizard** (`kompile enforcer init` or `/enforcer init` in chat): full step-by-step wizard covering agent, evaluation mode, rules, banned tools/commands, diff patterns, archiving, judge configuration, semantic matching, and max corrections.

3. **CLI flags**: `kompile enforcer run --agent=claude --judge-mode=subprocess --judge-agent=codex --rules="no rm -rf"`

#### Background Enforcer (Direct Passthrough)

When an enforcer config exists and the user chooses **direct passthrough** mode (the agent owns the terminal natively), enforcement works differently. The agent cannot be intercepted inline because it has full PTY control. Instead, a background monitor watches the agent's output and flags violations via file-based communication.

**How it works:**

1. The agent command is wrapped with the `script` utility to tee terminal output to a log file while preserving the agent's native interactive experience:
   ```
   script -q -f ~/.kompile/sessions/<sessionId>/output.log -c "claude --dangerously-skip-permissions"
   ```

2. A daemon thread (`BackgroundEnforcerMonitor`) polls the log file every 500ms using `RandomAccessFile` for efficient seeking, strips ANSI escape sequences, and checks each line against the configured rules (banned keywords, commands, and tools).

3. When a violation is detected, the monitor writes a JSON entry to an interrupt file at `~/.kompile/sessions/<sessionId>/enforcer-interrupt.json`.

4. The `enforcer_check` MCP tool (injected into the agent's MCP server) reads the interrupt file. The agent's system prompt instructs it to call `enforcer_check` before executing potentially dangerous operations.

```
┌──────────────────────────────────────────────────────────────┐
│                    Direct Passthrough                        │
│                                                              │
│  ┌────────────┐     script tee     ┌─────────────────────┐  │
│  │   Agent     │ ──────────────────▶│    output.log       │  │
│  │ (full PTY)  │                    └──────────┬──────────┘  │
│  │             │                               │ poll 500ms  │
│  │ MCP tools   │◀─── enforcer_check ───┐       ▼             │
│  └────────────┘                        │ ┌────────────────┐  │
│                                        │ │  Background    │  │
│                                        │ │  Enforcer      │  │
│                                        │ │  Monitor       │  │
│                                        │ └───────┬────────┘  │
│                                        │         │ write     │
│                                        │         ▼           │
│                                        └── interrupt.json    │
└──────────────────────────────────────────────────────────────┘
```

**Interrupt file format** (JSON array of violation entries):

```json
[
  {
    "timestamp": "2026-06-14T05:30:00Z",
    "violation": "Banned keyword detected: rm -rf",
    "rule": "BAN_CMD: rm -rf",
    "severity": "BLOCK",
    "acknowledged": false
  }
]
```

#### `enforcer_check` MCP Tool

Registered in both the stdio and socket MCP servers, this tool lets agents query enforcer state without any runtime coupling to the monitor process — communication is purely through file I/O.

| Action | Description |
|--------|-------------|
| `check` (default) | Return any pending (unacknowledged) violations |
| `acknowledge` | Mark all violations as seen and clear the interrupt |
| `status` | Show enforcer config summary — mode, banned keywords/tools/commands count, pending violations |

The tool resolves the interrupt file path from the `KOMPILE_ENFORCER_INTERRUPT_FILE` environment variable (set by `PassthroughCommand` when launching the agent), falling back to `<workingDir>/.kompile/enforcer-interrupt.json`.

#### Managed Mode Enforcement

When the user chooses **managed mode** (`kompile chat` or `kompile enforcer run`), enforcement runs inline — the `EnforcerService` intercepts agent output before displaying it, evaluates it against the rules, and can request corrections or stop the agent. No background monitor is needed because kompile controls the agent subprocess directly.

#### Enforcer Config

Stored at `.kompile/enforcer-config.json` in the project directory (check it into version control to share team policies):

```json
{
  "agent": "claude",
  "keywordMode": true,
  "judgeMode": "subprocess",
  "judgeAgent": "codex",
  "judgeInjectTools": true,
  "inlineRules": "No destructive operations",
  "bannedCommands": ["rm -rf", "DROP TABLE"],
  "bannedTools": ["bash_dangerous"],
  "bannedKeywords": ["TODO", "FIXME"],
  "maxCorrections": 2,
  "archiveDiffs": true,
  "autoRollbackOnViolation": true,
  "semanticMode": "wordnet",
  "semanticThreshold": 0.78,
  "diffPatternRules": ["BAN_DIFF_REGEX: password\\s*=\\s*\"[^\"]+\""]
}
```

Key fields:

| Field | Description |
|-------|-------------|
| `keywordMode` | `true` for pattern matching, `false` for LLM-based evaluation |
| `judgeMode` | `keyword`, `local`, `remote`, or `subprocess` |
| `judgeAgent` | Agent binary for subprocess judge (e.g. `codex`, `gemini`) |
| `bannedCommands` | Shell commands to block (e.g. `rm -rf`, `git push --force`) |
| `bannedTools` | MCP tool names to block |
| `bannedKeywords` | Strings to flag in agent output |
| `semanticMode` | `none`, `wordnet`, `embedding`, or `both` — fuzzy matching for bans |
| `diffPatternRules` | Patterns checked against file diffs (e.g. hardcoded secrets) |
| `archiveDiffs` | Save diffs before corrections for audit trail |
| `autoRollbackOnViolation` | Revert file changes when a violation is detected |
| `maxCorrections` | How many retry rounds before stopping |

#### Presets

Three built-in presets for quick setup (`kompile enforcer quick`):

| Preset | Guards |
|--------|--------|
| **strict** | Bash banned, diff archiving, auto-rollback, secret detection, WordNet semantics, TODO/FIXME/HACK bans |
| **standard** | Destructive command bans, diff archiving, auto-rollback, secret regex detection |
| **minimal** | Only critical stop-rules: force push, `rm -rf /`, `DROP TABLE`. No diff archiving |

### Model Management

```bash
kompile model list                             # List models in catalog
kompile model download --model=bge-base        # Download a model
kompile model convert --inputFile=m.pb --outputFile=m.fb   # TensorFlow → SameDiff
kompile model convert --inputFile=m.h5 --outputFile=m.zip  # Keras → DL4J
kompile model convert --inputFile=m.onnx --outputFile=m.fb # ONNX → SameDiff
kompile model export                           # Export .karch archive
kompile model import                           # Import .karch archive
```

The `model` command delegates to the `kompile-model` federated CLI binary.

### MCP Server

```bash
kompile mcp-stdio                              # Start MCP stdio server
kompile mcp-stdio --profile=full               # All ~44 tools
kompile mcp-stdio --profile=core               # 15 tools
kompile mcp-stdio --profile=explore            # 10 tools (read-only + code)
kompile mcp-stdio --profile=minimal            # 5 tools
```

Runs the CLI as a JSON-RPC 2.0 MCP server over stdin/stdout. Supports `initialize`, `tools/list`, `tools/call`, `prompts/list`, `prompts/get`, `resources/list`, `ping`.

Tools include: file I/O (`read`, `write`, `edit`, `patch`), search (`grep`, `glob`, `list`, `explore`), execution (`bash`), code intelligence (`code_search`, `code_graph`, `local_code_index`), knowledge (`rag_search`, `graph_search`), memory (`memory`, `semantic_memory`), agent delegation (`task`, `multi_task`, `quorum_task`), and web (`webfetch`, `websearch`).

Custom profiles can be placed in `~/.kompile/config/mcp-profiles/<name>.json`. Schema optimization levels: `none`, `moderate`, `aggressive`, `compact` (default).

### Knowledge Graph

```bash
kompile graph build                            # Build knowledge graph from documents
kompile graph browse                           # Interactive graph browser
kompile graph query                            # Query the graph
kompile graph extract                          # Entity/relation extraction
kompile graph merge                            # Merge graphs
kompile graph export                           # Export (CSV, JSON, GraphML, Cypher, etc.)
kompile graph import                           # Import graph
kompile graph shell                            # Interactive graph shell
```

### Cloud

```bash
kompile cloud register                         # Create account
kompile cloud login                            # Authenticate
kompile cloud status                           # Credits and auth status
kompile cloud apps create --name=my-app        # Create application
kompile cloud apps deploy <id>                 # Deploy to cloud
kompile cloud jobs list                        # Check build jobs
kompile cloud local list                       # Show locally running instances
```

### Installation

```bash
kompile install all                            # GraalVM + Maven + Python
kompile install graalvm                        # GraalVM only
kompile install python                         # Miniconda
kompile install maven                          # Maven
kompile install kompile-app                    # Download kompile-app-main JAR
kompile install kompile-model-staging          # Download model-staging JAR
kompile install native-tools                   # CMake, build tools
```

### Other Commands

| Command | Purpose |
|---------|---------|
| `info` | Report installed versions, running instances, environment |
| `configure` | Interactive guided configuration wizard |
| `config` | Generate pipeline, Python, server, variable configs |
| `build` | Build RAG apps, native images, ND4J backends |
| `build-rag-app` | Generate a custom RAG Spring Boot project with chosen modules |
| `init-project` | Full Maven project scaffolding wizard |
| `deploy` | Deploy a built project |
| `agent` | Multi-agent workflows (delegates to `kompile-agent`) |
| `lite` | Standalone Kompile Lite chat + RAG (delegates to `kompile-lite`) |
| `code-index` | Build, search, and export code indexes |
| `eval` | Run evaluation suites |
| `enforcer` | Policy enforcement for agent tool calls |
| `harness` | Performance benchmarking harness |
| `skills` | Manage and invoke skills |
| `schedule` | Scheduled tasks and cron jobs |
| `jobs` | Inspect and manage background jobs |
| `a2a` | Agent-to-Agent protocol |
| `manage` | Start/stop/status of managed components |
| `pipeline` | Pipeline management and execution |
| `sdk` | SDK operations |
| `uninstall` | Remove managed components |
| `run` | Download and serve a local LLM |
| `daemon` | Manage the shared daemon (Unix socket multiplexer) |

### Federated CLIs

Some commands delegate to separate binaries that must be on PATH or in `~/.kompile/bin/`:

- `kompile model` → `kompile-model` (model management)
- `kompile agent` → `kompile-agent` (agent workflows)
- `kompile lite` → `kompile-lite` (standalone chat + RAG)

### Plugin System

Third-party JARs can register additional CLI subcommands via `ServiceLoader<CliCommandRegistrar>`.

## Configuration

All persistent configuration is stored under `~/.kompile/`:

```
~/.kompile/
├── config/                          # JSON config files
│   ├── app-index-config.json        # Vector/keyword index paths
│   ├── nd4j-environment-config.json # ND4J runtime settings
│   ├── feature-flags-config.json    # Feature flags
│   ├── tool-gateway-config.json     # Tool gateway rules
│   └── mcp-profiles/               # Custom MCP tool profiles
├── components/                      # Auto-installed JARs
│   ├── kompile-app-main/
│   └── kompile-model-staging/
├── data/
│   ├── pids/                        # Running instance registry
│   ├── models/                      # Downloaded models
│   └── input_documents/             # Default document storage
├── logs/                            # MCP activity logs
└── backups/                         # Periodic backups
```
