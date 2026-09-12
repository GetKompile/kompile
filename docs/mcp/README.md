# MCP Integration

Kompile exposes its full tool set to any MCP-compatible agent (Claude Code, Codex, Gemini Code Assist, Qwen, OpenCode) via the Model Context Protocol. Two transport modes are supported.

## Transport modes

### Stdio mode

The agent launches kompile as a subprocess:

```bash
kompile mcp-stdio --profile=full --work-dir /path/to/project
```

With no `--url`, the stdio process is also the crawl/index worker: `crawl_documents`,
`crawl_source`, `crawl_discover`, `crawl_control`, `knowledge_search`, and `knowledge_status` use a
project-local knowledge base under `data/crawls`. The same session exposes `memory` and
`semantic_memory`. Add `--url` only to route those contracts to a distributed Kompile instance.

#### Offline crawl pipelines

Call `crawl_discover` with `section: "pipelines"` before composing a crawl. Its response is generated
from the local registry and lists pipeline templates, loaders, chunkers, supported file types,
model bindings, and unified runtime capabilities. The project-local engine accepts every managed pipeline kind: `STANDARD_TEXT`, `CODE`, `TABLE_AWARE`,
`KEYWORD_ONLY`, `OCR`, `VLM`, and `CUSTOM`.

A `crawl_documents` request can select a pipeline per document, set a default, or route files by
extension or content type:

```json
{
  "documents": [
    { "path": "docs/guide.md", "pipelineId": "text" },
    { "path": "scans/report.pdf", "pipelineId": "vision" }
  ],
  "pipelines": [
    {
      "pipelineId": "text",
      "pipelineType": "STANDARD_TEXT",
      "loaderName": "markdown",
      "chunkerName": "recursive-character"
    },
    {
      "pipelineId": "vision",
      "pipelineType": "VLM",
      "modelBindings": {
        "default": "my-document-model"
      },
      "options": {
        "outputFormat": "MARKDOWN",
        "maxNewTokens": 2048,
        "pdfRenderDpi": 150
      }
    }
  ],
  "knowledgeBase": { "name": "project-docs" }
}
```

Text and local parsing steps run in crawl orchestration. Every model-backed step—including OCR
and VLM PDF extraction—uses a versioned `UnifiedPipelineDefinition` and the same pooled
`pipeline-serving` stdio runtime. The MCP host resolves models, starts compatible runtime children
on demand, reuses them across documents and calls, and releases them through bounded leases.
Agents build and maintain definitions with the `pipeline` MCP tool; executable paths, ports, and
subprocess modes are never part of the caller contract. The `VLM_DOCUMENT` step accepts PDF files
(including scanned and image-heavy PDFs), not standalone raster-image paths.

#### Managed connector sources: download then process

`crawl_documents` accepts managed connector `sourceType` values that run in-process with explicit
request credentials — no app server required. These split into two families:

- **File-backed — GDRIVE and ONEDRIVE.** These download ORIGINAL FILES and then process each one
  through its content-type pipeline, exactly like local files: PDFs get text extraction (or the
  VLM OCR pipeline via `pipelineId`), spreadsheets get the excel loader, and so on. Never assume
  connector payloads are text; a scanned PDF from Drive is rendered and OCR-processed, not
  byte-mangled.
- **Text connectors — GMAIL, GDOCS, GOOGLE_WORKSPACE, SLACK, SLACK_HISTORY, DISCORD,
  DISCORD_HISTORY, NOTION, REDDIT, JIRA, CONFLUENCE, EMAIL, IMAP, POP3.** Payloads are text and
  materialize directly; each accepts provider-native query properties (for example GMAIL
  `gmailQuery: "in:inbox newer_than:7d"`, DISCORD `guildId`/`channelIds`, SLACK
  `loadAllChannels`, REDDIT `subreddit` via path plus `sortType`/`timePeriod`).

