# Kompile CLI Code Indexing & Project Management Audit — LSP Gap Analysis

**Date:** 2026-07-02
**Scope:** `kompile-cli` code indexing (`local_code_index`, `code_search`, `code_graph`), project management (`register_project`, `project_config`), and the edit path (`edit`, `patch`, `edit_coordinator`) — evaluated against the goal: *build and analyze codebases using LSPs, and update codebases without grep*.
**Method:** read-only audit of source + tool schemas; no code changed.

---

## 1. Verdict

The indexing subsystem is a well-engineered **lexical** index with an **LSP-shaped storage schema but zero semantic analysis**. Every "semantic" feature (call graphs, impact analysis, Spring DI resolution, spath addressing) is built on **regex extraction + SQL suffix matching**, not resolved types. There is **no LSP presence anywhere in the repo** (no lsp4j, no LanguageServer references), **no symbol-aware editing** (all edits are text-anchored), and **no build execution** (build commands are recorded, never run).

The good news: the SQLite schema (`entities_meta` + `relations`), the incremental fingerprint/watcher machinery, and the ranking pipeline are exactly the substrate an LSP integration needs. LSP results can be written into the existing tables, upgrading precision without rearchitecting storage or search.

---

## 2. Current architecture map

### 2.1 Tool surface (MCP, served by native `kompile-cli-main`)

| Tool | Execution | Notes |
|---|---|---|
| `local_code_index` | Always local | 25 actions (LocalCodeIndexTool.java:273-304) |
| `code_search` | Backend REST `/api/code-indexer/*` when up, else local SQLite | `ranked_search`/`blended_search`/`signatures`/`health`/`routing` are **local-only even when backend is up** (CodeSearchTool.java:136-149) |
| `code_graph` | Backend REST `/api/code-indexer/graph/*` when up, else local | `remove_directory` has **no local fallback** (CodeGraphTool.java:863) |
| `unified_code_search` | Pure dispatcher | Routes to the three above (UnifiedCodeSearchTool.java:227-248) |

MCP profiles (`minimal`/`explore`/`core`/`full`, McpStdioCommand.java:75-103); code-intelligence tools ship in `explore`+`full`. Schemas served COMPACT by default.

### 2.2 Local index internals (`ai.kompile.cli.main.codeindex`, 25 classes)

- **Storage:** SQLite (xerial 3.45.1.0), WAL, FTS5 (`unicode61`) at `~/.kompile/code-index/<projectId>/index.db`; per-file JSON shards (`files/<sha256>.json`) are the durable source of truth, SQLite is a rebuildable cache (IndexFileStore.java:61-95, atomic temp+rename writes).
- **Schema:** `entities_meta` (fqn, entity_type, signature, doc_comment, visibility, start/end_line, inherited_from, implements_list, annotations), `relations` (source_fqn, target_name, target_fqn, relation_type, file_path, line), `entities_fts`, plus `pagerank`, `clones`, `fragments`, `cochanges`, `file_status`.
- **Incrementality:** fingerprint fast path (mtime+size, then sha256) (LocalCodeIndexer.java:245-265); `IndexFileWatcher` (WatchService, 500ms debounce / 5s max coalesce); `IndexLockManager` = in-process RW lock + cross-process `FileChannel.tryLock` on `project.lock`.
- **Parsing: 100% regex.** Patterns at LocalCodeIndexer.java:116-171 for Java (classes/methods/fields/Spring annotations), Python (indent heuristic for methods), Go, Rust, TS/JS, C/C++. 30+ extensions; config formats produce FILE entities only. **Files >1MB silently skipped** (LocalCodeIndexer.java:1326).
- **FQN resolution:** package + name within one file; cross-file via `ensureConnectivity()` SQL suffix match `LIKE '%.' || target_name` (IndexDatabase.java:657) — **false links on common simple names**.
- **Search pipeline:** CodeTokenizer (camelCase split) → IntentClassifier (7 keyword intents → weight profiles) → BlendedCodeSearch (SYMBOL_PATH/WILDCARD/BROAD/NATURAL router) → CodeRelevanceRanker (FTS5 + 1-2 hop graph boosts + git recency/churn/author + PageRank + co-change + path-tier penalties). **No embeddings, purely lexical.**
- **Analyses:** ImpactAnalyzer (reverse-dep BFS, depth cap 20), CloneDetector (MinHash 128, **O(n²), no LSH**), CoChangeAnalyzer (git log pairs), GitSignals, ComplexityClassifier (FAST/BALANCED/POWERFUL routing tiers), UnusedExportDetector, IndexHealthScorer (staleness/coverage/density penalties, A-D grades).
- **Spath DSL:** path-addressing (`pkg.**.Class.member`, `[file.java]` selectors, `/imports` properties) → SQL translation (SpathParser/SpathResolver). **Splan** parser exists but is **not wired to any MCP action**.

### 2.3 Project management

