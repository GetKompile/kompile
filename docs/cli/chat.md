# Chat

Kompile supports first-party local model chat, external/direct model chat, an installed chat subprocess, server-connected RAG, and agent passthrough.

## Context windows and token usage

Standard CLI chat reads the selected vendor's model metadata at session startup
and uses refreshed picker metadata after model/provider changes. Anthropic,
Gemini, OpenRouter, Copilot, Radius and OpenCode have separate limit-field
mappings in `ai/kompile/cli/main/chat/context-metadata.json`. Codex also reads
`context_window` from the catalog written by its authenticated discovery process,
not an unrelated account's `~/.codex` cache. Claude Code's session-reported limits
remain authoritative for its native route.

Deployments can override vendor JSON-pointer mappings with
`-Dkompile.chat.contextMetadataConfig=/path/to/context-metadata.json` (same
`schemaVersion: 1` / `providers` shape). Missing limit fields stay unknown; providers
whose model-list API omits limits use the provider-scoped catalog/documented fallback,
marked `~` in the context counter until the vendor reports a limit or you set an override.
Explicit `contextWindowTokens` and `maxOutputTokens` settings still override resolver
budgets. Metadata lookups do not generate chat completions.

The context counter is the latest request's input, including cached input, divided
by the active model's window—not cumulative session usage. Session token totals
include all requests and outputs. OpenCode multi-step turns keep these ledgers
separate. Session metrics JSON persists the window and last measured input under
`context`; compaction invalidates that input until another request reports usage.

## Terminal image previews

In the standard terminal chat, `/image <path>`, `/file <image-path>`, and clipboard
image paste show the latest attachment in a bounded Kitty-protocol preview when
terminal support is detected. The original image is still attached to the next
message; only its preview is downsampled. Submit your next line or use `/ui close`
to return to the retained chat transcript.

- `/ui set images.protocol auto` — default; detects support on the background preview
  worker. Direct terminals use JLine's hints; multiplexers also check their running
  instance and client metadata. Unknown/unsupported setups keep the text/chip UI.
- `/ui set images.protocol kitty` — force on for a known-compatible setup, bypassing
  automatic capability checks.
- `/ui set images.protocol off` — disable previews.

It previews one static frame through installed Java image decoders (PNG/JPEG/GIF;
WebP needs an installed decoder). Files over 5 MiB or 32 megapixels are not previewed;
preview failures do not reject attachments. No model/tool escape sequences are
passed through, and image bytes are not embedded in transcript text.

### Multiplexers

Multiplexer previews are automatically enabled when the prerequisites below are
recognized. Detection uses bounded, read-only multiplexer metadata checks, never
terminal responses from the chat input stream. Kompile does not change multiplexer
configuration. Forced `kitty`/`off` modes remain optional overrides.

- **tmux 3.3+ (one layer):** enable `allow-passthrough on` in your own tmux
  configuration, use a UTF-8 client, and ensure tmux recognizes the outer terminal's
  true-color (`RGB`/`Tc`) capability. The outer terminal must support Kitty Unicode
  placeholders (for example Kitty 0.28+), not just basic Kitty graphics. Uploads
  and virtual placements use DCS passthrough; cursor movements and explicitly
  positioned placeholder cells stay inside tmux's pane grid. This keeps pane
  moves, splitting and clipping under tmux's control. Automatic mode checks the
  running server version, inherited pane passthrough permission, and each attached
  client's UTF-8/RGB features and known placeholder-capable terminal identity
  (Kitty/Ghostty). No attached client or unknown capabilities means text fallback.
- **Zellij 0.45.1+:** enable Kitty graphics in Zellij and its host terminal. Kompile
  uses Zellij's native, pane-managed ordinary placements, not tmux passthrough or
  Unicode placeholders. Automatic mode checks the running ancestor executable's
  version and a known Kitty-capable host; unavailable process metadata falls back
  to text. The forced override is available when automatic recognition is unavailable.
- **GNU screen and nested/mixed multiplexers:** not supported. Screen contexts and
  detectable mixed nesting retain text fallback. Nesting multiple tmux servers
  cannot be reliably inferred from `TMUX`; do not enable previews there.

After attaching a new tmux client/terminal, returning to a window whose initial
upload happened while hidden, or losing terminal image resources, use `/redraw`
to re-upload the retained preview. Tmux's saved placeholder text is not an image
cache and does not replay passthrough uploads. Misconfigured RGB/passthrough or an
unsupported placeholder extension can show missing images/private-use glyphs;
use `/ui set images.protocol off` to retain the normal attachment UI.

## Web chat

