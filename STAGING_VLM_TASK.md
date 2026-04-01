# Task: Stage SmolDocling VLM Models via MCP

## Prerequisites

The kompile-model-staging server must be running on port 8090:

```bash
cd kompile-app/kompile-model-staging
/home/agibsonccc/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn spring-boot:run -DskipTests -Dskip.ui -Dspring-boot.run.arguments="--server"
```

Verify it's running: `curl http://localhost:8090/mcp/status`

## MCP Connection

An SSE-based MCP server is configured at `http://localhost:8090/mcp/sse` (see `.mcp.json` in project root). The server name is `kompile-model-staging`.

## Task Description

Use the MCP tools exposed by the `kompile-model-staging` server to stage, download, optimize, and register the **SmolDocling-256M VLM** (Vision-Language Model) for production use.

The SmolDocling-256M model is a document understanding VLM from HuggingFace (`ds4sd/SmolDocling-256M-preview`). It consists of 4 components:
- **Vision encoder** (`smoldocling-256m-vision-encoder`): SigLIP vision encoder, 512x512 input
- **Decoder** (`smoldocling-256m-decoder`): Qwen2-based autoregressive decoder with KV cache
- **Embed tokens** (`smoldocling-256m-embed-tokens`): Token embedding layer (vocab 151936)
- **Pipeline** (`smoldocling-256m-pipeline`): Complete VLM pipeline bundle with all components + tokenizer

The target is 100+ tok/s decode throughput. See `../deeplearning4j/platform-tests/run-benchmark.sh` for the benchmark reference.

## Step-by-Step Instructions

Use the following MCP tools in order. All tools are prefixed with `staging_`.

### 1. Explore the Catalog
- Call `staging_list_catalog` to see all available models including the VLM entries
- Call `staging_get_catalog_model` with `modelId: "smoldocling-256m-pipeline"` to see the full pipeline definition

### 2. Stage the VLM Models from HuggingFace
Stage each component from the catalog:
- Call `staging_stage_from_catalog` with `modelId: "smoldocling-256m-vision-encoder"`
- Call `staging_stage_from_catalog` with `modelId: "smoldocling-256m-decoder"`
- Call `staging_stage_from_catalog` with `modelId: "smoldocling-256m-embed-tokens"`
- Call `staging_stage_from_catalog` with `modelId: "smoldocling-256m-pipeline"` (this downloads all files: vision_encoder.onnx, decoder_model_merged.onnx, embed_tokens.onnx, tokenizer.json, tokenizer_config.json)

Check progress with `staging_get_staging_status` for each model.

### 3. Promote Staged Models
Once staging is complete, promote each model to the registry:
- Call `staging_promote_model` with `modelId` for each staged model

### 4. Verify Registration
- Call `staging_list_models` to confirm all VLM components appear in the registry
- Call `staging_get_model` for each to verify metadata (hidden_size=576, etc.)

### 5. Optimize Models
For each ONNX model component, run optimization:
- Call `staging_compiler_optimize` with:
  - `modelId`: the model to optimize
  - `profile`: "inference"
  - `selectedPasses`: "dead_code_elimination,constant_folding,operator_fusion"
  - `createBackup`: true

Or use `staging_optimize_model` with:
  - `modelId`: the model
  - `optimizations`: "fp16_precast,dead_code_elimination,fusion"
  - `quantizationType`: "fp16"

### 6. Activate Models
- Call `staging_activate_model` for each VLM component to make them the active VLM models

### 7. Verify Final State
- Call `staging_get_active_models` to confirm all VLM components are active
- Call `staging_get_model` for each to verify optimized status

## Available MCP Tool Reference

### Registry Operations
- `staging_list_models` - List all models (filter by `type`)
- `staging_get_model` - Get model details by `modelId`
- `staging_update_model` - Update model metadata
- `staging_delete_model` - Delete a model
- `staging_activate_model` - Set model as active for its type
- `staging_get_active_models` - List all active models

### Catalog Operations
- `staging_list_catalog` - Browse the model catalog (encoders, cross-encoders, VLM, OCR)
- `staging_get_catalog_model` - Get catalog entry details

### Staging Operations
- `staging_stage_from_catalog` - Download and stage a model from the catalog
- `staging_stage_from_source` - Stage from HuggingFace/GitHub with custom params
- `staging_promote_model` - Promote staged model to registry
- `staging_get_staging_status` - Check staging/download progress
- `staging_cancel_staging` - Cancel in-progress staging

### Optimization Operations
- `staging_optimize_model` - Run optimization passes on a model
- `staging_compare_models` - Compare original vs optimized
- `staging_list_optimization_passes` - List available optimization passes
- `staging_get_optimization_history` - View optimization history

### Compiler Operations
- `staging_compiler_optimize` - Advanced graph compiler optimization
- `staging_compiler_graph_info` - Inspect model graph structure
- `staging_compiler_compare_graphs` - Diff two model graphs
- `staging_compiler_list_passes` - List compiler passes
- `staging_compiler_start_job` - Start async compilation job
- `staging_compiler_save_compiled` - Save optimized model

### Execution Operations
- `staging_vlm_load` - Load a VLM model for inference
- `staging_vlm_generate` - Generate text from image+prompt
- `staging_vlm_status` - Check VLM model status
- `staging_vlm_unload` - Unload VLM model
- `staging_llm_load` - Load LLM model
- `staging_llm_generate` - Generate text

### Settings & Maintenance
- `staging_get_settings` - View staging settings
- `staging_update_settings` - Update settings
- `staging_validate_model` - Validate model file integrity

## Model Types for Reference
- `vlm_vision_encoder` - Vision encoder component
- `vlm_decoder` - Decoder component
- `vlm_embed_tokens` - Token embedding component
- `vlm_pipeline` - Complete VLM pipeline
- `dense_encoder` - Text embedding encoder
- `cross_encoder` - Reranking cross-encoder