- `register_project` = **endpoint binding, not a project catalogue**: persists `{endpointUrl, projectId, directory, registeredAt, active, sessionId, source}` to `<projectDir>/.kompile/registration.json` + `~/.kompile/registrations/<id>.json` (ProjectRegistration.java:80-103). **No language or build-command fields.** Actions: register/status/discover/sync/activate.
- "Active project" exists **server-side only** (`CodeProject.isActive`, CodeProjectController.java:247-261); the CLI never consults `registration.json` to route searches.
- **`project_id` defaults to the literal `"default"`** in every search tool (CodeSearchTool.java:131, CodeGraphTool.java:158) with no resolution from registration → omitting it silently searches/creates the wrong index.
- `project_config` manages agent-mandate files (AGENTS.md, .mcp.json, CLAUDE.md, .cursorrules, system prompts) — not project data.
- **No cross-project search** (one SQLite DB per project, no fan-out). Server-side `/api/code-projects` is a separate JPA world (`CodeEntity`/`CodeRelation` in kompile-code-indexer) — a **second regex indexer** duplicating the CLI one.
- **Builds:** `test_milestone` stores `buildCommand`/`testCommand` but its only ProcessBuilder use is `git` (TestMilestoneTool.java:1040) — it records outcomes, never executes. `maven-invoker` is in the pom but used once, in `WebCommand.java:758` (web launcher). **Building a codebase today = raw `bash`.**

### 2.4 Edit path

- `edit` = exact-string replace + trimmed-line fallback (EditTool.java:126-157). `patch` = system `patch -u` with backup/restore (PatchTool.java:107). `local_code_index action=replace` = `Pattern.replaceAll` on raw content, dry-run default, re-indexes after (not scope-aware). `usages` = word-boundary regex scan with string-heuristic usage kinds.
- `edit_coordinator` = advisory multi-agent file locks (register_edit/release_edit).
- **No post-edit verification of any kind** — no diagnostics, no compile check, no type check.

### 2.5 Docs drift (docs/concepts/code-projects.md)

`code-index watch` and `configure code-index` documented but absent from the MCP surface; entity types listed that the schema doesn't expose (`TYPE_ALIAS`, `RATIONALE`); the Picocli `graph callers/dossier/tests-for/...` subcommands (CodeIndexGraphCommand.java) are not MCP-exposed; the strongest features (`blended_search`, `ranked_search`, `impact`, offline-first) are undocumented.

---

## 3. What to keep (genuinely good)

1. **Storage schema is LSP-ready.** `entities_meta` + `relations` are the right shape for documentSymbol/references results; shards+SQLite rebuild cleanly; WAL + file locks already handle CLI+MCP concurrency.
2. **Incrementality is solid** — fingerprints, watcher, atomic shard writes, staleness warnings after search (LocalCodeIndexTool.java:1448).
3. **The ranking blend is orthogonal to symbol quality** — git signals, PageRank, path-tier penalties, intent weights all survive an LSP upgrade unchanged.
4. **Token-budget discipline** (`max_tokens` on every action, signature compression, `fetch_result` handles) fits agent consumption.
5. **Offline-first duality** — backend-down fallback works (verified fallback chains).

---

## 4. Gap analysis vs the goal

### 4.1 Precision defects (all consequences of regex)

| Defect | Impact |
|---|---|
| `CALL_BARE` `\b(\w+)\s*\(` matches calls in comments/strings/any language | False CALLS edges → poisoned callers/trace/impact/PageRank |
| Cross-file FQN by SQL suffix match | False links on `Config`/`Handler`/`Service`-style names |
| Method body extent = "until next entity's start_line" | Wrong for nested classes, lambdas, anonymous types |
| No type information | Overloads, generics, receiver types, interface params invisible |
| Python method-vs-function by 4-space indent | Wrong on non-standard indentation |
| C++ templates/macros, TS arrow functions/decorators, Go interfaces/receivers, Rust `use`/macros | Missed or misclassified |
| >1MB files silently skipped; clones O(n²) | Coverage holes; scale cliff |

### 4.2 Missing capabilities for the stated goal

| Goal | Today | Gap |
|---|---|---|
| Analyze with LSP | Regex index | No LSP client, no server lifecycle, no lsp4j/JSON-RPC anywhere |
| Find references precisely | `usages` = word-boundary text scan | No semantic references/definition/hover |
| Update without grep | `edit` (unique string), `replace` (regex) | No rename, no WorkspaceEdit application, no code actions |
| Know the edit compiled | Nothing | No diagnostics loop after edits |
| Build codebases | Raw bash | No build runner; registry has no build commands; test_milestone can't execute |
| Multi-codebase | Per-project SQLite, `project_id="default"` footgun | No registration→search routing, no cross-project fan-out |

---

## 5. Recommended path: LSP as a precision layer over the existing index

### Phase 0 — quick wins (no LSP, days)
1. **Fix the `project_id` footgun:** resolve from `<cwd>/.kompile/registration.json` or nearest indexed ancestor; warn instead of silently using `"default"`.
2. Expose `watch` as an MCP action (IndexFileWatcher exists, only Picocli reaches it); fix docs drift; expose the useful Picocli graph subcommands (`callers`, `tests-for`, `dossier`) as `code_graph` actions.
3. Log >1MB skips; add LSH bucketing or a pair cap to CloneDetector.
4. Optional: cross-project search = fan-out over `listProjects()` + result merge (the ranker already normalizes scores).

