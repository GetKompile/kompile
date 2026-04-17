# Research Feature Delta — Implementation Plan

**Date:** 2026-04-17
**Scope:** Close the gap between Kompile and modern research-notebook tooling (SurfSense, open-notebook/Open NotebookLM, Google NotebookLM).
**Out of scope:** Content generation (Audio Overviews, podcast generation, briefing generation, video overviews). This plan focuses on **research, source types, actions, and information architecture** only.

## 0. Scope notes

- "openclaw" in the original ask was ambiguous. There is no external project by that name in the research-notebook space. Kompile already has a `kompile-app/kompile-openclaw/` module — a **multi-channel agent gateway** (Slack/Discord adapters, heartbeat, sessions, permissions). That is orthogonal to the notebook-research axis and is treated here as existing Kompile infrastructure, not a benchmark. The research agents also surveyed `lfnovo/open-notebook`, which is the likely intent; its findings are folded in.
- Kompile's frontend has `openclaw-*` components (hub, chat, channel-manager, agent-manager, session-viewer, permission-manager). These belong to the channel gateway, not the notebook surface.
- The plan references specific modules, entities, controllers, and tools by path so it is immediately actionable. Where a new module would be added, it also specifies the four-place update rule from `CLAUDE.md` (parent POM, main POM, sample POM, `RagPomGenerator`).

## 1. Benchmark summary

### 1.1 SurfSense (MODSetter/SurfSense)
- **28 indexed source types** via dedicated OAuth connectors: Google Drive, Gmail, Google Calendar, Notion, Slack, Teams, GitHub, Discord, Jira, Confluence, ClickUp, Linear, Airtable, OneDrive, Dropbox, BookStack, Luma, Circleback, Elasticsearch, Obsidian. Plus file upload (50+ formats), web crawl (Firecrawl/Trafilatura), YouTube transcripts, podcast-style file ingest.
- **Live-search** (Tavily, Linkup, SearxNG, Baidu) as context-only tools.
- **Browser extension** (Plasmo/Manifest V3) that saves auth-protected pages via semantic-markdown DOM conversion.
- **Hybrid retrieval** (pgvector cosine + PG full-text, RRF fusion, reranker options: Flashrank, Cohere, Pinecone). Two-tier (document summary → chunk materialization).
- **Agent:** LangGraph + DeepAgents middleware stack with TodoList, SubAgent, KnowledgeBaseSearch, MemoryInjection, Summarization. Persistent user memory + team memory.
- **Write-back actions** with HITL approval (create Linear issues, Notion pages, Jira tickets, Gmail drafts, GDrive/Dropbox files, Calendar events).
- **MCP client** (stdio + HTTP) with dynamic tool discovery and HITL gating.
- **Multi-user** with RBAC (Owner/Admin/Editor/Viewer + custom roles), invitations, real-time collab via Rocicorp Zero.
- **No timeline, no graph view, no entity extraction** in the product itself.

### 1.2 Open Notebook (lfnovo/open-notebook)
- **Sources:** PDF (digital + OCR), Office, EPUB, Markdown, HTML, web URL, YouTube (captions + transcription fallback), playlists, podcast RSS, direct PDF URL, audio (MP3/WAV/M4A/OGG/FLAC), video (MP4/AVI/MOV/MKV/WebM) — audio/video transcribed via OpenAI Whisper/Groq/ElevenLabs.
- **Retrieval:** SurrealDB native functions for BM25 text search and vector cosine search, selected by the user (no hybrid fusion, no reranker).
- **"Ask" agent** (LangGraph): decomposes a question into up to 5 parallel vector-search branches, per-branch LLM analysis, then synthesis. Closest thing to a multi-step research loop.
- **Transformations:** user-defined prompt templates applied per source → `SourceInsight` (ephemeral) → optionally promoted to `Note` (durable, embedded, searchable). Built-ins include Summary, Key Concepts, Methodology, Takeaways, Questions.
- **Notes as first-class** objects (human or ai), embedded and searchable alongside sources. Explicit per-source context control (full / summary / excluded) per chat session.
- **MCP server** (`open-notebook-mcp`) exposes the REST API as MCP tools — reversal of the usual direction: external agents can drive the notebook.
- **Single-tenant**, no user accounts, optional password protection.

