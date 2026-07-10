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
  one project's maintenance state with `project_id`). Searches during the
  build see the index as it fills.
- `background=false` forces the legacy fully-synchronous behavior;
  `wait_seconds=0` returns a handle immediately.
- Jobs are deduplicated per project: submitting while one is queued/running
  returns the existing job. Concurrent passes stay safe across processes via
  `IndexLockManager` file locks.

`stats` and `list` show maintenance state per project (`watching`,
`indexing in background`, last background pass age).

## Tunables

System property first, environment variable fallback:

| Variable | Default | Meaning |
|---|---|---|
| `KOMPILE_CODE_INDEX_BACKGROUND` | `true` | Master switch; `false` restores the legacy inline throttled refresh everywhere. |
| `KOMPILE_CODE_INDEX_WATCH` | `true` | Auto-start per-project file watchers. |
| `KOMPILE_CODE_INDEX_MAX_WATCHERS` | `8` | Watcher cap (LRU-evicted). |
| `KOMPILE_CODE_INDEX_READ_WAIT_MS` | `2000` | Freshness-join budget on read actions. |
| `KOMPILE_CODE_INDEX_BACKSTOP_SECONDS` | `300` | Backstop sweep period (`0` disables). |

## Notes

- All state is in-memory and per-process (the long-lived `mcp-stdio` server is
  the intended host); daemon threads die with the process.
- Failure policy is inherited from `IndexAutoRefresher`: log to stderr and
  swallow — index maintenance must never break a search.
- Tests: `BackgroundIndexServiceTest` (job lifecycle, dedupe under a held
  write lock, read-your-writes join, watcher auto-start on external writes,
  disabled fallback).