### Phase 1 — LSP client infrastructure (the keystone)
- **`LspServerManager`** in kompile-cli-main: one external language-server subprocess per (project, language), lazy-started, health-checked, idle-shutdown. Reuse `BackgroundProcessManager` lifecycle/tracking patterns, but LSP needs **duplex stdio** (BPM only file-captures output) — a dedicated duplex pipe pair per server.
- **Hand-rolled minimal JSON-RPC client over Jackson, NOT lsp4j.** Rationale: the CLI ships as a GraalVM native image; lsp4j is gson+reflection-heavy (native config burden), while Jackson is already native-configured here. Precedent: Splan deliberately avoided the ANTLR runtime for the same reason (SplanPlanParser docstring). Needed surface is small: `initialize`/`initialized`/`shutdown`, `didOpen`/`didChange`/`didClose`, `documentSymbol`, `definition`, `references`, `hover`, `prepareRename`/`rename`, `workspace/symbol`, `publishDiagnostics` (+ pull diagnostics), `codeAction`, Content-Length framing.
- **Server registry config** `~/.kompile/lsp-servers.json` (pattern: cli-agents.json): language → `{command, args, initOptions}`. Auto-select servers from `languageCounts` already collected in index metadata (RegisterProjectTool.collectIndexMetadata). Suggested first servers: **jdt.ls** (Java — kompile itself; external JVM process is fine even though the CLI is native), **typescript-language-server** (the Angular UI), then gopls / rust-analyzer / basedpyright / clangd.
- **Write-back seam:** add a `source` column (`regex` | `lsp`) to `entities_meta`/`relations`; the LSP pass upgrades/replaces regex rows per file. Ranking, spath, impact, health all improve automatically — no changes above the storage layer.
- **Position mapping:** LSP wants 0-based line + UTF-16 char. `entities_meta.start_line` + a name scan within the line seeds requests; spath → FQN → `entities_meta` → position gives **symbol-addressed** APIs (agent never supplies file:line:col).

### Phase 2 — semantic tools (the "update without grep" deliverable)
New `lsp` MCP tool (separate from `local_code_index`, which already has 25 actions; keep schemas COMPACT with a `compactHint`, document fully in AGENTS.md):
- `definition` / `references` / `hover` / `symbols` — replace grep-based discovery.
- `rename` — prepareRename → rename → **WorkspaceEdit applied atomically across files**: take `edit_coordinator` locks per file, backup/restore on partial failure (PatchTool precedent), targeted re-index after (doReplace precedent, LocalCodeIndexTool.java:866), `dry_run=true` default returning a unified diff.
- `diagnostics` — pull diagnostics for changed files; offer as an automatic post-`edit`/`write`/`patch` check. This closes the "did my edit compile" loop **without running a build**.
- `code_action` / `format` — organize imports, quick fixes.

### Phase 3 — build integration
- Add `buildCommand`/`testCommand` (+ language/toolchain) fields to `ProjectRegistration` (currently absent).
- A `build` action executes via `BackgroundProcessManager` (output capture to `~/.kompile/process-output/` exists), parses javac/tsc/cargo/maven output into structured diagnostics, and auto-records into `test_milestone` (which today must be fed manually). `maven-invoker` precedent: WebCommand.java:758.

### Phase 4 — consolidation (decision, not prescription)
The CLI codeindex package and the server-side `kompile-code-indexer` (JPA) are two divergent regex indexers. Once LSP write-back lands CLI-side, decide: server consumes the CLI SQLite index, or the extraction core moves to a shared module. Deferrable.

---

## 6. Constraints to respect

- **Native image:** new client code must be reflection-light (hand-rolled Jackson JSON-RPC is; lsp4j isn't). External LSP servers are subprocesses — no native-image impact. Rebuild + MCP reconnect to deploy (JDK-21-graal, ~4min).
- **Tool schema budget:** mcp-stdio serves COMPACT schemas; per-tool usability goes in `compactHint()`, full docs in AGENTS.md.
- **Multi-agent:** WorkspaceEdit application must take `edit_coordinator` locks; index writes already have `IndexLockManager`.

## 7. Open decisions

1. ~~Hand-rolled JSON-RPC vs lsp4j~~ — **RESOLVED 2026-07-02: lsp4j** (user directive; native-image handled via a bulk-registration Feature + proxy-config).
2. ~~Separate `lsp` tool vs folding into `local_code_index`~~ — **RESOLVED: separate `lsp` tool** (`full` profile + DynamicToolManager core/search group).
3. ~~First language servers~~ — **RESOLVED: mainstream set** — jdt.ls (Java), clangd (C/C++ + CUDA via `.cu`/`.cuh`), rust-analyzer, pyright, typescript-language-server (JS/TS), gopls.
4. Whether Phase 0's `project_id` resolution should hard-error or warn on fallback to `"default"` — still open.

**Implementation handoff spec (for a delegated agent): `docs/architecture/lsp-integration-implementation-plan.md` (WP-L1..WP-L10).**
