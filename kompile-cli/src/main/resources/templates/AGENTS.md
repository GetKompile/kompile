# AGENTS.md — Kompile Tool Orchestration Mandate

## MANDATORY RULES — READ BEFORE DOING ANYTHING

**STOP. Read these rules in full before executing any action in this project.**

You are operating in a kompile-managed project. A kompile MCP server is available and provides dedicated tools for file I/O, search, code navigation, task management, web access, and multi-agent orchestration. You MUST use these tools. This is not a suggestion. This is a hard requirement.

## TOOL LOADING — DEFERRED TOOLS MUST BE ACTIVATED FIRST

Kompile tools are **deferred** — they appear in `<available-deferred-tools>` but cannot be called until their schemas are loaded. **At the start of every session**, you MUST load the core tools before doing any work:

```
ToolSearch query="select:mcp__kompile__file_read,mcp__kompile__file_edit,mcp__kompile__file_write,mcp__kompile__search_grep,mcp__kompile__search_glob,mcp__kompile__shell,mcp__kompile__explore"
```

Load additional tools as needed:
```
ToolSearch query="select:mcp__kompile__task,mcp__kompile__multi_task,mcp__kompile__todowrite,mcp__kompile__todoread"
ToolSearch query="select:mcp__kompile__webfetch,mcp__kompile__websearch,mcp__kompile__memory"
ToolSearch query="select:mcp__kompile__process,mcp__kompile__edit_coordinator,mcp__kompile__code_search,mcp__kompile__code_graph"
```

**If a tool becomes unavailable mid-session** (connection drop), re-run the ToolSearch to reload it. If loading fails, the MCP server may need restart — fall back to built-in tools temporarily and note the issue.

### Tool Name Mapping

All kompile tools are prefixed with `mcp__kompile__` when called. Here is the mapping:

| Task                | Tool name to call              | Short name |
|---------------------|--------------------------------|------------|
| Read files          | `mcp__kompile__file_read`      | file_read  |
| Edit files          | `mcp__kompile__file_edit`      | file_edit  |
| Write/create files  | `mcp__kompile__file_write`     | file_write |
| Search file content | `mcp__kompile__search_grep`    | search_grep|
| Find files by glob  | `mcp__kompile__search_glob`    | search_glob|
| Run shell commands  | `mcp__kompile__shell`          | shell      |
| Explore directories | `mcp__kompile__explore`        | explore    |
| List directory      | `mcp__kompile__list`           | list       |
| Apply patch         | `mcp__kompile__patch`          | patch      |
| Fetch URLs          | `mcp__kompile__webfetch`       | webfetch   |
| Web search          | `mcp__kompile__websearch`      | websearch  |

---

### RULE 1: USE KOMPILE TOOLS — NO EXCEPTIONS

Every action that has a kompile tool equivalent MUST use that tool. You are PROHIBITED from substituting shell commands, built-in tools, or any other mechanism when a kompile tool covers the operation.

| PROHIBITED                               | REQUIRED (kompile tool)          |
|------------------------------------------|----------------------------------|
| `cat file`, `head file`, `tail file`     | `file_read`                      |
| `echo > file`, heredoc, write to stdout  | `file_write`                     |
| `sed -i`, `awk`, inline string replace   | `file_edit`                      |
| `grep`, `rg`, `ag`, `ack`               | `search_grep`                    |
| `find`, `fd`, `ls -R`                    | `search_glob`                    |
| `ls`                                     | `list`                           |
| Fetching URLs with `curl`, `wget`        | `webfetch`                       |
| Web search via any shell command         | `websearch`                      |
| Built-in `Read` tool                     | `mcp__kompile__file_read`        |
| Built-in `Edit` tool                     | `mcp__kompile__file_edit`        |
| Built-in `Write` tool                    | `mcp__kompile__file_write`       |
| Built-in `Grep` tool                     | `mcp__kompile__search_grep`      |
| Built-in `Glob` tool                     | `mcp__kompile__search_glob`      |

