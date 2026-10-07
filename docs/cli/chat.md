# Chat

Kompile supports first-party local model chat, external/direct model chat, an installed chat subprocess, server-connected RAG, and agent passthrough.

## Web chat

Run `kompile chat --web` (add `--open-browser` to open it locally). The installed
CHAT service listens on all interfaces by default, including for published distributions.
The CLI prints the selected port and a local URL; from another machine use
`http://<server-hostname-or-LAN-IP>:<port>/`. Both `/` and `/chat` open the UI;
`/chat` and `/chat/` redirect to the browser's `/#/chat` route automatically.

### Folder-based chat manager

`kompile chat --web` opens the chat manager at `/#/chat`. The launch folder is
registered automatically. Chats are grouped by their canonical local folder, not
by a distributed server project. Open several chats and switch tabs without
stopping runs in the other panes; stop a running chat before closing its tab.

- **Add existing folder** registers an absolute directory on the Kompile host and
  shows its native CLI conversations without importing copies.
- **New project** creates a fresh folder under an existing absolute parent, registers
  it, and opens its first chat. Existing files/folders are never overwritten. This
  is a lightweight chat folder, not a full scaffold or Git initialization; features
  such as local crawls initialize their folder configuration when needed. If folder
  creation succeeds but registry persistence fails, the error includes the created
  path; fix the registry error and use **Add existing folder** to recover without deleting files.
- **New chat** offers **Folder default**, **Kompile standard**, or an installed native
  framework (Claude, Codex, Gemini, Qwen, OpenCode, Pi). An optional model belongs to
  that chat: for example OpenCode with `zai/glm-5`, alongside Claude and OpenAI chats
  in the same folder. Framework/vendor login stays in the framework's own configuration;
  Kompile standard keeps its existing cross-vendor model picker.
- Each chat uses its own folder's CLI configuration (or global defaults); no
  credentials are copied from the launch folder into new projects. Configure missing
  provider defaults through Session Configuration or `kompile chat --setup` in that folder.
- Native framework selection is pinned per chat. Resume uses its recorded native
  session ID and folder, never the vendor's latest conversation. Use another **New chat**
  to change frameworks. **Session Configuration → Model** accepts the framework's
  own model ID; `/model <native-model-id>` does the same and changes only this chat.

The setup wizard offers **Browser** for supported **managed passthrough** as well as
standard chat. Native web passthrough streams text, thinking, tools and usage through
Kompile's normal transport and supports process-tree cancellation. It is one bounded
native turn per request: native interactive approvals/questions, live input/background
controls, Kompile roles/workflow controls and attachments require the terminal or standard
harness. Permission bypass is opt-in; it is not silently enabled for browser chats.

The non-secret folder/chat registry lives in `~/.kompile/chat-workspace.json`;
conversation history stays in the native CLI transcripts. `--workspace` and the
old `/#/workspace` URL remain compatible aliases. Use `kompile chat --web --single-chat`
for the original single-chat view. Hosted launches without a CLI workspace still
use ordinary web chat and cannot create/register host folders.

Operators control network access and security. To opt into local-only access, use
`KOMPILE_CHAT_ADDRESS=127.0.0.1 kompile chat --web` (Spring's `SERVER_ADDRESS`
is also honored). The CLI no longer overrides the service's bind configuration.

## Setup wizard and standard chat

Bare chat preserves the normal interactive setup flow:

```bash
kompile chat
```

The wizard offers Standard Chat, Passthrough, Resume Previous Conversation, and Resume All. Resume Previous Conversation opens the session browser; Resume All launches every resumable session that was active within the last 30 minutes in new terminal windows. Standard Chat then asks which runtime to use:

1. **Kompile local model** — starts the distribution's first-party low-overhead serving subprocess against a local `.gguf` or `.sdz` model. It does not start or connect to a full Kompile instance.
2. **External local endpoint** — connects to Ollama or another OpenAI-compatible endpoint without a Kompile instance.
3. **Direct model provider** — calls a cloud model API directly without a Kompile instance.
4. **Kompile instance** — connects to a configured instance or starts the installed `kompile-chat` service for a local URL.

The first option is the native Kompile path and uses the same low-overhead serving component as the higher-level application: `ServingSubprocessMain`. The CLI writes the standard `ServingSubprocessArgs` JSON and launches one of the installed forms:

```text
<kompile-app-native> --subprocess=serving <args.json>
<java> -jar <kompile-app-main-fat.jar> --subprocess=serving <args.json>
```

The child preloads the selected model and exposes Kompile's bounded LLM API only on a private loopback port. Standard Chat waits for `/api/llm/status`, uses `/api/llm/chat` for canonical structured messages, tool definitions, and tool calls, and stops the owned child when the REPL exits. This is not the full Kompile application instance.

