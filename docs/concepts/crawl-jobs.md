# Crawl Jobs

A crawl job discovers and ingests documents from one or more sources into the vector index. Crawl jobs are the primary mechanism for getting data into Kompile.

## Source types

Kompile supports 20+ source types:

| Source | Description |
|--------|------------|
| `FILE` | Local files and directories |
| `WEB_CRAWL` | Recursive web crawl with configurable depth |
| `S3` | Amazon S3 buckets |
| `SFTP` | SFTP servers |
| `SQL` | SQL databases |
| `EMAIL` | IMAP, POP3, Gmail, Outlook PST, MBOX, Maildir |
| `CONFLUENCE` | Atlassian Confluence |
| `JIRA` | Atlassian Jira |
| `NOTION` | Notion workspaces |
| `SLACK` | Slack channels |
| `DISCORD` | Discord channels |
| `GOOGLE_DRIVE` | Google Drive |
| `ONEDRIVE` | Microsoft OneDrive |
| `GOOGLE_WORKSPACE` | Google Workspace |
| `GMAIL` | Gmail (dedicated connector) |
| `REDDIT` | Reddit threads |
| `SMB` | SMB/CIFS network shares |

Cloud sources use OAuth connections managed through the **Connected Services** screen in the web UI.

## Configuration

Each crawl job is configured with:

| Parameter | Description | Default |
|-----------|------------|---------|
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

### Pipeline routing

Crawl jobs support **route rules** that direct discovered items to different processing pipelines based on content type, file extension, or URL pattern. For example, PDFs can be routed through the VLM OCR pipeline while HTML goes through standard text extraction.

### Graph extraction

Optionally enable graph extraction on a crawl job to build a knowledge graph as documents are ingested. Configure:

- LLM provider for entity/relationship extraction
- Entity types and relationship types (or use schema presets)
- Schema mode (free-form vs. schema-constrained)
- Entity resolution with embedding similarity and string matching

### Incremental crawls

Crawl jobs support checkpointing. When `previousState` is provided, only new or changed documents are processed, making re-crawls efficient.

## Lifecycle

A crawl job progresses through states:

```
Created -> Running -> Completed
                  \-> Paused -> Running (resumed)
                  \-> Cancelled
                  \-> Failed
```

Jobs can be paused, resumed, cancelled, and restarted at any point.

## Starting a crawl

### From the CLI

```bash
# Simple file ingest
kompile app crawl start --source=file --path=/path/to/docs

# Web crawl
kompile app crawl start --source=web --seed=https://docs.example.com --maxDepth=2

# Interactive wizard
kompile app crawl wizard

# Check status
kompile app crawl status
```

### From the web UI

Knowledge tab > New Crawl Job. The form lets you add multiple sources, configure graph extraction, vector indexing, and PDF routing. Progress streams in real-time with pipeline counters, memory meters, and per-document status.

### From the REST API

```bash
# Start a crawl
curl -X POST http://localhost:8080/api/crawlers/start \
  -H "Content-Type: application/json" \
  -d '{"seed": "/path/to/docs", "sourceType": "FILE", "maxDocuments": 500}'

# Check status
curl http://localhost:8080/api/crawlers/jobs/{jobId}

# Pause / resume / cancel
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/pause
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/resume
curl -X POST http://localhost:8080/api/crawlers/jobs/{jobId}/cancel
```

## PDF routing

Kompile auto-classifies PDFs as text-heavy or image-heavy. Image-heavy PDFs are routed through VLM OCR for better extraction. You can force a specific mode in the crawl job configuration:

- **auto**: Let Kompile decide (default)
- **force-vlm**: Always use VLM OCR
- **force-text**: Always use text extraction

## Memory and performance

Each crawl job reports heap, native memory, direct buffers, and subprocess RSS. Adaptive batching adjusts embedding batch sizes based on available ND4J memory pressure. Configure batch sizes, thread counts, and subprocess memory in the pipeline and subprocess config files.
