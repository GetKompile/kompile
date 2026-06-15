# Pipeline Commands

`kompile pipeline` manages and executes ML pipelines. Pipelines compose multi-step workflows from Python, SameDiff, DL4J, ONNX, and VLM steps.

## Execute a pipeline

```bash
kompile pipeline exec --file=pipeline.json --input=data.json --output=result.json
kompile pipeline exec --file=pipeline.yaml --format=yaml
```

## Validate

```bash
kompile pipeline validate --file=pipeline.json
```

## List available step types

```bash
kompile pipeline list-steps
kompile pipeline list-steps --verbose
```

Available step types:

| Step | Description |
|------|------------|
| `SAMEDIFF` | SameDiff model inference |
| `PYTHON` | Python script execution |
| `ONNX` | ONNX Runtime inference |
| `DL4J` | DL4J model inference |
| `VLM` | Vision-language model inference |
| `IMAGE_TO_NDARRAY` | Image to NDArray conversion |
| `IMAGE_CROP` | Image cropping |
| `IMAGE_RESIZE` | Image resizing |
| `DRAW_BOUNDING_BOX` | Draw bounding boxes |
| `LOGGING` | Pipeline logging |
| And more... | Various image processing and ML steps |

## Serve a pipeline as REST

```bash
kompile pipeline serve --file=pipeline.json --port=9090 --endpoint=/predict
```

## Create and manage pipelines

```bash
# Create/update a pipeline definition
kompile pipeline create --file=pipeline.json --id=my-pipeline

# Create a step interactively
kompile pipeline create-step --output=step.json
```

Pipeline definitions are stored in `~/.kompile/data/pipelines/`.