File-backed identification: set `properties.fileIds` / `properties.itemIds` (comma-separated or
array), OR a single `properties.folderId` to load a folder's immediate file children (cap with
`properties.maxFiles`, default 500; recursive traversal of deep trees belongs to the crawler
source types). Credentials come from `properties.accessToken` or the connected OAuth account.
Downloaded originals land under `data/knowledge-sources/<knowledgeBase>/external/` and are
registered as per-file sources, so `pipelineId`, `chunkerName`, and other pipeline options on the
document entry apply to them like any local file.

Scanned-PDF example (download from Drive, then VLM OCR each file):

```json
{
  "documents": [
    {
      "sourceType": "GDRIVE",
      "properties": { "accessToken": "ya29...", "folderId": "<folder-id>", "maxFiles": 100 },
      "pipelineId": "vlm-ocr-pdf"
    }
  ],
  "pipelines": [
    { "pipelineId": "vlm-ocr-pdf", "pipelineType": "VLM",
      "modelBindings": { "default": "my-document-model" } }
  ],
  "knowledgeBase": { "name": "drive-scans" }
}
```

Run `crawl_discover` with `section: "sources"` for the live connector list with per-type guidance;
file-backed types are flagged in their descriptions.

Use `modelBindings.default` as the authoritative model selector. The compatibility shorthands are
resolved in this order: `modelId`, deprecated `vlmModel`, then `modelSetId`; conflicting
`modelId` and `vlmModel` values are rejected. `model_runtime status` distinguishes
`artifactReady` from `runtimeStatus`, because locating a model artifact does not prove that native
initialization succeeds.

Use the `vlm_model_definition` MCP tool to `create`, `update`, `validate`, `list`, or `delete` provider-neutral model entries. Definitions expose every model/component field (provider, repository or local path, revision, format, model type, component files/URLs, checksum, shapes, pipeline stage, runtime options, and metadata) plus free-form provider-specific keys. `pipelineConfig`, `runtime`, and `metadata` are preserved unchanged; omitted runtime values use sane defaults.  Reference a saved entry by id from `modelBindings`/ `modelSetId`, or supply an inline `modelDefinitions` override.

Model acquisition is a separate MCP operation: call `model_runtime` with `action: "bootstrap"` for configured remote download/acquisition, `action: "import"` to force provisioning from `localPath` or remote fields, and `action: "status"` to inspect `modelId`, `modelPath`, `tokenizerPath`, `artifactReady`, and `runtimeStatus`. For local format conversion, call `model_runtime` with `action: "convert"`, `localPath` as the input file, `outputPath` ending in `.sdz`, and optional `format`; this invokes the standalone `kompile-model convert` command from stdio MCP and supports ONNX, TensorFlow/Keras, GGUF/GGML, and SafeTensors. The model CLI resolves the configured native staging worker in native-image distributions and the packaged executable staging JAR in the JAR distribution; explicit executable/JAR overrides remain available. Use the returned model ids in `pipeline` `modelBindings`; for a composed vision graph, bind its `visionEncoder`, `textEmbedding`, and `decoder` roles from the `VISION_MULTIMODEL` template returned by `pipeline action: "capabilities"`. Existing local directories can instead be supplied through inline `modelDefinitions.<id>.localPath`, which remains local-only and does not stage or mutate the project registry.

After conversion, optimization is an explicit composable action rather than an implicit provider behavior:

```json
{"action":"convert","localPath":"models/encoder.onnx","outputPath":"models/encoder.sdz","format":"onnx"}
{"action":"optimize","localPath":"models/encoder.sdz","outputPath":"models/encoder.optimized.sdz","profile":"TRANSFORMER","selectedPasses":["dead_code_elimination","algebraic"],"maxIterations":3,"force":false,"createBackup":true,"dryRun":false}
```

`optimize` accepts either `localPath` or `modelId`. `selectedPasses` overrides the sane `BASIC` profile; `maxIterations`, `quantizationType`, `force`, `createBackup`, `dryRun`, `modelExecutable`/`modelJar`, `stagingExecutable`/`stagingJar`, `javaExecutable`, `timeoutMinutes`, and request-scoped `environment` are all configurable. Omitting `outputPath` updates the local artifact in place (with a backup by default). Local optimization bypasses registry mutation; catalog optimization updates the selected model. The action invokes the same staging `GraphOptimizer` for native-image and executable-JAR distributions, so ONNX -> SDZ -> optimized SDZ is reproducible through stdio MCP.

