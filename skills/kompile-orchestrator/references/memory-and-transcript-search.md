# Memory and transcript search

Use these tools for continuity and prior evidence, not as substitutes for current source-of-truth validation. Inspect live schemas before relying on the actions or fields below.

## Choose the right source

| Need | Tool |
|---|---|
| Durable project notes or preferences | `memory` |
| Relevance-ranked recall across indexed memories | `semantic_memory` |
| Exact prior conversation wording or chronology | `transcript_search` |
| Imported/session continuation workflows | `conversation_import` or `resume` |
| Prior tool invocations and operational patterns | `tool_call_catalog` |
| Indexed documents and external artifacts | `rag_search` |

Search before creating new memory. Prefer project scope unless a fact genuinely applies across projects.

## `memory`: persistent knowledge

The live `memory` tool exposes three conceptual layers:

1. **Flat files** for detailed free-form notes and logs. Typical actions: `list`, `read`, `search`, `append`, and `write`; use `file`, `content`, and `scope`.
2. **Typed memories** for structured, searchable facts. Typical actions: `save`, `recall`, `types`, and `forget`; use `name`, `memoryType`, `description`, `content`, and `scope`. Common types are `user`, `feedback`, `project`, and `reference`.
3. **Memory graph** for durable entities, observations, and relationships. Typical actions include `create_entity`, `create_relation`, `add_observation`, `search_nodes`, `open_nodes`, and `read_graph`, plus explicit delete actions.

Examples:

```text
memory({action: "recall", query: "release validation rules", scope: "project"})

memory({
  action: "save",
  name: "release-validation",
  memoryType: "feedback",
  description: "Required release validation",
  content: "Rule, rationale, source, and date verified",
  scope: "project"
})
```

Use `append` for chronological notes. Use `write` only when replacement is intentional and the existing memory was read first. Treat `forget` and graph deletion as destructive mutations requiring exact targets and clear authorization.

## `semantic_memory`: relevance-ranked recall

Use `semantic_memory` when the wording is unknown or conceptual similarity matters. Its live schema may expose `query`, `top_k`, `threshold`, and `role`, with actions for searching or saving depending on the server version.

```text
semantic_memory({
  action: "search",
  query: "constraints for delegating implementation work",
  top_k: 5,
  threshold: 0.35
})
```

Start with a modest result count. Lower thresholds increase recall but also noise. Verify returned facts against current files, configuration, user instructions, or authoritative external sources.

## `transcript_search`: exact conversation evidence

Use transcripts to recover prior decisions, commands, failures, unresolved questions, agent reports, or exact wording.

- `list`: enumerate saved sessions, optionally filtered by agent.
- `recent`: inspect recent sessions using `count`.
- `read`: retrieve a known `session_id`.
- `search`: grep history using `pattern` or `query`.

Useful search controls include `literal`, `case_sensitive`, `agent`, `session_id`, `before`, `after`, `context`, `max_results`, `invert`, `files_with_matches`, and `line_numbers`.

```text
transcript_search({
  action: "search",
  pattern: "role.*dispatch",
  case_sensitive: false,
  context: 4,
  max_results: 30,
  line_numbers: true
})
```

Use `literal: true` for exact strings and regex mode for variants. Start narrowly, then widen by agent or session only when needed. Cite session IDs and relevant transcript locations when a prior conversation materially supports a decision.

## Workflow integration

1. During planning, decide whether continuity could change scope, constraints, or implementation.
2. Search project memory and relevant transcripts before repeating investigation.
3. Separate retrieved observations from conclusions.
4. Verify time-sensitive or technical claims against live primary evidence.
5. Record which memories or sessions influenced the plan.
6. Persist only durable, reusable outcomes after validation; do not save raw speculation as fact.
7. Update or remove obsolete memory when the task proves it wrong and mutation is authorized.

## Safety and quality rules

- Never store secrets, credentials, private keys, tokens, or unnecessary personal data.
- Use global scope only for truly cross-project preferences or rules.
- Include provenance and a verification date for facts likely to change.
- Do not treat transcript consensus as correctness; transcripts contain abandoned ideas and agent errors.
- Do not overwrite or delete memory without reading and resolving the exact target.
- Do not persist temporary task state when `todowrite`, logs, or the current transcript are the proper home.
- When memory conflicts with current user instructions or project files, current authoritative evidence wins.