### RULE 2: SHELL IS FOR SYSTEM COMMANDS ONLY

The `shell` tool exists for operations that have NO dedicated kompile tool: compiling code, running tests, git operations, package management, starting/stopping services. If you find yourself using `shell` to read a file, search for text, list a directory, or edit a file, you are violating Rule 1. Stop and use the correct tool.

### RULE 3: ALWAYS READ BEFORE EDIT

You MUST `file_read` a file before calling `file_edit` or `file_write` on it. Never guess at file contents. Never assume you know what a file contains from a previous session. Read it now, in this session.

### RULE 4: COORDINATE IN MULTI-AGENT SCENARIOS

When multiple agents operate concurrently, you MUST use `edit_coordinator` to register your edits and check for conflicts BEFORE modifying any file. Skipping coordination causes data loss and merge conflicts that cannot be recovered.

### RULE 5: TRACK YOUR WORK

For any task with more than two steps, use `todowrite` to create a task list and update it as you complete each step. This is not optional overhead — it is how the orchestrator tracks your progress and decides whether to intervene.

---

## PROHIBITED ACTIONS — VIOLATIONS WILL BE FLAGGED

The following are explicit violations. If you catch yourself doing any of these, stop immediately and correct your approach.

1. **Using `shell` to read files.** No `cat`, `head`, `tail`, `less`, `more`, `bat`, or piping file contents through any command. Use `file_read`.

2. **Using `shell` to search.** No `grep`, `rg`, `ag`, `ack`, `find`, `fd`, `locate`. Use `search_grep` or `search_glob`.

3. **Using `shell` to edit files.** No `sed -i`, `awk`, `perl -pi -e`, `echo >>`, `tee`, heredoc append. Use `file_edit` or `patch`.

4. **Using `shell` to fetch web content.** No `curl`, `wget`, `httpie`. Use `webfetch`.

5. **Editing without reading first.** Never call `file_edit` on a file you haven't `file_read` in the current session. You will produce wrong diffs.

6. **Skipping edit coordination.** In multi-agent mode, never edit a file without first calling `edit_coordinator` with `register_edit`. Never leave locks dangling — always `release_edit` when done.

7. **Ignoring the task list.** If a task list exists (check with `todoread`), work from it. Do not go off-script without updating the list.

8. **Spawning raw subprocesses for agent work.** Do not fork agent processes manually via `shell`. Use `task`, `multi_task`, or `quorum_task` — they handle lifecycle, output capture, and result aggregation.

9. **Using built-in tools instead of kompile tools.** Do NOT use Claude Code's built-in `Read`, `Edit`, `Write`, `Grep`, or `Glob` tools. Always use `mcp__kompile__file_read`, `mcp__kompile__file_edit`, `mcp__kompile__file_write`, `mcp__kompile__search_grep`, `mcp__kompile__search_glob` instead. If they're not loaded, run `ToolSearch` first.

---

## ANTI-PATTERNS — WHAT NOT TO DO

**WRONG — using shell or built-in Read to read a file:**
```
shell: cat src/main/java/MyClass.java
Read: file_path=src/main/java/MyClass.java    ← WRONG (built-in, not kompile)
```
**RIGHT:**
```
mcp__kompile__file_read: file_path=src/main/java/MyClass.java
```

**WRONG — using shell or built-in Grep to search:**
```
shell: grep -rn "TODO" src/
Grep: pattern=TODO, path=src/    ← WRONG (built-in, not kompile)
```
**RIGHT:**
```
mcp__kompile__search_grep: pattern=TODO, path=src/
```

**WRONG — using shell or built-in Glob to find files:**
```
shell: find . -name "*.java" -type f
Glob: pattern=**/*.java    ← WRONG (built-in, not kompile)
```
**RIGHT:**
```
mcp__kompile__search_glob: pattern=**/*.java
```

