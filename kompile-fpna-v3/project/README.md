# kompile-fpna-v3

FP&A Workflow Console v3 — Financial Planning & Analysis document processing with knowledge graph extraction.

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Run everything (build, start, crawl, graph extract)
./scripts/run-all.sh
```

The single `run-all.sh` script handles: build → staging server → app server → the production `kompile crawl` agent. The agent performs preflight, starts and monitors the unified crawl through `crawl_control`, runs graph reasoning, and persists checkpoints/transcripts.

## Production Crawl Harness

If the app is already running, create a request and attach the production CLI harness:

```bash
cat > /tmp/fpna-crawl.json <<'JSON'
{
  "name": "FP&A Workflow v3",
  "factSheetId": 1,
  "sources": [{
    "label": "FP&A workflow artifacts 2026-05",
    "sourceType": "DIRECTORY",
    "pathOrUrl": "/path/to/FP&A workflow artifacts 2026-05",
    "maxDepth": 3,
    "maxDocuments": 1000,
    "excludePatterns": ["*.m4v", "*.mp4", "*.mov"]
  }],
  "graphExtraction": {
    "enabled": true,
    "schemaPresetId": "fpna-cpg-channel-v1",
    "schemaMode": "LENIENT",
    "llmProvider": "llm-chat",
    "temperature": 0.0,
    "maxTokens": 128,
    "entityResolution": true,
    "entityResolutionUseEmbeddings": true,
    "entityResolutionEmbeddingThreshold": 0.88,
    "minConfidence": 0.5
  },
  "vectorIndex": {
    "enabled": true,
    "collectionName": "fpna-workflow-v3",
    "chunkerName": "recursive",
    "embeddingBatchSize": 2,
    "maxEmbeddingBatchSize": 2,
    "adaptiveBatching": true
  }
}
JSON

kompile crawl --url http://localhost:8080 --mode auto --headless \
  --session-id fpna-$(date +%Y%m%d-%H%M%S) \
  --request-file /tmp/fpna-crawl.json
```

`--mode supervised` pauses before mutating crawl operations; use `/crawl approve`, `/crawl pause`, `/crawl resume`, `/crawl step`, `/crawl stop`, and `/crawl status` in the interactive form. `--mode auto` is intended for unattended CI. `--resume <session-id>` restores the CLI transcript and run checkpoint ledger.

The FP&A wrapper is `./launch-crawl.sh`; it generates the request, starts services when needed, invokes this same CLI profile, and writes machine artifacts under `data/logs/`.

## Preset

Generated with `kompile build app --preset=cli-agent-full` (49 modules).

## Modules

| Category | Modules |
|----------|---------|
| **Core** | app-main, app-core, loaders-orchestrator, app-anserini, chat-history, pipelines-llm |
| **LLM** | llm-cli-agent (uses LFM2.5 via staging server) |
| **Embedding** | embedding-anserini (bge-base-en-v1.5, local) |
| **Vector Store** | vectorstore-anserini (Lucene HNSW) |
| **Loaders** | loader-pdf-extended, loader-pdf-tables, loader-microsoft, loader-excel, loader-web, loader-mail, loader-email-inbox, loader-audio, loader-tika |
| **Chunkers** | chunker-sentence, chunker-markdown, chunker-table-aware |
| **Tools** | tool-filesystem, tool-rag, tool-model-staging, tool-workflow, tool-camel, tool-gateway, tool-crawler |
| **Graph** | knowledge-graph, graph-algorithms, crawl-graph |
| **Enterprise** | compute-graph, compute-graph-scripting, compute-graph-excel, kvcache, model-staging, model-manager, rag-pipeline, react-agent, process-engine, orchestrator, query-transformer, filter-chain, guardrails, evaluation, a2a, crawler-core, code-indexer, ocr-integration |

## FP&A Dataset

Source: `FP&A workflow artifacts 2026-05/`

| Files | Type | Loader | Graph Extractor |
|-------|------|--------|----------------|
| 17 HTML files | Process maps, dashboards, emails, methodology | loader-web | HtmlWebGraphExtractor |
| 7 XLSX files | Regional forecasts (AMER/EMEA/APAC), P&L, consolidation | loader-excel | ExcelFormulaGraphExtractor + OfficeGraphExtractor |
| 1 MD file | Interview transcript (Japanese) | loader-tika / chunker-markdown | LlmRelationExtractionAgent |
| 1 M4V video | Projection extraction demo | loader-audio | AudioGraphExtractor |

## Graph Schema

Uses `fpna-cpg-channel-v1` preset with 21 node types and 17 relationship types:

**Key Entity Types:** CHANNEL_TAXONOMY, REGIONAL_FORECAST, FORECAST_ADJUSTMENT, VARIANCE_TRIAGE, CONTROL_ASSERTION, CLOSE_STEP, PERSON, TABLE, SPREADSHEET, EMAIL_MESSAGE

**Key Relationships:** CONTAINS, VALIDATES, FEEDS_INTO, SUBMITTED_BY, APPROVED_BY, SENT_BY, HAS_ATTACHMENT, ESCALATED_TO, TRIGGERS

## LFM2 Configuration

Relation extraction uses `lfm2.5-1.2b-instruct` served by the staging server at `http://localhost:8090`.
The model must be staged and promoted in `~/.kompile/models/registry.json` before extraction works.

## Scripts

| Script | Purpose |
|--------|---------|
| `scripts/run-all.sh` | Full pipeline: build, start, and invoke the production crawl agent |
| `launch-crawl.sh` | Production CLI crawl/reasoning harness and request/artifact wrapper |
| `scripts/run-graph-algorithms.sh` | Run PageRank, community detection, centrality |
| `scripts/verify-ingestion.sh` | Validate docs, graphs, RAG query, model status |

## Ports

| Service | Port |
|---------|------|
| App | 8080 |
| Staging (LFM2) | 8090 |
