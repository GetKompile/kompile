# Code-Index Usefulness Plan (WP-C series)

**Date:** 2026-07-02
**Context:** Expands the scope of `docs/architecture/cli-code-index-lsp-audit.md` beyond the delegated LSP tool build (`docs/architecture/lsp-integration-implementation-plan.md`, WP-L series). The WP-C series makes the *existing* index trustworthy and more useful — independent of, and complementary to, the LSP work. WP-C1/C2 shipped 2026-07-02; C3-C5 are delegatable now; C6 is blocked on the WP-L build landing.

---

## WP-C1 — Project auto-resolution ✅ SHIPPED 2026-07-02

**Problem:** every code tool silently fell back to a guessed `project_id` (`"default"` in `code_search`/`code_graph`, cwd dir-name in `local_code_index`) — wrong index whenever the caller sat in a subdirectory or the project used a custom id.

**Shipped:** `codeindex/ProjectIdResolver.java` — resolution order: explicit param → nearest `.kompile/registration.json` walking up from cwd (id used only if its index exists) → deepest indexed root containing cwd (ties → most recent `indexedAt`) → registration-without-index (surfaces "index first" errors under the *right* id) → legacy cwd-name.

Wiring:
- `LocalCodeIndexTool`: resolved once centrally in `execute()` and stashed back into params (per-action fallbacks now inert); `index`/`list`/`modules` keep directory-derived defaults. Non-obvious resolutions are annotated in the output (`[project_id 'x' auto-resolved via registration]`).
- `CodeSearchTool` / `CodeGraphTool`: same resolver; **backend-bound calls preserve the legacy `"default"` bucket when nothing authoritative matched** (backend data was historically indexed under it); `CodeGraphTool` directory-management actions (`build`/`add_directory`/`remove_directory`/`list_directories`) keep legacy behavior.

Tests: `ProjectIdResolverTest` (8). Module codeindex suite green (302 total).

## WP-C2 — Staleness self-heal ✅ SHIPPED 2026-07-02

**Problem:** the index detected staleness *after* answering (`checkStaleness` warning) — reads could lie, agents had to re-index manually.

**Shipped:** `codeindex/IndexAutoRefresher.java` — before read actions, a throttled (30s/project, atomic claim) incremental index pass (fingerprint fast path = no-op when clean; failure-proof: errors logged to stderr, never break a search). Annotates output `[index auto-refreshed: N files re-indexed, M deleted]` when it changed anything. Opt-out param `auto_refresh=false` on all three tools. Deliberately exempt: `health`/`stats` (they *report* staleness) and, in `code_search`/`code_graph`, anything answered by the backend rather than the local index.

Tests: `IndexAutoRefresherTest` (4: pickup, throttle, clean no-op, missing project).

---

## WP-C3 — Import-aware FQN resolution (precision; delegatable now)

**Problem (audit §4.1):** `IndexDatabase.ensureConnectivity()` (IndexDatabase.java:657) resolves cross-file relation targets by SQL suffix match `fqn LIKE '%.' || target_name` — on common simple names (`Config`, `Handler`, `Service`) it fabricates CALLS/EXTENDS links, poisoning `callers`, `trace`, `impact`, PageRank, and co-change weighting.