### 1.3 NotebookLM (Google)
- **Sources:** PDF, Google Docs/Slides/Sheets, DOCX/PPTX, CSV, TXT, MD, ePub, web URL, YouTube (captioned), **audio files transcribed at import**, **images** (12 formats), pasted text, Gemini chat imports. 200MB per source, 300 sources per notebook (Plus).
- **Discover:** paste a topic in natural language → Gemini queries the web, returns ~10 annotated recommended sources for one-click add. Available on web/Android, not iOS at launch.
- **Mind Map:** auto-generated topic graph with clickable expansion and per-node Q&A (not user-editable).
- **Studio artifacts:** Study Guide, FAQ, Table of Contents, Timeline, Key Topics, suggested questions, Data Tables (Dec 2025 — structured column/row extraction, exportable to Sheets).
- **Notes** panel: user-authored notes alongside sources, selectable into context, exportable to Docs.
- **Grounded chat** with clickable page/passage citations. ~13% reported hallucination rate (RAG-grounded).
- **Sharing:** Owner/Editor/Viewer/Chat-only, public notebooks (consumer), Featured notebooks, 7-day usage analytics.
- **Enterprise REST API** (alpha, Sep 2025): notebook CRUD, source management, audio overview generation, IAM-based sharing.

## 2. Kompile current state — strengths and gaps

### Strengths to preserve
- **Hybrid search + reranker suite** beats open-notebook, matches SurfSense. `HybridOptimizedRetriever` (BM25 + dense + RRF), plus RM3, BM25-PRF, Rocchio, Axiom, MMR, CrossEncoderReranker.
- **Knowledge graph** beats all three benchmarks: two implementations (`kompile-knowledge-graph` PostgreSQL-backed + `kompile-graph-neo4j` optional), entity extraction (PERSON/ORG/LOCATION/DATE/TECHNICAL_TERM/CONCEPT/PRODUCT/EVENT), entity resolution, edge computation, source weighting. D3 `graph-visualizer`, `knowledge-graph-builder`, `entity-browser` UI.
- **Vector store pluggability:** Anserini (Lucene HNSW + BM25 in one), pgvector, Chroma, Vespa. SurfSense is pgvector-only.
- **OCR / VLM pipeline:** `kompile-ocr-*`, `VlmDocumentPipeline`, `VlmDocumentExtractorService`, structured `ParsedDocument` with tables/fields.
- **Tool breadth:** 40+ `@Tool`-annotated beans spanning RAG, knowledge, agents, pipelines, infra, models, eval. MCP server with compression/optimization and external MCP client.
- **Agent surface:** `kompile-react-agent` full ReAct loop with pluggable reasoners (default, GraphRag, eval-based), parallel actor, hooks.
- **Channel deployment (openclaw):** Slack and Discord channel adapters, heartbeat, session state, permissions — a capability NotebookLM and open-notebook lack entirely.

### Gaps (what's missing)

| Area | Kompile today | Benchmarks have |
|---|---|---|
| **User-authored notes** | Not present | All three |
| **Saved/promoted summaries per source** | Not as a saved artifact | open-notebook (SourceInsight → Note), NotebookLM (Study Guide, FAQ, TOC) |
| **Audio/video file ingest with STT** | Not present | open-notebook, NotebookLM |
| **Image ingest (standalone)** | Only through OCR/VLM upstream | NotebookLM |
| **Podcast RSS / playlist ingest** | Not present | open-notebook |
| **YouTube transcript loader (as a DocumentLoader)** | Source provider wiring present, no standalone loader | open-notebook, NotebookLM, SurfSense |
| **GitHub/Linear/Discord/Airtable/ClickUp connectors** | Not present | SurfSense |
| **Dropbox/OneDrive connectors** | Microsoft OAuth handler exists, no OneDrive source | SurfSense |
| **Web clipper browser extension** | Not present | SurfSense |
| **Discover-style web research loop** | Not present | NotebookLM |
| **Transformations (user-defined prompt → saved insight)** | Not present | open-notebook |
| **Data Tables (structured extraction to table)** | Not present | NotebookLM |
| **Mind map / topic map view** | Not present (graph view is entity-level, not topic-level) | NotebookLM |
| **Timeline view / timeline extraction** | Not present | NotebookLM |
| **Study Guide / FAQ / TOC / Key Topics artifacts** | Not present | NotebookLM |
| **Notebook primitive (parallel, not switchable)** | FactSheet is switchable only | All three |
| **Per-source context control in chat** | Folder-level filtering only | open-notebook (per-source full/summary/exclude) |
| **Citation export (BibTeX/footnotes)** | Metadata tracked, not exported | None ship this; opportunity |
| **User accounts / auth / sharing / RBAC** | Not present | SurfSense, NotebookLM |
| **Persistent agent memory (user + team)** | Not present | SurfSense |
| **HITL approval gates for tool calls** | Not present | SurfSense |
| **Multi-step autonomous web research that indexes** | Not present; web search is query-only | NotebookLM Discover |
| **Follow-up question suggestions** | Not present | open-notebook, NotebookLM, SurfSense |

