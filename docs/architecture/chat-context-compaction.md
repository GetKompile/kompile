# Chat context compaction

Both chat surfaces — the `kompile chat` CLI and the kompile-app-main web chat window —
compact conversation history against the **real context window of whatever the chat is
talking to**, instead of assuming a fixed budget.

## Context-window resolution (which metadata wins)

| Chat lane | Authoritative source | Runtime query |
|---|---|---|
| CLI local mode, catalog-known model (claude/gpt/gemini/deepseek/ollama…) | Provider-scoped `CliModelCatalog` (live models.dev data on disk) → bounded native-provider aliases → `ModelContextWindows` static table | `ModelContextWindows.getContextWindow(provider, model)` |
| CLI local mode, unknown model on a loopback endpoint (kompile-staged GGUF behind the staging OpenAI facade) | Serving origin's `GET /api/llm/status` → `maxContextLength` | `ModelContextResolver` (30 s cache, 2 s probe timeout) |
| Web chat, CLI agents + hosted API agents | `ModelCapabilityService` → registered `LlmProvider`s → `ModelContextWindows` | `ChatContextBudgetService.resolve(agent)` → `source="catalog"` |
| Web chat, `kompile-local` / `local-staging` / any `local/…` model | Live `GET /api/llm/status maxContextLength` (serving truth after KV bucketing) → staging registry `metadata.max_sequence_length` → conservative 4096 default | `ChatContextBudgetService.resolve(agent)` → `source="staging-live" / "staging-registry" / "staging-default"` |

CLI model limits use the configured **provider and exact model ID together**. A missing
provider entry must not borrow an identically named model from an unrelated gateway.
Only explicit native aliases (`openai-codex`/`codex` → `openai`, `claude` → `anthropic`,
`gemini` → `google`) can share upstream catalog metadata before the static fallback.
Explicit chat context/output overrides still win; slash-containing model IDs remain
intact in the provider-scoped lookup.

A staged local model must **never** inherit the 128K catalog default: a GGUF is
typically 4K–32K, and both trigger math and `max_tokens` depend on the truth.

The web budget splits the window into `maxOutputTokens` (generation reservation, capped at
half the window; window/4 capped at 1024 for local models), a proportional system
overhead, and `inputBudgetTokens` for prompt + history.

## CLI (`kompile chat`, local/direct mode)

- `ConversationLedger` is the durable source of truth at
  `~/.kompile/conversations/<session>.context/journal.jsonl` (older `.context.json`
  snapshots migrate once and remain preserved). It retains immutable user,
  assistant, tool-call, and tool-result events. Compaction commits a versioned
  checkpoint (portable summary plus an optional provider-native payload) without
  deleting the raw audit events. Resume and provider switching project from this
  checkpoint instead of replaying the full pre-compaction transcript.
- Compaction boundaries are complete user exchanges. A tool call is never separated
  from its successful, denied, failed, missing-tool, or cancelled result.
- `CompactionService` budgets are **model-aware and proportional**: the loop refreshes
  `setMaxTokens(...)` each turn via `ModelContextResolver`; the trigger headroom is
  `min(window × configured threshold, window - effective reserve)` (floor 1024).
  Automatic reserve is `min(maxOutputTokens, window/2) + safety`; safety is
  `clamp(window/20, 256, 8192)`. The effective reserve cannot consume the final 1024
  input tokens. An explicit reserve override bypasses the half-window automatic cap.
  Thus a 1,050,000-token model with a 128,000-token output limit and the default 85%
  threshold triggers at **892,500**, not at 40K. Even malformed catalog output limits
  equal to the context window cannot collapse the trigger to 1024.
  The preserved-recent span is `min(40K, window/3)` — fixed budgets would make a 4K
  model permanently "needs compaction" while never actually shrinking anything.
- The trigger also honors the **provider-reported prompt tokens** of the previous call
  (`usage.prompt_tokens` / `input_tokens`), which see the system prompt and tool
  definitions the char/4 estimate cannot. Notices label before/after counts as
  **portable history estimates**, not full provider context or measured token savings.
  In particular, a short portable digest does not measure the size of an encrypted
  Responses checkpoint. Progress completion emits one notice, not a second divider.
