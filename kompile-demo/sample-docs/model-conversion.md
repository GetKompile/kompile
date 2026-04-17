# Model Conversion in Kompile

Kompile centralizes model conversion in the `kompile-model-staging` server. The staging
server accepts an external model artifact, downloads it, runs format conversion, validates
the output, and then atomically moves the resulting bundle into a verified directory that
the main RAG application can promote and serve.

## Supported Input Formats

- **ONNX** (`.onnx`): Imported via `samediff-import-onnx` and emitted as a SameDiff
  flatbuffer (`.fb`) or zipped SameDiff bundle (`.sdz`).
- **TensorFlow** (`.pb`, frozen graphs and checkpoints): Imported via
  `samediff-import-tensorflow`.
- **Keras** (`.h5`, `.keras`): Imported through the Keras importer with a configurable
  network type (sequential or functional).
- **GGUF** (`.gguf`): Quantized LLM weights in the GGML/GGUF container format. The
  `GgmlImporter` parses GGUF metadata and tensors and lowers them into a SameDiff graph
  that can be executed by the same runtime as native SameDiff models.

## Conversion Flow

1. A client posts a stage request to `POST /api/staging/stage` with `source`,
   `repository`, `modelId`, `type`, and `format`.
2. `StagingService` selects a downloader (HuggingFace, HTTP, or GitHub) and fetches the
   artifact into a per-model `pending/` directory.
3. If the format requires conversion, `ConversionService.convert(modelPath, outputPath,
   format)` is called. For `format=gguf` this routes through `GgmlImporter`.
4. The converted output is validated, then moved from `pending/` to `verified/` under the
   staging root (`~/.kompile/models/.staging/verified/<modelId>`).
5. The model can then be promoted with `POST /api/staging/promote/{modelId}` and loaded
   into a kompile-app instance via `POST /api/llm/load`.

The staging directory is configurable through `kompile.staging.staging-dir` and defaults
to `~/.kompile/models/.staging`.