## 3. Implementation plan

Phased by research/information value per effort. Each phase can ship independently.

### Phase 1 — Notebook primitive + user notes + per-source context control

**Goal:** Give Kompile the core mental model that surfsense, open-notebook, and NotebookLM all share: a notebook that groups sources and user notes together with scoped chat sessions. Today Kompile has FactSheets (switchable, one active) and ChatFolders (orthogonal, chat-only). Neither matches the "notebook = sources + notes + chat sessions + artifacts, queryable in parallel" concept.

**New module:** `kompile-app/kompile-notebook/`

- Entities:
  - `Notebook` (id, name, description, icon, color, archived, createdAt, updatedAt, ownerUserId)
  - `NotebookSource` (many-to-many Notebook ↔ existing `Fact`/`Document`; allows a source to live in multiple notebooks)
  - `Note` (id, notebookId, title, content, type=HUMAN|AI, sourceOriginRef, embedding, tags, createdAt, updatedAt) — embedded and searchable in the same vector store as sources
  - `NoteSourceLink` (optional back-ref: a note derived from a source/chunk/response)
  - `ChatSessionContext` (per-session per-source mode: FULL | SUMMARY_ONLY | EXCLUDED) on the existing `ChatSession` entity
- Services: `NotebookService`, `NoteService`, `NoteEmbeddingService` (reuses current embedding pipeline)
- Controllers: `/api/notebook`, `/api/notebook/{id}/notes`, `/api/notebook/{id}/sources`, `/api/notebook/{id}/chat`
- Tools: `NotebookTool` (list/get/create/update/archive/addSource/removeSource), `NoteTool` (create/update/delete/promote from chat response, search notes)
- UI: new `notebook-hub` component (list + create), `notebook-detail` (sources tab, notes tab, chat tab, artifacts tab), integration into existing `unified-chat` to scope a chat session to a notebook and per-source context toggles.

**Migration from FactSheet:** keep FactSheet as the physical-index primitive. A Notebook becomes a logical grouping that can reference one or more FactSheets. Existing FactSheet switching stays for low-level operators; typical users interact only with notebooks. Document the separation: FactSheet = "physical index scope"; Notebook = "curated research workspace."

**Four-place POM update rule:** register `kompile-notebook` in `kompile-app/pom.xml` (module + dependencyManagement), `kompile-app-main/pom.xml` (dependency), `kompile-rag-builds/kompile-sample/project/pom.xml` (dependency), `RagPomGenerator.java` (`--includeNotebook` option + `addDependency` call).

### Phase 2 — Source type coverage (highest research value first)

**2.1 Audio/Video file ingest with STT**
- New module: `kompile-app/kompile-loader-audio-video/` (or extend `kompile-loader-tika`)
- Loader: `AudioVideoLoaderImpl implements DocumentLoader` with pluggable STT backends:
  - Local: `KompileLocalModelService` already has infrastructure for local models; add Whisper.cpp or `whisper-jni` via JavaCPP (matches existing JavaCPP pattern)
  - API: OpenAI Whisper (reuse `kompile-embedding-openai` auth), Groq, ElevenLabs
- Output: transcript with segment timestamps as chunk metadata (`timestamp_start`, `timestamp_end`, reuse `SourceMetadataConstants`)
- Source provider: `AudioVideoSourceProvider` with form fields (file, language, provider, timestamp granularity)
- Citation UI: extend `source-ref-link` to render `[00:12:34]` timestamp chips, jump to playback offset if the source is an audio/video type. Store playable URL in `Document` metadata.

