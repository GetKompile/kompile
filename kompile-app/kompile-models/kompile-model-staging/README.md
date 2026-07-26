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

### Pixel / trusted-LAN pairing

The server binds only to `127.0.0.1` by default. To open the staging UI from a Pixel on the same trusted LAN, bind explicitly, require a random token of at least 32 bytes, and list the browser's exact origin (scheme, host, and port; no path or wildcard):

```bash
export KOMPILE_STAGING_ADDRESS=0.0.0.0
export KOMPILE_STAGING_LAN_ENABLED=true
export KOMPILE_STAGING_API_TOKEN="$(openssl rand -hex 32)"
export KOMPILE_STAGING_ALLOWED_ORIGINS=http://192.168.1.42:8090
java -jar kompile-model-staging-*-exec.jar
```

Replace `192.168.1.42` with the staging host's LAN address, then open `http://192.168.1.42:8090` on the Pixel. If a development UI is served separately, list its exact origin instead (for example `http://192.168.1.42:4200`); multiple origins are comma-separated. `*`, paths, query strings, fragments, and non-HTTP(S) origins are rejected.

Use the toolbar's **Pair** control to enter `KOMPILE_STAGING_API_TOKEN`. The UI keeps it only in `sessionStorage`, clears it when the browser session ends or **Clear pairing** is selected, and sends it only in the `X-Kompile-Staging-Token` request header. Never place the token in a URL, query parameter, fragment, cookie, or saved import draft. A `401` response opens the pairing guidance again. Restrict port 8090 to the trusted LAN with the host firewall; LAN mode is not an internet-facing deployment mode.

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

## Offline Android chat artifacts

A targeted chat build has two explicit outputs. `outputFormat: model` plus a
`targetProfile` publishes a complete accelerator-ready `.sdz`: the model,
`tokenizer.json`, `tokenizer_config.json`, generation/chat configuration, and
the target payload are compiled and cached together. It deliberately contains no
knowledge graph or Markdown and does not require a configured Kompile project.

`outputFormat: kproject` runs that exact same content-addressed SDZ compilation
and then wraps the resulting `.sdz` in a portable graph-chat project. It fails
closed unless `kompile.staging.project-dir` points to a valid synced project
containing:

- `data/graph/project.kgraph`, exported by the existing project graph portability service
- at least one Markdown source below `data/markdown/`
- optional `data/fact-sheets/`, `data/sources/`, and `data/indexed-documents/` provenance

Hugging Face input accepts a repository ID or repository URL. Repository
discovery resolves the source revision and tokenizer/config files; when several
GGUF/GGML models are present, the caller must select one exact model path.
Manual/local and public HTTPS component imports must declare every runnable chat
asset instead of relying on discovery. Omitting both `targetProfile` and
`outputFormat` retains the legacy staging/promotion flow.

Vulkan, Hexagon, Tensor G5, and every quantized targeted build require an actual
target compiler. The compiler command is an argument vector and is executed
directly; it is never passed through a shell. An unquantized
`android-arm64-nnapi-accelerator` request instead uses
`SdxModelCompiler.nnapiDeviceCompilationPolicy`: the Pixel NNAPI driver performs
the device-specific compilation and the accelerator-only runtime persists its
device cache.

```yaml
kompile:
  staging:
    project-dir: /absolute/path/to/kompile-project
    sdx:
      cache-dir: /absolute/path/to/sdx-cache
      compiler-command:
        - /absolute/path/to/sdx-target-compiler
      compiler-id: libnd4j-mobile-aot
      compiler-version: "1"
      compiler-fingerprint: immutable-toolchain-and-config-digest
```

The configured executable is a real model compiler, not an APK/AAR builder or
validator. SDX provides the target artifact contracts, content-addressed
cache/package layer, Vulkan compiler primitives, Hexagon plan/finalize support,
the NNAPI device policy, the in-process Tensor G3 SameDiff INT8 graph rewriter,
and LiteRT-LM package validation. Targets without an in-process adapter remain
unavailable until their compiler command is configured. Tensor G3 compilation
requires calibrated per-tensor metadata embedded in the canonical SDZ and emits
a byte-distinct graph; Tensor G5 must use a supported LiteRT-LM exporter. Copying
an input SDZ or emitting metadata without a rewritten graph is rejected.

On an SDX cache miss the executable receives `--input`, `--target`,
`--output`, `--source-sha256`, and `--model-output`, plus any tokenizer,
generation, quantization, model-id, and cache-key options. An absent compiler,
unsupported graph, missing vendor output, or provider fallback fails the staging
job; staging never labels a CPU artifact as an accelerator build.