- Automatic and provider-native compaction are armed only when the active ledger is
  larger than the preserved-recent span and a complete older exchange can be removed.
  A large fixed system prompt or tool schema therefore cannot repeatedly compact a tiny
  checkpoint. Provider-overflow recovery remains separate: after an actual rejection it
  may compact any completed older prefix that strictly reduces the retried request.
- **Turn start** (`AgenticChatLoop.maybeAutoCompactBeforeTurn`): count the complete
  pending request through Anthropic `count_tokens`, Responses `input_tokens`, or
  Gemini `countTokens` when supported. An exact count supersedes stale usage and
  estimates in either direction, and becomes the growth baseline for subsequent
  agentic steps (without counting the pending user entry twice). Pending attachments
  use the fallback because the count API does not include them. Gemini's text-only
  count adapter also declines structured tool or multimodal history rather than
  reporting an incomplete count as exact. Otherwise combine the last provider-reported
  usage with estimated growth. At the trigger, select
  provider-native compaction or
  a structured generic summary, with a deterministic digest as the final fallback.
- Vendor-native paths are explicit and capability-gated: Anthropic server compaction
  retains its readable compaction block; OpenAI Responses retains the exact encrypted
  compaction output plus a portable digest; OpenCode calls its native session summarize
  endpoint. Proxy providers such as GitHub Copilot never inherit beta support solely
  from a model-name prefix.
- Utility summaries/judges use isolated clients, so they never clear or lock the live
  chat history while a queued turn is trying to start. Compaction checkpoints use a
  ledger-version compare-and-set and remain unchanged on cancellation or provider error.
- **Mid-loop** (between agentic steps): only structure-safe in-place shrinking —
  `DirectLlmClient.compactToolHistory` collapses old `tool` message contents and clips
  old assistant text without touching ids or `tool_calls`, so the pairing providers
  validate stays intact.
- **Provider overflow recovery**: direct providers preserve a typed
  `CONTEXT_OVERFLOW` failure instead of flattening it into terminal text. When no
  assistant output, tool call, or provider-owned session mutation has started, the loop
  summarizes only completed older exchanges, keeps the rejected user/tool-result tail
  outside the checkpoint, rebuilds protocol-correct history, and retries that exact
  logical request once. The reminder block and attachments are reused verbatim; tools
  are never re-executed and pending tool results are supplied exactly once. A second
  rejection, partial response, side effect, irreducible request, or no-progress summary
  is reported without replay.
- Reprojection batches contiguous parallel tool calls into one assistant turn and
  contiguous Anthropic results into one user turn; the active model override selects
  the replay protocol, so proxy routes cannot rebuild Claude context as OpenAI context.
- Every generic or deterministic checkpoint must strictly reduce the active token
  estimate before it can commit. Context errors from Anthropic/Responses must not be
  mistaken for an unsupported native-compaction feature and resent unchanged.
- `/compact [focus]` uses an LLM summary plus preserved recent turns. If a single
  oversized exchange must be summarized in full, the checkpoint covers that entire
  exchange; the raw events remain durable but must not reappear in active history.
  `/status` shows `Context used: ~N tokens (P%)` against the effective budget.
- Manual `/compact` dispatches through the same turn lifecycle as a chat turn
  (`ChatMessageHandler.dispatchMaintenanceTurn`): the occupancy check re-examines
  `llmBusy` + `turnActive` + the registered dispatch owner under the dispatch lock
  (closing the window where a Ctrl+B-backgrounded turn owns the model/history while
  `llmBusy` is false), and the compaction runs on a registered, Escape-interruptible
  worker instead of the REPL reader thread — a slow or wedged summarization call can
  no longer deadlock the session.

## Reducing CLI context ingress

- HTTP request projection removes only exact repeated persistent-memory snapshots,
  retaining the newest copy. Known incident sections may precede persistent memory;
  incidents, retrieved evidence, reminders, changed snapshots and message authority
  remain intact. Projection never rewrites the audit ledger.
- Native Claude turns suppress an unchanged persistent-memory body only after that
  exact body was acknowledged by the same live process. The receipt is updated on
  acceptance, even if the answer later fails, and is invalidated on compaction,
  explicit resume, session replacement or process restart. Receipts are neither
  persisted nor reconstructed from summaries. Changed snapshots and all per-turn
  reminders, incidents and retrieval evidence still travel with the user message;
  the shared conservative parser leaves malformed/literal envelopes untouched.
