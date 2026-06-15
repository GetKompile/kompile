# Model Management

Kompile provides commands for downloading, converting, and managing ML models.

## Model commands

```bash
kompile model download --source=huggingface --repo=BAAI/bge-base-en-v1.5
kompile model convert --inputFile=model.onnx --outputFile=model.fb
kompile model list
kompile model export
kompile model import
```

## Model conversion

Supported input formats: TensorFlow (.pb, checkpoints), ONNX (.onnx), Keras (.h5, .keras).

Supported output formats: DeepLearning4j (.zip), SameDiff (.fb), ONNX Runtime.

```bash
# Convert TensorFlow model
kompile model convert --inputFile=model.pb --outputFile=model.fb

# Convert Keras model
kompile model convert \
  --inputFile=model.h5 \
  --outputFile=model.zip \
  --kerasNetworkType=sequential

# Convert ONNX model
kompile model convert --inputFile=model.onnx --outputFile=model.fb
```

## Model staging

The model-staging service handles the full lifecycle from raw HuggingFace weights to production deployment. See the [Model Staging](../model-staging/README.md) section for details.

## Built-in model catalog

| Category | Models |
|----------|--------|
| Dense encoders | BGE base/small/large, BGE-M3 (multilingual, 8K context), Arctic Embed L, E5-base-v2 |
| Cross-encoder rerankers | MS MARCO MiniLM L-6, MS MARCO MiniLM L-12 |
| Vision-language | Florence-2 base, Florence-2 large |

## Kompile Archives (.karch)

Versioned bundles of pre-converted models for redistribution and offline installation.

```bash
kompile-model-staging archive export --output=models-v1.karch
kompile-model-staging archive import --input=models-v1.karch
```
