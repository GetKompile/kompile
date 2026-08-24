# MCP OCR/VLM Dogfood Findings

## Executive summary

The MCP-only path can crawl an image-heavy PDF into a new project-local knowledge base using the project-registered `vlm-ocr-pdf` pipeline and the staged `smoldocling-256m` model.

The successful run:

- Input: `kompile-rag-builds/kompile-sample/project/data/input_documents/uploads/wailingcaverns.pdf`
- Knowledge base: `dogfood-ocr-pdf`
- Pipeline: `vlm-ocr-pdf`
- Model: `smoldocling-256m`
- Result: `COMPLETED`
- Documents: 1
- Chunks: 1
- Extracted markdown: 602 characters
- Extracted words: 80
- Graph entities: 4
- Graph relations: 3
- Embeddings: 0 (intentionally disabled for this isolated test)

`knowledge_search` successfully returned extracted content including:

```text
D&D WAILING CAVERNS
[Image]
Fight against the nightmare in this dungeon for the world's greatest roleplaying games
```

No source code was modified during the dogfood run.

## Resolution status

All recommended follow-ups from this report were implemented on 2026-08-20:

- A request pipeline may inherit a project-owned default with the same ID using
  `registeredPipelineId`; project defaults are no longer suppressed by the request override.
- `crawl-result.json` now reports effective loader/chunker values. It also records all effective
  loaders/chunkers, whether the result is single- or mixed-pipeline, and the original profile defaults.
- `crawl_control operation=status` now reports `stage`, `stageDetail`, `progressPercent`, and
  `stageUpdatedAt`. `MODEL_INITIALIZATION` is emitted when the resolved pipeline actually invokes
  its model executor—not from a VLM/OCR/LLM type allowlist—followed by `PERSISTENCE` and `FINALIZING`.
- Local `knowledge_graph list_fact_sheets` now uses the same crawl-summary inventory as
  `knowledge_status`. Legacy graph-only folders remain discoverable, but an unreadable graph is
  isolated as a warning instead of failing the entire listing.
- Chunk records now include `pipelineType` and `loader` in addition to `pipelineId` and
  `chunker`, so document/chunk metadata can be checked directly.
- The exact project-registered `vlm-ocr-pdf` scanned-PDF request is covered by a model-stubbed MCP
  regression test, including metadata agreement and stage polling.
- Capability discovery now identifies VLM-based PDF extraction as the canonical scanned/image-heavy
  PDF path and describes `ocr-document` as an executor-defined generic OCR contract.
- Generated VLM/OCR definitions now have an explicit regression assertion for the concrete
  `SequencePipeline` value in `pipelineSpec.@class`; preflight also rejects a definition that omits it.
- Nested `modelBindings` and `resolvedModels` are intentionally passed as structured values. The
  pipeline-serving contract test converts the complete nested request with `Data.fromMap` and reads
  both maps back through typed `Data`, so JSON-string workarounds are neither needed nor used.

Focused validation: 198 tests passed across `PipelineServingSubprocessMainTest`,
`LocalCrawlCapabilitiesTest`, `LocalModelPipelineRunnerTest`, `ProjectCommandVlmOcrPresetTest`,
`LocalProjectCrawlBackendTest`, `KnowledgeGraphToolFactSheetTest`, and `KnowledgeGraphToolTest`.
The CLI reactor also packaged successfully after the focused suite, and repository plus untracked-report
whitespace checks passed.

## OCR and VLM selection

- **Scanned or image-heavy PDF:** use the active project pipeline `vlm-ocr-pdf` when
  `crawl_discover(section="pipelines")` advertises it. This is VLM-based extraction: PDF pages are
  rendered and processed by the bound vision-language model.
- **Generic `ocr-document` template:** use only when the project explicitly registers an OCR
  executor or OCR model. The template does not silently select or bundle a traditional OCR engine.
