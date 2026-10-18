# CLI transcript storage

## Boundaries

Durable history, model context, disposable search indexes and large tool-result
payloads have different lifecycles. Compaction still preserves ConversationLedger
events. Opening a ledger now migrates schema-1 context state to an incremental
journal while preserving the original files. This does not rewrite provider
history, deploy the CLI, or automatically prune existing data.

The shared SQLite JDBC dependency is 3.51.3.0, including the concurrent WAL-reset
fix. The conversation search projection uses DELETE journaling, operation-scoped
connections, a five-second busy timeout and transactional ingestion checkpoints.

## Session-wide retention

`kompile-agent chat-import cleanup` now **archives**, rather than immediately
deleting, expired sessions. Default caps remain 90 days, 2 GiB of owned hot payloads,
and 1,000 sessions per source namespace. Unprefixed IDs belong to the Kompile group;
`source__id` IDs form separate groups. Age is the latest owned-artifact mtime.

Owned artifacts include the readable transcript, context/native-session sidecars,
attachments, metrics/activity, the session directory (result-cache and tool-results),
and session-specific tool-call/details files. Orphan context/result sessions are
also discovered when the readable transcript is absent.

Safety:

- Live registry PIDs, existing TranscriptLogScope writer leases and `<id>.pinned`
  protect sessions. Unassociated MCP calls use a process-specific `mcp-stdio-<pid>`
  owner with an `owner.pid` marker.
- Retention holds the existing stable writer-lock inode and root retention lease.
  Durable publishers share the root lease; archive/purge/restore hold it exclusively.
  No operation deletes/replaces that lock inode.
- Native symlinks inside owned directories move as links; provider-owned targets
  are never traversed, counted or deleted.
- Payloads move to `.trash/<id>/<archive-id>/files/`. A manifest records state;
  subsequent cleanup completes interrupted archives. The listing is moved first
  so a crash does not advertise a partially moved session; ordinary I/O failures
  roll back moved payloads while the writer lease is still held.
- `.retired/<id>` hides retired records from derived transcript/catalog search.
  Restore removes the marker and refuses to overwrite new session data. An
  interrupted restore can be continued with the same restore operation.
- Dry runs create no archives, locks or tombstones. They report hot bytes, planned
  archive bytes and protection. **Archive moves reclaim zero filesystem bytes.**
  Only an explicit purge after the grace period reclaims archived payload bytes.
  This is not a filesystem-block or secure-erasure guarantee.

```text
kompile-agent chat-import cleanup --dry-run
kompile-agent chat-import cleanup --pin <session-id>
kompile-agent chat-import cleanup --unpin <session-id>
kompile-agent chat-import cleanup --restore <session-id>
kompile-agent chat-import cleanup --purge-trash --grace-days 7 --dry-run
kompile-agent chat-import cleanup --purge-trash --grace-days 7
```

Pin/unpin/restore/purge are local-only and mutually exclusive. Purge requires at
least a one-day grace period and skips incomplete/restoring/pinned archives.
Failures are reported instead of being presented as successful cleanup.

Global catalog/usage compaction is a separate explicit operation (below). Legacy
truncated-output files, diagnostic logs, backups and provider originals retain
their separate lifecycles and are **not** included in reclaimed-byte claims. Archive is not a privacy purge
of every copy. Legacy API deleted-count fields alias retired-session counts; new
fields report retirement, archive bytes, protection and failures explicitly.

## Search

`transcript_search` preserves its existing regex/literal `search` behavior:

```json
{"action":"fulltext","query":"\"writer lock\" OR retention","session_id":"abc","limit":20,"offset":0}
{"action":"read","session_id":"abc","offset":501,"limit":200}
{"action":"rebuild_index"}
```

Fulltext is explicit FTS5 syntax, not regex or arbitrary substring matching. It
supports agent, project, session-prefix and session-start date filters, BM25,
bounded snippets and continuation metadata. `.search-index.db` is a disposable
projection; rebuilding it does not modify transcripts. Physical lines are split
into overlapping chunks, so boundary matches may repeat and phrases spanning
physical lines are not matched as one phrase. Reads preserve long lines and return
line continuation; large pages still go through the normal tool-output guard.

Catalog search, statistics and facets use SQLite rather than building the entire
combined JSONL catalog in Java. Per-session/combined streams remain independent.
Grouped queries apply per-group limits in SQL before materializing calls. Append
checkpoints and unterminated imported JSON records are transactional. Growing
in-place rewrites outside the append-boundary fingerprint require `rebuild_index`.

## Durable results

Large-result handles remain `d1.<owner-session>.<full UUID>`. Metadata and a line
index publish under `conversations/<owner-session>/result-cache/<UUID>/`.
Format-2 payloads live in the same session's `result-cache/.blobs/<SHA-256>/`,
with independently encoded 64 KiB blocks: gzip only when smaller, otherwise raw.
Identical full outputs reuse a verified blob while each invocation keeps distinct
metadata. Format-1 raw outputs remain readable. Lookup requires no global scan.
Each read caches at most one decoded block plus the small UTF-16 regex window;
block size/decompression limits and SHA-256 checks reject malformed payloads.
Compression byte counts exclude the block/line indexes, so tiny outputs may have
more bookkeeping than the original text. Failed publications may leave safe
unreferenced blobs; these are retained until whole-session purge, not eagerly GC'd.
There are no shared cross-session references or global blob garbage collector. Handles survive restart/compaction until archival/purge; restoration makes
archived handles usable again. Errors distinguish retirement from legacy TTL.

