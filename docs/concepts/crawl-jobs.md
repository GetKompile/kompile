# Crawl Jobs

A crawl job discovers and ingests documents from one or more sources into the
vector index and knowledge graph. Crawl jobs are the primary mechanism for
getting data into Kompile.

## Two crawl systems

Kompile runs two crawl systems that share the same CLI surface:

| System | Endpoint | When used |
|--------|----------|-----------|
| **Simple crawl** | `POST /api/crawlers/start` | Single source, no graph extraction, no VLM |
| **Unified crawl** | `POST /api/unified-crawl/start` | Multi-source, graph extraction, VLM, adaptive batching, distributed |

The CLI auto-selects: if you enable `--graph`, `--multimodal`, or supply
multiple sources, it uses the unified crawl system. Otherwise it uses the
simple crawl.

## Source types

Kompile supports 25+ source types. Each maps to a dedicated loader and
optional crawler implementation:

| Source type | Description | Loader module |
|-------------|-------------|---------------|
| `FILE` | Single local file | (auto-detected by extension) |
| `DIRECTORY` | Recursive directory scan | (auto-detected by extension) |
| `WEB_CRAWL` | Recursive web crawl with configurable depth | `kompile-loader-web` |
| `S3` | Amazon S3 buckets | (remote folder crawler) |
| `SFTP` | SFTP servers | (remote folder crawler) |
| `SMB` | SMB/CIFS network shares | (remote folder crawler) |
| `SQL` | SQL databases (table extraction) | `kompile-crawler-core` (SqlCrawler) |
| `IMAP` | IMAP email server | `kompile-loader-email-imap` |
| `POP3` | POP3 email server | `kompile-loader-email-imap` |
| `GMAIL` | Gmail mailbox (OAuth) | `kompile-loader-gmail` |
| `MBOX` | MBOX mail archives | `kompile-loader-email-inbox` |
| `MAILDIR` | Maildir directories | `kompile-loader-email-inbox` |
| `EMLX_DIR` | Apple Mail EMLX files | `kompile-loader-email-inbox` |
| `PST` | Outlook PST archives | `kompile-loader-email-inbox` |
| `CONFLUENCE` | Atlassian Confluence (OAuth) | `kompile-source-confluence` |
| `JIRA` | Atlassian Jira (OAuth) | `kompile-source-jira` |
| `NOTION` | Notion workspaces (OAuth) | `kompile-source-notion` |
| `SLACK` | Live Slack channels (OAuth) | `kompile-loader-slack` |
| `SLACK_HISTORY` | Exported Slack history archives | `kompile-loader-slack` |
| `DISCORD` | Discord servers (OAuth) | `kompile-loader-discord` |
| `DISCORD_HISTORY` | Exported Discord history | `kompile-loader-discord` |
| `GDRIVE` | Google Drive (OAuth) | `kompile-loader-gdrive` |
| `GDOCS` | Google Docs (OAuth) | `kompile-loader-gdocs` |
| `ONEDRIVE` | Microsoft OneDrive (OAuth) | `kompile-loader-onedrive` |
| `GOOGLE_WORKSPACE` | Combined Google Workspace | `kompile-loader-google-workspace` |
| `REDDIT` | Reddit threads | `kompile-source-reddit` |
| `URL` | Single URL fetch | `kompile-loader-web` |

Cloud sources use OAuth connections managed through the **Connected Services**
screen in the web UI. Each provider shows connection status, token expiry,
required scopes, and connect/disconnect/refresh actions.

## Document loaders

Each source type maps to a specialized loader that understands the format:

