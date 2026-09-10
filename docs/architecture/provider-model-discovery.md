# Provider model discovery

This document describes provider-owned dynamic model discovery, typed failure semantics, credential-isolated short-lived caching, explicit live refresh, and the provider matrix implemented by `ChatProvider.modelDiscoveryStrategy()`.

## Capability precedence

Thinking controls are resolved in this order:

1. Live provider or native-runtime model metadata.
2. The selected provider's classpath resource under
   `ai/kompile/cli/main/chat/providers/<provider>.json`.
3. No selector. Unknown models never inherit another model family's controls.

Live metadata always wins. Provider resources may also declare a narrowly scoped
`wireValues` map to migrate obsolete persisted values before request serialization;
those aliases are never offered as selectable capabilities. OpenAI reasoning effort
choices end at `max`. Direct-provider endpoint, response-shape, identifier, and
pagination contracts are loaded from
`ai/kompile/cli/main/chat/model-catalogs.json`; that resource contains no model
ids. OpenAI subscription discovery binds the selected Kompile OAuth credential
to the installed Codex app-server and calls its authoritative `model/list`
method. This avoids pinning the catalog to a stale HTTP `client_version`; the
Codex CLI must be installed, but it does not need a separate login. OpenCode is
an explicitly selected native-agent provider and uses `opencode models --verbose`. Anthropic reads
`capabilities.effort`, and OpenRouter reads
`reasoning.supported_efforts`.

The interactive setup and picker always force a provider request. Provider,
authentication, timeout, pagination, and schema failures return no live models
and a typed error. They never substitute stale rows or the currently
configured model as if they were live. Non-interactive callers may reuse only
a fresh, credential-scoped cache entry and receive an explicit cache
provenance message; there is no stale-cache fallback.

### Last known good catalog fallback

Every usable live response is recorded per provider (model ids, base URL, and
time) in `~/.kompile/cache/model-catalogs.json`. When the live request cannot
populate a list, the interactive picker and setup wizard:

1. retry transport-grade failures (`TIMEOUT`, `UNAVAILABLE`, `RATE_LIMITED`)
   once automatically;
2. otherwise offer the recorded last known good catalog with its age and the
   live failure reason, visibly labeled (never rendered as live results);
3. and still accept a manually typed model id.

An authoritative live list that simply does not contain a typed `/model <id>`
is still rejected; only transport failures unlock recorded-catalog matches.
The recorded store never blocks a live switch — recording is best-effort.

Built-in credentials are origin-bound to the provider or OAuth base URL. An
endpoint on another origin must use the explicit `custom` provider. Providers
without credentials, such as a remote Ollama runtime, may use their configured
endpoint directly.

## Documented fallback marker

Every provider resource must contain:

- `"metadataSource": "DOCUMENTED_FALLBACK"`
- an upstream `source.url`
- `source.verifiedAt`
- a notice containing the literal `DOCUMENTED_FALLBACK`
- optional `wireValues` aliases for migrating obsolete persisted values

When a fallback supplies controls, the setup and picker label contains the
classpath resource and the interactive wizard prints the resource-to-source
pointer. This makes documented data visibly distinct from a live capability
response.

Provider resources with no safe static transport contract contain an empty
`profiles` array. They document the authoritative capability source without
inventing controls that the request serializer cannot honor.

## Validation

Focused tests cover endpoint and pagination behavior, direct OAuth Codex and
native OpenCode catalogs, provider-specific response shapes and identifier
fields, credential origin binding, exact wire values, loud outage/schema
failures, defaults, and unsupported-model behavior.