Run `kompile chat --web` (add `--open-browser` to open it locally). This launches
the web console directly: no setup wizard or saved provider configuration is required.
Configure sessions in the browser; use `--setup --web` only to configure before launching.
For a stable URL, run `kompile chat --web --web-port 9181` (prints
`http://127.0.0.1:9181/#/chat`). The terminal setup wizard also asks for a web UI port
when you choose Browser, including with `--setup --web`. Enter a port from 1 to 65535,
or leave it blank / enter 0 for automatic selection (the existing default).
`--web-port` overrides the wizard choice and skips that prompt; it is launch-only, not
saved in model profiles. A busy port fails without reusing another server or switching
ports. The existing `--port` remains the terminal server connection option.
The installed CHAT service listens on all interfaces by default, including for published distributions.
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
- Native frameworks and standard vendors are one list of vendors, and a chat can move
  between any of them at any time: the selector above the chat, **Session Configuration**
  (chat mode, framework, model, effort) and `/model <vendor>:<model-id>` all switch it,
  from the folder default onto a framework, from one framework to another, or from a
  framework back to a standard vendor. `/model <vendor>` (or picking a vendor chip) only
  browses that vendor's live model list. `/model <model-id>` changes only the current
  vendor's model. Where a key names both a framework and a standard vendor (OpenCode), it
  means the kind the chat runs now.
- A switch carries the transcript, as Kompile's own harness does across vendors. A
  framework switched to starts a new native session on the next turn, seeded with the
  chat's whole transcript, including turns other vendors ran; switching back to a
  framework the chat used before seeds it again rather than resuming its older session,
  which would miss the turns in between. If the transcript cannot be handed over, the
  turn fails with the reason instead of starting the framework empty. A switch to a
  standard vendor resumes the chat in Kompile's harness from the same transcript. Native
  sessions are always resumed by their recorded ID and folder, never the vendor's latest
  conversation. An original vendor chat opened from a vendor folder keeps resuming its
  source session on its own framework; history from before it was opened lives only in
  that vendor's store, so another vendor it switches to receives the turns Kompile recorded.
  When the chat comes back to its source framework, the next turn hands that session every
  turn other vendors ran since it last answered, once.
- The chat list shows where each chat runs now (for example `codex / gpt-5-codex` after
  a switch); its launch selection is kept.

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

### Configurable chat display

Standard chat defaults to **Classic**, preserving existing inline rendering. Run **`/ui`** for the live display wizard, or **`kompile configure ui`** outside chat. Both use the same numbered-list flow, with one choice per line and the current selection marked `* current`. Every page—including preset, layout, content placement, sidebar sections, sizes, shortcuts and save scope—accepts **numbers only**. `0` returns to the previous page (or finishes at the main menu); each page has a numbered Cancel option. No setting paths or raw enum values are required. UI settings are independent of provider/model configuration and `/thinking` effort.

- **Classic:** existing inline presentation.
- **Focused:** chat and thinking inline, successful tool bodies in a collapsed results sidebar; failures still have an inline summary.
- **Verbose:** inline details plus sidebar navigation.

Choose **Preset**, then **Focused**, **Classic**, or **Verbose** from its numbered list. **Layout** independently offers Inline, Split, and Automatic; Split/Automatic use a right-hand sidebar when chat can retain its minimum width, otherwise `/ui results` opens a full-width drawer. In Split/Automatic mode the sidebar extends to the terminal bottom, beside the left-column input, queue, and activity/status rows. The input editor is full-width when no sidebar is visible. Windows shorter than 16 rows or narrower than the configured minimum chat width collapse to the transcript and input only: header, dashboard, queue, activity/status bars, and sidebar are hidden. Enlarging the window restores the configured layout without changing saved preferences.

The sidebar supports tool arguments/output/metadata, diffs, file content, processes/monitors, subagents, todos, sources and custom `side_panel` content. Click card/section headers to collapse or expand them. Mouse-wheel scrolling is pane-specific. **Ctrl+X S** toggles the sidebar (or opens the drawer); **Ctrl+X R** focuses its selection. With an empty draft, Up/Down selects cards and Enter toggles a split-pane card or inspects a drawer selection. Inspection never changes the chat input's recipient. `/ui close` returns to Main.

Other controls: `/ui inspect`, `/ui next`, `/ui previous`, `/ui sidebar expand`, `/ui section output`, `/ui filter tools`, `/ui filter all`, `/ui pin`, and `/ui status`. Pinning keeps a result at the top of the filtered list. A full-width inspector supports the usual transcript selection/copy controls. Sidebar, results drawer and inspector retain the transcript's tool-heading colors, syntax highlighting for file/search output, edit/patch colors, and formatted assistant/thinking/subagent content. Wrapping or clipping changes the viewport, not token styles. Plain/non-ANSI output remains unstyled; tool-supplied cursor controls are still stripped.

