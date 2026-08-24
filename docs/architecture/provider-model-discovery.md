# Provider model discovery

This document describes provider-owned dynamic model discovery, typed failure semantics, stale-cache fallback, credential-isolated caching, explicit refresh, and the provider matrix implemented by `ChatProvider.modelDiscoveryStrategy()`.

## Capability precedence

Thinking controls are resolved in this order:

1. Live provider or native-runtime model metadata.
2. The selected provider's classpath resource under
   `ai/kompile/cli/main/chat/providers/<provider>.json`.
3. No selector. Unknown models never inherit another model family's controls.

Live metadata always wins. Codex binds the selected Kompile OAuth credential
to the official app-server with the external `chatgptAuthTokens` flow before
calling `model/list`; it never relies on a separate Codex CLI login. OpenCode
uses `opencode models --verbose`, Anthropic reads
`capabilities.effort`, and OpenRouter reads
`reasoning.supported_efforts`.

Codex discovery runs with an isolated temporary `CODEX_HOME`. Upstream
`model/list` uses `OnlineIfUncached`, so isolation prevents a stale user-level
Codex cache from satisfying an explicit Kompile refresh without contacting the
provider. The selected external OAuth token is process-local and the temporary
home is removed when discovery finishes.

Codex also logs and swallows upstream catalog refresh failures before returning
its bundled catalog. Kompile recognizes that diagnostic (and any unexpected
cache hit in the isolated home), rejects the bundled rows, and reports
`UNAVAILABLE` instead of labeling them live.

Codex discovery results identify the resolved app-server runtime. Protocol,
authentication, timeout, process-exit, and sanitized stderr diagnostics remain
attached to typed failures so an old or broken Windows npm shim cannot look
like a successful static model fallback.

## Documented fallback marker

Every provider resource must contain:

- `"metadataSource": "DOCUMENTED_FALLBACK"`
- an upstream `source.url`
- `source.verifiedAt`
- a notice containing the literal `DOCUMENTED_FALLBACK`

When a fallback supplies controls, the setup and picker label contains the
classpath resource and the interactive wizard prints the resource-to-source
pointer. This makes documented data visibly distinct from a live capability
response.

Provider resources with no safe static transport contract contain an empty
`profiles` array. They document the authoritative capability source without
inventing controls that the request serializer cannot honor.

## Validation

Focused tests cover endpoint and pagination behavior, native Codex/OpenCode
catalogs, provider response shapes, fallback provenance, exact wire values,
defaults, and unsupported-model behavior.