- **Text-bearing PDF:** use the ordinary PDF loader when model-based visual extraction is not needed.
- **Standalone raster image:** use a project pipeline with an explicit image input adapter. The
  verified dogfood contract in this report is PDF input; it does not claim that the PDF VLM runner
  accepts arbitrary image files directly.

## Environment and capability discovery

Project root:

```text
/home/agibsonccc/Documents/GitHub/kompile
```

`crawl_discover(section="pipelines")` reported:

- Project-registered pipeline: `vlm-ocr-pdf`
- Pipeline type: `VLM`
- Supported input: `application/pdf`
- Runner: `ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner`
- Model reference: `smoldocling-256m`
- Unified runtime: MCP-owned pooled stdio pipeline runtime
- Available pipeline templates: `vlm-document`, `ocr-document`, `vision-multimodel`, and others

`model_runtime(action="status")` reported:

- Model ID: `smoldocling-256m`
- Role: `VLM`
- Artifact status: `AVAILABLE`
- Artifact path:
  `/home/agibsonccc/Documents/GitHub/kompile/data/models/vlm-pipelines/smoldocling-256m/decoder.opt.sdz`
- Runtime status: `NOT_PROBED` until a pipeline is actually initialized

Important: `artifactReady` only proves that the model artifact exists. It does not prove that runtime initialization will succeed.

## Working MCP workflow

### 1. Discover capabilities

```json
{
  "section": "pipelines"
}
```

### 2. Check or provision the model

```json
{
  "action": "status",
  "modelId": "smoldocling-256m"
}
```

The artifact was already available, so no download was required. If absent, use `model_runtime` with `action: "bootstrap"`.

### 3. Run a dry run first

The dry run validated the PDF, model binding, pipeline definition, and output paths without writing artifacts.

The effective request was:

```json
{
  "name": "MCP OCR dogfood dry run",
  "dryRun": true,
  "async": false,
  "documents": [
    {
      "path": "/home/agibsonccc/Documents/GitHub/kompile/kompile-rag-builds/kompile-sample/project/data/input_documents/uploads/wailingcaverns.pdf",
      "sourceType": "FILE",
      "pipelineId": "vlm-ocr-pdf"
    }
  ],
  "pipelines": [
    {
      "pipelineId": "vlm-ocr-pdf",
      "pipelineType": "VLM",
      "loaderName": "pdf",
      "chunkerName": "sentence",
      "modelId": "smoldocling-256m",
      "options": {
        "outputFormat": "MARKDOWN",
        "maxPages": 1,
        "pageRange": "1",
        "maxNewTokens": 768,
        "pdfRenderDpi": 144,
        "pageBatchSize": 1,
        "temperature": 0.0,
        "doSample": false,
        "timeoutMinutes": 30
      }
    }
  ],
  "defaultPipelineId": "vlm-ocr-pdf",
  "knowledgeBase": {
    "name": "dogfood-ocr-pdf"
  },
  "modelRuntime": {
    "autoBootstrap": false,
    "type": "vlm_pipeline",
    "timeoutMinutes": 30
  },
  "steps": [
    "LOADING",
    "MARKDOWN_EXTRACTION",
    "CHUNKING"
  ],
  "strictSteps": true,
  "deriveOntology": false,
  "embeddingTraining": {
    "enabled": false
  },
  "reasoningLearning": {
    "enabled": false
  }
}
```

The dry run resolved:

- Pipeline: `vlm-ocr-pdf`
- Definition: `vlm-document`
- Runner: `VlmDocumentStepRunner`
- Model binding: `default -> smoldocling-256m`
- Existing local model artifact: used without bootstrap
- Intended output paths under `data/crawls/dogfood-ocr-pdf` and `data/markdown/dogfood-ocr-pdf`

### 4. Start the real crawl

The same request was submitted with `dryRun` omitted/false and `async: true`.

The start call returned:

```text
jobId=local-b1516703-24b6-4406-aaef-de22458e1c29
status=QUEUED
pollAfterMs=1000
```

### 5. Poll the job

Poll using the same job ID:

