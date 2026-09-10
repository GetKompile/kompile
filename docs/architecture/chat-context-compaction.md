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
  `~/.kompile/conversations/<session>.context.json`. It retains immutable user,
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
