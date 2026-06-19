# kompile-model-staging

The model lifecycle server for the Kompile platform. Downloads, converts, optimizes, trains, evaluates, and serves ML models. Exposes both a REST API and an OpenAI-compatible API so loaded models work as drop-in backends.

## Building

```bash
# JVM JAR
mvn clean package

# GraalVM native image
mvn clean package -Pnative

# Without the staging UI (faster)
mvn clean package -Dskip.ui
```

Main class: `ai.kompile.staging.ModelStagingApplication`

## Running

The server operates in two modes based on the first argument:

```bash
# Server mode — starts REST API on port 8090
java -jar kompile-model-staging-*-exec.jar

# CLI mode — runs a command and exits
java -jar kompile-model-staging-*-exec.jar download --source=huggingface --repo=BAAI/bge-base-en-v1.5
```

If the first argument does not start with `--server`, it dispatches to the CLI. Otherwise it starts Spring Boot.

You usually don't run this manually — `kompile web` or `kompile-app-main` launches it automatically as a child process on port 8090.

## Default Configuration

| Setting | Default |
|---------|---------|
| Port | `8090` |
| Model directory | `~/.kompile/models` |
| Staging directory | `~/.kompile/models/.staging` |
| Settings directory | `~/.kompile` |
| Database | H2 file at `~/.kompile/data/staging-db` |
| Max upload size | 10 GB |
| Archive catalog refresh | 24 hours |
| Download chunk size | 8 MB |
| Download timeout | 5 minutes |

Override via `application.yml`, command-line `--property=value`, or environment variables.

## Model Catalog

Preconfigured models in `model-sources.yml` (downloaded from HuggingFace):

### Encoders

| Model | Dimensions | Max Seq Len | Description |
|-------|-----------|-------------|-------------|
| `bge-base-en-v1.5` | 768 | 512 | BGE Base English — default dense retrieval |
| `bge-small-en-v1.5` | 384 | 512 | BGE Small — lightweight |
| `bge-large-en-v1.5` | 1024 | 512 | BGE Large — high quality |
| `bge-m3` | 1024 | 8192 | BGE-M3 — multilingual, 100+ languages |
| `arctic-embed-l` | 1024 | 8192 | Snowflake Arctic Embed — long context |
| `e5-base-v2` | 768 | 512 | E5 Base v2 — general purpose |

### Cross-Encoders (reranking)

| Model | Layers | Max Seq Len |
|-------|--------|-------------|
| `ms-marco-MiniLM-L-6-v2` | 6 | 512 |
| `ms-marco-MiniLM-L-12-v2` | 12 | 512 |

### Vision-Language Models

| Model | Hidden Size | Description |
|-------|-----------|-------------|
| `florence-2-base` | 768 | Document understanding and OCR |
| `florence-2-large` | 1024 | High-quality document understanding and OCR |

Custom model mirror: set `KOMPILE_MODEL_MIRROR_URL` and `KOMPILE_MODEL_MIRROR_ENABLED=true`.

## Model Lifecycle

```
1. Discover    GET /api/staging/catalog           Browse available models
2. Download    POST /api/staging/stage             Download from HuggingFace/GitHub/HTTP
3. Convert     (automatic)                        ONNX/TF/Keras/GGUF → SameDiff
4. Validate    (automatic)                        Verify converted graph
5. Optimize    POST /api/compiler/optimize         Graph passes, Triton, quantization
6. Promote     POST /api/staging/promote/{id}      Make active for the RAG pipeline
7. Serve       POST /api/llm/load                  Load for inference
               GET  /v1/models                     OpenAI-compatible model list
               POST /v1/chat/completions           OpenAI-compatible chat
```

Models transition through statuses: `PENDING` → `DOWNLOADING` → `CONVERTING` → `VALIDATING` → `READY` → `ACTIVE`. Progress is streamed via SSE at `GET /api/staging/models/{id}/stream`.

## REST API

### Model Registry & Staging (`/api/staging`)