Supported Android targets:

| Target profile | App/runtime | Default SoC |
|---|---|---|
| `android-arm64-vulkan` | Vulkan replay | `Android_Vulkan_1_1` |
| `android-arm64-hexagon-htp` | Qualcomm Hexagon HTP | `SM8650` |
| `android-arm64-nnapi-accelerator` | Pixel 8a / Tensor G3 NNAPI accelerator | `Tensor_G3` |
| `android-arm64-google-tensor-g5` | Google Tensor G5 direct NPU | `Tensor_G5` |

Request `int8` and staging resolves the actual scheme from the target. Tensor G3
uses signed symmetric per-tensor INT8 weights plus calibrated FLOAT32 activation,
weight, and output scales required by NNAPI; Hexagon and Tensor G5 retain their
per-channel contracts. The resolved scheme is recorded in model/project metadata
and participates in the SDX content-addressed cache key. Legacy
`int8-per-channel` request values are accepted as an intent alias, but output
metadata always reports the scheme actually compiled.

Example model-only mobile chat build:

```http
POST /api/staging/stage
Content-Type: application/json

{
  "source": "huggingface",
  "repository": "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
  "modelId": "qwen-mobile",
  "type": "llm_ggml",
  "format": "gguf",
  "outputFormat": "model",
  "targetProfile": "android-arm64-nnapi-accelerator",
  "quantizationProfile": "none",
  "targetSoc": "Tensor_G3"
}
```

Poll `GET /api/staging/status/qwen-mobile`. After it reaches `completed`,
download `GET /api/staging/models/qwen-mobile/output`. This request returns the
complete target `.sdz`. Change `outputFormat` to `kproject` only when the
configured project already contains a valid portable graph and Markdown tree;
the resulting archive embeds the same cached target SDZ.

## Generated audio synthesis

`audio_synthesis` is a registered model type with a typed serving ABI. The initial production backend, `samediff_waveform`, deliberately accepts only an end-to-end SameDiff graph whose text-token inputs produce a finite normalized mono waveform in `[-1, 1]`. The reusable `samediff-audio` module owns tokenization, graph execution, completed-file results, and streaming PCM WAV writing. This service owns model staging and activation, registry/catalog persistence, SHA-256 verification before load, path containment, generator lifecycle, run idempotency, artifact manifests, and bearer-authenticated transfer.

A registry or catalog entry carries the ABI under `audio_synthesis`:

```json
{
  "type": "audio_synthesis",
  "model_file": "model.sdz",
  "checksum": "sha256:<64 lowercase hex characters>",
  "audio_synthesis": {
    "backend": "samediff_waveform",
    "tokenizer_type": "hugging_face",
    "tokenizer_file": "tokenizer.json",
    "token_ids_input": "input_ids",
    "attention_mask_input": "attention_mask",
    "waveform_output": "waveform",
    "token_data_type": "int64",
    "sample_rate_hz": 22050,
    "channels": 1,
    "sample_format": "pcm_s16le",
    "media_type": "audio/wav",
    "file_extension": ".wav",
    "max_input_tokens": 2048,
    "max_output_samples": 6615000,
    "voice": "standard",
    "language": "en"
  }
}
```

`token_lengths_input` and a scalar `confidence_output` are optional. `utf8_bytes` is available for graphs explicitly trained/exported with byte-token inputs; it does not use a tokenizer file. Voice and language are fixed registry labels for this single-voice backend, not request-selected model parameters. Unknown backend behavior, arbitrary inference configuration, multi-speaker selection, implicit vocoders, normalization, transcoding, and codec conversion are rejected or left to future typed backends.

`POST /api/audio/synthesize` returns metadata only: run ID, opaque UUID `artifactReference`, media type, SHA-256, byte length, registry-authoritative model/version, configuration hash/evidence, and confidence. `GET /api/audio/artifacts/{uuid}/content` streams the completed file. Both endpoints fail closed unless `KOMPILE_STAGING_ARTIFACT_TOKEN` is configured and supplied as a bearer token. Callers cannot supply a staging filesystem path, artifact URL, callback destination, or arbitrary remote URL. Identical retries reuse the first result; conflicting run reuse is rejected.

## REST API

### Model Registry & Staging (`/api/staging`)

- `GET /api/staging/catalog` — browse available models (static YAML + registered)
- `POST /api/staging/stage` — start staging a model (async, returns immediately)
- `GET /api/staging/models/{modelId}/output` — download a completed target `.sdz` or full graph-chat `.kproject`
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
