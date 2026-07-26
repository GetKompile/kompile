# Portable Knowledge Bases

A portable knowledge base is a versioned `.kproject` archive containing the project tree plus a
semantic inventory of the knowledge assets in that tree. It is prepared from the **Source
Maintenance** tab on the crawl screen so source updates, graph refreshes, and portability work are
visible in one place.

## Lifecycle

1. **Prepare portable state** flushes fact-sheet, chat, source-sync, and indexed-document catalogs
   from the application database into the project tree.
2. **Export graphs** writes scoped and project-wide graph artifacts under `data/graph/`.
3. **Package** creates a format-v2 `.kproject` archive without credentials, runtime PID files,
   logs, caches, or Git internals.
4. **Verify** performs structural preflight and exposes the package inventory, warnings, and
   external bindings in the maintenance UI.
5. **Import** verifies every payload size and SHA-256, extracts into a new sibling directory, and
   publishes only after the complete archive passes.
6. **Inspect restoration** shows the staged project's fact sheets, source bindings, external paths,
   models, graph, and rebuildable catalogs without reading them into the active runtime.
7. **Restart explicitly** activates the imported project using
   `kompile.project.root=<staged-project-path>`. Import never hot-switches the active root or starts
   source schedulers, provider calls, crawls, or graph mutation.
8. **Restore safely** first previews, then idempotently creates missing fact sheets and source
   connection records. Recreated sources are disabled, contain no credentials, and have remote
   synchronization and automatic commits turned off.
9. **Bind, test, enable** is performed per source. Credentialed sources cannot be enabled, pulled,
   or scheduled until **Test Auth** succeeds.

An import target is never merged with or written over an existing directory.

## Source behavior

| Source | Portable configuration | Portable content | Binding required after import |
| --- | --- | --- | --- |
| Notion | Scope, direction, poll schedule, webhook ID, status | Project-local synced notes and crawl artifacts when present | `NOTION_TOKEN` |
| Obsidian | Vault scope, REST URL, direction, poll schedule, status | Project-local Markdown snapshots when present | `OBSIDIAN_TOKEN` when REST is used |
| Local folder | Scope, direction, poll schedule, status | Files only when copied under the project tree | Access to an external folder when the scope remains outside the project |
| Git repository | Repository URL, branch, username, pull/push settings, schedule, status | Files checked into the project tree or included source snapshot | `GIT_CREDENTIAL` and the external repository |
| Crawl/upload | Crawl profiles, result metadata, project-local source documents | Files under project source/document directories | Remote URLs or external files not copied into the project |

Encrypted tokens, API keys, cookies, SSH keys, and provider credentials are never written into the
portable catalog. The catalog records a credential-binding name and stable binding ID instead.
Repository URLs are stripped of user-info, queries, and fragments before runtime persistence,
responses, or catalog export. Provider tokens are accepted only when credential encryption is
available; Kompile fails closed instead of storing plaintext. Global sync-configuration APIs
likewise report only whether a webhook secret is configured, preserve it when an update omits the
write-only field, and never echo the secret.
Detailed provider error messages and auth-check timestamps are also omitted because they can carry
machine-local paths or upstream request details; only coarse source status is portable.

## Restoration checklist

The Source Maintenance screen reports each entry as preserved, rebuildable, staged, missing,
unbound, unverified, disabled, invalid, or ready. A normal imported-source sequence is:

1. inspect the staged project's checklist;
2. restart with the staged project root;
3. run **Preview Restore**;
4. run **Restore Missing Catalogs**;
5. bind a machine-local token or external path;
6. run **Test Auth**;
7. enable the source, then use **Pull Updates** or **Auto Sync**.

Fact sheets are matched by name. Source connections are matched by fact-sheet name, provider, and
external scope, so repeating restoration does not duplicate runtime records. Chat and indexed
document catalogs remain provenance summaries; indexes are rebuilt from the preserved corpus.
Models and external directories remain explicit checklist items.

Portability maintenance jobs are journaled under `.kompile/cache/portability/`, which is excluded
from archives. Completed downloads and job history survive an application restart. A queued or
running job found after restart is recorded as `INTERRUPTED` rather than silently disappearing.

## Manifest v2

Format v2 adds a semantic block while retaining the file inventory and SHA-256 contract. It records:

- project description, lifecycle, tags, and component types;
- portable asset classes such as source corpus, fact-sheet catalog, search indexes, graph,
  ontologies, process definitions, and models;
- derived assets that can be rebuilt;
- external requirements such as credentials, model artifacts, and code repositories.

Readers accept both format v1 and v2. A v1 archive is reported as legacy because it has no reliable
semantic inventory. Inspection is intentionally non-mutating and structural; import repeats
preflight and verifies every payload checksum before publication.

## Current restoration boundary

The package is a safe project snapshot, not a live distributed transaction. Format v2 deliberately
reports the following boundaries instead of hiding them:

- provider credentials must be rebound;
- imported source schedulers and remote synchronization remain dormant;
- paths that point outside the project still require the external location;
- missing indexes or graphs are listed as rebuildable assets;
- external model and code repositories must be made available separately;
- the imported directory must become the configured root after a restart before runtime
  rehydration.

This boundary prevents an uploaded archive from triggering network calls or mutating a populated
runtime while leaving enough metadata for the UI and future migration tooling to guide restoration.