| Loader | Formats | Notable features |
|--------|---------|-----------------|
| PDF extended | `.pdf` | Content classification (text-only/image-based/mixed), table extraction, streaming for large files |
| PDF tables | `.pdf` | Tabula-based table extraction, formula dependency graphs |
| Microsoft Office | `.docx`, `.pptx`, `.xlsx` | Word, PowerPoint, Excel; streaming variant for large files |
| Excel | `.xlsx`, `.xls`, `.xlsm`, `.xlsb` | Formula dependency graph extraction (cell-to-cell, cross-sheet) |
| Tika | 1000+ formats | Apache Tika fallback for any format not handled by a dedicated loader |
| Audio | `.mp3`, `.wav`, `.m4a`, etc. | Whisper-based transcription, transcript graph extraction |
| Web/HTML | `.html` | Link structure extraction, metadata extraction |
| Email (IMAP/POP3) | IMAP, POP3 | Live mailbox access, attachment extraction |
| Email inbox | `.eml`, `.mbox`, `.pst`, `.emlx` | Thread graph extraction, MIME parsing, attachment handling |
| Slack | Slack API / export | Channel message loading, attachment extraction |
| Discord | Discord API / export | Channel/message loading, attachment extraction |
| Google Docs | Google API | Document parsing, structure extraction |
| Google Drive | Google API | Sheets parser, Slides parser, file download |
| Gmail | Google API | Message parsing, thread reconstruction |
| OneDrive | Microsoft API | File download, metadata extraction |

The **loaders orchestrator** (`ConfigurableDocumentLoadingServiceImpl`) routes
each document to the correct loader based on source type and configuration.

## Crawl configuration

### Simple crawl options

| Parameter | Description | Default |
|-----------|-------------|---------|
| `crawlerId` | Unique job identifier | auto-generated |
| `seed` | URL, path, or connection string | required |
| `sourceType` | One of the source types above | required |
| `maxDepth` | How deep to follow links | 3 |
| `maxDocuments` | Maximum documents to process | 1000 |
| `requestDelay` | Delay between requests (ms) | 500 |
| `timeout` | Job timeout | 1 hour |
| `includePatterns` | URL/path patterns to include | all |
| `excludePatterns` | URL/path patterns to exclude | none |
| `allowedContentTypes` | MIME types to process | all supported |
| `sameDomainOnly` | Stay on the same domain (web crawl) | true |
| `respectRobotsTxt` | Obey robots.txt (web crawl) | true |
| `collectionName` | Vector collection name | default |
| `loaderName` | Override which loader to use | auto-detect |
| `chunkerName` | Override which chunker to use | default |
| `forceRecrawl` | Re-crawl all URLs even with prior checkpoint | false |

### Unified crawl request

The unified crawl wraps multiple sources in a single request with shared
configuration:

```json
{
  "name": "Q2 knowledge base refresh",
  "factSheetId": "fs-001",
  "sources": [
    {"sourceType": "WEB_CRAWL", "pathOrUrl": "https://docs.example.com", "maxDepth": 3},
    {"sourceType": "DIRECTORY", "pathOrUrl": "/data/reports/", "includePatterns": ["*.pdf"]},
    {"sourceType": "CONFLUENCE", "pathOrUrl": "https://company.atlassian.net/wiki/spaces/ENG"}
  ],
  "graphExtraction": {
    "enabled": true,
    "schemaMode": "LENIENT",
    "entityResolution": true,
    "entityResolutionSimilarityThreshold": 0.85,
    "entityResolutionUseEmbeddings": true,
    "entityResolutionEmbeddingThreshold": 0.88,
    "minConfidence": 0.5
  },
  "vectorIndex": {
    "enabled": true,
    "adaptiveBatching": true,
    "collectionName": "q2-docs"
  },
  "processingRoute": {
    "pdfRoutingMode": "AUTO",
    "extractTablesFromTextPdfs": true,
    "fallbackEnabled": true
  },
  "runtimeConfig": {
    "graphExtractionParallelism": 4,
    "sourceLoadParallelism": 2,
    "vectorBatchSize": 32,
    "parallelVectorAndGraph": true,
    "llmCallTimeoutSeconds": 120
  }
}
```

### Pipeline routing

Crawl jobs support **content route rules** that direct documents to different
processing pipelines. Rules are matched by priority (lower number = higher
priority, default 100). All present conditions are AND-combined:

| Match condition | Description |
|-----------------|-------------|
| `contentTypes` | MIME type list (`text/html`, `application/pdf`) |
| `fileExtensions` | File extension list (`pdf`, `xlsx`) |
| `urlPatterns` | Regex list matched against the full URL |
| `sourceTypes` | Source type list |
| `minSizeBytes` / `maxSizeBytes` | File size range |
| `languages` | ISO 639-1 language codes (`en`, `de`, `fr`) |
| `minLanguageConfidence` | Minimum confidence from language detection |
| `tags` | Document tag list |
| `contentPatterns` | Regex list matched against document text content |
| `metadataMatchers` | Key-value regex pairs matched against document metadata |
| `minPages` / `maxPages` | Page count range (PDF/Office) |

