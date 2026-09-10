# Code Index — Background Maintenance

The local code index (`~/.kompile/code-index/<projectId>/`) is kept fresh by a
per-process background service instead of blocking tool calls. Implemented in
`ai.kompile.cli.main.codeindex.BackgroundIndexService`, wired into the
`local_code_index`, `code_search`, and `code_graph` tools plus the `edit`,
`write`, and `patch` write paths.

## What changed

Previously:

- Every read action (`search`, `usages`, `callers`, …) ran a **synchronous**
  throttled incremental pass inline (`IndexAutoRefresher`) — the caller paid
  the stat-walk latency on its own thread.
- `action='index'` was fully synchronous: a first-time index of a large repo
  blocked the MCP tool call for its whole duration.
- `IndexFileWatcher` existed but was only used by the interactive
  `kompile code-index watch` command; nothing maintained indexes proactively.

Now, per code project:

1. **Write-hook trigger** — kompile's own `edit`/`write`/`patch` tools call
   `noteFileWritten(path)` after each successful write. If the file sits under
   an indexed project root, the project is marked dirty and a debounced
   (750 ms) incremental refresh runs on a daemon executor.
2. **Watcher trigger** — the first read against a project auto-starts an
   `IndexFileWatcher` (in the background; registration never runs on the read
   path), so *external* edits (IDE, git checkout) also trigger incremental
   re-index within the 500 ms/5 s debounce. Watchers are capped
   (default 8) and LRU-evicted by last touch; if a watcher can't start
   (e.g. inotify exhaustion) the project degrades to periodic polling.
3. **Periodic backstop** — a low-frequency sweep (default every 5 min) re-runs
   the cheap fingerprint pass for projects touched in the last hour, catching
   watcher misses (overflow events).
4. **Freshness join instead of inline refresh** — before a read action the
   tool calls `prepareForRead(projectId)`: if background work is pending or
   in flight for that project it waits briefly (default 2 s) so callers still
   **read their own writes**; when the project is quiet it returns immediately
   — no stat walk at all. Completed background passes surface as
   `[index auto-refreshed in background: N files re-indexed]` annotations on
   the next read.

## Background index jobs

`action='index'` now runs as a **background job by default**:

- The call submits the job, waits up to `wait_seconds` (default 10, max 120)
  and, when the pass finishes inside the window (the common incremental case),
  returns the familiar synchronous result plus a
  `Mode: background job idx-N` line.
- If the job is still running (big first-time builds), the call returns
  immediately with the job id; poll with `action='index_status'` (all jobs, or
  one project's maintenance state with `project_id`). Database readers see the
  previous committed generation until the indexing transaction commits.
- `background=false` forces the legacy fully-synchronous behavior;
  `wait_seconds=0` returns a handle immediately.
- Jobs are deduplicated per project: submitting while one is queued/running
  returns the existing job. Concurrent passes stay safe across processes via
  `IndexLockManager` file locks.

`stats` and `list` show maintenance state per project (`watching`,
`indexing in background`, last background pass age).

## SQLite integrity and recovery

Each indexing pass, including the periodic backstop on unchanged source trees,
runs throttled database maintenance under the project writer lock:

- `PRAGMA quick_check(1)` checks storage, then FTS5 `integrity-check` with
  `rank=1` compares postings against `entities_meta` (row counts cannot do this).
- If storage passes but FTS reports corruption, maintenance attempts an in-place
  FTS `rebuild` and validates it before committing. Entities, relations and the
  committed generation are preserved. Failure rolls back the repair.
- A missing database or interrupted publication triggers a full source reindex,
  restoring relations as well as entities. JSON shards alone cannot restore relations.
- Physical corruption is **not** fixed by deleting/replacing a live database.
  Maintenance reports failure and leaves database/WAL/SHM files in place.
  BUSY, I/O, disk-full and schema errors are not treated as corruption.

Manual retry (synchronous, bypasses the integrity throttle):

```json
{"action":"repair","project_id":"my-project"}
```

Use this with `local_code_index`; `action="index_status"` reports the last
in-process maintenance result. If source reindexing is requested, run
`action="index", force_reindex=true`. For physical corruption, stop **all**
index clients first, preserve the database and sidecars together, then perform
offline SQLite recovery or move the damaged cache aside and index from source.
Do not delete WAL/SHM while any client is running. Automatic physical salvage is
not implemented.

Transaction/lifecycle protections:

- Any failed SQL/file publication aborts the update rather than committing a
  partially applied entity batch without corresponding FTS postings.
- `update.pending` survives until SQLite and fingerprint/metadata publication
  complete; the next pass rebuilds from source after an interrupted update.
- Parse workers are read-only; only the locked writer publishes shards.
- Schema migrations and shard rebuilds are transactional. Missing/unreadable
  shard input aborts a shard rebuild without clearing the existing index.
- Rollback invalidates interned-ID caches; failed rollback closes the connection.
  Failed opens release JDBC/file/JVM locks. Tool/LSP readers own short-lived,
  read-only connections rather than sharing one mutable cached connection.

These fixes address concrete logical inconsistency risks; they do not establish
that every reported physical SQLite corruption has the same cause.

## Tunables

System property first, environment variable fallback:

| Variable | Default | Meaning |
|---|---|---|
| `KOMPILE_CODE_INDEX_BACKGROUND` | `true` | Master switch; `false` restores the legacy inline throttled refresh everywhere. |
| `KOMPILE_CODE_INDEX_WATCH` | `true` | Auto-start per-project file watchers. |
| `KOMPILE_CODE_INDEX_MAX_WATCHERS` | `8` | Watcher cap (LRU-evicted). |
| `KOMPILE_CODE_INDEX_READ_WAIT_MS` | `2000` | Freshness-join budget on read actions. |
| `KOMPILE_CODE_INDEX_BACKSTOP_SECONDS` | `300` | Backstop sweep period (`0` disables). |

The JVM system property `kompile.codeIndex.integrityIntervalMs` controls the
integrity-check throttle (default `300000`, per process/project). Failed checks
are throttled too; manual `repair` bypasses this delay. Integrity scans hold the
SQLite writer reservation, so tune the interval for large indexes.

## Notes

- All state is in-memory and per-process (the long-lived `mcp-stdio` server is
  the intended host); daemon threads die with the process.
- Failure policy is inherited from `IndexAutoRefresher`: log to stderr and
  swallow — index maintenance must never break a search.
- Tests: `BackgroundIndexServiceTest` (job lifecycle, dedupe under a held
  write lock, read-your-writes join, watcher auto-start on external writes,
  disabled fallback).
