# Ingestion Pipeline

When documents are discovered by a crawl job, they pass through a multi-phase pipeline. Each phase runs as an isolated subprocess for memory safety.

## Pipeline phases

```
QUEUED -> DISCOVERING -> LOADING -> CHUNKING -> GRAPH_EXTRACTION ->
ENTITY_RESOLUTION -> EMBEDDING -> VECTOR_INDEXING -> COMPLETED
```

### 1. Discovering

The crawler finds documents at the source. For web crawls this means following links up to `maxDepth`. For file sources it means scanning directories. For cloud sources it means listing items via their API.

### 2. Loading

Documents are loaded and parsed into raw text with metadata. Kompile selects the appropriate loader based on content type:

| Loader | Formats |
|--------|---------|
| PDF Extended | `.pdf` (text-heavy) |
| PDF Tables | `.pdf` (table extraction) |
| VLM OCR | `.pdf` (image-heavy, auto-detected or forced) |
| Office | `.docx`, `.xlsx`, `.pptx` |
| Excel | `.xls`, `.xlsx`, `.xlsm`, `.csv` |
| Tika | Fallback for 1000+ formats |
| Audio | Audio files (transcription) |
| Email | `.eml`, `.msg`, `.mbox`, `.pst` |
| Web | HTML pages |

### 3. Chunking

Loaded text is split into chunks using one of the available strategies:

| Strategy | Description |
|----------|------------|
| `token` | Fixed token-count chunks |
| `sentence` | Sentence-boundary splitting |
| `recursive` | Recursive character splitting with overlap |
| `markdown` | Markdown-aware splitting (respects headings, code blocks) |
| `table-aware` | Preserves table structure during splitting |

Configuration:

```json
{
  "chunkSize": 500,
  "chunkOverlap": 50,
  "chunkerType": "recursive"
}
```

### 4. Graph extraction (optional)

If enabled, an LLM reads each chunk and extracts structured entities and relationships. Extracted types depend on the schema mode:

- **Free-form**: The LLM decides what entities and relationships to extract
- **Schema-constrained**: Only extract types from a predefined schema

Schema presets provide ready-made entity/relationship type sets for common domains.

### 5. Entity resolution (optional)

Extracted entities are deduplicated using embedding similarity combined with string matching. This merges "OpenAI", "Open AI", and "openai" into a single entity node.

### 6. Embedding

Each chunk is converted to a vector using the configured embedding model. Batch processing with adaptive sizing based on available memory. The embedder checks `Thread.isInterrupted()` for graceful cancellation.

### 7. Vector indexing

Embeddings are written to the vector store (Anserini Lucene HNSW, pgvector, Chroma, or Vespa) along with the chunk text and metadata. Supports COSINE, EUCLIDEAN, and DOT_PRODUCT similarity metrics.

## Subprocess architecture

Each compute-heavy phase runs as a subprocess:

| Subprocess type | What it does |
|----------------|-------------|
| `ingest` | Load and chunk documents |
| `vector-population` | Embed chunks and write to vector store |
| `embedding` | Dedicated embedding computation |
| `model-init` | Model download and initialization |
| `vlm-test` | VLM OCR processing |
| `training` | Model training jobs |

Subprocesses are the same binary re-launched with `--subprocess=TYPE`. Each gets its own JVM with configurable heap size, timeout, and worker count. Configuration lives in `subprocess-ingest-config.json`.

## Monitoring

Real-time progress is streamed via SSE (Server-Sent Events) to both the web UI and CLI. Metrics include:

- Pipeline phase counters: Found, Loaded, Chunks, Entities, Embedded, Indexed
- Memory meters: heap, native, direct buffers, subprocess RSS
- Adaptive batch sizing adjustments
- Per-document status and errors

## Related concepts

- **[Crawl Jobs](crawl-jobs.md)** — the crawl system that feeds documents
  into this pipeline, including source types, pipeline routing, content
  classification, and adaptive batching
- **[Knowledge Graphs](knowledge-graphs.md)** — the graph extraction and
  entity resolution phases in detail, plus Bayesian networks and graph
  algorithms
- **[Information Retrieval](information-retrieval.md)** — how the indexed
  data is searched and retrieved during queries
- **[Fact Sheets](fact-sheets.md)** — how crawl jobs scope to fact sheets
  and how facts are indexed alongside document chunks
