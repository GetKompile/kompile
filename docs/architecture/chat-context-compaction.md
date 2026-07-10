# Chat context compaction

Both chat surfaces — the `kompile chat` CLI and the kompile-app-main web chat window —
compact conversation history against the **real context window of whatever the chat is
talking to**, instead of assuming a fixed budget.

## Context-window resolution (which metadata wins)

| Chat lane | Authoritative source | Runtime query |
|---|---|---|
| CLI local mode, catalog-known model (claude/gpt/gemini/deepseek/ollama…) | `CliModelCatalog` (live models.dev data on disk) → `ModelContextWindows` static table | `ModelContextWindows.getContextWindow(model)` |
| CLI local mode, unknown model on a loopback endpoint (kompile-staged GGUF behind the staging OpenAI facade) | Serving origin's `GET /api/llm/status` → `maxContextLength` | `ModelContextResolver` (30 s cache, 2 s probe timeout) |
| Web chat, CLI agents + hosted API agents | `ModelCapabilityService` → registered `LlmProvider`s → `ModelContextWindows` | `ChatContextBudgetService.resolve(agent)` → `source="catalog"` |
| Web chat, `kompile-local` / `local-staging` / any `local/…` model | Live `GET /api/llm/status maxContextLength` (serving truth after KV bucketing) → staging registry `metadata.max_sequence_length` → conservative 4096 default | `ChatContextBudgetService.resolve(agent)` → `source="staging-live" / "staging-registry" / "staging-default"` |

A staged local model must **never** inherit the 128K catalog default: a GGUF is
typically 4K–32K, and both trigger math and `max_tokens` depend on the truth.

The budget splits the window into `maxOutputTokens` (generation reservation, capped at
half the window; window/4 capped at 1024 for local models), a proportional system
overhead, and `inputBudgetTokens` for prompt + history.

## CLI (`kompile chat`, local/direct mode)

- `CompactionService` budgets are **model-aware and proportional**: the loop refreshes
  `setMaxTokens(...)` each turn via `ModelContextResolver`; the trigger headroom is
  `min(20K, window/8)` and the preserved-recent span `min(40K, window/3)` — the old
  fixed 20K/40K constants made a 4K model permanently "needs compaction" while never
  actually shrinking anything.
- The trigger also honors the **provider-reported prompt tokens** of the previous call
  (`usage.prompt_tokens` / `input_tokens`), which see the system prompt and tool
  definitions the char/4 estimate cannot.
- **Turn start** (`AgenticChatLoop.maybeAutoCompactBeforeTurn`): when near the budget,
  run the same LLM summarization as `/compact` (`forceCompact`); if summarization
  fails, fall back to deterministic pruning plus a digest rewrite of the wire history.
  This fixes the old auto-compact, which only pruned the *tracking* list and never
  changed what `DirectLlmClient` actually sent.
- **Mid-loop** (between agentic steps): only structure-safe in-place shrinking —
  `DirectLlmClient.compactToolHistory` collapses old `tool` message contents and clips
  old assistant text without touching ids or `tool_calls`, so the pairing providers
  validate stays intact.
- `/compact [focus]` (manual, LLM summary + preserved recent turns) is unchanged;
  `/status` now shows `Context used: ~N tokens (P%)` against the effective budget.

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

- CLI: `CompactionServiceModelAwareTest`, `ModelContextResolverTest`
  (`kompile-cli-main`).
- App: `ChatContextBudgetServiceTest`, `ChatHistoryCompactorTest`
  (`kompile-app-agent`).
- Frontend: `unified-chat-compaction.spec.ts` (divider rendering, no chat bubble for
  compaction messages, expandable summary, digest-fallback note, server SSE event,
  System/Monitor labels). Note: `tsconfig.spec.json` quarantines the dark-mode,
  token-usage, and context-panel unified-chat specs — the live suite is
  compaction + rag + message-actions.