#### Wiring composed models

The `pipeline` tool's `capabilities` response is the executable authoring contract. Use `composition.topologies` and `stepCatalog` to choose steps, copy `composition.defaultTemplates` or assemble a custom `pipelineSpec`, bind each model role, and call `validate` before `test` and `run`.

For a `SequencePipeline`, `steps` execute in order and each step receives the previous `Data` record. For a `GraphPipeline`, each node names upstream nodes in `inputs`; `pipeline_input` is the external request, and `outputNodeName` selects the result. Standard nodes pass through one predecessor and merge multiple predecessors in declaration order. When a step needs named slots instead of the whole merged record, set `stepConfig.parameters.inputDataBindings` (or `parameters.inputDataBindings` for sequence steps). Each value is `node.outputKey`, `node` for a whole record, or `pipeline_input[.key]`.

The default multimodal template explicitly connects `vision_encoder.image_features`, `text_embedding.text_embeddings`, and token tensors from `pipeline_input` into fusion, then carries the fused embeddings and decoder control tensors through the autoregressive loop. Raw `text` is not tokenized implicitly; add a tokenizer/adapter step or provide `input_ids`, with optional `attention_mask` and `position_ids`. This contract is provider-neutral and also applies to custom OCR, embedding, LLM, and postprocessing chains.

An explicit `crawl_documents` request with `dryRun: true` is isolated from prior crawl sources.
The returned `metadata.preview` contains the normalized documents, effective request, resolved
pipeline/model inputs, warnings, intended paths, and `persistentWrites: false`. Likewise,
`pipeline test` does not register or refresh project models; provision them first with
`model_runtime`. Runtime failures include structured stage, cause-chain, and stack diagnostics.

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

## Custom MCP servers

Install third-party MCP servers for standard chat, headless chat, and managed passthrough with the shared CLI lifecycle:

```bash
kompile mcp add context7 -- npx -y @upstash/context7-mcp
kompile mcp add --scope user --transport http docs https://mcp.example.com/mcp
kompile mcp list
kompile mcp disable context7
kompile mcp remove context7
```

Project entries live in `.mcp.json`; user entries live in
`~/.kompile/config/mcp-servers.json`, and project names override user names.
Custom tools are namespaced as `mcp__server__tool` and are also available through
the stable `mcp_tool_search` / `mcp_tool_call` gateway used by passthrough agents.
See **[Custom MCP servers](custom-servers.md)** for transports, auth references,
trust behavior, tool filtering, examples, and the open-source harness comparison.

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
| Knowledge | `knowledge_search`, `knowledge_status`, `rag_search`, `graph_rag_search`, `semantic_memory`, `memory`, `transcript_search` |
| Crawl | `crawl_discover`, `crawl_documents`, `crawl_source`, `crawl_control`, `crawl_result` |
| Subprocess watchdog | `subprocess_watchdog` (local crawl admission plus tracking/limits for pooled children) |
| Code | `code_search`, `code_graph`, `local_code_index`, `tool_call_catalog` |
| Edit history | `diff_index` (search, filter, and sort old/new text and unified diffs) |
| Delegation | `task` (single subagent), `multi_task` (parallel), `quorum_task` (consensus voting) |
| Coordination | `edit_coordinator`, `file_activity` (file watcher for multi-agent) |
| Config | `project_config`, `enforcer_config`, `role_manager`, `skill_manager`, `config_archive` |

Any tool can run asynchronously with `_background: true` -- returns a task ID immediately, use `poll` to check status later.

### Pause a resource-blocked agent

Resource admission is **opt-in**. With the default policy nothing is classified high, so
launches never block, reserve, or install watches. Once a policy marks specific commands
`high` (user or project `resource-policy.json`), affected tool launches install a one-shot
resource watch when blocked. The response includes `resourceWaitId` and `wakeSupported`.
Nothing is launched or reserved while the agent is waiting. The host checks every five
seconds for the user-wide activity lane, project peer processes, and configured RAM/GPU
admission to clear.

