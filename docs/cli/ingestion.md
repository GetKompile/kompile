# Document Ingestion

Kompile supports 20+ data sources for document ingestion, all accessible from both the web UI and CLI.

## CLI commands

```bash
kompile ingest file /path/to/document.pdf          # Upload a local file
kompile ingest path /path/to/documents/             # Register a directory
kompile ingest url https://docs.example.com         # Add a URL source
kompile ingest status                               # Check job progress
```

## Supported sources

Local files and directories, web crawl (recursive with configurable depth), S3, SFTP, SQL databases, email (IMAP, POP3, Gmail, Outlook PST, MBOX, Maildir), Confluence, Jira, Notion, Slack, Discord, Google Drive, OneDrive, Google Workspace, and SMB shares.

Cloud sources use OAuth connections managed through the **Connected Services** screen in the web UI.

## The ingestion pipeline

Documents pass through phases tracked in real-time via SSE:

```
QUEUED -> DISCOVERING -> LOADING -> CHUNKING -> GRAPH_EXTRACTION ->
ENTITY_RESOLUTION -> EMBEDDING -> VECTOR_INDEXING -> COMPLETED
```

The pipeline runs as isolated subprocesses (the same binary re-launched with `--subprocess=TYPE`):

```
Documents -> Loader -> Chunker -> (Graph Extraction) -> Embedder -> Vector Index
```

### PDF routing

Auto-classifies image-heavy PDFs and routes them through VLM OCR. Force a specific mode with the crawl job configuration.

### Graph extraction (optional)

An LLM extracts entities and relationships from each chunk. Entity resolution deduplicates using embedding similarity and string matching.

### Adaptive batching

Embedding batch sizes adjust based on available ND4J memory pressure.

## Web UI

Knowledge tab > New Crawl Job. The form lets you add multiple sources, configure graph extraction (LLM provider, entity types, schema mode, entity resolution), vector indexing (collection name, batch size, adaptive batching), and PDF routing. The UI streams pipeline counters, memory meters, and per-document status in real-time.
