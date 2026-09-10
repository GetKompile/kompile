# Pipelines

Kompile uses one `UnifiedPipelineDefinition` contract for VLM, OCR, LLM, embedding, reranking, graph-extraction, and custom processing steps. The long-lived stdio MCP host owns runtime startup, model loading, process reuse, cancellation, and cleanup.

## Agent-managed lifecycle

Use the MCP `pipeline` tool to manage project-local definitions:

```json
{"action":"capabilities"}
{"action":"create","definition":{...},"expectedActiveVersion":0}
{"action":"update","definition":{...},"expectedActiveVersion":1}
{"action":"validate","definition":{...}}
{"action":"test","definition":{...},"input":{...}}
{"action":"run","pipelineId":"document-pipeline","input":{"filePath":"report.pdf"}}
{"action":"status","runId":"..."}
{"action":"cancel","runId":"..."}
{"action":"promote","pipelineId":"document-pipeline","version":2,"expectedActiveVersion":1}
{"action":"rollback","pipelineId":"document-pipeline","version":1,"expectedActiveVersion":2}
```

Definitions are immutable versions under `data/pipelines/unified/<pipelineId>/`. Promotion changes an atomic active-version pointer; it does not overwrite prior versions. `list` and `get` also include active registrations from `kompile.project.json`; every result identifies its `registrySource`. A project registration can point at an inline definition, a project-relative definition file, or a managed definition id.

Local artifact-backed runs use the same pooled stdio runtime. Callers provide pipeline definitions, model bindings, and input data—not executable paths, ports, subprocess modes, or worker configuration. When a model definition has `localPath` (or `path`), the runtime consumes that existing file/directory directly: it does not stage, register, or mutate `kompile.project.json`. Without a local path, `pipeline run` may bootstrap a project model; `pipeline test` remains read-only and requires prior provisioning. Test and run failures return a structured diagnostic with a run id, failure stage, exception chain, and bounded stack trace.

## Composed pipeline steps

`pipelineSpec` is the executable composition. Use either `SequencePipeline` or `GraphPipeline` and place any compatible catalog steps in its `steps` or `nodes`; model bindings are supplied to those steps through the runtime context. A VLM-specific pipeline is not required. `VLM_DOCUMENT` is only a convenience end-to-end document adapter, useful when its PDF contract fits; custom VLM, OCR, and multimodal flows should compose preprocessing, encoders, fusion, decoding, and postprocessing steps directly.

The crawl capability catalog includes two provider-neutral compositions:

- `text-model-text` is a `SequencePipeline` with a text input and one caller-bound local language-model step that returns text.
- `vision-multimodel` is a `GraphPipeline` beginning with `image_preprocess`, then independently bound `visionEncoder`, `textEmbedding`, and `decoder` model roles, followed by token decoding.

These defaults contain role references, never a model id or provider. Supply `modelBindings` and optional inline `modelDefinitions`/`modelRefs`; local artifacts are resolved just before execution and existing `localPath` directories are used in place. To create a different composition, provide `pipelineDefinition` inline, `pipelineDefinitionPath`, `pipelineDefinitionId`, `pipelineRegistry.definitions`, or a `registeredPipelines` entry. The custom definition is passed through as the concrete `SequencePipeline` or `GraphPipeline`, so adding or replacing steps does not require changing the crawl adapter.

### Graph wiring contract

Use `pipeline action=capabilities` as the source of truth for the live `stepCatalog`, templates, and `composition.graphWiring` contract:

- A sequence runs `steps` in array order. Each step receives the preceding step's `Data` record.
- A graph node lists upstream node names in `inputs`; use `pipeline_input` for the external request. `inputNodeName` and `outputNodeName` define the graph boundary.
- A standard node with one predecessor receives that record unchanged. With multiple predecessors, upstream records are merged in declaration order.
- Use `stepConfig.parameters.inputDataBindings` when a step needs an explicit slot map. Values are `node.outputKey`, `node` for a whole record, or `pipeline_input[.key]`. Explicit bindings replace the default whole-record/merge handoff for that node.
- A loop merges its `inputs` into loop state and replaces the keys named by `feedbackKeys` after each body iteration.

For multimodal graphs, the default fusion node demonstrates the explicit form:

```json
{
  "name": "fusion",
  "inputs": ["vision_encoder", "text_embedding", "pipeline_input"],
  "stepConfig": {
    "runnerClassName": "<stepCatalog runner>",
    "parameters": {
      "inputDataBindings": {
        "image_features": "vision_encoder.image_features",
        "text_embeddings": "text_embedding.text_embeddings",
        "input_ids": "pipeline_input.input_ids",
        "attention_mask": "pipeline_input.attention_mask",
        "position_ids": "pipeline_input.position_ids"
      }
    }
  }
}
```

The composed vision steps exchange named tensors: image preprocessing emits image tensors, the vision encoder emits `image_features`, text embedding emits `text_embeddings`, and fusion emits `inputs_embeds`. The decoder loop also needs `input_ids` and, when applicable, `attention_mask`/`position_ids`. Raw `text` is not tokenized implicitly; add a tokenizer/adapter step or provide `input_ids` in the request.