```json
{
  "operation": "status",
  "jobId": "local-b1516703-24b6-4406-aaef-de22458e1c29",
  "pollAfterMs": 1000
}
```

The job transitioned from `QUEUED` to `RUNNING` to `COMPLETED`. Runtime startup and extraction took approximately 48 seconds.

Do not resubmit the crawl while it is `QUEUED` or `RUNNING`.

### 6. Retrieve the result

```json
{
  "jobId": "local-b1516703-24b6-4406-aaef-de22458e1c29"
}
```

Call this through `crawl_result` only after `crawl_control` reports `terminal: true`.

## Persisted evidence

Knowledge-base status:

```text
knowledgeBase: dogfood-ocr-pdf
status: COMPLETED
sources: 1
documents: 1
chunks: 1
graph entities: 4
graph relations: 3
```

The following artifacts were created:

```text
data/crawls/dogfood-ocr-pdf/mcp-request.json
data/crawls/dogfood-ocr-pdf/crawl-result.json
data/crawls/dogfood-ocr-pdf/documents.jsonl
data/crawls/dogfood-ocr-pdf/chunks.jsonl
data/crawls/dogfood-ocr-pdf/analysis.json
data/crawls/dogfood-ocr-pdf/graph.kgraph
data/markdown/dogfood-ocr-pdf/kompile-rag-builds-kompile-sample-project-data-input_documents-uploads-wailingcaverns.pdf.md
```

`documents.jsonl` recorded:

```json
{
  "extractionStatus": "EXTRACTED",
  "pipelineId": "vlm-ocr-pdf",
  "pipelineType": "VLM",
  "loader": "pdf",
  "chunker": "sentence",
  "markdownChars": 602,
  "wordCount": 80
}
```

`chunks.jsonl` contained one non-empty chunk with the extracted Markdown and front matter.

`knowledge_search` against `dogfood-ocr-pdf` returned the extracted document, confirming that the crawl was added to the searchable knowledge base rather than only writing an output file.

## Usability issues found

### 1. `registeredPipelineId` is easy to misuse — resolved

The first dry-run attempt included:

```json
{
  "pipelineId": "vlm-ocr-pdf",
  "registeredPipelineId": "vlm-ocr-pdf"
}
```

This failed with:

```text
Unknown registeredPipelineId: vlm-ocr-pdf
```

The working request omitted `registeredPipelineId` and selected the project pipeline directly using the document and pipeline `pipelineId` fields.

Suggested fix: make the error explain the distinction between a project pipeline ID and a reusable registry/default pipeline ID, or accept the project ID as an alias when it is already registered.

### 2. Top-level crawl result reports the wrong loader/chunker — resolved

The successful `crawl_result` summary reported:

```text
loader: auto
chunker: recursive-character
```

However, the persisted document metadata and actual chunk record correctly reported:

```text
loader: pdf
chunker: sentence
```

Suggested fix: populate top-level result fields from the effective resolved pipeline, or label them as profile defaults when multiple documents/pipelines are present.

### 3. VLM startup latency is not obvious — resolved

The request stayed in `RUNNING` for roughly 48 seconds while the pooled VLM runtime initialized. The polling contract worked, but a progress field or current stage would improve the experience.

Suggested improvement: expose a stage such as `MODEL_INITIALIZATION`, `PDF_RENDERING`, `VLM_EXTRACTION`, or `PERSISTENCE` in `crawl_control` status.

### 4. `knowledge_graph list_fact_sheets` still failed independently — resolved

During discovery, this call returned:

```text
Project-local crawl failed: Input length = 1
```

This did not prevent `knowledge_status`, `crawl_documents`, `crawl_result`, or `knowledge_search` from working. After the successful crawl, `knowledge_status(knowledgeBase="dogfood-ocr-pdf")` worked and returned the completed KB.

Suggested fix: align local fact-sheet listing with the now-working inventory/status path.

### 5. OCR/VLM terminology is ambiguous — resolved