- **Interactive Standard Chat:** with `wakeSupported=true`, the agent may end its current turn.
  A system event starts the next agent turn (or queues behind an active turn), even with ordinary
  auto-dequeue disabled. Open todos and outstanding validation stay pending, not completed.
- **External MCP / headless clients:** `wakeSupported=false` means the server cannot start a new
  model turn on its own. Keep a normal tool call pending; its response resumes the caller:

  ```json
  {"action":"wait_for_activity","wait_id":"<resourceWaitId>","timeout_seconds":25}
  ```

  On `WAITING`, repeat **the wait**, not the blocked launch. Choose a timeout below the client's
  tool timeout (1–300 seconds; default 25). Do not detach this call with `_background:true`.
  On `READY`, retry the original tool through normal admission; capacity is advisory, not reserved.

Use `edit_coordinator` `watch_activity` with `activity_kind` and `description` to watch without
attempting a launch. `preflight_activity` remains a dry inspection. `query_activity_waits` lists
only the current tool session's watches; `cancel_activity_wait` with `wait_id` abandons one.
Cancelling a held call cancels its watch. Identical pending requests share a watch; successful
admission clears its matching watch. Watches survive idle turns but not host shutdown, expire
after 24 hours, and are bounded to 128 entries per host. Notifications never auto-execute commands.
Existing hosts must be rebuilt/restarted before these actions are available.

### Crawl jobs are independently pollable

`crawl_documents` and `crawl_source` are asynchronous by default for long-running local and managed crawls.
The response contains a `crawlResult` handle with `jobId`, `status`, `terminal`, `pollAfterMs`, and
`nextActions`. Use the returned id with `crawl_control`:

```json
{"operation":"status","jobId":"local-..."}
```

Poll after the server-provided `pollAfterMs` (the local default is 1000 ms) until `terminal` is true;
then call `crawl_result` with the same `jobId`. Do not submit the same crawl repeatedly while it is
`QUEUED` or `RUNNING`. `crawl_control` `cancel` is available for local jobs; completed handles are
retained for a bounded period. Set `async:false` (or `waitForCompletion:true`) only for explicit
blocking compatibility. `dryRun:true` remains synchronous because it never persists artifacts.

### Local crawl subprocess watchdog

Project-local crawls run model-backed work in pooled children (model-serving, pipeline runtime,
learning). Three watchdog layers protect them:

- **Pre-admission** — before a non-dry-run `crawl_documents` worker starts expensive work, the MCP
  host samples Linux `MemAvailable` (with an OS MXBean fallback) and NVIDIA VRAM. The default
  `wait` mode keeps the async job in `WAITING_FOR_CAPACITY` until pressure clears or the bounded
  timeout expires; `fail` rejects immediately and `off` disables this gate. GPU admission is
  device-agnostic: it admits when any device has the configured fractional and optional absolute
  headroom, preserving lower-level device failover rather than pinning a card.

