# Model Importers

Three modules for importing models from external frameworks into SameDiff/DL4J format.

## Modules

| Module | Input formats | Description |
|--------|--------------|------------|
| `kompile-model-importer-tensorflow` | `.pb`, checkpoints | TensorFlow graph import via `TensorFlowImporter` |
| `kompile-model-importer-onnx` | `.onnx` | ONNX model import via `OnnxImporter` |
| `kompile-model-importer-keras` | `.h5`, `.keras` | Keras model import via `KerasImporter` (sequential and functional) |

## Conversion process

1. Importer reads the source format and builds a SameDiff/DL4J graph representation
2. Model graph transformation and optimization
3. Serialization to target format (`.fb` for SameDiff, `.zip` for DL4J)
4. Optionally register in `ModelConstants` for auto-download from the model catalog

## Building

```bash
# Build a specific importer
cd kompile-model-importer-onnx
mvn clean install -DskipTests

# Or build all importers as part of the full build
mvn clean install -DskipTests
```

These modules are dependencies of `kompile-cli` for the `kompile model convert` command.