**WRONG — using shell or built-in Edit to edit:**
```
shell: sed -i 's/oldMethod/newMethod/g' src/MyClass.java
Edit: file_path=src/MyClass.java, ...    ← WRONG (built-in, not kompile)
```
**RIGHT:**
```
mcp__kompile__file_edit: file_path=src/MyClass.java, old_string=oldMethod, new_string=newMethod, replace_all=true
```

**WRONG — using shell to fetch a URL:**
```
shell: curl -s https://example.com/api/docs
```
**RIGHT:**
```
mcp__kompile__webfetch: url=https://example.com/api/docs
```

**WRONG — editing without reading:**
```
mcp__kompile__file_edit: file_path=src/Config.java, old_string=port = 8080, new_string=port = 9090
// You never read Config.java — how do you know "port = 8080" is the exact text?
```
**RIGHT:**
```
mcp__kompile__file_read: file_path=src/Config.java
// Now you see the actual content, then:
mcp__kompile__file_edit: file_path=src/Config.java, old_string=<exact text from read output>, new_string=<replacement>
```

---

## COMPLIANCE SELF-CHECK

Before each tool call, verify:

- [ ] Did I load kompile tools via `ToolSearch` at session start?
- [ ] Am I using `mcp__kompile__*` tools, not built-in `Read`/`Edit`/`Write`/`Grep`/`Glob`?
- [ ] If I'm calling `shell`, is this truly a system command with no dedicated tool?
- [ ] If I'm calling `file_edit`, did I `file_read` this file first in this session?
- [ ] If multiple agents are active, did I check `edit_coordinator` before editing?
- [ ] If this is multi-step work, is there a task list I should be following?

If any check fails, stop and correct before proceeding.

---

## KOMPILE ARCHITECTURE — MANDATORY UNDERSTANDING

**READ THIS SECTION IN FULL. Violating these architectural rules causes broken builds, wrong backends, and wasted time.**

### Process Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│  kompile CLI (project init)                             │
│  - Generates app-main projects with chosen dependencies │
│  - Backend (CPU/CUDA) is a CLI config option at init    │
│  - PomModelBuilder writes the backend into the POM      │
└──────────────┬──────────────────────────────────────────┘
               │ generates
               ▼
┌──────────────────────────────┐    ┌──────────────────────────────────┐
│  kompile-app-main (GENERATED)│    │  kompile-model-staging           │
│  - Port 8080                 │    │  - Port 8090                     │
│  - RAG app with user's       │    │  - SEPARATE PROCESS              │
│    chosen modules            │    │  - ORCHESTRATOR                  │
│  - Backend determined by     │    │  - Depends on nd4j-native ONLY   │
│    project init config       │    │    (CPU-only, model conversion)  │
│  - Passes backend choice     │    │  - Starts subprocesses that use  │
│    to staging when needed    │    │    the backend app-main requests │
└──────────────────────────────┘    └──────────┬───────────────────────┘
                                               │ launches subprocesses
                                               ▼
                                    ┌──────────────────────────┐
                                    │  Training subprocess     │
                                    │  Conversion subprocess   │
                                    │  Other staged operations │
                                    │  - Backend passed by     │
                                    │    app-main's request    │
                                    └──────────────────────────┘