Each rule maps matching documents to a `pipelineId` that references an
`IngestPipelineDefinition`.

### Pipeline types

| Type | Description |
|------|-------------|
| `STANDARD_TEXT` | Default text extraction and chunking |
| `VLM` | Vision-language model OCR for image-heavy documents |
| `OCR` | Traditional OCR pipeline |
| `CODE` | Source code-aware chunking |
| `TABLE_AWARE` | Table-preserving extraction |
| `KEYWORD_ONLY` | Skip embedding, index for BM25 keyword search only |
| `CUSTOM` | User-defined processing with arbitrary options |

Each pipeline definition can override: loader, chunker, embedding model,
language, chunk size/overlap, graph extraction settings, LLM provider for
extraction, and processing mode (in-process, subprocess, or auto).

### Content type routing

When a document is loaded, its `content_type` metadata determines routing:

| `content_type` | Routing |
|----------------|---------|
| `text` (or null) | Standard text pipeline |
| `table` | Text pipeline; uses `full_table_content` for embedding |
| `vlm_document` | VLM pipeline (image-heavy PDFs, scanned documents) |
| `formula_graph` | Graph-only; JSON formula dependency persisted on DOCUMENT node |
| `slide` / `presentation` | VLM pipeline |
| `spreadsheet` | Text pipeline with spreadsheet-aware chunking |
| `image` | Graph-only; excluded from text pipeline |
| `audio` / `video` | Graph-only; excluded from text pipeline |
| `chart` | Table promotion + graph-only |

### Multimodal pipeline construction

When `--multimodal` is set on the CLI, the system automatically constructs
four pipelines and route rules:

1. **text** pipeline (`STANDARD_TEXT`) -- default
2. **visual** pipeline (`VLM`) -- PDFs + images routed here (priority 10)
3. **tables** pipeline (`TABLE_AWARE`) -- spreadsheets + CSV (priority 20)
4. **email** pipeline (`STANDARD_TEXT`) -- email formats (priority 30)

## PDF routing

Kompile auto-classifies PDFs using a content classifier that inspects page
resources. Classification results:

| Classification | Meaning |
|----------------|---------|
| `TEXT_ONLY` | Extractable text on all pages |
| `IMAGE_BASED` | Primarily scanned images |
| `MIXED` | Both text and significant images |
| `UNKNOWN` | Unable to classify |

The threshold is `textThresholdCharsPerPage=50` -- pages with fewer characters
are considered image-based.

**Routing modes:**

| Mode | Behavior |
|------|----------|
| `AUTO` (default) | IMAGE_BASED and MIXED go to VLM; TEXT_ONLY goes to text pipeline |
| `FORCE_VLM` | All PDFs go to VLM regardless of content |
| `FORCE_TEXT` | All PDFs go to text extraction regardless of content |
| `DISABLED` | PDF routing logic skipped entirely |

**Processing backends for VLM:**

Multiple backends can be configured for VLM processing with automatic
fallback:

| Backend type | Example | Priority |
|--------------|---------|----------|
| `LOCAL_MODEL` | Local SameDiff VLM | 1 (highest) |
| `CLI_AGENT` | Claude Code agent | 2 |
| `API_AGENT` | OpenAI Vision API | 3 |

Each backend has concurrency limits, rate limits, and GPU memory budgets.
When `fallbackEnabled=true`, capacity-based failover routes to the next
available backend.

## Preprocessing pipeline

Before documents enter the main ingestion pipeline, they pass through
a preprocessing pipeline of ordered steps:

| Step | Order | Description |
|------|-------|-------------|
| Language detection | 1 | Detects document language |
| Translation | 2 | Translates non-target-language documents |
| Boilerplate removal | 3 | Strips navigation, footers, headers |
| PII redaction | 4 | Redacts names, emails, SSNs, etc. |
| Deduplication | 5 | Content-hash deduplication |
| Unicode normalization | 6 | Normalizes to NFC/NFKC |