## Native chat compositions

Native chat stages run in the MCP host through normal chat authentication, without
model downloads or local tensor runtimes. Choose exact models independently per stage:

```json
{
  "action": "test",
  "definition": {
    "pipelineId": "extract-then-summarize",
    "kind": "LLM",
    "topology": "SEQUENCE",
    "modelBindings": {"step0": "reader", "step1": "writer"},
    "modelDefinitions": {
      "reader": {"source": "chat", "provider": "codex", "modelId": "your-reader-model"},
      "writer": {"source": "chat", "provider": "claude", "modelId": "your-writer-model"}
    },
    "pipelineSpec": {
      "@class": "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
      "steps": [
        {
          "@class": "ai.kompile.pipelines.framework.core.config.GenericStepConfig",
          "runnerClassName": "CHAT_MODEL",
          "parameters": {"processor": {"type": "CHAT_MODEL", "prompt": "Extract the important facts."}}
        },
        {
          "@class": "ai.kompile.pipelines.framework.core.config.GenericStepConfig",
          "runnerClassName": "CHAT_MODEL",
          "parameters": {"processor": {"type": "CHAT_MODEL", "prompt": "Summarize these facts faithfully."}}
        }
      ]
    }
  },
  "input": {"text": "Source document text"}
}
```

Provider aliases are optional; any registered native provider or configured custom
endpoint can be selected. Discover exact IDs and probe text and vision separately using
[the native model capability tools](chat.md#native-chat-for-mcp-pipelines).

The host chat composition contract is deliberately narrower than tensor graph wiring:

- A composition has 1–100 `CHAT_MODEL` stages only. Sequence bindings use `step0`,
  `step1`, etc.; graph bindings use node names. A `default` binding is the fallback.
- Graphs use `STANDARD` nodes, must be acyclic, and every node must contribute to
  `outputNodeName`. Loops, disconnected stages and mixed tensor/chat runners are rejected.
- A single predecessor is passed through; multiple predecessors supply text joined in
  declared input order, not merged tensor records. Intermediate outputs are text.
- `parameters.inputDataBindings` may select exactly one `text`, `filePath`, or `path`
  slot from a declared input. Intermediate references must be `node.text`; file paths
  can only come from the original `pipeline_input`, never from model-generated text.
- Original input can be text, an image or a PDF. A later stage needing the original
  image must reference `pipeline_input`, rather than a preceding text response.
- All stages undergo configuration/adapter preflight before any inference. This does
  not prove model capability or authentication; opt-in live probes are separate.
- Project `source=chat`/`remote` model aliases resolve to their exact model descriptors.
  Local artifact aliases are not silently reinterpreted as remote model names.

Crawls can use these definitions through the existing pipeline registry. Text stages
receive loaded/chunked source text; binary documents requested as text need an explicit
text loader. Native vision/PDF stages receive the original file. Credentials are never
stored in definitions or forwarded to a managed crawl server. Embeddings, token tensors,
tool execution and graph-learning engines remain outside this chat executor.

## Acquire models, then bind them into a composed pipeline

There is no provider-specific download step in `pipeline`. Use the separate `model_runtime` MCP tool first:

```json
{"action":"status"}
{"action":"bootstrap","modelId":"<vision-encoder-id>","source":"<configured-source>","repository":"<configured-repository>","revision":"<configured-revision>","type":"<configured-type>","format":"<configured-format>"}
{"action":"bootstrap","modelId":"<text-embedding-id>","source":"<configured-source>","repository":"<configured-repository>","revision":"<configured-revision>","type":"<configured-type>","format":"<configured-format>"}
{"action":"bootstrap","modelId":"<decoder-id>","source":"<configured-source>","repository":"<configured-repository>","revision":"<configured-revision>","type":"<configured-type>","format":"<configured-format>"}
```

`bootstrap` is the remote acquisition/download operation and returns inventory fields such as `modelPath`, `tokenizerPath`, and `artifactReady`. `import` is the forced provisioning variant and accepts either `localPath` or the same configured remote fields. For a local source file, `model_runtime` also supports `action: "convert"` with `localPath` as input and `outputPath` as a `.sdz` destination. That action invokes the standalone `kompile-model convert` command from stdio MCP and supports ONNX, TensorFlow/Keras, GGUF/GGML, and SafeTensors; native-image distributions resolve the configured native staging worker, while the JAR distribution resolves its packaged executable staging JAR; explicit executable/JAR overrides remain available. The staging worker and backend distribution are resolved by the project; callers do not add a backend dependency or executable class path.

To make conversion and optimization an explicit local chain, invoke `model_runtime` again:

```json
{"action":"optimize","localPath":"models/model.sdz","outputPath":"models/model.optimized.sdz","profile":"BASIC","selectedPasses":["dead_code_elimination","algebraic"],"maxIterations":3,"createBackup":true}
```

`optimize` also accepts a catalog `modelId`, and exposes `profile`, `selectedPasses`, `maxIterations`, `quantizationType`, `force`, `createBackup`, and `dryRun`. Omitting `outputPath` is an in-place local optimization with a backup by default. The model CLI delegates to the configured native staging executable first or executable staging JAR in JVM mode; no provider/backend dependency or caller-managed classpath is introduced.

Then copy the provider-neutral `visionMultimodel` template from `pipeline action=capabilities`, give it a pipeline id, and bind each role to the acquired model id:

```json
{
  "action":"create",
  "definition":{
    "pipelineId":"<pipeline-id>",
    "kind":"VLM",
    "topology":"GRAPH",
    "modelBindings":{
      "visionEncoder":"<vision-encoder-id>",
      "textEmbedding":"<text-embedding-id>",
      "decoder":"<decoder-id>"
    },
    "pipelineSpec":"<composition.defaultTemplates.visionMultimodel.pipelineSpec>"
  }
}
{"action":"run","pipelineId":"<pipeline-id>","input":{"image":"<image-payload>","input_ids":"<token-id-tensor>","attention_mask":"<optional-mask>","position_ids":"<optional-positions>"}}
```

Replace the `pipelineSpec` placeholder with the object returned by `pipeline action=capabilities`; it is shown as a placeholder here so the example stays provider- and model-neutral.

The graph composes image preprocessing, the independently bound vision encoder and text embedding models, fusion, decoding, and token output. The role names are part of the template, not a hardcoded provider or model. For a local-only run, replace the acquired ids with inline `modelDefinitions` entries whose `localPath` points at existing directories; this bypasses staging and registry/project-manifest writes. `pipeline action=capabilities` exposes the live `VISION_MULTIMODEL` image contract and the `modelAcquisition` workflow.

## Configure a VLM model definition

Use the provider-neutral `vlm_model_definition` MCP tool to create and maintain model entries:

```json
{"action":"create","definition":{
  "setId":"my-vlm",
  "displayName":"My VLM",
  "provider":"my-provider",
  "repository":"registry://models/my-vlm",
  "revision":"stable",
  "modelType":"vlm_pipeline",
  "components":[{"componentKey":"vision","fileName":"vision.sdz"}],
  "runtime":{"autoBootstrap":true},
  "metadata":{"endpoint":"https://provider.example"}
}}
{"action":"update","modelId":"my-vlm","definition":{...}}
```

Creating or updating a VLM definition stores provider/runtime metadata; it does not download model files. Acquire remote artifacts with `model_runtime bootstrap`/`import`, or keep the definition local-only with `localPath`.

For a model that already exists on disk, use an inline definition and set `localPath` to its directory (absolute or project-relative):

```json
{
  "modelBindings":{"vision":"local-vlm"},
  "modelDefinitions":{
    "local-vlm":{
      "id":"local-vlm",
      "modelType":"vlm_pipeline",
      "localPath":"models/my-vlm"
    }
  }
}
```

This local form is self-contained: model files stay in that directory and no model registry or project manifest entry is created.

Reference the entry from a VLM definition with `modelBindings.default` or `modelSetId`. Inline `modelDefinitions` may override a registry entry for one request. Provider-specific fields are carried in `runtime` and `metadata`; no provider or model is selected implicitly.

## Validate a definition from the CLI

The human-facing CLI retains schema inspection and offline validation:

```bash
kompile pipeline validate --file=pipeline.json
kompile pipeline list-steps --verbose
```

Execution and lifecycle mutation intentionally remain MCP operations so agents, crawls, and direct pipeline runs share the same versioning, optional staging, pooling, cancellation, and audit behavior.

## Canonical definition shape

```json
{
  "schemaVersion": 1,
  "pipelineId": "document-pipeline",
  "displayName": "Document extraction",
  "kind": "VLM",
  "topology": "SEQUENCE",
  "modelBindings": {"default": "<configured-model-id>"},
  "pipelineSpec": {
    "@class": "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
    "id": "document-pipeline",
    "steps": [{
      "@class": "ai.kompile.pipelines.framework.core.config.GenericStepConfig",
      "runnerClassName": "ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner",
      "parameters": {"outputFormat": "DOCTAGS", "pdfRenderDpi": 300}
    }]
  }
}
```

Use `pipeline action=capabilities` for the live step catalog, input/output contracts, defaults, and parameter schemas. For `VLM_DOCUMENT`, pass `input.filePath` (or `input.path`) pointing to an `application/pdf` file. It processes scanned and image-heavy PDF pages, but does not accept standalone raster-image files.

Model selection uses this order: `modelBindings.default`, `modelId`, `vlmModel` (deprecated alias), then `modelSetId`. Explicit bindings are authoritative, and conflicting `modelId`/`vlmModel` values are rejected. `model_runtime status` reports `artifactReady` separately from `runtimeStatus`; artifact presence alone is not proof that native runtime initialization will succeed.
