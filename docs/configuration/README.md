# Configuration

Kompile uses a JSON config file system rooted at `~/.kompile/config/`. All three entry points -- CLI wizards, the web UI, and the REST API -- read and write the same files. Spring properties exist only for bootstrap defaults; the JSON files always take precedence at runtime.

## Config files

| File | What it controls |
|------|-----------------|
| `app-index-config.json` | Vector store type (Anserini/pgvector/Chroma/Vespa), index paths, subprocess settings, batch sizes |
| `pipeline-config.json` | Batch sizes, thread counts (embedding, chunking, indexing), chunking strategy and parameters |
| `subprocess-ingest-config.json` | Subprocess JVM heap, timeout, parallel workers, queue capacity |
| `nd4j-environment-config.json` | ND4J threads, BLAS settings, CUDA config, Triton compiler, SameDiff optimizer, memory limits |
| `feature-flags-config.json` | Toggle: guardrails, query transformation, contextual RAG, tool gateway, KV cache, graph RAG, multi-modal |
| `model-roles-config.json` | Dense/sparse retrieval models, reranking model, hybrid search weights |
| `llm-provider-config.json` | LLM provider, model, API key, base URL |
| `tool-gateway-config.json` | Model source, fail-open, evaluation timeout, judge scoring |
| `backup-config.json` | Backup schedule, retention, format |

## CLI wizards

```bash
kompile configure init          # Creates ~/.kompile/ and default config files
kompile configure app           # Interactive 9-section config wizard
kompile configure chat          # Chat session mode, LLM provider, agent preferences
kompile configure mcp           # MCP profile and schema level
kompile configure enforcer      # Per-project policy rules
kompile configure judge         # LLM judge mode, model, scoring
```

## Auto-configuration

Probes your hardware (GPU count, VRAM, CPU cores, RAM) and sets subprocess, ND4J, and pipeline configs simultaneously.

```bash
# Detect hardware and apply recommended settings
curl -X POST http://localhost:8080/api/auto-configure/apply

# Preview what would change
curl http://localhost:8080/api/auto-configure/detect
```

## Config archives

Export and import all configs as a `.zip` bundle for portability:

```bash
# From the API
curl -X POST http://localhost:8080/api/config-archives/export -o config-backup.zip
curl -X POST http://localhost:8080/api/config-archives/import -F file=@config-backup.zip
```

Also available from the web UI: Settings > Config Archive Manager.

## When to configure what

| If you want to... | Configure... |
|-------------------|-------------|
| Change the LLM provider or model | `llm-provider-config.json` or `kompile configure chat` |
| Change the embedding model | `model-roles-config.json` or Settings > Embedding |
| Adjust batch sizes for large crawls | `pipeline-config.json` or Settings > Pipeline |
| Enable Graph RAG | `feature-flags-config.json` → `graphRagEnabled: true` |
| Enable guardrails | `feature-flags-config.json` → `guardrailsEnabled: true` |
| Tune ND4J/CUDA settings | `nd4j-environment-config.json` or Settings > ND4J |
| Set up the tool gateway | `tool-gateway-config.json` or `kompile configure gateway` |
| Switch vector store backends | `app-index-config.json` → `vectorStoreType` |
| Configure subprocess memory | `subprocess-ingest-config.json` |
| Let the system decide | `POST /api/auto-configure/apply` |

## Related concepts

- **[How It All Fits Together](../concepts/README.md)** — how configuration
  connects to the rest of the system
- **[Crawl Jobs](../concepts/crawl-jobs.md)** — `pipeline-config.json`
  and `subprocess-ingest-config.json` control crawl behavior
- **[Knowledge Graphs](../concepts/knowledge-graphs.md)** — graph extraction
  config (`graph-extraction-config.json`) and schema presets
- **[Information Retrieval](../concepts/information-retrieval.md)** —
  `model-roles-config.json` controls embedding and reranking models
- **[Guardrails and Evaluation](../concepts/guardrails-and-evaluation.md)** —
  `feature-flags-config.json` toggles guardrails