Each step reports start, complete, fail, or skip events to a listener.

## Ingestion pipeline phases

Documents pass through phases tracked in real-time:

```
QUEUED -> LOADING -> OCR_PROCESSING -> CONVERTING -> CHUNKING
  -> EXTRACTION -> INDEXING_AND_EMBEDDING -> GRAPH_EXTRACTION
  -> EMBEDDING -> INDEXING -> COMPLETED / FAILED
```

The unified crawl tracks more granular stages internally:

```
DISCOVERING -> LOADING -> CONVERTING -> ROUTING -> CHUNKING
  -> GRAPH_PREP -> GRAPH_EXTRACTION -> ENTITY_RESOLUTION
  -> EDGE_COMPUTATION -> EMBEDDING -> INDEXING -> VECTOR_INDEXING
  -> COMPLETED
```

Subprocesses are the same binary re-launched with `--subprocess=TYPE`:

| Subprocess | What it does |
|------------|-------------|
| `ingest` | Document loading and chunking |
| `vector-population` | Embedding generation and vector indexing |
| `embedding` | Standalone embedding computation |
| `model-init` | Model download and initialization |
| `vlm-test` | VLM pipeline testing |
| `training` | Model training jobs |

## Adaptive batching

Embedding batch sizes adjust dynamically using AIMD (Additive Increase /
Multiplicative Decrease), the same algorithm used in TCP congestion control:

- **On success** for N consecutive rounds: `batchSize += additiveIncrease`
- **On memory pressure**: `batchSize *= (1 - multiplicativeDecrease)`, clamped to minimum
- **On OOM**: double multiplicative decrease (emergency shrink)

Each pipeline stage has its own tuning profile:

| Stage | Heap warn/crit | Native warn/crit | Increase | Decrease |
|-------|---------------|-------------------|----------|----------|
| Embedding | 78% / 88% | 78% / 88% | +4 | 0.5x |
| Graph extraction | 82% / 92% | 82% / 92% | +1 | 0.6x |
| Vector indexing | 80% / 90% | 80% / 90% | +4 | 0.5x |
| Chunking | 85% / 95% | 85% / 95% | +8 | 0.7x |

EMA (Exponential Moving Average) latency tracking with alpha=0.3 provides
smoothed throughput metrics visible in the WebSocket progress stream.

## Memory monitoring

The `CrawlMemoryMonitor` tracks memory across all crawl jobs:

**JVM heap**: updated from `Runtime.getRuntime()` on every check.

**Native memory** (rate-limited to every 2 seconds): JavaCPP
`Pointer.physicalBytes()`, direct buffer pools via `BufferPoolMXBean`,
ND4J workspace memory.

**Wait behavior** when memory is under pressure:

1. One-time cleanup: trim native memory + `System.gc()`
2. Back-off spin: 2s -> 3s -> 4s -> 5s intervals (capped)
3. GC trigger every ~15 seconds (every 5th iteration)
4. Timeout after 300 seconds (configurable) -- logs warning and continues

**Native memory trimming** (rate-limited to 5-second intervals):

1. `Pointer.deallocateReferences()`
2. `Nd4j.getExecutioner().commit()`
3. `Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread()`
4. `Nd4j.getNativeOps().trimMemoryPool()` per CUDA device
5. Second `deallocateReferences()` pass

**Configuration:**

| Setting | Default | Description |
|---------|---------|-------------|
| `memoryWaitThresholdPercent` | 82 | Start waiting for memory below this |
| `memoryCriticalThresholdPercent` | 90 | Emergency cleanup threshold |
| `memoryWaitTimeoutSeconds` | 300 | Give up waiting after this |
| `nativeMemoryCleanupEnabled` | true | Enable native memory trimming |
| `nativeMemoryCleanupPasses` | 3 | Number of cleanup passes |

## Crawl lifecycle

### States

**Simple crawl:**

```
RUNNING -> COMPLETED
       \-> PAUSED -> RUNNING (resumed)
       \-> CANCELLED
       \-> FAILED
       \-> INTERRUPTED (unclean shutdown)
```