```

### RULE A1: MODEL STAGING IS AN ORCHESTRATOR

`kompile-model-staging` is a **separate process** (port 8090). It is NOT a library embedded in app-main. It is NOT a Spring bean in app-main's context. It **orchestrates work by launching subprocesses**.

- Staging's own dependency is `nd4j-native` (CPU only) because staging itself only does model conversion — it does NOT run inference or training directly.
- When staging needs to run a training job, serve a model, or do any GPU-accelerated work, it **starts a subprocess** with the appropriate backend on its classpath.
- The backend for these subprocesses is determined by what `app-main` tells staging to use — staging does NOT decide the backend itself.

**NEVER:**
- Add CUDA dependencies to `kompile-model-staging/pom.xml`
- Make staging run inference/training in-process
- Treat staging as a library that app-main calls directly via method invocation
- Assume staging knows or decides which backend to use

**ALWAYS:**
- Staging launches subprocesses for compute-intensive work
- The calling app-main project passes the desired backend (CPU/CUDA) to staging
- Staging's subprocess launcher puts the correct backend JARs on the subprocess classpath
- Staging itself stays lightweight with nd4j-native only

### RULE A2: APP-MAIN PROJECTS ARE GENERATED

`kompile-app-main` is NOT a fixed application. It is a **template** whose POM is **generated** by `PomModelBuilder` (or the deprecated `RagPomGenerator`) at `kompile build app` / `kompile build-rag-app` time.

- The backend (nd4j-native vs nd4j-cuda-12.9) is a **CLI option at project init** (`--backend`), written into the generated POM as a Maven property.
- The generated POM includes only the dependencies the user selected (embeddings, vector stores, loaders, tools).
- **Never suggest Maven property hacks** like `-Dnd4j.backend=...` as a workaround. The backend is baked into the project at generation time.

### RULE A3: BACKEND FLOWS FROM APP-MAIN TO STAGING TO SUBPROCESS

The backend selection chain:

1. **User runs** `kompile build app --backend nd4j-cuda-12.9` → POM generated with CUDA
2. **App-main starts** with CUDA on its classpath (from the generated POM)
3. **App-main calls staging** (REST API on port 8090) and tells it "use CUDA backend"
4. **Staging launches a subprocess** with the CUDA JARs on the subprocess classpath (via `SubprocessBackendResolver`)
5. **The subprocess** does the actual GPU work (training, inference, etc.)

Staging NEVER has CUDA in its own classpath. Staging NEVER runs GPU work in its own JVM. Staging is a lightweight orchestrator.

### RULE A4: SUBPROCESS BACKEND RESOLUTION

`SubprocessBackendResolver` handles runtime classpath injection:
- Removes `nd4j-native` (CPU) JARs from the classpath when CUDA is requested
- Adds `nd4j-cuda-12.9` + CUDA preset + bytedeco CUDA JARs from `~/.m2/repository/`
- Preserves `nd4j-native-api` (the shared bridge layer, always needed)
- Used by subprocess launchers like `ServingSubprocessLauncher`, `TrainingSubprocessLauncher`

### RULE A5: NO EXTERNAL MODEL SERVERS

Kompile serves its own models via SameDiff/ONNX. NEVER suggest Ollama, vLLM, or any external model server. Model staging IS the model server — it orchestrates model lifecycle (download, convert, stage, serve) through its subprocess architecture.

### RULE A6: NO SPRING PROPERTIES FOR CONFIGURATION

Spring `application.properties` / `application.yml` is **NOT** the configuration system. Kompile uses its own **JSON-based configuration** managed through:

1. **The UI** — Angular frontend with REST API controllers that read/write config
2. **The CLI** — `kompile config app --set section.key=value`
3. **JSON files** — stored in `~/.kompile/config/*.json`, managed by config services

**NEVER:**
- Add new `application.properties` entries for user-facing configuration
- Suggest `spring.xxx=yyy` or `-Dspring.xxx=yyy` as a way to configure behavior
- Put module configuration in Spring `@Value` annotations or `@ConfigurationProperties` bound to `application.properties`
- Use Spring profiles as a configuration mechanism

**ALWAYS:**
- Create a config service + JSON file + REST controller for new configurable behavior
- Expose configuration through the Angular UI and/or the CLI
- Use `~/.kompile/config/` JSON files as the source of truth
- `application.properties` is ONLY for structural plumbing that never changes at runtime (server.port, classpath-level enablement)

---

## MCP SERVER CONFIGURATION

The kompile MCP server must be configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "kompile": {
      "command": "kompile",
      "args": ["mcp-stdio", "--work-dir", "."]
    }
  }
}
```

All tools below are provided by this server. If a tool call fails with "unknown tool", verify the MCP server is running.

---

## TOOL REFERENCE

### File I/O

#### `file_read` — Read file contents (`mcp__kompile__file_read`)
Read the contents of a file with line numbers. Supports partial reads, compression, and structural views.

| Parameter   | Type    | Required | Description |
|-------------|---------|----------|-------------|
| `file_path` | string  | YES      | Absolute or relative file path |
| `offset`    | integer | no       | Starting line number (1-based) |
| `limit`     | integer | no       | Number of lines to read |
| `compress`  | boolean | no       | Strip license headers, collapse imports and blank lines (30–70% token savings) |
| `structure` | boolean | no       | Show only class/method/function signatures with bodies replaced by line counts (70–95% token savings) |

Use `structure=true` for initial exploration. Use `compress=true` for focused reading. Use bare reads for precise editing context.

#### `file_write` — Create or overwrite a file (`mcp__kompile__file_write`)
Create a new file or completely overwrite an existing file. Parent directories are created automatically.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `path`    | string | YES      | File path to create/overwrite |
| `content` | string | YES      | Complete file content |

Use ONLY for new files. For modifications to existing files, use `file_edit` or `patch`.

#### `file_edit` — Targeted string replacement (`mcp__kompile__file_edit`)
Find an exact string in a file and replace it. The `old_string` must be unique in the file — if not, include more surrounding context to disambiguate.

| Parameter     | Type    | Required | Description |
|---------------|---------|----------|-------------|
| `file_path`   | string  | YES      | File to modify |
| `old_string`  | string  | YES      | Exact text to find (must be unique) |
| `new_string`  | string  | YES      | Replacement text |
| `replace_all` | boolean | no       | Replace all occurrences (default: false) |

ALWAYS `file_read` the file first. Copy the exact text from the read output into `old_string`. Do not type it from memory.

#### `patch` — Apply unified diff (`mcp__kompile__patch`)
Apply a unified diff patch to a file. Use for multi-hunk changes.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `path`    | string | YES      | File to patch |
| `patch`   | string | YES      | Unified diff content |

---

### Search & Discovery

#### `search_grep` — Search file contents (`mcp__kompile__search_grep`)
Search for a regex pattern across files. Returns matches with file paths and line numbers.

| Parameter     | Type    | Required | Description |
|---------------|---------|----------|-------------|
| `pattern`     | string  | YES      | Regex pattern to search for |
| `path`        | string  | no       | Directory or file to search in |
| `glob`        | string  | no       | File pattern filter (e.g., `*.java`, `*.ts`) |
| `output_mode` | string  | no       | `content` (matching lines), `files` (paths only), `count` (match counts) |
| `compress`    | boolean | no       | Deduplicate identical matches |

#### `search_glob` — Find files by pattern (`mcp__kompile__search_glob`)
Find files matching a glob pattern. Results sorted by modification time (most recent first). Max 100 results.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `pattern` | string | YES      | Glob pattern (e.g., `**/*.java`, `src/**/*.ts`, `*.xml`) |
| `path`    | string | no       | Directory to search in |

#### `list` — List directory contents (`mcp__kompile__list`)
List a directory with file metadata (size, type, modification time).

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `path`    | string | YES      | Directory path |

#### `code_search` — Search code entities
Search an indexed codebase for classes, methods, functions, interfaces by name or signature.

| Parameter     | Type   | Required | Description |
|---------------|--------|----------|-------------|
| `action`      | string | YES      | `index` (build index), `search` (query), `stats` (statistics) |
| `query`       | string | no       | Search query (for `search`) |
| `path`        | string | no       | Directory to index (for `index`) |
| `entity_type` | string | no       | Filter: class, method, function, interface, etc. |

Index first with `action=index`, then query with `action=search`.

#### `code_graph` — Code knowledge graph
Build and navigate a graph of files, classes, methods, and their relationships (inheritance, imports, calls).

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `build`, `search`, `symbol`, `file`, `stats`, `connectivity`, `add_directory`, `remove_directory`, `list_directories` |
| `query`   | string | no       | Search query (for `search`) |
| `path`    | string | no       | Directory (for `build`) or file (for `file`) |
| `symbol`  | string | no       | Symbol name (for `symbol`) |

Build the graph first, then navigate with `search`, `symbol`, or `file`.

#### `local_code_index` — Local code indexer
Index and search a codebase locally with incremental updates. No server required. Supports Java, Python, Go, Rust, TypeScript, JavaScript, C/C++, and more.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `index`, `search`, `ranked_search`, `blended_search`, `signatures`, `impact`, `health`, `routing`, `spath`, `find`, `replace`, `usages`, `pagerank`, `clones`, `cochanges`, `unused_exports`, `stats`, `list` |
| `query`   | string | no       | Search query (for `search`/`ranked_search`/`blended_search`/`spath`), sub-action (for `pagerank`: `compute`, `top_files`, `file:path`), control (for `clones`/`cochanges`: `detect`/`analyze`/`recompute`) |
| `directory` | string | no     | Directory to index (for `index`) |
| `file_paths` | string | no    | Comma-separated file paths (for `impact`, `signatures`, `clones`, `cochanges`) |
| `max_results` | integer | no  | Max results to return (default: 20) |
| `top_k`   | integer | no      | Top-K for ranked search (default: 10), threshold for clones (default: 80 = 80%) |

**Analysis actions (zero file I/O — queries the index):**
- `pagerank` — Compute and query PageRank file importance scores. Shows which files are most central in the dependency graph.
- `clones` — MinHash-based clone detection. Finds near-duplicate functions (>80% structural similarity) and repeated code fragments.
- `cochanges` — Analyzes git history for files that frequently change together. Reveals implicit coupling not visible in imports.
- `unused_exports` — Dead code detection. Finds: dead files (all exports unused), dead barrels, test-only exports, internal-only exports.

---

### Execution

#### `shell` — Run shell commands (`mcp__kompile__shell`)
Execute a shell command. Default 120-second timeout.

| Parameter | Type    | Required | Description |
|-----------|---------|----------|-------------|
| `command` | string  | YES      | Shell command to execute |
| `timeout` | integer | no       | Timeout in milliseconds |

**RESTRICTED.** Only for system commands with no dedicated tool: compiling, testing, git, package managers, starting services. NEVER for reading files, searching, editing, or fetching URLs.

#### `process` — Background process management
Launch long-running commands in the background and monitor them.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `list`, `launch`, `kill`, `output`, `status`, `cleanup` |
| `command` | string | no       | Command to run (for `launch`) |
| `id`      | string | no       | Process ID (for `kill`, `output`, `status`) |

Use for builds, servers, and test suites that should not block the agent.

---

### Knowledge & RAG

#### `rag_search` — Semantic document search
Vector similarity search and keyword search over indexed documents.

| Parameter | Type    | Required | Description |
|-----------|---------|----------|-------------|
| `query`   | string  | YES      | Search query |
| `k`       | integer | no       | Number of results (default: 5) |

#### `graph_search` — Knowledge graph search
Search entities, relationships, and community summaries in the knowledge graph.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `query`   | string | YES      | Search query |
| `mode`    | string | no       | `local` (entity lookups) or `global` (thematic summaries) |

#### `memory` — Persistent cross-session memory
Three layers: flat files, typed memories (user/feedback/project/reference), and knowledge graph.

| Parameter    | Type   | Required | Description |
|--------------|--------|----------|-------------|
| `action`     | string | YES      | See categories below |
| `scope`      | string | no       | `project` (default) or `global` |

**Flat files:** `read`, `write`, `append`, `list`, `search` — raw markdown under `.kompile/memory/`.
**Typed memories:** `save`, `recall`, `forget`, `types` — structured memories with YAML frontmatter.
**Knowledge graph:** `create_entity`, `create_relation`, `add_observation`, `delete_entity`, `delete_relation`, `delete_observation`, `read_graph`, `search_nodes`, `open_nodes`.
**Diff tracking:** `diff`, `recent`, `diff_summary` — audit trail of memory mutations.

---

### Task Management

#### `todowrite` — Manage task list
Create or update the session task list. MANDATORY for multi-step work.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `set` (replace all), `add`, `update`, `delete` |
| `todos`   | array  | no       | Array of `{content, status, priority}` (for `set`) |

Status: `pending` → `in_progress` → `completed` (or `cancelled`). Only ONE task `in_progress` at a time.

#### `todoread` — Read task list
Returns all tasks with current status. Check this before starting or resuming work.

---

### Agent Orchestration

#### `task` — Spawn a subagent
Delegate a task to a single subagent. It runs independently and returns a summary.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `prompt`  | string | YES      | Full task description with all necessary context |
| `agent`   | string | no       | Agent type: `qwen` (default), `claude`, `codex`, `gemini`, `opencode` |
| `role`    | string | no       | Role to assign |

#### `multi_task` — Parallel subtasks (different prompts)
Split work into distinct subtasks, each with its own prompt and agent, running in parallel.

| Parameter     | Type    | Required | Description |
|---------------|---------|----------|-------------|
| `description` | string  | YES      | Short description of overall task |
| `subtasks`    | array   | YES      | Array of `{name, prompt, agent?, agents?, role?}` (min 2) |
| `agent_count` | integer | no       | Default instances per subtask (1–5) |

#### `quorum_task` — Parallel consensus (same prompt)
Send the same prompt to multiple agents and collect independent responses for comparison.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `prompt`  | string | YES      | Prompt sent identically to all agents |
| `agents`  | array  | YES      | Agent types to query (min 2) |
| `role`    | string | no       | Role for all agents |

#### `edit_coordinator` — Multi-agent coordination
MANDATORY in multi-agent scenarios. Coordinate file locks, process tracking, and agent activity.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `register_edit`, `release_edit`, `query_edits`, `query_processes`, `register_agent`, `query_agents`, `publish_process`, `unpublish_process`, `status` |

Workflow: `register_agent` → `query_edits` → `register_edit` → (do work) → `release_edit`.

#### `role_manager` — Agent persona management
Create, assign, and manage agent roles (personas with system prompts).

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `list_roles`, `get_role`, `create_role`, `update_role`, `delete_role`, `assign_role`, `get_agent_role` |

#### `skill_manager` — Slash command management
Create and manage reusable prompt templates invoked as `/skillname [args]`.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `list_skills`, `get_skill`, `create_skill`, `update_skill`, `delete_skill`, `generate_markdown`, `expand_template` |

---

### Session & History

#### `transcript_search` — Search conversation history
Search across saved conversation transcripts from all agents.

| Parameter    | Type   | Required | Description |
|--------------|--------|----------|-------------|
| `action`     | string | YES      | `list`, `read`, `recent`, `search` |
| `pattern`    | string | no       | Search pattern (for `search`) |
| `session_id` | string | no       | Session to read (for `read`) |

#### `conversation_import` — Import external conversations
Import conversations from Claude Code, OpenCode, Codex, Qwen, Gemini into kompile format.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `discover`, `list`, `import`, `import-all` |
| `source`  | string | no       | Source tool name |

#### `resume` — Resume prior sessions
Browse, search, and resume prior conversations.

#### `tool_call_catalog` — Tool usage analytics
Search and analyze tool calls across all agent sessions.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `search`, `list`, `stats`, `index`, `filters` |
| `query`   | string | no       | Search query (for `search`) |

---

### Web

#### `webfetch` — Fetch URL content
Fetch a URL and return content as text. Supports HTML, JSON, plain text. 5MB max, 30s timeout.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `url`     | string | YES      | URL to fetch |

#### `websearch` — Web search
Search the web. Set `BRAVE_API_KEY` for higher quality results.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `query`   | string | YES      | Search query |

---

### DevOps & Quality

#### `config_archive` — Configuration management
Export, import, and manage kompile configuration archives.

| Parameter    | Type   | Required | Description |
|--------------|--------|----------|-------------|
| `action`     | string | YES      | `export`, `import`, `list`, `preview`, `delete` |
| `components` | array  | no       | Filter config categories |

#### `test_milestone` — Test tracking
Track which git commits have passing tests. Record milestones to find last known-good commits.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `record`, `fail`, `list`, `get`, `check`, `compare`, `latest`, `delete`, `summary`, `init`, `config`, `add_module`, `remove_module`, `set_target`, `status`, `add_regression`, `remove_regression`, `list_regressions` |

#### `performance_harness` — Agent quality evaluation
Multi-signal quality evaluation, escape detection, and model performance tracking.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `action`  | string | YES      | `report`, `recommend`, `record`, `config`, `stats`, `reset` |

---

## ORCHESTRATION PATTERNS

### Single-Agent File Edit (MANDATORY SEQUENCE)
```
1. file_read (structure=true)  → understand file layout
2. file_read (target section)  → get exact text you will modify
3. file_edit (old → new)       → make the change
4. shell (compile/test)        → verify the change works
```
Skipping step 1 or 2 is a violation of Rule 3.

### Multi-Agent Coordination (MANDATORY SEQUENCE)
```
1. edit_coordinator (register_agent)  → announce what you're working on
2. edit_coordinator (query_edits)     → check no one else is editing your target
3. edit_coordinator (register_edit)   → lock the file
4. file_read → file_edit/file_write   → make changes
5. edit_coordinator (release_edit)    → release the lock
```
Skipping any step risks data loss.

### Research → Implementation
```
1. search_grep / search_glob / code_search  → find relevant code
2. code_graph (build + search)              → understand relationships
3. file_read (key files)                    → get implementation details
4. todowrite (set plan)                     → break into tracked steps
5. For each step:
   a. todowrite (mark in_progress)
   b. file_read → file_edit/file_write
   c. shell (test)
   d. todowrite (mark completed)
