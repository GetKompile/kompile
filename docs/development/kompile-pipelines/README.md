# kompile-pipelines-framework

The pipeline execution engine for composing multi-step ML workflows.

## Modules

| Module | Purpose |
|--------|---------|
| `kompile-pipelines-framework-api` | Core types: `Configuration`, `StepConfig`, `Data`, `ValueType` |
| `kompile-pipelines-framework-core` | Pipeline execution engine |
| `kompile-pipelines-framework-runtime` | Runtime support |

## Pipeline steps

Under `kompile-pipeline-steps-parent`:

| Step | Description |
|------|------------|
| `kompile-pipelines-steps-samediff` | SameDiff model inference |
| `kompile-pipelines-steps-python` | Python script execution |
| `kompile-pipelines-steps-onnx` | ONNX Runtime inference |
| `kompile-pipelines-steps-dl4j` | DL4J model inference |
| `kompile-pipelines-steps-vlm` | Vision-language model inference |

## API notes

- `ValueType` enum uses `INT64` (not `LONG`)
- `Data` interface has no `getLongArray()` -- use `getList(key, ValueType.INT64)` instead
- Pipeline configuration is JSON-based

## Building

```bash
cd kompile-pipelines-framework
mvn clean install -DskipTests
```