**Unified crawl:**

```
PENDING -> RUNNING -> COMPLETED
                  \-> COMPLETED_PENDING_EMBEDDING
                  \-> PAUSED -> RUNNING (resumed)
                  \-> CANCELLED
                  \-> FAILED
```

### Checkpointing

Simple crawl jobs checkpoint every 60 seconds (configurable via
`kompile.crawl.checkpoint.interval-ms`). The checkpoint serializes
`visitedUrls` (Set) and `pendingUrls` (Queue) to the database.

On startup, any jobs left in RUNNING or PAUSED state are marked INTERRUPTED
to handle unclean shutdowns.

### Resume and restart

Jobs are resumable when they have a checkpoint and their status is
INTERRUPTED, FAILED, or CANCELLED. Resuming:

1. Loads the original `CrawlConfig` from the database
2. Loads the `CrawlState` checkpoint (visited + pending URLs)
3. Sets `previousState` on the new config
4. Starts a new job, skipping already-visited URLs
5. Records `resumedFromJobId` for lineage tracking

`forceRecrawl=true` overrides this and re-crawls all URLs.

### Failure tracking

Each job tracks 16 failure reasons:

`NONE`, `OUT_OF_MEMORY`, `MEMORY_KILLED`, `USER_CANCELLED`, `LOAD_ERROR`,
`CONVERSION_ERROR`, `CHUNKING_ERROR`, `EMBEDDING_ERROR`, `INDEXING_ERROR`,
`SUBPROCESS_ERROR`, `IO_ERROR`, `INVALID_INPUT`, `TIMEOUT`,
`MODEL_NOT_FOUND`, `STAGING_ERROR`, `UNKNOWN`

Restart tracking: `restartAttempts`, `maxRestartAttempts`, `lastRestartTime`,
`recoveredAfterRestart`, with full restart history JSON.

## Graph extraction during crawl

When graph extraction is enabled, each batch of chunks is sent to an LLM that
extracts structured entities and relationships. See
[Knowledge Graphs](knowledge-graphs.md) for full details.

**Configuration within a crawl:**

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | true | Enable graph extraction |
| `schemaMode` | LENIENT | NONE / LENIENT / STRICT |
| `schemaPresetId` | -- | Named schema preset |
| `entityTypes` | -- | Explicit entity type names |
| `relationshipTypes` | -- | Explicit relationship type names |
| `llmProvider` | default | LLM for extraction |
| `modelName` | -- | Model name for extraction |
| `temperature` | 0.0 | LLM temperature |
| `maxTokens` | 4096 | Max tokens per extraction call |
| `customPrompt` | -- | Override the extraction prompt |
| `entityResolution` | true | Deduplicate entities across chunks |
| `entityResolutionSimilarityThreshold` | 0.85 | Text similarity threshold |
| `entityResolutionUseEmbeddings` | true | Use vector embeddings for resolution |
| `entityResolutionEmbeddingThreshold` | 0.88 | Cosine similarity threshold |
| `minConfidence` | 0.5 | Minimum confidence to accept a triple |

**Schema modes:**

| Mode | Behavior |
|------|----------|
| `NONE` | No schema enforcement -- LLM extracts any types |
| `LENIENT` | Schema-guided but allows novel types not in the schema |
| `STRICT` | Only types defined in the schema are accepted; others are discarded |

## Token budget tracking

The unified crawl tracks LLM costs per job:

- `totalInputTokens`, `totalOutputTokens`
- `estimatedCostCentsX100` (cost in hundredths of a cent)
- Per-backend routing stats: EMA latency, active requests, health status

Backend cooling: unhealthy backends are temporarily removed from the
rotation with a cooldown period before retrying.

## Distributed crawl

Unified crawls support distributed execution across multiple workers:

| Partition strategy | Description |
|--------------------|-------------|
| `ROUND_ROBIN` | Distribute sources evenly across workers |
| `BY_TYPE` | Group sources by type (all PDFs on one worker, etc.) |
| `BY_SIZE` | Balance by estimated document size |
| `PER_SOURCE` | Each source gets its own worker |
| `HASH_SHARD` | Consistent hash-based sharding |