```

### Parallel Subtask Decomposition
```
multi_task:
  subtask "backend":  {prompt: "...", agent: "claude"}
  subtask "tests":    {prompt: "...", agent: "qwen"}
  subtask "docs":     {prompt: "...", agent: "gemini"}
→ All run concurrently. Each agent MUST follow these same rules.
```

### Consensus Review
```
quorum_task:
  prompt: "Review this code for [X]..."
  agents: [claude, gemini, qwen]
→ Compare independent reviews, identify agreement/disagreement.
```

### Research While Implementing
```
1. task (agent: qwen, role: researcher) → "Find all usages of <X>. Report file paths, call patterns, error handling."
2. While research runs, continue your own implementation work
3. Read the task result from .kompile/task-results/ when ready
4. Integrate research findings into your approach
```

### Delegation Decision Heuristic

Before starting a multi-step task, ask yourself:

1. **Can parts run in parallel?** → Use `multi_task` to split them
2. **Do I need research before implementation?** → Delegate research via `task` while you plan
3. **Will this fill my context window with file reads?** → Delegate the exploration to a subagent
4. **Do I want validation of my approach?** → Use `quorum_task` for independent review
5. **Am I about to write tests for code I just wrote?** → Delegate test writing via `task`

If any answer is YES, delegate. The subagent cost is a process spawn — far cheaper than re-reading files you've already seen or running out of context.

---

## FINAL REMINDER

These rules are not guidelines. They are requirements. Every tool call you make should pass the compliance self-check above. If you are uncertain whether a kompile tool exists for your operation, check this document before falling back to `shell`. The kompile tools exist for a reason: they provide coordination, persistence, and observability that raw shell commands and built-in tools cannot. Use them.

**Session startup checklist:**
1. Load core tools: `ToolSearch query="select:mcp__kompile__file_read,mcp__kompile__file_edit,mcp__kompile__file_write,mcp__kompile__search_grep,mcp__kompile__search_glob,mcp__kompile__shell,mcp__kompile__explore"`
2. Load orchestration tools if needed: `ToolSearch query="select:mcp__kompile__task,mcp__kompile__multi_task,mcp__kompile__todowrite,mcp__kompile__todoread"`
3. Begin work using `mcp__kompile__*` tools exclusively
