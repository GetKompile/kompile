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