The live project pipeline is named `vlm-ocr-pdf` and uses the VLM document runner. Capability discovery also advertises a separate `ocr-document` template. The successful image-PDF path used the project-registered VLM OCR pipeline, not the generic `ocr-document` template.

Suggested improvement: document clearly whether `OCR` means a traditional OCR engine, VLM-based document extraction, or both, and show which pipeline to choose for scanned PDFs.

## Earlier `knowledge_status` issue

The no-argument `knowledge_status` failure was resolved. It now inventories persisted local knowledge bases instead of implicitly triggering a full repository crawl when persisted KB summaries already exist.

Current behavior:

- `knowledge_status` succeeds.
- The default `kompile-knowledge` base is `COMPLETED`.
- Historical failed test/audit crawl records remain visible but do not prevent status initialization.

## Historical caveats from the earlier VLM audit — rechecked

- Generated VLM/OCR definitions include a concrete `SequencePipeline` in `pipelineSpec.@class`,
  and crawl preflight rejects custom definitions that omit the polymorphic class.
- Nested `modelBindings`/`resolvedModels` maps are supported by the serving contract. Its regression
  test converts them with `Data.fromMap` and reads them back as typed nested `Data` values.
- The managed-runtime test executes a caller-supplied unified definition, so the generic
  custom-definition path is covered independently of the project-registered compatibility route.
- Standalone raster-image support still requires an explicit project image adapter. The built-in VLM
  document runner and the dogfood workflow documented here use PDF input.

## Completed follow-ups

1. [x] Clarified and fixed `registeredPipelineId` versus project `pipelineId`.
2. [x] Corrected top-level `crawl_result` loader/chunker metadata.
3. [x] Added crawl-stage progress to status polling.
4. [x] Aligned local `knowledge_graph list_fact_sheets` with `knowledge_status` inventory.
5. [x] Added a first-class MCP regression test for the working image-heavy PDF request.
6. [x] Asserted that `documents.jsonl` and `chunks.jsonl` agree on effective pipeline metadata.
7. [x] Established `vlm-ocr-pdf` as the canonical scanned-PDF example; `ocr-document` remains an
   explicit executor-defined OCR contract.

## Multi-page OCR failure (2026-08-20)

A fresh real parse was run to test multiple pages rather than only the known-good one-page case.

Request:

```json
{
  "documents": [
    {
      "path": "kompile-vlm-demo/sample-pdfs/sample.pdf",
      "pipelineId": "vlm-ocr-pdf"
    }
  ],
  "pipelines": [
    {
      "pipelineId": "vlm-ocr-pdf",
      "pipelineType": "VLM",
      "registeredPipelineId": "vlm-ocr-pdf",
      "modelId": "smoldocling-256m",
      "loaderName": "pdf",
      "chunkerName": "sentence",
      "options": {
        "outputFormat": "MARKDOWN",
        "maxPages": 3,
        "pageRange": "1-3",
        "maxNewTokens": 768,
        "pdfRenderDpi": 144,
        "pageBatchSize": 1,
        "temperature": 0.0,
        "doSample": false,
        "timeoutMinutes": 30
      }
    }
  ],
  "defaultPipelineId": "vlm-ocr-pdf",
  "knowledgeBase": { "name": "ocr-three-page-reparse" },
  "modelRuntime": {
    "autoBootstrap": false,
    "timeoutMinutes": 30
  },
  "steps": ["LOADING", "MARKDOWN_EXTRACTION", "CHUNKING"],
  "strictSteps": true
}
```

MCP job:

```text
jobId: local-7eca22cf-ec0c-476b-bdfc-2ac28832cb27
status: FAILED
```

Exact document failure:

```text
ai.kompile.pipeline.serving.launcher.PipelineRuntimeSession$RuntimeFailure:
assign(Number) destination has CLOSED DataBuffer. Variable lifecycle violation:
array was freed but still referenced. dst.shape=[1, 2305] dst.dtype=LONG dst.id=3474
```