- `/status context` provides a read-only local text inventory without provider calls:
  active portable history by entry type, last-assembled prefix size and schema text
  size. Character-based token estimates are explicitly labeled, unobserved values
  are not zeros, and attachment payloads/protocol overhead are excluded. Prefix and
  schemas may be stale after settings changes. Native session/tools remain opaque.
  These independent snapshots are **not** a per-request attribution or additive with
  provider usage; ordinary `/status` includes the same inventory.
- Both synchronous/poll stdio responses and socket MCP responses use the same
  `ToolResultReferenceCache.prepareForDelivery` budget. Large successes **and error
  diagnostics** receive a compact preview and a retrievable reference; `isError`
  and full output remain preserved, including completed background polls. Storage
  failure returns full output with an explicit cache failure, never a fictitious
  handle or a false success. This bounds output text, not the entire serialized
  payload: domain metadata retains its existing structured contract.
- MCP delivery now uses configurable soft text-ingress pressure. Defaults: 8,000
  output characters inline, 6,000 per fetch response, a 64,000-character allowance
  over a 300-second fixed time window, and a 2,048-character floor after that
  allowance is consumed. This is **not** a hard cumulative cap, provider-context
  measurement, or compaction reset: evidence remains accessible even under pressure.
  It reduces bulk ingestion; it cannot prevent arbitrary repeated paging.
  Reservations account for concurrent calls, settle against actual output text,
  and are scoped to an owner within a cache process (up to 128 tracked owners),
  not coordinated globally across processes or restarts. Titles, structured domain
  metadata and other protocol framing are outside this output-text allowance.
- Configure `~/.kompile/config/tool-output-budget.json` and/or
  `<project>/config/tool-output-budget.json`; supplied project keys override the
  global keys at cache initialization. Supported keys: `enabled` (boolean),
  `inlineChars`, `fetchChars` (2,048–16,000), `floorChars` (2,048 up to both limits),
  `windowChars` (16,384–4,000,000), and `windowSeconds` (1–86,400). Missing keys keep
  inherited defaults. An invalid file warns and leaves the prior layer intact.
  `{"enabled":false}` restores the legacy 16,000-character bounds. No configuration
  files are created automatically. Restart the owning CLI/MCP process after changes.
- Both fetch tools share that allowance with normal delivery, but fetched pages are
  never re-cached as new handles. Continuations retain source/matching-line offsets
  and partial-line character offsets as pages shrink. A batch shares one response
  allowance rather than multiplying it by request count. When all requested minimum
  slices cannot fit, the response identifies a deferred suffix and a 1-based
  `nextRequestIndex`; those requests were **not read** and must be resubmitted
  unchanged. Served sections retain their own continuation instructions. Use
  `pattern` or narrow ranges when sufficient rather than blindly fetching every page.
- Socket sessions expose `fetch_result` and `fetch_result_batch`. A restricted profile
  without `fetch_result` keeps output inline rather than issuing unreachable handles.
  Explicit fetch responses already have their own paging budget and are not re-cached.
- Native Claude's built-in tools execute outside this MCP boundary. When Kompile MCP
  recovery is configured, the native transport registers an SDK `PostToolUse` callback
  for `Read`, `Grep` and `Bash`. Claude's `updatedToolOutput` contract (introduced in
  Claude Code 2.1.121) replaces model-facing results **before delivery**, not later UI
  text. At 16,000 combined known-text characters, full fields are durably cached and
  replaced with at most 1,000-character excerpts plus `fetch_result` references;
  filename arrays retain complete paths and their full JSON representation is cached.
  The hook preserves execution status, unrelated metadata and permissions. Images, PDFs, structured Bash
  content, unknown output shapes and failure-hook events are left untouched. Failed
  durable publication retains the original output with a warning, not a bogus handle.
  Tool-free clients register no hooks; `-Dkompile.chat.claude.boundNativeOutput=false`
  disables this projection. This is a text-field ingress bound, not a universal token
  or serialized-payload cap. Mock-native control-protocol and durable recovery tests
  cover the integration; live vendor conformance and compaction-frequency improvements
  require separate qualification. Generic direct-route progressive tools still do not
  configure Claude's own tool discovery or compaction.
