# Model Staging

A model lifecycle service that handles the path from raw HuggingFace weights to production-ready deployment. Runs as a REST API on port 8090 with its own Angular UI, or as a CLI tool.

## The staging pipeline

Models pass through managed states:

```
DOWNLOADING -> CONVERTING -> VALIDATING -> READY -> PROMOTING -> COMPLETED
```

Failed models land in `.staging/failed/` with diagnostics.

```bash
# Download from HuggingFace
kompile-model-staging download \
  --source=huggingface \
  --repo=BAAI/bge-base-en-v1.5 \
  --format=onnx

# Convert: ONNX, TensorFlow, GGUF/GGML -> SameDiff (sharded .sdnb)
kompile-model-staging convert --input=model.onnx --output=model.sdz

# List registered models
kompile-model-staging list

# Promote a staged model to production
kompile-model-staging promote <modelId>
```

After promotion, the staging service sends an HTTP callback to the running kompile-server telling it to hot-swap the model in memory. No server restart needed.

## Local LLM inference

The staging service includes a full inference engine. Load a converted model and query it from any OpenAI client:

```bash
curl http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "qwen3-0.6b", "messages": [{"role": "user", "content": "Hello"}]}'
```

Supports streaming, speculative decoding, prompt templates, text processing pipelines, and generation cancellation.

## Training and fine-tuning

Training jobs with PEFT (LoRA, etc.), knowledge distillation, and alignment are managed through REST endpoints with SSE log streaming and metrics tracking. Dataset management is built in.

## Graph compiler

SameDiff graph optimization with Triton GPU compilation, caching, and async compilation jobs. Compare optimized vs. unoptimized graph performance.

## Kompile Archives (.karch)

Versioned bundles of pre-converted models for redistribution and offline installation. Each archive contains a manifest with checksums, compatibility ranges, and model metadata.

```bash
kompile-model-staging archive export --output=models-v1.karch
kompile-model-staging archive import --input=models-v1.karch
```

Remote catalogs at GitHub Releases and kompile.ai are checked every 24 hours for updates.
