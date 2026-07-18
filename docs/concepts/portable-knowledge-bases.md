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
6. **Open explicitly** activates the imported project. Import never starts source schedulers,
   provider calls, crawls, or graph mutation by itself.

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
portable catalog. The catalog records a credential-binding name instead.

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
- the imported directory must be opened explicitly before runtime rehydration.

This boundary prevents an uploaded archive from triggering network calls or mutating a populated
runtime while leaving enough metadata for the UI and future migration tooling to guide restoration.