Fetch tools preserve offset/limit, regex and partial-long-line continuation
(`character_offset`). Pages are bounded and disk backed, including regex access
to enormous lines. Durable payloads retain no Java cache entries. Compatibility
caches have an 8 MiB accounted cap and lazy idle expiry, without per-cache threads.
Publication failure issues no handle and visibly returns the original output.
Pathological regex backtracking can still be expensive. Atomic directory rename
and fsync need separate non-POSIX qualification; no native-image deployment is
claimed here.

## Incremental context journal

`ConversationLedger` now writes `<id>.context/journal.jsonl` rather than rewriting
all prior events on each append/checkpoint. A checksum-bearing schema-2 envelope
orders conversation events, compaction checkpoints and native-session associations.
Commits force the new record before acknowledgment; stale ledger instances fail
instead of overwriting another writer. In-memory snapshots/API semantics remain.

Migration writes an immutable seed and ready record, then a forced `committed`
marker. Schema-1 context/native-session originals and attachment files remain
untouched; legacy attachments are readable and new ones use `.context/attachments`.
A missing committed journal never falls back to the obsolete preserved snapshot.
Startup replays history. Only an unterminated final fragment is treated as an
uncommitted tail; complete corruption, invalid sequences/checksums and unsupported
schemas fail closed. I/O failures roll back the tentative append under the writer
lease. Startup still reads full history; this is not a bounded-memory ledger rewrite.
The whole `.context` directory is owned by session archival/restore/purge.

Do not resume a migrated session with an older CLI: preserved JSON is a migration
source/backup, not a continuously refreshed projection for backward writers.
Mixed-vintage writers must restart on the same new build before using this format.

## Explicit global-journal compaction

```text
kompile-agent chat-import cleanup --compact-journals --dry-run
kompile-agent chat-import cleanup --compact-journals
```

A successful purge of the archive currently named by `.retired/<id>` produces a
`.purged/<id>` receipt. Compaction removes records for these IDs only; archived but
restorable sessions remain in both combined catalog and usage journals. Pins or
reappearing context/transcript/payload data prevent journal removal. Legacy purges
without receipts are not inferred or automatically converted.

All three catalog writers (CLI, app service and React hook) use the same stable
per-file lease; usage writes/repair/pruning share their original lease through a
common JVM/OS coordinator. Root retention lease serializes maintenance with
archive/restore. App backfill skips purged IDs, and writes do not resurrect their
catalog records. Purged session IDs are not reusable; start a fresh session.
Before running compaction, restart **all** catalog-writing CLI/app processes on
this build, or stop the older writers. Older binaries do not honor the new shared
catalog lease; concurrent maintenance with those writers is not supported.

Compaction streams records (maximum 8 MiB each), preserves unparseable/anonymous
records and unterminated tails byte-for-byte, forces a temporary replacement, then
atomically replaces each file. It never truncates a live journal in place. Files
are committed independently: interrupted maintenance can be repeated, and one
file may already be compacted if a later file fails. No age-based usage revision
folding or conflict-diagnostic deletion is performed. Dry-run sizes are advisory
snapshots when no existing writer lease can be acquired without mutation.
No forensic secure-erasure or removal from backups/providers is claimed.

## First-phase validation receipt (2026-10-11)

- CLI-common full suite: 308 tests, 305 passed, 3 live-agent assumptions skipped
  (Aider, Continue and Cline absent); no failures/errors. Includes 12 retention tests.
- CLI-main focused storage/search plus ledger replay/native-resume/writer-scope
  regressions: 135 passed, no failures/errors/skips.
- Cleanup CLI: 5 passed, no failures/errors/skips.
- Chat-history REST controller compiled against the freshly installed common API.
  Compiler emitted JNA manifest classpath warnings; no native image was built.
- Root/common/agent CLI artifacts were installed in the local Maven repository
  before downstream validation. No runtime was redeployed and no real data pruned.

Build/test invocations used `/home/agibsonccc/dev-apps/mvn/bin/mvn`, offline scoped
reactor selection, `-Dnd4j.backend=nd4j-native`, `-Dasciidoctor.skip=true` and
`-Dexec.skip=true`. SQLite alone was fetched explicitly before offline builds;
no remote DL4J snapshot refresh was performed. Incremental ledger persistence,
session-local result compression/deduplication and explicit purge-only journal
compaction were implemented in the follow-up phase documented above. This does
not retroactively compress all existing result payloads or provide cross-session
deduplication.

## Follow-up validation receipt (2026-10-11)

- CLI-common: 317 tests, 314 passed, 3 absent-agent live assumptions skipped.
- CLI-main focused storage, migration, compression, catalog compaction and
  provider/attachment replay: 159 passed, no failures/errors/skips.
- Cleanup CLI: 6 passed, no failures/errors/skips.
- App MCP, React hook and ingest/backfill modules compiled successfully. Their
  build emitted existing manifest classpath/deprecation warnings; no native build.
- `proc-017` final serialized run installed CLI-common before downstream tests.
  Earlier attachment-replay fixture failure was corrected to delete the new
  `.context/attachments` blob, keeping the missing-image assertion unchanged.
- Ledger worker accepted after source audit and parent shared-lease/retention
  integration fixes. Compression worker stopped at supervision without an accepted
  implementation; compression was implemented and validated directly.
- No runtime redeploy, real-session migration/pruning/compaction, Git operation,
  remote DL4J refresh, or performance benchmark was performed. Automatic migration
  occurs when the new runtime opens a ledger, not as a bulk operation in this task.