- **Child-internal** — each serving/learning child runs its own heap/GPU/off-heap
  `SubprocessMemoryWatchdog` from its args thresholds (kill exits the child; the crawl job fails
  with the child's diagnostics). `GET /api/llm/status` on a serving child embeds the live
  `memoryWatchdog` snapshot.
- **Parent-side** — the MCP host tracks every pooled child PID, samples RSS, and force-kills
  children exceeding `min(maxRssMb, maxRssFraction × system RAM)` for `breachCount` consecutive
  checks (a `graceSeconds` window exempts model-load spikes). Enforcement is off until a limit is
  set; tracking is always on.

Manage it through `subprocess_watchdog`:

```json
{"action":"status"}
{"action":"config_update","config":{"maxRssFraction":0.5,"breachCount":2}}
{"action":"config_update","config":{"admissionMode":"wait","admissionTimeoutMs":300000,"admissionMaxRamUsedFraction":0.75,"admissionMinAvailableRamMb":8192,"admissionMaxGpuUsedFraction":0.75,"admissionMinAvailableGpuMb":0}}
{"action":"kill","id":"serving-<runId>","reason":"stuck model"}
```

Admission configuration keys are `admissionMode` (`wait|fail|off`), `admissionTimeoutMs`,
`admissionPollIntervalMs`, `admissionMinAvailableRamMb`, `admissionMaxRamUsedFraction`,
`admissionMinAvailableGpuMb`, and `admissionMaxGpuUsedFraction`. A zero threshold disables that
individual check. The default fractional RAM/GPU limits are 0.75, preserving 25% dynamic headroom
before Kompile's 85% high-pressure/OOM band; absolute floors default to zero. Waiting crawls re-read the
mode, timeout, poll interval, and thresholds, so a runtime config update takes effect without a restart.
If `nvidia-smi` is unavailable, the default fractional GPU check is fail-open; an explicitly
configured nonzero absolute GPU floor fails closed because that guarantee cannot be verified.

`crawl_discover` / `crawl_control` `runtime_config` embed the current watchdog state under
`subprocessWatchdog`. On non-Linux hosts per-child RSS sampling is unavailable; host RAM admission
uses the OS MXBean when available.

The discovery response from `crawl_discover`/`crawl_control preflight` also advertises this contract in
`asyncLifecycle`, including terminal statuses, the poll interval, and the status/result tool names, so
an agent can assemble the loop without relying on prose.

## Agent delegation defaults

The `task`, `multi_task`, and `quorum_task` tools can launch `codex`, `claude`, or `opencode`. Each accepts explicit `model` and `thinking` overrides; `multi_task` also accepts them per subtask.

Configure project defaults from either the main CLI or the standalone agent CLI:

```bash
kompile configure agent-defaults --agent codex --model gpt-5.6-terra --thinking medium
kompile agent defaults --agent codex --model gpt-5.6-terra --thinking medium
kompile configure agent-defaults --agent claude --model claude-opus-4-1 --thinking high
kompile configure agent-defaults --agent opencode --model openai/gpt-5 --thinking high
```

Add `--global` to write user defaults instead of `.kompile/agent-defaults.json`. Use `--thinking-model MODEL` to set thinking for a model other than the model being selected, or omit both model options to set an agent-wide thinking fallback. The persisted shape is:

```json
{
  "agents": {
    "codex": {
      "model": "gpt-5.6-terra",
      "thinking": {
        "default": "medium",
        "models": {
          "gpt-5.6-sol": "max"
        }
      }
    }
  }
}
```

Roles can override these defaults per provider. Both `create_role` and `update_role` on `role_manager` accept:

```json
{
  "agent_defaults": {
    "codex": {
      "model": "gpt-5.6-terra",
      "thinking": {
        "default": "medium",
        "models": {
          "gpt-5.6-sol": "max"
        }
      }
    },
    "claude": {
      "model": "claude-sonnet",
      "thinking": { "default": "high" }
    },
    "opencode": {
      "model": "openai/gpt-5.6",
      "thinking": { "default": "medium" }
    }
  }
}
```

The equivalent flat role frontmatter is `agent_defaults.codex.model: gpt-5.6-terra`, `agent_defaults.codex.thinking.default: medium`, and `agent_defaults.codex.thinking.models.gpt-5.6-sol: max`. The older top-level role `model:` field remains a prompt hint and is not a launch default.

Model and thinking resolve independently: explicit task/subtask value, selected role default, nearest project default, user default, then the native agent default. An explicit model can therefore select that model's thinking value from the role. If a task omits `role`, Kompile uses the persisted role assignment for that agent; an explicit role wins over the assignment. Kompile maps thinking to Codex `model_reasoning_effort`, Claude `--effort`, and OpenCode `run --variant`. Direct `kompile chat` and `kompile passthrough` launches also accept `--model` and `--thinking` (alias `--effort`); OpenCode's interactive TUI has no variant flag, so its thinking selection is applied to managed delegation runs.

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