**Content placement** first lists content types and result sections. Select one, then choose **In chat** or **In sidebar**. Chat offers Hide, Summary only, and Show content; the sidebar offers Hide, Collapsed card, and Expanded card. Individual-tool choices come from available session tools and already-configured tool rules. **Sidebar** provides numbered choices for width, minimum chat width, wrapping, follow mode, section visibility/order, retained completed-card count, and retained content budget. Width choices are actual percentages, not repeated increase/decrease controls; choosing a value accepts it once and returns to the Sidebar menu. Minimum chat width works the same way. Choosing a content-placement value finishes that content dialog and returns to the content list. **Keyboard shortcuts** lists available choices rather than requiring key strings.

Changes preview live. **Done automatically saves** your choices and closes the wizard; no separate Save action is required. **Cancel** or Escape discards the preview and leaves saved preferences unchanged. To choose a different scope, select **Save preferences**, then This conversation (inside chat), This project, or All projects. A successful save **finishes and closes the wizard**. Back from the scope picker returns to settings. `/ui preset`, `/ui layout`, `/ui set`, and sidebar visibility toggles (including **Ctrl+X S**) also save automatically.

UI preferences survive exit, restart, and conversation resume. UI files are `~/.kompile/chat-ui-config.json`, `<project>/.kompile/chat-ui-config.json`, and `~/.kompile/conversations/<id>.chat-ui-config.json`. Fields merge in that order over preset defaults. Automatic saves use the highest valid existing scope, or user-wide preferences when no scoped configuration exists; a conversation-only save intentionally applies only to that conversation. Saving writes explicit edits and preserves unrelated settings. Layout, sizes, sidebar visibility, section order, wrapping/follow, content/tool rules, and shortcuts are restored at startup. Focus, selection, filters, and individual result-card state remain transient. Preset changes preserve explicit content overrides. Invalid configuration layers are reported and ignored without erasing valid lower-layer settings. `/ui status` and `kompile configure ui --status` print effective preferences and the automatic-save path.

Completed tool results and edit/patch diffs accumulate across turns; streaming updates replace only the same invocation's card. Display retention defaults to 256 completed cards and 2,097,152 retained presentation characters (including plain/styled copies). Configure both in **`/ui` → Sidebar → Retained completed cards / Retained content**, or with `/ui set sidebar.maxItems 512` and `/ui set sidebar.maxChars 4194304`. Limits accept 1–4096 cards and 1024–67,108,864 characters and persist in the usual UI preference scopes; `/ui status` shows the effective values. When a limit is exceeded, hidden content is evicted before configured visible results, then the oldest unprotected cards are evicted with an omission notice. Temporary filters do not change retention priority. Active cards are outside the completed-history budget; selected and pinned cards are protected, so protected content can exceed the limits. Individual typed card sections share a 128k-character ceiling; cuts are disclosed. Inline previews remain smaller. These limits do not alter tool results supplied to the model or persisted by the existing transcript/result machinery. Legacy replay text without typed metadata stays inline; this feature does not reinterpret raw passthrough-agent screens.

### Input and background activity

At the standard chat prompt, Ctrl-C exits through the normal session cleanup path. Use the configured cancel key (Escape by default) to cancel an in-progress main-model operation. Main-model thinking is not advertised as backgroundable. While a backgroundable tool invocation is running — `task` delegations and `bash` commands — Ctrl+B detaches it to the activity pane so the main prompt can continue; press Down to select the row, Enter to open it, then type a follow-up, or press Delete to stop it. The pane suppresses the duplicate parent-turn task row while the active or retained child row is available, so opening the visible backgrounded row cannot silently target Main chat instead. Escape leaves active work running rather than acting as a second kill key.

Subagents show only a compact launch notice in the main transcript, with their id/type and a pointer to the process browser below. This applies to Classic, Focused and Verbose presentation, including Claude Code's native agents. Live status, steps, tool output and streamed answers stay in the browser: press Down to select the subagent, then Enter to browse its full transcript; select **Main chat** and press Enter (or `/activity close`) to return. Ctrl+B still backgrounds eligible invocations, and Delete stops selected running work.

With Claude Code in standard `kompile chat`, Ctrl+B also backgrounds its native `Agent` (legacy `Task`) and `Bash` calls. Kompile sends Claude's native background control, keeping the agent and session alive while the parent resumes and queued or fresh messages proceed. These tasks retain their process-panel rows; they are not detached copies of the parent conversation. If Claude rejects the request (for example, background tasks are disabled or the installed CLI does not support the control), chat shows the error and leaves the work running.