The CLI prefers the installed app-native executable and falls back to the installed `kompile-app-main` fat JAR plus Java. Set `KOMPILE_CHAT_SERVING_EXECUTABLE` or `KOMPILE_CHAT_SERVING_JAR` to override runtime discovery. Set `KOMPILE_CHAT_MODEL_PATH` or `KOMPILE_CHAT_TOKENIZER_PATH` to override model discovery. Supported cached models are discovered under `~/.kompile/models/chat` and the DL4J model cache; matching first-party tokenizer assets are resolved from `~/.kompile/models/tokenizers` and passed explicitly to the serving subprocess.

The external-local option is deliberately separate. Choose Ollama to accept its default URL, or choose OpenAI-compatible to enter another endpoint such as one started by `kompile run ... --serve`.

The direct-provider path lists each vendor once. After selecting a vendor, the wizard offers its available authentication and billing routes; vendors with a single route proceed directly. Internal provider routes such as OpenAI's ChatGPT OAuth endpoint are not shown as duplicate vendors.

For Z.AI, choose **API key (subscription)** for `https://api.z.ai/api/coding/paas/v4`, or **API key (credits)** for `https://api.z.ai/api/paas/v4`. Both choices reuse the same Z.AI API key. The selected endpoint is saved with the chat configuration and used for model discovery and chat requests. Use `/auth` to switch back to subscription usage when desired. Existing configurations without an explicit endpoint continue using the subscription endpoint. See [Z.AI's API endpoint documentation](https://docs.z.ai/api-reference/introduction).

Inside an active external/direct standard-chat session, run `/auth` (or its alias `/provider`) to switch vendor and authentication. The picker walks the whole flow in order: the vendor, its authentication route when it has more than one, which stored credential to use or **Sign in / add another credential**, a base URL for a custom endpoint, then the model. Enter accepts the default, marked `*` on the vendor, route and model pages and `[default]` on a credential page, where it is the credential already in use. `back` returns to the previous page; on a credential page `q` or Esc does the same, and elsewhere Esc closes the picker. The session changes only when a model is chosen, although a credential added along the way stays saved for reuse. In session scope the chosen credential is pinned to this session; in global scope (`/auth global`) choosing a stored credential makes it the vendor's global default at once. Anthropic's Claude Code route uses your Claude Code login, so it has no credential page; a vendor whose own CLI owns the login, such as OpenCode, offers that CLI's existing login or a new sign-in through it. `/model` keeps the current vendor and credential; type `credentials` or `provider` there to change them. Use `/thinking` for thinking effort, or `/setup` to reconfigure the runtime. The switch applies to subsequent messages without changing the session ID or transcript; provider-specific wire envelopes are rebuilt from the same canonical text conversation so the new provider retains context. Selecting the first-party local subprocess or a Kompile instance is saved for the next session because either changes the underlying runtime.

For direct providers and models that expose reasoning effort, the wizard also offers a Thinking Effort choice after model selection. Choose the provider/model default or an explicit value from `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, and `max`; the choice is saved with the standard-chat configuration and sent on direct model requests. Not every reasoning model supports every explicit value.

At the standard chat prompt, Ctrl-C exits through the normal session cleanup path. Use the configured cancel key (Escape by default) to cancel an in-progress main-model operation. Main-model thinking is not advertised as backgroundable. While a backgroundable tool invocation is running — `task` delegations and `bash` commands — Ctrl+B detaches it to the activity pane so the main prompt can continue; press Down to select the row, Enter to open it, then type a follow-up, or press Delete to stop it. The pane suppresses the duplicate parent-turn task row while the active or retained child row is available, so opening the visible backgrounded row cannot silently target Main chat instead. Escape leaves active work running rather than acting as a second kill key.

While a `task` delegation runs, the main transcript shows a collapsed subagent block rather than a running transcript: a header line with the child id/type and elapsed time, plus only the last few one-line steps — `⟳` marks the command currently in flight, `⎿` its finished outcome — and a hidden-step count. Raw tool output and the child's streamed text never repaint the main window; they stay in the retained transcript, opened with Down then Enter.

With Claude Code in standard `kompile chat`, Ctrl+B also backgrounds its native `Agent` (legacy `Task`) and `Bash` calls. Kompile sends Claude's native background control, keeping the agent and session alive while the parent resumes and queued or fresh messages proceed. These tasks retain their process-panel rows; they are not detached copies of the parent conversation. If Claude rejects the request (for example, background tasks are disabled or the installed CLI does not support the control), chat shows the error and leaves the work running.

The agent can background its own work without waiting for Ctrl+B: a `task` or `bash` call with `background: true` requests the same transfer while the invocation is starting. The parent turn continues immediately with a placeholder result, and each detached item delivers its final result as a `[System background task completion]` message at the next boundary — so several independent delegations and commands can run concurrently and are collected as they finish. For long-lived processes the `process` tool remains the better fit (monitoring and output capture); for server-side crawls and pipelines the native async job APIs (`_background: true` on the MCP stdio surface) already detach. If the running harness cannot background (headless runs, subagent child contexts), the request is silently ignored and the call runs synchronously; the tool result metadata reports `backgrounded` either way.

Direct task subagents apply the same context compaction as the main chat loop. Each child session sizes its budget from its own model (context overrides in the chat config apply to children too) and shares the auto-compact policy: when the next child request approaches the trigger, old tool bodies are pruned first, and if that is not enough the older half of the child conversation is summarized at a complete-exchange boundary. A retained child keeps accepting follow-ups across compaction. If the provider still rejects a child request as too long, the child history is summarized once and the same request is retried; a child whose context cannot shrink below the provider limit fails its task with an explicit error instead of looping.

The activity pane also contains a **Project activity** row. Open it with Down/Enter or run
`/activity agents` to see currently registered project agents, their verified processes, and tool
calls from the last five minutes. `/activity agent <session-prefix|agent|role>` filters the view,
`/activity refresh` forces an update, and `/activity close` returns to the retained main transcript.
The dashboard reads bounded per-session tool-call tails; it never scans the combined historical
tool-call index. A process whose PID is absent or reused is marked `UNVERIFIED` even if an older
runtime record still declares it `RUNNING`. Bare `/activity`, `/activity local`, and `/processes`
retain the local process/subagent summary. While an interactive child transcript is open, non-slash
input is sent as a follow-up when that child supports it; select **Main chat** and press Enter, or use
`/activity close`, to return explicitly. An expired or non-interactive child automatically returns to
Main and the same input continues through the parent chat instead of being dropped.

## Insights

Judge verdicts and overrides, tool calls, test milestones, crawls and knowledge graphs are read back
as one report per topic, across the project's chat sessions. Every surface reads the same reports:

- The read-only `insights` MCP tool answers a topic and a plain-words question ("flags for bash",
  "slowest calls of the read tool, last 3 days") with a table, sparklines or a small graph; the web
  chat draws the chart in the tool's card.
- When the project configures no dashboard of its own, the terminal chat's dashboard area shows
  **Session insights**: this session's judge flags, tool calls and latency, the latest test result
  and crawl progress. `/dashboard hide` hides it.
- The web chat shows the same rows in a **Session insights** drawer in each chat, read again as tool
  calls finish and replies end, and more often while a crawl runs. Its **All reports for this
  project** link opens the **Insights** page (`/#/insights`, also in the top navigation): a tab per
  topic plus an overview, a question and a window per report, an optional 30-second refresh, and a
  URL that keeps the topic, question, window and project directory, so a report can be bookmarked.

### Tool token drill-down

Bare `/stats` keeps the live session summary. `/stats tools` adds measured argument and returned-payload
tokens, per-tool trends, per-session totals and paginated individual calls. For example:

```text
/stats tools
/stats tools tool:read
/stats tools session:SESSION_ID call:INVOCATION_ID
/stats tools all sessions last 7 days offset:10
```

The default is the current session; `session:` and `call:` use full exact IDs. In the web **Insights →
Tools** tab, use token mode and click a tool, session or invocation to narrow the report. Call details
show timing, outcome, measurement status and tokenizer provenance. Filters and call pages are
bookmarkable; the session drawer also links to token details. Count/latency reports remain available.

These are **local tool measurements**, not billed provider tokens. Partial/unavailable payloads are
excluded from measured totals and appear as chart gaps, not zeroes. Provider/model execution usage
is shown separately and deduplicated by event ID. Coverage includes only calls in the usage journal;
legacy calls without token records are not counted. Bounded-tail truncation is disclosed, and totals
are computed before pagination.

`~/.kompile/config/insights.json` holds the limits every report keeps to. A missing key or a limit
that is not positive keeps its default, so a hand edit never breaks a report; a file that cannot be
read keeps every default and the reports say so. The Insights page edits the file under **Report
limits**: it writes only the settings changed and refuses a limit that is not a positive whole number.

| Key | Default | Limit |
|---|---|---|
| `defaultWindowDays` | `7` | The window, in days, when a question names none |
| `maxRows` | `10` | Rows in a ranked table or call page |
| `maxExamples` | `5` | Recent examples under a table, such as the newest judge flags |
| `maxSessions` | `200` | Judge session logs read per report, newest first |
| `maxBytesPerFile` | `4194304` (4 MiB) | Bytes read from the end of each judge session log |
| `maxToolIndexBytes` | `268435456` (256 MiB) | Bytes read from the end of the tool-call index or usage journal |
| `sparklineBuckets` | `14` | Points in a sparkline and in a chart series |
| `sessionPanel` | `true` | Whether the terminal shows Session insights when the project has no dashboard |

## Project chat profiles

After choosing **Standard Chat** or **Passthrough**, the wizard asks **Use a saved
project profile? [y/N]** when matching profiles exist. Choose Yes, then a vendor and
profile; choose No (or Enter) to continue normal setup. New choices can be saved under
a name at the end of setup. Reusing a name within the same vendor and mode requires
confirmation before replacement.

Profiles live in **`<project>/.kompile/chat-profiles.json`**, grouped by vendor and
`standard`, `passthrough-managed`, or `passthrough-direct`. They retain the model,
thinking selection, endpoint/authentication route, and applicable fast-mode or agent
style choices—not API keys or OAuth tokens. Credentials continue to come from the
managed credential store. Profiles never fall back to another project or the global
profile collection; `--working-dir` selects the project even with `--global-config`.

Both passthrough styles offer optional native model/thinking choices. Native model
catalogs are used when available; otherwise enter the CLI's model ID. Thinking is
only offered where the CLI launch contract supports it (Claude/Codex, and managed
OpenCode). Blank values retain agent defaults; other CLIs keep their native thinking
settings. A profile for one agent never supplies model/thinking overrides to another.

Interactive `kompile chat --mode standard` and `--mode passthrough` also offer the
picker. Explicit command-line overrides win over a selected profile. Headless runs
never prompt. Resume Previous Conversation and Resume All remain session actions:
they preserve recorded sessions rather than applying a new launch profile.

## Project judge profiles and defaults

In the setup wizard, choose **Manage project judge profiles and vendor defaults**,
then a vendor. You can create, replace, select, delete, or clear a named judge
profile. Replacement and deletion require confirmation. Each vendor can have one
active judge profile; deleting it returns that vendor to its fallback defaults.

Judge profiles use the same **`<project>/.kompile/chat-profiles.json`** store as chat
profiles, under the separate `judge` mode, with per-vendor `activeJudges` selections.
They remain project-local even with `--global-config`; `--working-dir` selects the
project. They store only model/thinking and its provider capability provenance—not
credentials, endpoints, or main-chat launch settings. Selecting one neither enables
a disabled judge nor changes its vendor or authentication.

Model precedence is: **explicit judge model → active project judge profile → project
vendor default → global vendor default → backend fallback**. Claude/Anthropic and
Codex/OpenAI/OpenAI-Codex aliases share their vendor's profiles. Profiles never leak
into another project or the main-chat profile picker. Native CLI thinking flags are
only emitted for supported agent contracts.

New judge profiles/defaults select the lowest advertised thinking option (`off`,
`none`, `minimal`, or `low`, where supported). Unknown capabilities remain unset;
main-chat thinking is never inherited. The wizard still offers **Edit fallback
vendor default**, saving `judge-defaults.json` beside `chat-config.json` in the
selected project or global scope. An active project profile takes precedence until
cleared.

## Fast mode

In standard chat, `/fast` toggles premium fast mode independently of thinking effort.
Use `/fast on`, `/fast off`, or `/fast status` for explicit control. Setup and the
`/model` picker also offer an on/off choice for documented supported provider/model
combinations. It defaults to **off**, is saved at the loaded chat-config scope, and
applies to subsequent requests without clearing the conversation. The top pane
shows `fast: on (requested)` or `fast: off` for eligible models.

The current documented eligibility includes OpenAI/Codex GPT-5.4, GPT-5.5, GPT-5.6
and GPT-6 Astra, and Anthropic Claude Opus 5 / Opus 4.8. Unknown models and other
providers do not get a fast-mode picker. Switching providers or selecting an
unsupported model clears the preference. Model eligibility and dated sources live
in `chat/providers/*.json`; eligibility does **not** guarantee account access,
regional availability, or available fast capacity.

Fast mode costs more; it is not a cheaper/smaller model or reduced reasoning effort.
Kompile sends `service_tier: "priority"` for OpenAI/Codex (and explicit `"default"`
when off), or `speed: "fast"` plus `anthropic-beta: fast-mode-2026-02-01` for
Anthropic. Existing authentication and compaction beta headers are preserved.
The provider may reject an unavailable request or use standard capacity; the UI
reports the requested setting, not a measured speed guarantee.

Native Claude/Codex **passthrough** keeps the native CLI's `/fast` handler and
settings; it does not use the standard-chat preference. Claude documents
`--settings '{"fastMode":true}'` for non-interactive CLI launches; Codex documents
`service_tier="fast"` with `features.fast_mode=true`. Kompile does not rewrite those
CLIs' global settings or bypass their organization/usage-credit checks.

Sources (checked 2026-09-06):
- [Claude Code fast mode](https://code.claude.com/docs/en/fast-mode)
- [Claude API fast mode](https://platform.claude.com/docs/en/build-with-claude/fast-mode)
- [Codex speed](https://developers.openai.com/codex/agent-configuration/speed)
- [Codex configuration reference](https://developers.openai.com/codex/config-file/config-reference)
- [OpenAI API fast mode](https://developers.openai.com/api/docs/guides/fast-mode)

## Native chat for MCP pipelines

Select a **provider and an exact model ID**, not just a provider family. Discover IDs
with `pipeline action=list_models provider=<provider>` or
`knowledge_graph action=list_models model_provider=<provider>`. These use native chat
catalog discovery and host authentication (and may contact the provider or use its recent
catalog cache), not inference. No configured default model is required. The result
includes `catalogStatus`, `models[].id`, and the configured model when present. Manual,
unlisted IDs remain valid selections; a failed or empty catalog does not establish
whether a model is available. Thinking variants are not evidence of text or vision support.

Use `pipeline action=capabilities provider=<provider> model=<exact-id> operation=text`
to inspect a selection without network calls or configuration writes. `probe=true`
opts into one synthetic inference request (`probeTimeoutSeconds` 1–60). Repeat with
`operation=image` for that same model before using vision: a passing text probe does not
prove vision support. Provider aliases such as `codex` and `claude` are conveniences;
other registered native providers and configured `custom` endpoints work the same way.

Results separate `adapterSupport`, `modelSupport`, and authentication. Only the tested
operation on the exact selected provider/model becomes `modelSupport=PROBED` after a
pass; other model capabilities remain `UNKNOWN`. A failed request or inconclusive answer
is not proof of an unsupported model. The image probe asks about six randomized colored
tiles whose answer is only in the pixels. `pdf` probes exercise rendered-page image
understanding, **not native PDF parsing**; PDF ingestion renders pages locally.
`json_schema` probes exercise the native strict-schema wire path. Custom/Anthropic
strict-schema requests are currently rejected rather than silently downgraded; ordinary
graph extraction still works through validated text. All probes are smoke tests, not
quality guarantees or durable promises about future requests.

A standalone remote stage is a normal versioned pipeline definition:

```json
{
  "action": "test",
  "definition": {
    "pipelineId": "remote-summary", "kind": "LLM", "topology": "SEQUENCE",
    "processor": {"type": "CHAT_MODEL", "provider": "claude", "modelId": "your-model",
      "prompt": "Summarize the supplied document faithfully."}
  },
  "input": {"text": "Example document text"}
}
```

Use the same definition with `create`, then `run` its `pipelineId`; `test` does not save
it. Inputs are `text` or `filePath`/`path` (text, images, PDF). `modelBindings.default`
and `source=chat` model definitions override defaults, including the provider. A project
manifest can register `metadata.pipelineType=CHAT_MODEL` and `modelRefs`. For document
crawls, select `pipelineType=CHAT_MODEL` or `processor.type=CHAT_MODEL`, with `provider`
in processor/options or the bound model definition.

Use a standalone host chat stage or an all-`CHAT_MODEL` `pipelineSpec` with independently
selected models in a sequence or directed acyclic graph; see [chat compositions](pipelines.md#native-chat-compositions).
Every stage is preflighted before the first provider call. Mixed tensor/chat runners,
loops, embeddings, tool execution, and KGE/reasoning training are not supported by this
chat executor; those keep their existing engines. Calls use fresh native chat clients and host-owned
authentication; selecting a different provider does not switch active chat, inherit the
old provider's endpoint/model, or store credentials in pipeline definitions. Specify a
model when the selected provider has no matching saved configuration. Configure custom
endpoints through chat setup, not request JSON.

## Native chat for graph extraction

Graph extraction can use the native standard-chat transport directly, without starting a
Claude/Codex CLI. Select it explicitly with the **`chat:` prefix**:

```json
{
  "documents": [{"path": "notes.md"}],
  "graphExtraction": {"llmProvider": "chat:codex", "modelName": "your-model"}
}
```

`chat` alone selects the configured provider. `chat:claude`, `chat:openai`, or another
supported native provider selects that provider with its saved project/global settings.
Bare `codex`, `openai`, `claude`, and `*-cli` aliases **retain legacy CLI routing**;
they are not shorthand for native HTTP chat. Model overrides are request-scoped.

Alternatively, use a native backend in `processingRoute` (do not also set a `chat:`
graph provider shorthand):

```json
{
  "processingRoute": {
    "fallbackEnabled": false,
    "backends": [{
      "id": "native-text", "type": "CHAT_MODEL", "provider": "codex",
      "modelName": "your-model", "capabilities": ["llm"]
    }]
  }
}
```

`fallbackEnabled:false` pins the first enabled text-capable backend by priority: a
failure or unavailable primary never calls a backup/default provider. With fallback
enabled, an explicitly supplied chain can try alternatives. Missing host-native wiring
is a configuration error, not permission to launch a CLI.

The graph `CHAT_MODEL` backend supports **text/llm only**, with deadlines, bounded
output, and no tools. It does not claim structured tool calls, required tool choice,
VLM, or embeddings. The production graph orchestrator still parses and validates the
text protocol. `apiKey`, `endpointUrl`, and other credential/endpoint fields are rejected
on native routes; endpoints and credentials belong in chat configuration and are never
copied to crawl configuration. These host-owned routes stay local even with a managed
crawl URL configured. Document `processor.type=CHAT_MODEL` is a separate contract.
Per-backend `maxConcurrent`/`requestsPerMinute` are not yet implemented for native graph
chat; nonzero values are rejected, not ignored. Bound extraction workers with
`runtimeConfig.graphExtractionRemoteParallelism` instead.

`crawl_source` accepts `provider:"chat:codex"` and `model:"your-model"` and forwards
both to graph extraction. Dry-run graph selections are **configuration/capability
previews**, not authentication or inference proof; they do not call the selected model.
Dry-run pipeline and resolved-document payloads redact credential fields.

`knowledge_graph action=extract text=... model_provider=chat:codex model_name=...`
uses the same production headless extractor. Local extraction defaults to `chat`, is
limited to 200,000 input characters, and writes no graph/crawl archive unless
`persist=true`. Local `agents`/`merge_strategy` are unsupported (managed multi-agent
extraction is a different operation). `list_providers` locally reports native metadata;
`list_models` returns the selected provider's native model catalog (also when a managed
graph URL is configured). `capability_probe` with `model_provider`, the exact `model_name`,
and `probe_operation:"text"` is pure by default. Only explicit `live:true` requests one
bounded native probe call. Probe `image`, `pdf`, `graph_extraction`, or `json_schema`
separately as needed; a pass on one operation does not qualify the others. Use that exact
model ID in extraction's `model_name`, a crawl's `graphExtraction.modelName`, or a
standalone pipeline's `processor.modelId`/`modelBindings`.

### Managed graph extraction with host-owned chat

With a managed graph URL, native extraction uses a prepare/complete exchange: the server
validates the source, agent selection and merge strategy; the MCP host runs the exact
selected native chat model; the server validates and merges the returned graph. Credentials
stay on the host. Other configured server extraction providers also receive the requested
model ID rather than silently using their default model.

Stored sources must belong to the selected fact sheet and contain full source text in
`metadata.text` or `metadata.content`; a content preview is not extraction input. Inline
text is also supported. Invalid model configuration or missing source text fails closed.

Managed extraction can persist a validated graph or create an extraction job with real
reviewable triple proposals, not both in the same request. Host-native job completion is
synchronous, records the provider/model selection, and uses the existing job/proposal
status APIs. Cancellation prevents late results and progress updates from turning a
terminal job back into a running or completed one. Server-side extraction jobs dispatch
actual extraction work rather than returning a queued job with no worker.

### Native models for graph evidence interpretation

`graph_reason`, `ask_graph_explain`, `ask_graph_explain_fused`, `ask_graph_synthesize`,
`graph_reasoning_query`, and `graph_forecast` accept an optional selector:

```json
{
  "target": "worksFor(Alice, Acme)",
  "chatModel": {
    "provider": "codex",
    "modelId": "your-exact-model-id",
    "timeoutSeconds": 60
  }
}
```

This `graph_reason` example first obtains graph evidence, then asks the selected native
model to reason over it. The original engine result, verdicts, scores, derivations and
forecast values remain unchanged. The model answer is separately labeled **unverified**,
with no tools or graph-write privileges. Missing evidence is not proof of absence. Input
is bounded and truncation is reported; provider failures are reported while preserving
engine evidence. Provider credentials are resolved only by host chat configuration.

Use `knowledge_graph action=capability_probe` with that exact provider/model and
`probe_operation="text", live=true` to qualify text inference independently of vision.
`chatModel.dryRun=true` only previews selection: no graph access, authentication,
inference or persistence. A configured selection is not a successful capability probe.
Omitting `chatModel` retains engine-only behavior. `graph_forecast.preferred_llm_provider`
is a compatibility alias for `chatModel.provider`; use `modelId` for exact selection.

Chat does not replace PSL/MEBN/KGE learning, numerical inference, embeddings, tensors,
or graph mutations. `graph_reasoning_query` rejects the selector for capability, schema
and asset inspection. Local `ask_graph_explain_fused` reports retrieval-only evidence
and no fused confidence; a model narrative does not imply that all engines ran.

`crawl_control operation=start` preserves native chat routes, pipeline registry entries,
per-source processors and model bindings, including requests using `body.sources` with
`pathOrUrl`. Host-owned contracts use the same local routing as `crawl_documents` even
when a crawl-manager URL is configured. `operation=preflight` with a body previews the
composed request without starting a job, regardless of the body's `async`/`dryRun` values.
A failed managed start is **not** replayed locally: inspect manager jobs before retrying
to avoid duplicate ingestion after an ambiguous network failure.

## Non-interactive and streaming JSON chat

Passing a prompt runs one standard-chat turn without constructing JLine or the TUI. The
command resolves the same project/global chat configuration and supports the same runtime,
provider, model, thinking, role, RAG, memory, permissions, session, and compaction settings
as interactive standard chat:

```bash
# Stream plain assistant text (progress remains on stderr)
kompile chat "summarize this project"

# Claude-style output selection: one JSON object per line
kompile chat --output-format stream-json "summarize this project"

# Codex-style shorthand and stdin input
echo "summarize this project" | kompile chat --json

# Explicit direct-provider settings without changing the saved config
kompile chat --json --provider anthropic --model claude-sonnet-4-5 \
  --thinking high --role architect --no-rag --memory "review the changes"

# Select the same OpenAI subscription/OAuth route offered by the wizard
kompile chat --json --provider openai --auth oauth --model gpt-5.6-terra \
  "review the changes"

# Resume with the recorded project context and save the final response separately
kompile chat --json --resume <session-id> \
  --output-last-message result.md "continue with the next task"
```

`stream-json`, `json`, and `jsonl` are accepted JSON output names; `--json` is the
short form. Stdout contains only JSONL. Human diagnostics and terminal-oriented progress
are routed to stderr. Events have a monotonic `seq` and use these `type` values:

- `session`: effective non-secret configuration (`session_id`, wire `provider`, `auth`,
  `model`, `thinking`, `agent`, optional `role`, `cwd`, `rag`, and `memory`)
- `text`: a raw assistant delta
- `backend`: confirmed server agent/process metadata when using a Kompile instance
- `sources` and `stats`: server-confirmed retrieval and completion metadata
- `tool_start` and `tool`: tool lifecycle records with call IDs
- `usage`: provider-reported input/output/cache token counts
- `result`: final text, tool count, session ID, and exit code
- `error`: a terminal configuration or execution error

Run `kompile chat --setup` first when no usable provider configuration exists. Credentials
remain in the managed credential store or provider environment variables and are never
included in JSON events. The Standard Chat route is provider-neutral: API-key and
OAuth/subscription providers, native OpenCode, external local endpoints, first-party local
serving, and Kompile instances all use the same event contract. Managed passthrough also
adapts native framework events to this contract; direct terminal passthrough keeps the
native agent's own structured-output protocol.

Use `/clear` to end the current conversation and immediately start a fresh one. Kompile
closes the old transcript, generates a new transcript UUID, clears and redraws the chat
window, and keeps the same CLI process running. Server connections and an owned first-party
local model runtime stay alive across the reset; the old transcript remains available to resume.

Use `/reset` (or `/restart`) to reload the CLI in a fresh process and resume the **same**
transcript in the **current terminal**, including over SSH or inside tmux. No desktop
terminal popup or terminal-launcher configuration is needed. Chat and terminal cleanup
finish before handoff; repeated resets reuse one foreground supervisor instead of nesting
waiting CLI processes. Managed passthrough retains its agent and Kompile UI.
`/reset-all` also resets the invoking chat in place; peer chats retain the existing
independent-terminal launch behavior.

### Judge control and conversation

The quality harness is advisory: it records scores and model-routing evidence but never interrupts
the main agent. Configured policy and direction judges are the intervention lanes. Their feedback is
delivered as an interrupting user-authored turn, with the original request included, rather than as a
system-only transcript annotation.

Use `/judge off` to disable intervention for the session without losing access to the judge chat.
Judge conversation uses normal REPL commands and retains its own conversational history:

```text
/judge why did you block that tool?
/judge ask which rule did you apply?
/judge chat I meant that only resets are prohibited.
/judge feedback Interpret the Git reminder as reset-specific guidance.
```

`/judge feedback` is durable guidance for later verdicts; ordinary judge chat does not silently
change policy or enable intervention. Ambiguous applicability should pass, and qualified rules must
not be widened into broader prohibitions.

### Prompt reminders

Use reminders when an instruction must be repeated at the front of every prompt sent by Kompile Chat. Session reminders are the default and follow the conversation when it is resumed:

```text
/reminder Always run the focused tests before answering.
/reminder
/reminder clear
```

Project-global reminders apply to every chat session started in the same project folder and are stored in `.kompile/chat-reminders.json`:

```text
/reminder-global Do not edit generated files.
/reminder-global list
/reminder-global clear
```

`/reminder add <text>` and `/reminder-global add <text>` are explicit aliases for adding reminders. Project-global reminders are prepended first, followed by session reminders.

By default the reminder block is injected on every user prompt. Configure the interval to inject it only every n-th prompt instead: `/reminder interval 5` (session), `/reminder-global interval 5` (project), `/reminder interval off` to disable injection, or `/reminder interval reset` to fall back to the enclosing scope. `KOMPILE_CHAT_REMINDER_INTERVAL` is not used; the `kompile.chat.reminder.interval` system property is the last-resort fallback. The user turn is written to the transcript exactly as it was sent to the model, reminder block included; the session title is still derived from the raw prompt text.

When an LLM-backed project judge policy is active, the same non-disabled project and session reminders are supplied to full-response, streaming, and pre-execution tool review as explicit **enforceable constraints**, not merely prompt suggestions. An interval greater than one only reduces how often the reminder block is repeated to the main model; the active judge continues checking the reminders on every reviewed turn. `interval off`, `/judge off`, and `/judge global off` remain explicit opt-outs. Explicit enforcer rules take precedence if they conflict with a reminder. Keyword-only mode continues to enforce literal keyword/tool rules and does not pretend to semantically interpret free-form reminder text.

### Scheduled loops

`/loop` schedules a recurring prompt for the current conversation. Its schedules follow the
same transcript ID across resume and do not appear in other sessions:

```text
/loop add 5m review this conversation
/loop add cron */15 * * * * -- check the current task
/loop list
/loop clear
/loop pause <id>
/loop resume <id>
/loop run <id>
/loop remove <id>
```

Use `/loop-global` with the same subcommands for project-global schedules:

```text
/loop-global add 30m review project activity
/loop-global list
/loop-global clear
```

`clear` stops and removes every schedule in the selected scope; clearing session loops does not
change project-global loops, and vice versa.

Both scopes are local-only and run while a matching Kompile Chat is open; missed firings are
not replayed. Session schedules are stored beside conversation state under
`~/.kompile/conversations/`. Project-global schedules remain under
`~/.kompile/scheduled-loops/` and are loaded whenever a chat rooted in that project opens.
Each chat that loaded a global schedule receives its firings; changes made from another
already-running process take effect the next time that chat opens. Project schedules created
by older versions of `/loop` remain project-global and are now managed with `/loop-global`.
Interactive `kompile crawl` sessions support the same schedules; a firing waits for the active
crawl turn and then runs through the bounded crawler agent. Headless crawl invocations remain
one-shot and do not start schedule timers.

If Standard Chat selects the local `Kompile` provider and no process is listening at its local chat URL, the CLI starts only `kompile-chat` from the installed distribution and connects the REPL to it. Startup happens after the wizard has resolved the mode and provider; it never replaces or bypasses model configuration.

The native executable is preferred, with `kompile-chat.jar` as fallback, through the normal Kompile process manager. When `kompile` itself is a native image, it infers the distribution root from its own executable and directly launches the sibling `bin/kompile-chat` binary. It uses `~/.kompile` as its data directory, writes subprocess output under `~/.kompile/logs`, and does not create a project or start app-main, model staging, crawling, or model-serving infrastructure.

Explicit `--url`, `--port`, `--local`, and passthrough routes retain their existing behavior and never trigger subprocess startup implicitly. A configured remote Kompile URL is connect-only.

```bash
# Connect to the default chat URL without launching the installed subprocess
kompile chat --no-start

# Allow longer for the chat application to initialize
kompile chat --startup-timeout 300
```

Run the wizard explicitly at any time with `kompile chat --setup`.

## Local and direct LLM chat

A saved first-party local, external-local, or direct-provider configuration is used automatically; no full Kompile instance is needed. First-party local chat owns Kompile's serving subprocess for the session, while Ollama and OpenAI-compatible endpoints remain optional external routes.

```bash
kompile chat --setup
kompile chat --local
```

## Server-connected RAG chat

An explicit URL is connect-only and does not start local services. The chat persona uses port 8081 by default.

```bash
kompile chat --url=http://localhost:8081
```

Queries flow through optional query rewriting, embedding, vector search, reranking, filters, and LLM generation with retrieved context.

## Agent passthrough

Wraps an AI agent (Claude Code, Codex, DeepSeek Harness, etc.) with Kompile's chat UI, project context, transcript, and the integration capabilities verified for that agent.

```bash
kompile chat --mode passthrough --agent=claude-code --rag --role=architect
```

Kompile manages project context and transcripts for every passthrough agent. When an agent exposes a verified MCP configuration contract, Kompile also injects its MCP tools.

DeepSeek Harness is available as `--agent=dsh` when the `dsh` executable from
`@deepseek-ai/dsh` is installed on `PATH`. The current developer-preview alpha requires
Node 22.19.x or Node 24+:

```bash
kompile chat --mode passthrough --agent=dsh
```

DeepSeek Harness is currently a developer preview. Its released terminal automation
surface is `dsh --profile headless <task>`: one fresh persisted session per invocation,
reasoning on stderr, the final answer on stdout, and no interactive follow-up. Kompile
therefore routes it through managed passthrough, keeps reasoning out of assistant
transcripts, and starts a fresh DeepSeek task for every prompt. The raw
`kompile passthrough --agent=dsh` TUI handoff is intentionally rejected because upstream
does not ship a default `tui` profile. Configure models and credentials through DeepSeek
Harness itself; Kompile does not pass undocumented `--model`, effort, resume, or MCP flags.

### Project-local knowledge runtime

A passthrough agent can crawl and search the current project without a Kompile server. Start the
stdio MCP server with the project as its work directory, call `crawl_discover` to inspect executable
loaders, chunkers, and pipeline templates, then use `crawl_documents` or `crawl_source`. The agent
host performs loading, pooled stdio pipeline execution, chunking, and indexing into
`data/crawls/<knowledge-base>`; `knowledge_search`, `knowledge_status`, and `memory` remain available
in the same session.

The local runtime supports standard text, code, tables, keyword-only indexes, OCR, VLM, and custom
unified pipelines. Every model-backed step uses the same packaged runtime contract. Connecting with
`--url` keeps the same MCP contracts but hands job scheduling, shared state, and distributed model
coordination to the full server.

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
