# CLI incidents (warn-only)

Incidents capture one failure mechanism: symptom, cause (when known), invariant,
applicable paths/symbols, checks and links to supporting history. They complement
ordinary memory, historical diffs and test milestones; they do not replace them.

## Storage and lifecycle

The `incident` MCP tool stores records under the session working directory:
`.kompile/incidents/INC-<id>.json`. No server, embeddings or Git operations are used.
Reads never create the directory. Updates require `expected_revision` and serialize
writers with a file lock; each old revision is preserved under
`.kompile/incidents/history/<id>/<revision>.json`. Current records are atomically
replaced. Use `get` with `revision` to inspect old records.

Separate fields describe diagnosis (`verification`: suspected/confirmed), lifecycle
(`status`: open/fixed/invalidated/superseded), and advice (`guardrail.mode`: off/warn).
Severity does not grant permissions, block edits or promote advice into policy.
New records start open. Confirmed records require evidence references. `resolve`
requires confirmed diagnosis, named checks and evidence; resolution is **reported**,
not an independently verified passing test receipt. Fixed incidents stay eligible
for recurrence prevention. Invalidated, superseded and off records are excluded from
default search/context but remain available through get/list.

## Tool usage

Activate the `memory` or `workflow` tool group. The tool is also included in the
stdio core/full profiles and the socket tool surface. It is not added to read-only
stdio profiles because it supports mutations.

```json
{
  "action": "add",
  "record": {
    "title": "Judge infrastructure failures block ordinary work",
    "symptom": "Agent execution stops when the judge backend is unavailable.",
    "invariant": "Infrastructure errors fail open; valid policy violations still block.",
    "scope": {
      "paths": ["src/**/enforcer/*.java"],
      "symbols": ["EnforcerToolCallDecision.parse"],
      "tags": ["judge", "fallback", "fail-open"]
    }
  }
}
```

The tool returns the generated id and revision. Other calls:

```json
{"action":"get","id":"INC-<id>"}
{"action":"search","query":"judge fallback"}
{"action":"search","file_path":"src/enforcer/Guard.java"}
{"action":"search","symbol":"EnforcerToolCallDecision.parse"}
{"action":"list","limit":20}
```

Update using `record` containing only changed top-level fields. Provided scope,
checks and guardrail objects replace their previous values; evidence references
accumulate without removing existing provenance. Older versions remain in history.

```json
{
  "action":"update",
  "id":"INC-<id>",
  "expected_revision":1,
  "record":{
    "verification":"confirmed",
    "cause":"Backend errors are interpreted as blocking policy verdicts.",
    "checks":[{"type":"test","module":"cli","selector":"GuardFallbackTest"}],
    "evidence":[{"type":"transcript","ref":"session-id:tool-call-id"}]
  }
}
```

Use `resolve` after a fix, linking its validation evidence. References are not
fetched, executed or verified by this tool. Never label an old agent claim as a
runner-verified result. Repeated occurrences can add evidence to the existing
incident rather than create disconnected diagnoses.

```json
{
  "action":"resolve",
  "id":"INC-<id>",
  "expected_revision":2,
  "record":{"evidence":[{"type":"validation","ref":"report-or-test-receipt"}]}
}
```

Use `update` with status `invalidated` for a disproven diagnosis, or `supersede`
with `replacement_id` and `expected_revision` when another incident replaces it.
There is intentionally no delete action or automatic incident seeding/migration.

## Retrieval, controls and limits

- Search is lexical plus exact scoped symbol and project-relative glob matching.
  Supported globs: `*` (within a directory), `?`, `**`, and `**/` (zero or more directories).
  Matches report why they were selected. Similarity is not a regression verdict.
- CLI chat memory includes up to three relevant lexical matches in a small leading
  block within its existing memory budget. Existing memory/persistent-memory switches
  disable that automatic block. `file_context` includes path matches, even when the
  code index is unavailable. This explicit read still works when chat memory is off.
- External MCP agents must request incidents or file context; they do not automatically
  receive the CLI chat memory block. No every-edit interceptor is introduced.
- Incident text is untrusted historical evidence, not instructions. Invariants and
  suggested checks are advisory. The tool never executes a command or benchmark.
- Reads use `read` permission. Mutations require `memory` and `write` permission and
  remain subject to workflow checkpoints. Incident bookkeeping does not create code
  validation debt. A stale revision is rejected; get current state and reconsider.
- Scan at most 500 current records, return at most 100 matches, and read at most 64 KiB
  per record. Context is capped at 2,000 characters. Partial scans and malformed records
  are surfaced; missing matches or evidence are not a declaration of safety.
- Symlinked store directories/files and unsafe ids are rejected. Record reads/writes
  use securely opened, no-follow directory handles and relative atomic rename, so
  replacing an opened ancestor does not redirect record I/O. Filesystems without
  SecureDirectoryStream support fail explicitly (chat still continues without incident
  context). Directory setup assumes a trusted project root; no filesystem sandbox or
  automatic rollback claim is made.

For performance incidents, name the workload, backend/environment and benchmark
profile in scope/checks/evidence. Prefer a cheap structural reproducer when it captures
the mechanism (for example RPC count) and keep benchmark samples as linked evidence.
Automatic check enforcement and semantic matching are future extensions, not current
features.