**Design:** replace the bulk suffix `UPDATE` with a per-source-file resolution pass, ranking candidates for each unresolved `target_name`:
1. entity in the **same file**;
2. entity in the **same package** (source file's `PACKAGE` entity prefix);
3. entity matching an **IMPORTS row of the source file** — exact simple-name match on the import's FQN, plus wildcard imports (`pkg.*` → candidates with FQN prefix `pkg.`);
4. **globally unique** suffix match (current behavior, but only when exactly one candidate exists);
5. otherwise: **leave `target_fqn` NULL** — an unresolved edge is honest; a guessed edge corrupts every downstream analysis.

Implementation notes: runs at index time (after relations are written); data needed is already in `relations` (IMPORTS rows carry the imported FQN) and `entities_meta`. Keep the old behavior behind a system property for rollback (`kompile.codeindex.legacyConnectivity`). Acceptance: fixture project with two same-named classes in different packages + discriminating imports — `callers`/`impact` must not cross-link; suffix-unique case still resolves; ambiguous-no-import case stays unresolved.

## WP-C4 — Cross-project search (delegatable now)

Single-project SQLite DBs, no fan-out today (audit §7 of project-mgmt report). Add `project_id="*"` (or comma-list) to `local_code_index` `search`/`ranked_search`/`blended_search`: enumerate via `listProjects()`, query each, merge with per-project result labels and a per-project result cap (scores are comparable enough within a strategy; do not re-rank across strategies). `list` already enumerates projects — reuse it. Keep `"*"` out of `replace` (multi-project writes are a footgun).

## WP-C5 — MCP parity + docs drift (delegatable now)

1. Expose `watch` as a `local_code_index` action (`start`/`stop`/`status`) — `IndexFileWatcher` exists but only the Picocli CLI reaches it. One watcher per project, tracked statically, stopped on `closeAll()`.
2. Expose the useful Picocli-only graph subcommands (`callers`, `tests-for`, `dossier`) as `code_graph` actions (implementations live in `CodeIndexGraphCommand`).
3. Fix `docs/concepts/code-projects.md`: remove/implement `kompile configure code-index`; correct the entity-type list; document `blended_search`/`ranked_search`/`impact`/`signatures` and offline-first behavior (the audit found the best features undocumented).
4. Log >1MB file skips (currently silent, LocalCodeIndexer.java:1326) — one summary line per index pass.
5. CloneDetector: add LSH bucketing or a hard pair cap (currently O(n²) unguarded).

## WP-C6 — LSP write-back, "Phase 1b" (BLOCKED on WP-L1..L10 landing)

Once the `lsp` tool + `LspServerManager` exist, upgrade the index from regex-extracted to LSP-verified facts *without touching the search/ranking stack*:

- **Schema:** `ALTER TABLE entities_meta ADD COLUMN source TEXT NOT NULL DEFAULT 'regex'`; same on `relations`. Migration on `IndexDatabase.open()` (additive — but since WP-C7, opens skip `ensureSchema()` when `PRAGMA user_version` matches, so this migration must also bump `IndexDatabase.SCHEMA_VERSION`).
- **`LspIndexEnricher`:** background queue fed by index passes (hook: after `IndexAutoRefresher`/`index` re-parses files). Per changed file with an available server: `documentSymbol` → replace that file's `entities_meta` rows (`source='lsp'`); `references` on the file's declared symbols → CALLS/IMPLEMENTS rows (`source='lsp'`).
- **Reconciliation:** per-file atomic swap — a successful LSP pass deletes that file's regex rows; files/languages without servers keep regex rows. Never mix sources within one file.
- **Budgeted:** enrichment must never block reads; cap per-pass file count; queue survives via the existing shard store.
- **Visibility:** `health` gains a source-mix line (`entities: 62% lsp / 38% regex`); `IndexHealthScorer` can weight lsp coverage later.

**Sequencing:** C3 first (biggest precision win, no dependencies), then C5, C4 opportunistically; C6 after the LSP tool merges. C3 and C6 overlap in `IndexDatabase` — if both run, land C3 first and rebase C6's migration on it.

## WP-C7 — Indexing/browsing performance overhaul ✅ SHIPPED 2026-07-08

**Problem:** on the real kompile index (9,770 files / 370k entities / 779k relations / 792MB DB), a 347-file incremental pass did not finish in 5 minutes, and even a *zero-change* pass ran multi-second work. Measured causes, in impact order:

1. `ensureConnectivity()` was one correlated leading-wildcard `LIKE` UPDATE — a full `entities_meta` scan per unresolved relation, O(relations × entities), re-run over the **whole table** after every pass. With 64,749 steady-state-unresolvable relations that was ~24B row comparisons per pass, re-failing on the same names every time.
2. `insertEntities()` did per-entity `executeUpdate` + `getGeneratedKeys` (two round trips per entity; a full pass writes 370k+ of them).
3. Relation extraction (pure CPU) and shard writes ran on the single DB-writer thread, while every parsed file's full `String[]` line array stayed retained until that phase.
4. `ensureSchema()` ran on **every** `IndexDatabase.open()` (i.e. every search): ~18 DDL statements plus `SELECT COUNT(*)` over `entities_fts` *and* `entities_meta` — two full-table scans per open on a 792MB file.
5. Each pass statted every file up to 3× (walk, diff, parse) and always rewrote `fingerprints.json` (2.5MB pretty-printed) + `metadata.json` + opened the DB even when nothing changed — and the auto-refresher triggers this before read actions every 30s.

**Shipped (all in `codeindex/`, no schema or API changes):**

- `IndexDatabase.open()` gates `ensureSchema()` behind `PRAGMA user_version` (`SCHEMA_VERSION=2`); adds `busy_timeout=5000` and `temp_store=MEMORY`.
- `insertEntities()` pre-allocates row ids from `sqlite_sequence` and batches both the meta and FTS inserts; `deleteFiles(Collection)` bulk-deletes with IN-chunks.
- `ensureConnectivity(Collection changedFiles)` collects distinct unresolved target names (scoped to changed files + older unresolved relations pointing at names those files (re)define), resolves each name **once** — indexed fqn/name probes plus an FTS-narrowed suffix probe when ≤200 names, one streamed `entities_meta.fqn` scan when more — then applies the mapping with batched UPDATEs on `idx_rel_target_name`. Suffix matching is now case-sensitive: the legacy ASCII-case-insensitive `LIKE` fabricated links (632 `StringBuilder` calls resolved to a private field named `stringBuilder`; `isalive`/`passed`/`fail` to arbitrary same-spelling entities) — 940 of 125k resolutions on the bench corpus were such false links and are now honestly unresolved. No-arg `ensureConnectivity()` keeps the full-pass behavior for `CodeGraphTool`.
- `LocalCodeIndexer.index()`: parse, relation extraction and shard writes all run on the parse pool; results drain into the DB in completion order (parse overlaps DB writes; peak RAM bounded by in-flight files); the walk's `BasicFileAttributes` feed the diff and fingerprints (one stat per file); a clean tree returns early without touching the DB or rewriting fingerprints/metadata; `fingerprints.json` is written compact.

**Measured (real kompile repo):** fresh full index of `kompile-cli/` (824 files, 66k entities): old binary 5m28s → **8.9s** (~37×). 347-file incremental on the full index: old killed at 5m timeout → **~47s**. Zero-change pass (the 30s auto-refresh tax): old >4min when any file had changed, multi-second otherwise → **187ms**. Search on the 370k-entity index after open: 0–25ms (FTS). Index content verified equivalent on the bench corpus (identical per-file entity counts, FTS rowids aligned) except the 940 false links above. `codeindex` suite green (302 tests).