The agent can background its own work without waiting for Ctrl+B: a `task` or `bash` call with `background: true` requests the same transfer while the invocation is starting. The parent turn continues immediately with a placeholder result, and each detached item delivers its final result as a `[System background task completion]` message at the next boundary — so several independent delegations and commands can run concurrently and are collected as they finish. For long-lived processes the `process` tool remains the better fit (monitoring and output capture); for server-side crawls and pipelines the native async job APIs (`_background: true` on the MCP stdio surface) already detach. If the running harness cannot background (headless runs, subagent child contexts), the request is silently ignored and the call runs synchronously; the tool result metadata reports `backgrounded` either way.

Direct task subagents apply the same context compaction as the main chat loop. Each child session sizes its budget from its own model (context overrides in the chat config apply to children too) and shares the auto-compact policy: when the next child request approaches the trigger, old tool bodies are pruned first, and if that is not enough the older half of the child conversation is summarized at a complete-exchange boundary. A retained child keeps accepting follow-ups across compaction. If the provider still rejects a child request as too long, the child history is summarized once and the same request is retried; a child whose context cannot shrink below the provider limit fails its task with an explicit error instead of looping.

For generic direct chat routes with measurable client-owned history, automatic compaction first projects old tool results as short previews and saved-output references, preserving complete recent exchanges and leaving the raw ledger unchanged. It remeasures the pending request and only summarizes if the projection still exceeds the trigger. Provider-native compaction and provider-owned sessions keep their own policies. Portable summaries carry current goals, active constraints and decisions, validation evidence, unresolved work, and resume references rather than an exhaustive narrative. They have an adaptive character budget capped at 12,000; oversized, refused, or provider-truncated summaries are rejected rather than silently clipped. The existing deterministic fallback remains available. Isolated summary requests use an output-token ceiling on supported HTTP transports without changing live chat limits; native session transports, including subscription Codex, retain ownership of their generation limits.

Anthropic direct API chats discover on-demand summarization and tool-result clearing through the Models API, scoped to endpoint, model and credentials. Supported models use server-side tool clearing (call identifiers and recent tool uses remain intact) and signed on-demand compaction at completed-turn boundaries or `/compact`. Summaries are committed before wire history is replaced; the signed block is replayed unchanged, first in history, with its required beta header. Unsupported models retain threshold compaction or portable summary fallback. Claude Code CLI sessions continue to own native compaction and are never rewritten by this API path.

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
- When the project configures no dashboard of its own, the terminal chat keeps the compact dashboard
  area and shows **`+ /dashboard to see session metrics`**. The hint does not scan metrics or poll
  historical stores. `/dashboard` prints the on-demand summary and report commands; `/dashboard hide`
  hides the hint. Configured project dashboards keep precedence and their existing behavior.
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
| `sessionPanel` | `true` | Whether the terminal shows the compact `/dashboard` hint when the project has no dashboard |

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

In standard chat, `/fast` toggles premium fast/priority mode independently of thinking
effort. Use `/fast on`, `/fast off`, or `/fast status` for explicit control. Setup
also offers an on/off choice for supported providers. It defaults to **off**, is
saved at the loaded chat-config scope, and applies to subsequent requests without
clearing the conversation. The top pane shows `fast: on (requested)` or `fast: off`.

Kompile accepts **every model ID** on Anthropic, OpenAI/Codex, Gemini, Groq,
OpenRouter, and xAI routes, including newly released and unlisted IDs. There is no
client model allowlist. Model changes within a provider retain the preference;
provider switches clear it. Provider request contracts and dated sources live in
`chat/providers/*.json`. The provider, not Kompile, validates model support, account
access, regional availability, and capacity.

Fast mode may cost more; it is not a cheaper/smaller model or reduced reasoning effort:

| Provider | `/fast on` | `/fast off` |
|---|---|---|
| OpenAI/Codex, OpenRouter, xAI | `service_tier: "priority"` | `service_tier: "default"` |
| Gemini (OpenAI-compatible API) | `service_tier: "priority"` | `service_tier: "standard"` |
| Groq | `service_tier: "performance"` (enterprise access required) | `service_tier: "on_demand"` |
| Anthropic API | `speed: "fast"` and `anthropic-beta: fast-mode-2026-02-01` | omit fast fields/header |

Existing authentication and compaction beta headers are preserved. The provider
may reject an unavailable request or use standard capacity; the UI reports the
requested setting, not a measured speed guarantee. Other routes remain unchanged
unless they declare a verified fast-mode request contract.

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

Additional provider contracts checked 2026-10-14:
- [Gemini OpenAI compatibility: Flex and Priority inference](https://ai.google.dev/gemini-api/docs/openai#flex_and_priority_inference)
- [Groq service tiers](https://console.groq.com/docs/service-tiers)
- [OpenRouter service tiers](https://openrouter.ai/docs/guides/features/service-tiers)
- [xAI Chat Completions request schema](https://docs.x.ai/developers/rest-api-reference/inference/chat-completions.md)

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
