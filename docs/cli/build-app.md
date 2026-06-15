# Build Applications

`kompile build app` generates a complete Maven project with your selected modules, downloads required ML models, and compiles the application.

## Usage

```bash
# Interactive wizard
kompile build app --wizard

# From a preset
kompile build app --configName=myapp --preset=hosted-llm-rag

# Fine-tune modules
kompile build app --configName=myapp \
  --preset=full \
  --exclude=graph-neo4j,ocr \
  --llm=anthropic \
  --embedding=anserini \
  --vectorstore=pgvector \
  --native                    # Compile to GraalVM native image
  --container                 # Or build an OCI container (Jib, no Docker needed)
```

The generated project lives at `kompile-rag-builds/<configName>/project/`.

## Presets

| Preset | Includes | API keys needed? |
|--------|----------|-----------------|
| `hosted-llm-rag` | OpenAI LLM + Anserini embeddings + PDF loader | Yes (OpenAI) |
| `cli-agent-rag` | CLI agent (Claude/Codex) + Anserini + filesystem tools | No |
| `samediff-rag` | Fully local -- SameDiff embeddings, no hosted LLM | No |
| `lite` | Self-contained chat + RAG + Graph RAG, minimal footprint | No |
| `full` | All LLMs, embeddings, vector stores, OCR, crawler, graph, training | Mixed |
| `pipeline` | Pipeline executor only (SameDiff, ONNX, Python steps) | No |
| `minimal` | OpenAI embeddings + OpenAI LLM + Anserini vector store | Yes (OpenAI) |

## Output structure

```
kompile-rag-builds/myapp/project/
  kompile.project.json            # Project manifest (auto-generated)
  pom.xml                         # Maven POM with your selected modules
  src/main/resources/
    application.properties        # Structural config (ports, paths, provider flags)
  scripts/
    start-all.sh                  # Starts staging -> serving -> app in order
    stop-all.sh
    start-app.sh
    start-staging.sh
  data/
    input_documents/              # Drop documents here
    models/                       # Model artifacts
    indices/                      # Built during ingestion
    prompt-templates/             # Pre-seeded templates
  target/
    myapp-0.1.0-SNAPSHOT-exec.jar # The compiled application
```

## Building a distribution

```bash
kompile build dist
```

Builds all three binaries (CLI, server, model-staging) into a distribution tarball for deployment.
