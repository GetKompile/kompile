# `/usage`: vendor account usage

Run `/usage` in CLI chat (including managed native passthrough) or web chat.
It reads the **currently selected account**, including web session credential pins
and provider overrides. It does not send a prompt, consume model tokens, or add
its output to later model context. `/stats` remains session usage.

| Route | Vendor-reported figures |
| --- | --- |
| OpenAI subscription / Codex | Primary/secondary allowance windows (usually 5-hour and 7-day), reset times, code-review allowance and extra-usage credits when returned |
| Claude Code subscription | 5-hour, 7-day and model-specific allowances, resets, extra-usage credits when returned |
| Z.AI coding plan | All returned token/credit quotas, their periods and resets, and MCP/search quota |
| DeepSeek API | Available, granted and topped-up balances with currency |
| OpenRouter API | Selected key's remaining budget and total/daily/weekly/monthly spend; a key budget is **not** the account credit balance |
| OpenAI / Anthropic inference API keys | Remaining credit is not exposed to inference keys; `/usage` explains this and links to billing/usage |
| Z.AI pay-as-you-go; other remote providers | Explicit unavailable/unsupported guidance, not a fabricated balance or subscription allowance |

Only fields actually returned by the vendor are shown. Missing values are not
zero/unlimited. Reset timestamps are UTC. Vendor failures, permission errors and
rate limits are reported without dumping response bodies or credentials. Lookups
use fixed vendor HTTPS endpoints, never follow redirects, have a 10-second request
and 5-second connection timeout, cap response size, and do not retry. Custom/proxy
endpoints are not queried using their credentials against the official vendor.

Claude Code and native Codex login remain owned by their CLIs. For this read-only
usage lookup only, Kompile reads their existing file-store access token (respecting
`CLAUDE_CONFIG_DIR` / `CODEX_HOME`). It never imports, persists or refreshes that
credential, and does not fall back to an API key or another account. Expired logins
must be renewed in the native CLI. OS-keychain-only logins are currently unavailable
for this lookup. Web lookups run on the host running the CLI harness, not in the
browser; no credential is sent to the browser.

The subscription/quota endpoints below are vendor-owned but not stable public
billing APIs. If their schema changes, `/usage` reports unavailable rather than
estimating a quota. No live account credentials are needed for the fixture tests.

## Endpoint/schema references

- Codex: `https://chatgpt.com/backend-api/wham/usage`, bearer OAuth token plus
  selected `ChatGPT-Account-Id`; [reference client](https://github.com/vbgate/opencode-mystatus/blob/main/plugin/lib/openai.ts).
- Claude: `https://api.anthropic.com/api/oauth/usage`, bearer OAuth token and
  `anthropic-beta: oauth-2025-04-20`; [reference client](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Claude/ClaudeOAuth/ClaudeOAuthUsageFetcher.swift).
- Z.AI: `https://api.z.ai/api/monitor/usage/quota/limit`;
  [quota period mapping](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Resources/Plugins/zai.js).
  Credit quotas are vendor units, not assumed USD.
- DeepSeek: [Get User Balance](https://api-docs.deepseek.com/api/get-user-balance).
- OpenRouter: [Current key](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key).
  Account-wide [credits](https://openrouter.ai/docs/api/api-reference/credits/get-credits)
  require a separate management key; `/usage` does not substitute another credential.