**2.2 YouTube transcript as a first-class DocumentLoader**
- Extract YouTube logic from `YouTubeSourceProvider` into `YouTubeTranscriptLoaderImpl implements DocumentLoader` under `kompile-loader-web` (it's web-adjacent) or a new `kompile-loader-youtube`
- Support playlists: iterate entries, create one source per video
- Fallback to audio transcription (reuse 2.1) when no captions available
- Multi-language preference order (match open-notebook's `en, pt, es, de, nl, fr, hi, ja`)
- Chunk metadata: `video_id`, `channel`, `published_at`, `timestamp_start`, `timestamp_end`

**2.3 Podcast RSS**
- New `PodcastRssSourceProvider` (no new loader needed if 2.1 exists — each episode goes through audio transcription)
- Source provider form: RSS URL, episode range filter, auto-sync toggle

**2.4 Image ingest (standalone, without full OCR pipeline wrapper)**
- Route through the existing `VlmDocumentPipeline` but expose it as a `ImageSourceProvider` at the same level as `FileUploadSourceProvider`
- Accept AVIF, BMP, GIF, HEIC, HEIF, JP2, JPEG, PNG, TIFF, WEBP (match NotebookLM)

**2.5 Missing OAuth connectors (ranked by value)**
1. **GitHub** — highest value for developer audience. New `kompile-source-github` module mirroring `kompile-source-slack` structure. OAuth via existing handler pattern; ingest: repo issues, PRs, READMEs, markdown docs, wiki pages. Use GitHub REST API v3 or GraphQL.
2. **Linear** — project management research. New `kompile-source-linear`. API key or OAuth. Ingest: issues, projects, documents.
3. **Discord** — mirrors Slack. New `kompile-source-discord`. Reuse `DiscordChannelAdapter` from `kompile-openclaw` (already has Discord API client wiring for the channel gateway — factor out the auth/client layer into `kompile-openclaw-discord-client` if needed).
4. **OneDrive** — `MicrosoftOAuthHandler` already exists in `kompile-oauth2-client`. Add `kompile-source-onedrive` that reuses it. Mirror `kompile-source-gdrive`.
5. **Dropbox, Airtable, ClickUp** — lower priority; template from GitHub implementation.

**Connector template** (applies to all 2.5 additions):
- Entity: reuse `OAuthConnection` + per-source config rows
- Service: `{Name}SyncService` with `initialSync()` and `incrementalSync(since)` methods
- Scheduler: reuse or extend an existing scheduled-sync pattern; all one-shot + periodic pull, no webhooks in v1 (match SurfSense)
- Indexer: emit `Document`s into the active FactSheet / notebook

### Phase 3 — Research actions (comprehension, not generation)

**3.1 Transformations** (open-notebook's strongest idea)
- New module: `kompile-app/kompile-transformations/`
- Entities:
  - `TransformationTemplate` (id, name, title, description, promptTemplate, applyByDefault, modelHint, outputType=INSIGHT|NOTE)
  - `SourceInsight` (id, sourceId, notebookId, transformationId, content, createdAt) — ephemeral, per-source
  - Promotion: an `Insight → Note` endpoint that copies content into a `Note`
- Built-in templates seeded at startup: Summary, Key Concepts, Methodology, Takeaways, Open Questions, Entities+Relations, Glossary
- Execution: LangGraph-style or simple `CompletableFuture` parallel fan-out — reuses existing LLM abstractions in `kompile-app-core/llm/`
- UI: `transformation-manager` for templates, "Apply transformation" button in `source-viewer-dialog`, batch-apply in `fact-sheet-manager`
- Tool: `TransformationTool` (list templates, apply, list insights, promote to note)
- Security: use `org.apache.velocity` or `pebble`'s sandboxed context for prompt templating — **do not use raw Jinja2/Freemarker without sandboxing** (open-notebook shipped CVSS 9.2 SSTI via Jinja2; learn from it)

**3.2 Notebook artifacts (Study Guide / FAQ / TOC / Timeline / Key Topics)**
- These are all structured extractions over a notebook's sources. Implementable as **special-purpose transformations** that run over the whole notebook (not one source), writing to a new `NotebookArtifact` entity (id, notebookId, type, content, model, createdAt, stale flag).
- Artifact types:
  - `STUDY_GUIDE`: key concepts + explanations (schema: list of {concept, explanation, sourceRefs})
  - `FAQ`: Q/A pairs extracted from content
  - `TOC`: hierarchical outline
  - `TIMELINE`: extracted events with dates, sorted (schema: list of {date, event, sourceRefs})
  - `KEY_TOPICS`: ranked topic list with source counts
  - `DATA_TABLE`: user-defined columns via natural-language prompt → structured rows with per-cell source links
- Staleness: mark artifacts stale when a notebook source is added/removed/re-indexed. UI shows "regenerate" button.
- UI: `notebook-detail` → Artifacts tab. Timeline renders as vertical timeline component. Data Tables render as editable grid with "export CSV" and "export to spreadsheet" actions.

**3.3 Topic mind map**
- Reuse `kompile-knowledge-graph` infrastructure but at a higher abstraction level: topics/concepts rather than entities.
- Service: `NotebookMindMapService` that (a) runs topic-level extraction (clustering of `CONCEPT` entities already extracted by `ConceptExtractor`) and (b) produces a tree/DAG of topics with source counts and per-node sample passages.
- UI: extend the existing D3 `graph-visualizer` with a "mind-map" layout mode (hierarchical/radial), click-to-expand with per-node summary drawer and "ask about this topic" follow-up input.

**3.4 Highlights, annotations, bookmarks**
- New entity `PassageAnnotation` (id, sourceId, chunkId, offsetStart, offsetEnd, text, note, tags, color, userId, createdAt)
- Citation from any chat response can be "saved" as an annotation
- UI: passage highlighting in `source-viewer-dialog`; `annotations-browser` component; "my annotations" tab in `notebook-detail`
- Tool: `AnnotationTool` (create/list/search/delete) so the agent can attend to user-saved passages preferentially

**3.5 Follow-up question suggestions**
- After each chat response, call the LLM with a compact prompt + top retrieved chunks to produce 3 follow-ups.
- Cache suggestions per-message in `ChatMessage` metadata to avoid regeneration.
- UI: chip row beneath each assistant response — click to send as next user turn.

**3.6 Saved searches**
- New entity `SavedSearch` (id, ownerUserId, notebookId, name, query, filters, retrievalMode, createdAt)
- UI: save button in `unified-chat` query bar; sidebar list to re-run
- Tool: `SavedSearchTool` (list/run/save/delete)

**3.7 Per-source context control in chat (open-notebook parity)**
- Part of Phase 1's `ChatSessionContext`. UI: per-source toggle in the chat right panel with three states (Full / Summary / Excluded). Summary uses whichever `SourceInsight` of type `Summary` exists, else generates on the fly and saves it.

### Phase 4 — Discover-style web research loop

- New module: `kompile-app/kompile-web-research/` (or integrate into `kompile-react-agent`)
- Service: `WebDiscoveryService` with a LangGraph-style plan:
  1. Accept a natural-language topic
  2. Expand to N search queries (LLM)
  3. Parallel web search (reuse existing search provider interfaces; add `TavilySearchProvider`, `LinkupSearchProvider`, `SearxngSearchProvider` as plug-ins)
  4. Fetch top results (extend `WebPageSourceProvider` to accept a crawl queue; add Firecrawl integration as an optional provider)
  5. LLM-score each candidate for relevance + quality + source-type diversity
  6. Return top-K (default 10) annotated candidates with "why this is relevant" reasoning
- Distinction from SurfSense's web_search: this flow **indexes** results into the target notebook once the user confirms — closes the research loop. Add an explicit confirmation step to avoid dumping low-quality sources.
- UI: new `discover-panel` inside `notebook-detail`: topic input → candidates list with reasoning → bulk add button.
- Tool: `DiscoverTool` (run discovery, get candidates, add candidate).

### Phase 5 — Auth, users, sharing

Treat as a single phased rollout — it's prerequisite for anything multi-tenant.

- New module: `kompile-app/kompile-auth/`
- Entities: `User`, `Role`, `Permission`, `Invitation`, `NotebookShare` (notebookId, userId, role), `Workspace` (optional — start without; promote if needed)
- Spring Security: OAuth2 login (Google, Microsoft, GitHub — reuse existing handlers), local username/password as a fallback, session or JWT tokens
- RBAC: Owner / Editor / Viewer / Chat-only on Notebook (match NotebookLM + SurfSense intersection). Custom roles deferred.
- Public notebooks: read-only share link with chat (mirror NotebookLM consumer-tier public sharing). Opt-in per notebook.
- All existing controllers gain principal injection and permission checks. Keep a `SINGLE_USER` profile for current deployments to avoid breaking existing users.
- UI: `login`, `account-settings`, `notebook-share-dialog`, `invitations`, "Shared with me" view.

### Phase 6 — Agent memory + HITL + browser extension

These are independent; pick any order.

**6.1 Persistent agent memory** (SurfSense pattern)
- Entities: `AgentMemory` (scope=USER|TEAM, ownerRef, content, updatedAt) per notebook or per workspace
- Services: `MemoryInjectionService` (injects into every agent turn's system prompt under a size budget), `MemoryExtractionService` (async, after each turn, extracts `(YYYY-MM-DD) [fact|pref|instr]` items from the exchange)
- Size discipline: 18k chars soft (trigger consolidation), 25k chars hard (LLM-driven compression). Borrow constants verbatim from SurfSense analysis.
- UI: memory editor under notebook settings.
- Hook into `kompile-react-agent` via new `AgentHook` implementations.

**6.2 HITL approval gates**
- New interrupt primitive in `kompile-react-agent`: `HumanApprovalStep` that pauses the loop, emits an SSE event, waits for approve/edit/reject.
- "Always allow" setting per tool per user to avoid approval fatigue.
- UI: inline approval card in `unified-chat` above the pending tool call.
- Required for any write-back connector action (match SurfSense).

**6.3 Browser extension** (web clipper)
- New project: `kompile-browser-extension/` (Manifest V3, Plasmo or raw).
- Uses `dom-to-semantic-markdown` (same as SurfSense) to convert rendered DOM → markdown preserving structure.
- Posts to a new `/api/source/clip` endpoint with an API token tied to a user (requires Phase 5).
- Saves into a default "Clips" notebook unless the user pre-selected one via the extension UI.
- Captures auth-protected pages (not just public HTML).

## 4. Prioritized sequencing

Ordering by (research value × breadth) / effort. Phases are independently shippable.

| Priority | Phase | Rationale |
|---|---|---|
| P0 | 1 — Notebook + notes + per-source context | Unblocks most of the rest; lowest-risk refactor since FactSheet stays underneath. |
| P0 | 2.1 + 2.2 — Audio/video + YouTube loader | Fills two large content modalities; low integration risk; reuses existing loader orchestrator. |
| P1 | 3.1 + 3.2 — Transformations + artifacts | Highest user-visible value; purely additive; reuses LLM abstractions. |
| P1 | 3.7 + 3.5 — Per-source control + follow-ups | Small, visible UX wins; completes Phase 1. |
| P1 | 2.5 (GitHub, Linear, Discord) | Highest-value missing connectors for dev audience. |
| P2 | 3.3 — Mind map | Builds on existing graph; medium effort. |
| P2 | 3.4 — Annotations | Medium effort; opens up many downstream features. |
| P2 | 4 — Discover web research | Distinct product feature; depends on search-provider plugins. |
| P3 | 5 — Auth + sharing | Prerequisite for SaaS; not required for local single-user deploys. |
| P3 | 6 — Memory, HITL, browser extension | Power-user features; lower mass-market value. |

## 5. Cross-cutting concerns

- **Embedding coverage of notes and insights:** anything text-based produced by the system (notes, insights, artifact bodies, annotations) must flow through the existing embedding pipeline and live in the same vector index, so hybrid search crosses sources + user content uniformly. This is how open-notebook surfaces notes in retrieval alongside sources; replicate it.
- **Citation integrity:** all LLM-emitted content that references material (artifact bodies, insights, answers) must cite by chunk id using the existing `source-ref-link` pattern. Store citation lists as structured metadata, not free text embedded in the content — so rendering can re-resolve as documents change.
- **Staleness propagation:** any derived artifact (insights, notebook artifacts, annotations, saved searches, memory snapshots) should carry a `derivedAtVersion` field; when the underlying sources change, mark `stale=true` rather than silently continuing to render. NotebookLM does not do this; Kompile should.
- **Security of prompt templates:** Phase 3.1 must use a sandboxed templating engine. Open-notebook's Jinja2 CVSS 9.2 SSTI is the cautionary tale; do not repeat.
- **Provider-agnostic STT:** Phase 2.1 should plug into the same provider-abstraction layer already used for LLMs and embeddings. Spring `@ConditionalOnProperty` for local vs API.
- **FactSheet continuity:** Every new research concept layers on top of FactSheets rather than replacing them. This preserves the strong existing hybrid retrieval + reranker suite and the cross-index tracking service, and does not break existing users.

## 6. Out of scope (explicit)

Per the original ask, the following benchmark features are **not** in this plan:

- Audio Overviews / two-host podcast generation
- Video Overviews / infographic generation
- Briefing Documents (generative summary docs)
- Flashcards / Quizzes (learning-content generation)
- Quizlet-style exports
- Voice chat input
- Interactive audio conversation ("Join the Conversation")

Kompile may choose to add these later, but they are content-generation concerns, not research/information concerns, and are explicitly de-prioritized here.