A `DistributedCrawlCoordinator` manages work distribution. The
`WorkStealingTaskQueue` rebalances load when workers finish early.

## Resource-aware scheduling

Crawl jobs are submitted to a `ResourceAwareJobScheduler` that gates
execution based on available resources. During graph extraction phases,
GPU resources can be yielded to the embedding subprocess when parallelism
is unavailable.

## Real-time monitoring

### WebSocket topics

| Topic | Content |
|-------|---------|
| `/topic/crawl/progress` | Periodic progress snapshots |
| `/topic/crawl/complete` | Job completion notification |
| `/topic/ingest/{taskId}/logs` | Per-task log events |
| `/topic/ingest/logs` | Global log stream |

### Progress counters in unified crawl

The unified crawl job tracks 30+ atomic counters visible in real-time:

- **Document flow**: `documentsDiscovered`, `documentsLoaded`, `documentsPreprocessed`, `documentsTranslated`, `documentsIndexed`
- **Chunk flow**: `chunksCreated`, `chunksProcessed`, `chunksQueuedForEmbedding`, `chunksEmbedded`
- **Graph flow**: `graphChunksProcessed`, `graphChunksTotal`, `entitiesExtracted`, `relationshipsExtracted`
- **Batch stats**: `vectorBatchesTotal`, `vectorBatchesCompleted`, `adaptiveBatchSize`, `batchSizeAdjustments`
- **Memory**: `heapUsedBytes`, `heapMaxBytes`, `nativePhysicalBytes`, `directBufferBytes`
- **Errors**: `graphExtractionRetries`, `graphExtractionParseFailures`, `reroutedItems`, `droppedItems`, `deadLetterCount`
- **Work stealing**: `workStealCount`, `workStealFailures`, `localDispatchCount`, `workImbalanceRatioX100`

### RSS monitoring

For subprocess-based crawls, the system reads `/proc/{pid}/status` VmRSS
for the main process and all child processes, identifying each subprocess
type by its command line arguments.

## Starting a crawl

### From the CLI

```bash
# Simple file ingest
kompile crawl start /path/to/documents/

# Web crawl with depth
kompile crawl start https://docs.example.com --depth=3

# With graph extraction and a schema preset
kompile crawl start /path/to/docs/ --graph --schema-preset=technology

# Multimodal (VLM for PDFs, table-aware for spreadsheets)
kompile crawl start /path/to/mixed/ --multimodal

# Full options
kompile crawl start https://docs.example.com \
  --depth=3 \
  --max-docs=500 \
  --same-domain \
  --graph \
  --graph-model-provider=anthropic \
  --graph-schema-mode=STRICT \
  --graph-entities="Person,Organization,Technology" \
  --graph-relations="WORKS_FOR,USES,DEPENDS_ON" \
  --graph-auto-accept \
  --graph-min-confidence=0.7 \
  --collection=my-docs \
  --fact-sheet=research \
  --watch

# Interactive wizard
kompile crawl wizard

# Check all jobs
kompile crawl status

# Check specific job
kompile crawl status <jobId>

# Lifecycle management
kompile crawl pause <jobId>
kompile crawl resume <jobId>
kompile crawl cancel <jobId>

# Clean up old jobs
kompile crawl cleanup --days=30

# List available source types
kompile crawl sources
```

**Auto-detection:** The CLI infers source type from the path/URL:
- `http://` or `https://` -> `WEB_CRAWL`
- `.xlsx`, `.xls`, `.xlsm`, `.xlsb` -> Excel
- Directory path -> `DIRECTORY`
- Everything else -> `FILE`

### From the web UI

Knowledge tab -> New Crawl Job. The form lets you:

1. Add one or more sources, each with its own type, path/URL, depth, and document limit
2. Configure graph extraction (LLM provider, entity types, schema mode, entity resolution)
3. Configure vector indexing (collection name, batch size, adaptive batching)
4. Configure PDF routing (auto/force-VLM/force-text)
5. Watch progress in real-time: pipeline counters, current phase, memory meters, per-document status

### From the REST API

