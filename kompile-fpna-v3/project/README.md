# kompile-fpna-v3

FP&A Workflow Console v3 — Financial Planning & Analysis document processing with knowledge graph extraction.

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Run everything (build, start, crawl, graph extract)
./scripts/run-all.sh
```

The single `run-all.sh` script handles: build → staging server → app server → unified crawl with graph extraction. It uses `kompile crawl start` under the hood.

## One-Command Crawl

If the app is already running, you can crawl directly:

```bash
kompile crawl start "/path/to/FP&A workflow artifacts 2026-05" \
  --schema-preset fpna-cpg-channel-v1 \
  --graph-schema-mode LENIENT \
  --name "FP&A Workflow v3" \
  --watch
```

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
| `scripts/run-all.sh` | Full pipeline: build, start, crawl with graph extraction |
| `scripts/run-graph-algorithms.sh` | Run PageRank, community detection, centrality |
| `scripts/verify-ingestion.sh` | Validate docs, graphs, RAG query, model status |

## Ports

| Service | Port |
|---------|------|
| App | 8080 |
| Staging (LFM2) | 8090 |