- The repository orchestration skill uses a compact preflight, task-triggered reference
  loading and same-context reuse of already-read documents. It asks for compact
  continuation notes (including exact test selectors and evidence references), not
  whole-transcript reconstruction. Existing versioned ledger checkpoints remain the
  durable continuation mechanism; this does not add another state service or silently
  rewrite installed provider skills.

Validation should compare the **first complete request after compaction**, context
added per tool step, and useful work between compactions. Provider input usage, cached
input and accumulated session usage have different meanings; cumulative tokens are
not current context occupancy. Missing usage-journal coverage must not be reported as
zero context cost, and native opaque context must not be labeled fully attributed.

## Tool-evidence projection (CLI direct HTTP)

The HELIX-1.0 comparison (source revision
[`b3e1b2bc`](https://github.com/SuperHelix77/HELIX-1.0/tree/b3e1b2bc0715e196f545c6f23230953ef8276943))
identified two useful gaps: invocation-aware duplicate-result receipts
([source](https://github.com/SuperHelix77/HELIX-1.0/blob/b3e1b2bc0715e196f545c6f23230953ef8276943/src/tool-result-aging.mjs))
and batched, cache-stable replacement decisions
([source](https://github.com/SuperHelix77/HELIX-1.0/blob/b3e1b2bc0715e196f545c6f23230953ef8276943/src/cache-stable-aging.mjs)).
Kompile already has durable originals, narrow range/search recovery, output-ingress
budgets, progressive tools, and versioned boundary-safe checkpoints. Those do not
need a second archive, proxy, memory service, or background model.

`ToolEvidenceProjection` now applies those two techniques to OpenAI Chat,
Responses, Anthropic Messages and Pi HTTP request builders:

- Without an owning readable archive, a result qualifies only when its tool name,
  argument representation and entire text exactly match an earlier call/result in the **same request**. Object arguments
  use their existing serialized representation; textual JSON is not normalized.
  Calls still execute normally: this is replay compression, not execution caching.
- In this archive-free mode, the first full matching output remains inline. Only a repeated result body becomes
  a receipt naming that original call, character count and SHA-256. All call IDs,
  arguments, result envelopes, positions and metadata stay intact. No recovery
  handle, archive availability, or previous request is needed to read the original.
- The newest **eight tool results**, including unsupported results, are protected.
  An older duplicate must be at least 1,024 characters and have a later model action.
  Flagged errors, recognized failure/security diagnostics, mixed/multimodal outputs,
  ambiguous call/result IDs and unknown structures are left alone. Native compaction
  items are evidence barriers: pre-checkpoint results cannot authorize replacements
  after a checkpoint. Opaque reasoning is never rewritten. Anthropic requests bypass
  evidence deduplication while native context edits are armed: server-side tool clearing
  could otherwise remove the only inline original independently of its duplicate receipt.
- Fresh replacements commit together after **eight candidates** or **16,384 saved
  characters** qualify. Existing decisions stay stable while their exact retention
  proof and eligibility still hold. A bounded, per-client map retains at most
  4,096 hashes, not output bodies. Clearing history or switching providers resets it.
  Active decisions are never evicted; at capacity, new candidates remain inline until
  stale decisions can be reclaimed. This is not a provider-cache guarantee. OpenAI
  error flags are tracked out of band (never added to wire messages), with a separate
  4,096-hash bound; overflow conservatively disables this pass until history is cleared.
- Projection never rewrites source history or the ledger. Token estimates, supported
  count APIs, streamed requests and Responses manual compaction use the same builder
  projection. Local estimation can select projection decisions and read existing
  archive files, but never writes archives or calls a provider. Disable this wire
  evidence pass with `-Dkompile.chat.deduplicateToolEvidence=false`; memory
  deduplication remains active.

### Archive-backed aging and deterministic log filtering

The owning loop now supplies `ToolResultStore` only while the current agent's actual
schemas expose `read`. This enables aging of **unique** older textual results as well
as repeated ones, using the same eight-result frontier, later-model-action guard and
cache-stable batching. `-Dkompile.chat.retireToolEvidence=false` returns to the
archive-free duplicate-only mode.

Every candidate of at least 2,048 characters requires a fresh, bounded read of its
existing saved result: matching tool/call header, successful status and exact body
(including the known host-added saved-path suffix on replay). Missing, changed,
symlinked or unrecognized files leave the full result inline. A resumed store restores
at most 4,096 recent file references from the existing index. No archive is created by
a count/estimate/stream builder, and no prior projection decision substitutes for proof.

The model receives an explicit partial-evidence receipt with character count, SHA-256,
head/tail excerpts and an exact `read` path. Recovery must never rerun a mutation.
For known command/log tools, `ToolLogShaper` can instead collapse terminal progress
rewrites, repeated/blank line runs and deep indented boilerplate when that saves at
least 1,024 characters. This is **not** a success judgment. Recognized warnings,
failures and security evidence are retained verbatim; source-reading tools are never
log-filtered. Archive-backed mode emits no duplicate pointers to inline originals that
it might itself retire. Missing recovery proof always restores the original body.

### Explicitly reviewed scope and program retirement

`/compact reviewed` (or `/compact reviewed prepare`) runs through the existing exclusive,
Escape-cancellable maintenance lifecycle. It prepares a digest-bound **complete older
prefix** and publishes a read-only candidate packet via the existing durable result
store. Inspect it with `fetch_result`; it contains full requested and actually executed
argument strings, even beyond the 500-character display header, plus raw-result handles
and authoritative outcome flags. Preparation changes no active context.

The returned template is applied explicitly from the user's command surface:

```text
/compact reviewed {"plan":"<prepared digest>","closed":true,"review":"Actual reviewed conclusions and continuing constraints","dependencies":[],"acknowledgedFailures":["<each failed call id>"]}
```

- A nonempty actual review, explicit closure/no continuing verbatim dependencies and
  **exact**, unique failure acknowledgments are required. Unknown JSON fields,
  duplicate keys and trailing JSON are rejected. A digest alone is not authorization.
- The plan binds session, ledger version, exact selected events, provider/model/endpoint
  and current tool schemas. Stale plans must be prepared again; approvals are not
  restored after restart or reused after commit.
- The newest user exchange is always verbatim, regardless of size. All retired calls
  must have unique, ordered, matching results and known terminal execution evidence.
  An existing portable summary is carried forward, not silently discarded.
- New result events carry optional `ToolOutcome`: real error flag, effective arguments,
  terminal-state evidence, exact raw-return archive handle/digest/character count,
  exit code where available and truncation status. Known synchronous operations can
  qualify; detached/async, aborted/timed-out or truncated outputs do not. Missing legacy
  outcome metadata means **unknown**, never success. Old scopes may still use ordinary
  `/compact`; this reviewed path does not fabricate historical outcome evidence.
- Provider-owned histories, opaque native/compaction/thinking items and routes without
  structured portable tool replay are refused (including Pi's current text-only replay).
  Older attachment-bearing scopes are also refused rather than claiming a packet of
  attachment metadata is exact binary recovery. Owned background work must finish,
  and `fetch_result` must be exposed to the agent.
- Exact candidate/final packets and raw returns use existing `DurableToolResultStore`
  storage, not a new daemon. Streaming verification checks payload SHA-256 **and every
  line-index offset/length** against UTF-8 content before publishing recovery receipts.
  A shared retention lease spans verification, publication and checkpoint commit.
- Only after archive verification, cancellation/version checks and strict estimated
  context reduction does the existing ledger CAS publish `reviewed-prefix-v1` and
  rebuild protocol-correct wire history. Raw audit events are never deleted. Failed
  publication/CAS/cancellation leaves the active projection unchanged; unreferenced
  evidence packets may remain for normal whole-session retention.

No retired program is replaced by an executable placeholder, automatically retried,
used as a new task queue or treated as execution authorization. This implements HELIX's
review/retention safeguards, not its disabled/offline program-execution optimizer.
Native Claude/OpenCode-owned histories are not intercepted by the HTTP evidence pass.
HELIX's reported token reduction came with fewer solved benchmark tasks; neither those
savings nor quality parity are claimed for Kompile. Tests establish recovery/protocol
invariants and synthetic payload reduction, not live token/cost savings.

## Web chat (kompile-app-main UnifiedChat)

Backend (`kompile-app-agent`):

- `ChatContextBudgetService` — per-lane budget resolution (table above).
- `ChatHistoryCompactor` — splits history into old head + verbatim recent tail
  (`min(4K, inputBudget/4)` tokens, always the last exchange), summarizes the head
  through **the same lane the chat uses** (staging `generate` for local models, a
  non-streaming `completeSync` for API agents, a bare one-shot subprocess via
  `executeChatSync` for CLI agents), and falls back to a deterministic role-labeled
  digest. The compacted history leads with a user/assistant summary exchange
  (`ChatHistoryCompactor.SUMMARY_MARKER`), mirroring the CLI's `/compact`.
- `AgentChatService` auto-compacts `request.chatHistory` before prompt assembly on
  **both lanes** and emits a `compaction` SSE event. The CLI lane now actually carries
  history: `buildPromptWithSources(..., embedHistory=true)` embeds the (compacted)
  history as a `## Conversation so far` block — previously `chatHistory` was silently
  ignored and every CLI-agent web turn was stateless. API lanes keep history as
  `messages[]` (never embedded, so it is not sent twice).
- `ApiAgentChatExecutor` clamps `max_tokens` to the model's output ceiling **and** the
  space left after the prompt; `KompileLocalModelService` registers `kompile-local`
  with a window-derived `maxTokens` instead of a hardcoded 4096.
- REST: `GET /api/agents/chat/context-budget?agentName=…` and
  `POST /api/agents/chat/compact` (`AgentChatCompactRequest` → summary + compacted
  history + token counts).

Frontend (UnifiedChat):

- Context-usage chip in the header (`~used/inputBudget (P%)`, warn ≥ 75%) fed by the
  budget endpoint; refreshed on agent select / session load.
- **Compact** button next to Clear (manual), auto-compact before send at
  `compactTriggerRatio` (default 0.8) of the input budget.
- The **visible transcript is never truncated** — compaction rewrites only the wire
  session (`agentSession.messages`) the next requests are built from.
- **Compaction is always visibly indicated in the transcript**: a full-width divider
  banner (`UnifiedMessage.kind === 'compaction'`, `data-testid="compaction-divider"`)
  renders at the point of compaction with the token math
  (`🗜️ Context compacted · ~45.0k → ~8.0k tokens · model (window)`), a note that the
  transcript above is unchanged (and whether the deterministic digest fallback was
  used), plus an expandable “View summary sent to the model” block. Client-initiated
  compacts and server-initiated ones (the `compaction` SSE event) both emit it; the
  banner persists in the saved session. Plain system notices are labeled “System”
  (monitor wake-ups keep their “Monitor” label via `kind: 'monitor'`).
- Fixed: `buildChatHistory` previously included the current user message in the
  history (`slice(-max-1, -1)` excluded only the assistant placeholder), so API-lane
  requests sent the current message twice.

## Tests

- CLI: `ConversationLedgerTest`, `CompactionServiceModelAwareTest`,
  `ProviderCompactionCapabilitiesTest`, `DirectLlmClientTokenCountTest`,
  `DirectLlmClientOAuthTest`, `DirectLlmClientConnectivityTest`,
  `AgenticChatLoopContextOverflowRecoveryTest`, `AgenticChatLoopHistoryReplayTest`,
  `OpenCodeServeClientTest`, and
  `ModelContextResolverTest` (`kompile-cli-main`).
- Catalog: `CliModelCatalogTest` (`kompile-app-core`) covers provider isolation,
  native aliases, slash-containing model IDs, and unrelated gateway metadata.
- Shared E2E contracts: `DirectLlmClientHistoryTest`,
  `ConversationSummarizerTest`, and `ForceCompactResultTest`.
- App: `ChatContextBudgetServiceTest`, `ChatHistoryCompactorTest`
  (`kompile-app-agent`).
- Frontend: `unified-chat-compaction.spec.ts` (divider rendering, no chat bubble for
  compaction messages, expandable summary, digest-fallback note, server SSE event,
  System/Monitor labels). Note: `tsconfig.spec.json` quarantines the dark-mode,
  token-usage, and context-panel unified-chat specs — the live suite is
  compaction + rag + message-actions.