Result metadata:

- Failed documents: 1
- Markdown documents: 0
- Chunks: 0
- Failure occurred during model-backed execution after approximately 42 seconds.

Interpretation: this is a concrete ND4J/array-lifecycle failure during multi-page VLM execution. An array with shape `[1, 2305]` and dtype `LONG` was freed, then reused as the destination of `assign(Number)`. The one-page run succeeded immediately beforehand, so this report specifically identifies a multi-page/runtime buffer reuse or ownership bug; it is not a generic `VLM returned no text` fallback and not a persistence or graph-extraction failure.

## Multi-page OCR retry after cleanup (2026-08-20)

After the reported cleanup, the same real three-page parse was retried with the explicit model and generation settings above. A dry run resolved successfully:

- Pipeline: `vlm-ocr-pdf`
- Model binding: `smoldocling-256m`
- Loader: `pdf`
- Chunker: `sentence`
- Page range: `1-3`
- `maxPages`: `3`
- `pageBatchSize`: `1`
- `pdfRenderDpi`: `144`
- `maxNewTokens`: `768`
- `temperature`: `0.0`
- `doSample`: `false`

The real crawl used:

```text
Knowledge base: ocr-three-page-retry
Input: kompile-vlm-demo/sample-pdfs/sample.pdf
Job: local-d18e2d55-1bfd-4207-8adf-80e3f11d4157
Stages: LOADING, MARKDOWN_EXTRACTION, CHUNKING
```

Terminal result:

```text
status: FAILED
failed documents: 1
markdown documents: 0
chunks: 0
```

Exact current error:

```text
ai.kompile.pipeline.serving.launcher.PipelineRuntimeSession$RuntimeFailure:
assign(Number) destination has CLOSED DataBuffer.
Variable lifecycle violation: array was freed but still referenced.
dst.shape=[1, 2305]
dst.dtype=LONG
dst.id=3474
```

The retry took approximately 46 seconds and failed during model-backed execution. This confirms that the closed-`DataBuffer` multi-page failure remained reproducible after cleanup in the runtime used by this MCP crawl; no Markdown or chunks were persisted for the failed document.

## Multi-page OCR current MCP retest (2026-08-20)

A fresh real three-page crawl was run after the latest cleanup using the current MCP-hosted runtime.

Request details:

```text
Knowledge base: ocr-three-page-current-retest
Input: /home/agibsonccc/Documents/GitHub/kompile/kompile-vlm-demo/sample-pdfs/sample.pdf
Pipeline: vlm-ocr-pdf
Pipeline type: VLM
Model: smoldocling-256m
Pages: 1-3
maxPages: 3
pageBatchSize: 1
outputFormat: MARKDOWN
maxNewTokens: 768
pdfRenderDpi: 144
temperature: 0.0
doSample: false
Loader: pdf
Chunker: sentence
Stages: LOADING, MARKDOWN_EXTRACTION, CHUNKING
Job: local-5d64f93a-4ccd-43e6-a216-dad867c71fe9
```

The model artifact was reported as available, but runtime status was `NOT_PROBED` until execution. The job progressed through `MODEL_INITIALIZATION` at 30% and `DOCUMENT_PROCESSING` at 60%, then reached a terminal failure.

Terminal result:

```text
status: FAILED
failed documents: 1
markdown documents: 0
chunks: 0
```

Exact current document failure:

```text
ai.kompile.pipeline.serving.launcher.PipelineRuntimeSession$RuntimeFailure:
assign(Number) destination has CLOSED DataBuffer.
Variable lifecycle violation: array was freed but still referenced.
dst.shape=[1, 2305]
dst.dtype=LONG
dst.id=3474
```

The crawl completed in approximately 47 seconds. This is a fresh reproduction of the same concrete multi-page VLM array-lifecycle failure. No Markdown or chunks were persisted. The MCP tools and pipeline resolution worked; the failure occurred during model-backed document processing, before chunking/persistence.