```bash
# Simple crawl
curl -X POST http://localhost:8080/api/crawlers/start \
  -H "Content-Type: application/json" \
  -d '{
    "seed": "/path/to/docs",
    "sourceType": "FILE",
    "maxDocuments": 500
  }'

# Unified crawl with graph extraction
curl -X POST http://localhost:8080/api/unified-crawl/start \
  -H "Content-Type: application/json" \
  -d '{
    "name": "docs-crawl",
    "sources": [
      {"sourceType": "DIRECTORY", "pathOrUrl": "/data/docs/"}
    ],
    "graphExtraction": {
      "enabled": true,
      "schemaMode": "LENIENT"
    }
  }'

# Unified crawl with file uploads
curl -X POST http://localhost:8080/api/unified-crawl/start-with-files \
  -F "request=@crawl-config.json;type=application/json" \
  -F "files=@document1.pdf" \
  -F "files=@document2.docx"

# Job lifecycle
curl http://localhost:8080/api/crawlers/jobs/{jobId}
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/pause
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/resume
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/cancel
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/restart

# List resumable jobs
curl http://localhost:8080/api/crawlers/jobs/resumable

# Unified crawl status and config
curl http://localhost:8080/api/unified-crawl/jobs
curl http://localhost:8080/api/unified-crawl/jobs/active
curl http://localhost:8080/api/unified-crawl/source-types
curl http://localhost:8080/api/unified-crawl/processing-route
curl http://localhost:8080/api/unified-crawl/processing-capacity
curl http://localhost:8080/api/unified-crawl/graph-stats
```

## Crawl profiles

Projects can store named crawl profiles that capture all settings for
repeatable ingestion. Profiles are saved in the project manifest and
include source configuration, graph extraction settings, VLM options,
and pipeline routing rules.

```bash
kompile project add-crawl-profile
```

## Graph node hierarchy from crawls

Crawled documents produce this graph structure:

```
SOURCE
  +-- DOCUMENT (one per crawled file/page)
        +-- SNIPPET (one per chunk, CONTAINS edge)
        +-- TABLE (extracted table, CONTAINS edge)
        +-- Entity nodes (from graph extraction)

SHEET (Excel worksheet)
  +-- CELL (formula cell)
        +-- DEPENDS_ON -> CELL
        +-- RANGE_INPUT -> CELL
        +-- CROSS_SHEET_DEPENDS_ON -> CELL
```

Each source-specific graph extractor adds domain-specific structure:

| Source type | Graph structure |
|-------------|-----------------|
| Email | Sender/recipient entities, thread relationships, attachment links |
| Excel | Cell dependency graph, cross-sheet references, formula structure |
| Confluence | Page hierarchy, space structure, author entities |
| Slack/Discord | Channel structure, user entities, thread relationships |
| PDF | Section hierarchy, table structure, figure references |
| Office docs | Section structure, embedded object references |
| Google Docs | Document structure, collaborative edit metadata |
| Audio | Speaker entities, topic segments, transcript structure |

## Audit trail

Every pipeline state transition is recorded as an `IngestEvent` in the
database. Event types include: QUEUED, PHASE_STARTED, PROGRESS,
PHASE_COMPLETED, STATE_TRANSITION, WARNING, ERROR, COMPLETED, FAILED,
CANCELLED, MEMORY_KILLED, RESTART_SCHEDULED, RESTART_ATTEMPTED,
RESTART_SUCCEEDED, RESTART_FAILED, MEMORY_ANALYSIS, HEAP_ADJUSTED,
THREADS_REDUCED, MANUAL_RESTART, and extraction-specific events.

## Related concepts

- **[Ingestion Pipeline](ingestion-pipeline.md)** — the pipeline phases
  that documents pass through after a crawl discovers them
- **[Knowledge Graphs](knowledge-graphs.md)** — how graph extraction
  during crawls builds entity/relationship graphs, including entity
  resolution, schema modes, and multi-agent extraction
- **[Information Retrieval](information-retrieval.md)** — how the indexed
  data is searched and retrieved during queries
- **[Fact Sheets](fact-sheets.md)** — how crawl jobs scope to fact sheets
  with `--fact-sheet`
- **[Configuration](../configuration/README.md)** — `pipeline-config.json`
  and `subprocess-ingest-config.json` control crawl and pipeline settings