- `GET /api/staging/catalog` — browse available models (static YAML + registered)
- `POST /api/staging/stage` — start staging a model (async, returns immediately)
- `POST /api/staging/stage/catalog/{modelId}` — stage from catalog by ID
- `GET /api/staging/models/{id}/stream` — SSE progress stream
- `POST /api/staging/promote/{modelId}` — promote to active
- `GET /api/staging/status` — all models in staging
- `GET /api/staging/active` — active model per type
- `GET /api/staging/registry` — full model registry
- `PUT /api/staging/registry/model/{id}` — update model entry
- `DELETE /api/staging/registry/model/{id}` — delete model
- `GET /api/staging/settings` — staging settings
- `POST /api/staging/upload` — upload model file (up to 10 GB)
- `POST /api/staging/upload-and-stage` — upload + stage in one call
- `POST /api/staging/convert` — convert uploaded file to SameDiff
- `POST /api/staging/export` — export model bundle
- `POST /api/staging/import` — import model bundle

### Archives (`/api/staging/archives`)

- `POST /api/staging/archives/export` — export models to `.karch` archive
- `POST /api/staging/archives/import` — import `.karch` archive
- `POST /api/staging/archives/download` — download `.karch` from URL (with resume)
- `GET /api/staging/archives/catalog/remote` — fetch remote catalog of available archives
- `GET /api/staging/archives/updates` — check for updates to installed archives
- `POST /api/staging/archives/updates/{id}/apply` — apply update
- `GET /api/staging/archives` — list installed archives
- `DELETE /api/staging/archives/{id}` — uninstall archive

### LLM Inference (`/api/llm`)

- `POST /api/llm/load` — load LLM model for inference
- `POST /api/llm/unload` — unload model
- `GET /api/llm/status` — current model status
- `POST /api/llm/generate` — generate text
- `POST /api/llm/generate/stream` — SSE streaming generation
- `POST /api/llm/generate/batch` — batch generation
- `POST /api/llm/chat` — multi-turn chat
- `POST /api/llm/chat/stream` — SSE streaming chat
- `GET /api/llm/presets` — sampling presets
- `GET/PUT /api/llm/decoder-config` — decoder configuration
- `POST /api/llm/cancel` — cancel ongoing generation
- `CRUD /api/llm/templates` — prompt template management
- `CRUD /api/llm/pipelines` — text pipeline management

### VLM Inference (`/api/vlm`)

- `POST /api/vlm/load` — load VLM model
- `POST /api/vlm/generate` — image-to-text generation (multipart)
- `POST /api/vlm/generate/batch` — batch image generation
- `POST /api/vlm/ocr/recognize` — OCR on image
- `POST /api/vlm/ocr/batch` — batch OCR
- `POST /api/vlm/doctags/parse` — parse DocTags to structured document
- `POST /api/vlm/doctags/to-markdown` — DocTags → Markdown
- `POST /api/vlm/embed` — embed image via vision encoder
- `GET /api/vlm/models` — list available VLM models

### OpenAI-Compatible API (`/v1`)

- `GET /v1/models` — list loaded models
- `POST /v1/chat/completions` — chat completions (streaming SSE or non-streaming)

Point any OpenAI-compatible client at `http://localhost:8090` and it works.

### Compiler (`/api/compiler`)

- `GET /api/compiler/passes` — available optimization passes
- `GET /api/compiler/profiles` — optimization profiles (presets)
- `POST /api/compiler/optimize` — run optimization passes on a model
- `POST /api/compiler/triton/compile` — Triton GPU compilation
- `GET /api/compiler/graph/{modelId}` — graph info (op count, variables)
- `POST /api/compiler/compare` — compare two model graphs
- `POST /api/compiler/jobs/start` — async compilation job with SSE log streaming

### Training (`/api/training`)

- `POST /api/training/start` — start training job
- `GET /api/training/jobs/{jobId}/stream` — SSE progress stream
- `GET /api/training/peft-types` — available PEFT types (LoRA, QLoRA, AdaLoRA, etc.)
- `GET /api/training/updater-types` — optimizers (Adam, SGD, etc.)
- `GET /api/training/lr-schedules` — learning rate schedules
- `GET /api/training/history` — paginated job history with filtering

### PEFT (`/api/peft`)

- `GET /api/peft/types` — available adapter types
- `POST /api/peft/create` — create adapter config for a model
- `GET /api/peft/{modelId}/info` — adapter info
- `POST /api/peft/{modelId}/merge` — merge adapter weights into base model

### Alignment (`/api/alignment`)

- `POST /api/alignment/start` — start alignment training (DPO, KTO, ORPO, PPO, GRPO)
- `GET /api/alignment/algorithms` — available algorithms
- `GET /api/alignment/jobs/{jobId}/stream` — SSE progress stream

### Distillation (`/api/distillation`)

- `POST /api/distillation/start` — start distillation (logit, feature, attention, combined)
- `GET /api/distillation/types` — available distillation types

### Evaluation (`/api/evaluation`)

- `POST /api/evaluation/run` — run evaluation (sync)
- `POST /api/evaluation/start` — run evaluation (async)
- `POST /api/evaluation/run-suite` — run benchmark suite
- `GET /api/evaluation/suite-presets` — curated presets
- `GET /api/evaluation/standard-datasets` — standard evaluation datasets
- `POST /api/evaluation/standard-datasets/{id}/download` — download dataset

### Datasets (`/api/datasets`)

- `POST /api/datasets/upload` — upload dataset (multipart)
- `GET /api/datasets/{id}/preview` — preview rows
- `GET /api/datasets/{id}/stats` — compute statistics
- `POST /api/datasets/preload` — preload from HuggingFace or built-in benchmarks

### Environment (`/api/environment`)

- `GET /api/environment` — all ND4J/CUDA/Triton runtime settings
- `PUT /api/environment` — update settings
- `GET /api/environment/memory` — memory stats (JVM + ND4J + GPU)
- `GET /api/environment/cuda` — CUDA device info
- `GET /api/environment/triton` — Triton compiler settings
- `GET /api/environment/profiles` — performance profiles
- `POST /api/environment/profiles/apply?profile=OPTIMAL` — apply profile
- `POST /api/environment/llm-config/apply?preset=optimal` — apply LLM preset

### MCP Server (`/mcp`)

- `GET /mcp/sse` — MCP SSE connection
- `POST /mcp/sse` — Streamable HTTP connection
- `POST /mcp/message` — JSON-RPC message delivery
- `GET /mcp/status` — session count and health

## CLI Commands

When run in CLI mode:

```bash
kompile-staging download --source=huggingface --repo=BAAI/bge-base-en-v1.5
kompile-staging download --source=http --repo=https://example.com/model.onnx
kompile-staging convert --input=model.onnx --output=model.sdz
kompile-staging promote --model=bge-base-en-v1.5 [--embedding-dim=768]
kompile-staging list
kompile-staging export --models=bge-base-en-v1.5 --output=bundle.tar.gz
kompile-staging import --bundle=bundle.tar.gz
kompile-staging pipeline --source=huggingface --repo=BAAI/bge-base-en-v1.5 --promote
kompile-staging archive export|import|download|list|check-updates|update
```

### Download options

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `-s`, `--source` | yes | — | `huggingface`, `github`, or `http` |
| `-r`, `--repo` | yes | — | Repository or URL |
| `-m`, `--model-id` | no | derived from repo | Registry model ID |
| `-t`, `--type` | no | `encoder` | `encoder`, `cross_encoder`, `reranker` |
| `-f`, `--format` | no | `onnx` | `onnx`, `tensorflow`, `keras` |
| `-o`, `--output` | no | `~/.kompile/staging/pending/<id>` | Output directory |
| `--revision` | no | — | Git revision/tag/branch |
| `--token` | no | — | Auth token (e.g. HuggingFace) |

The `pipeline` command runs the full sequence (download → convert → validate → optional promote) in one shot.

## Integration with kompile-app-main

When `kompile-app-main` starts, it auto-discovers and launches `kompile-model-staging` as a child process:

1. Searches for the executable: `./staging/kompile-model-staging` (native), `./staging/*.jar` (project-local), `~/.kompile/components/kompile-model-staging/<version>/*.jar` (global)
2. Starts it on port 8090 with `kompile.staging.auto-start=true`
3. Wires the staging URL into `AnseriniEncoderFactory` and `CrossEncoderRerankerAdapter` so promoted models are automatically available to the RAG pipeline
4. Passes the app-main classpath file so staging can launch inference subprocesses with the correct ND4J backend

Configuration in `kompile-app-main`:
```properties
kompile.staging.url=http://localhost:8090
kompile.staging.auto-start=true
kompile.staging.port=8090
kompile.staging.heap-size=4g
```

## Archive Authentication

`.karch` archive downloads support multiple auth methods via environment variables:

| Method | Variables |
|--------|-----------|
| Bearer token | `KOMPILE_ARCHIVE_TOKEN` |
| Basic auth | `KOMPILE_ARCHIVE_USER`, `KOMPILE_ARCHIVE_PASS` |
| S3 | `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` |
