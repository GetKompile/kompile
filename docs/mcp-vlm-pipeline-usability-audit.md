# MCP-Only VLM Pipeline Usability Audit

**Date:** 2026-08-18  
**Project:** `/home/agibsonccc/Documents/GitHub/kompile`  
**Scope:** Determine whether a VLM pipeline can be understood, validated, and executed using only MCP tools.  
**Original audit constraint:** No fixes, configuration changes, pipeline creation, or promotion were attempted during evidence collection.  
**Remediation status:** The implementation described below was applied on 2026-08-18 after the baseline audit.

## Executive summary

The baseline MCP-only workflow was understandable and the canonical pipeline definition validated successfully, but execution and authoring observability had ten concrete gaps. The remediation preserves the single `UnifiedPipelineDefinition` runtime while making diagnostics structured, previews isolated and inspectable, inline tests read-only, project registrations visible through the lifecycle tool, and the VLM media/model contracts explicit.

The intended workflow is:

1. Call `crawl_discover(section="pipelines")`.
2. Call `crawl_discover(section="models")` and/or `model_runtime(action="status")`.
3. Bootstrap or import the VLM with `model_runtime` if it is not ready.
4. Define a `UnifiedPipelineDefinition` and call `pipeline(action="validate")`.
5. Exercise it with `pipeline(action="test")`, or use `crawl_documents(dryRun=true)` for request-scoped ingestion validation.
6. For a persisted lifecycle, use `pipeline` actions `create`, `promote`, and `run`.
7. For request-scoped ingestion, rerun `crawl_documents` with `dryRun=false`.

## Remediation summary

| Finding | Implemented behavior |
|---|---|
| 1. Opaque execution failure | The runtime protocol carries `failureStage`, root-cause fields, a bounded exception chain, and stack frames. Bootstrap failures are emitted through the protocol, and `pipeline test/run` preserve the diagnostic. |
| 2. Split registries | `pipeline list/get` expose both managed definitions and active `kompile.project.json` registrations with `registrySource(s)`. `run` resolves executable project registrations as well as managed definitions. |
| 3. Polluted explicit dry-run | A non-empty explicit `documents` array with `dryRun=true` does not merge prior crawl sources or source configuration. |
| 4. Empty step catalog | `pipeline capabilities` always exposes the canonical `VLM_DOCUMENT` runtime entry; the provider schema now includes inputs, outputs, all consumed parameters, and defaults. |
| 5. Generic lifecycle schema | The MCP schema describes `UnifiedPipelineDefinition`, `pipelineSpec`, execution inputs including `filePath`, and model-runtime fields. |
| 6. Unclear model selection | The schema and capabilities publish `modelBindings.default > modelId > vlmModel > modelSetId`; differing `modelId` and `vlmModel` values are rejected. |
| 7. Ambiguous readiness | Inventory reports `artifactReady/artifactStatus` separately from `runtimeStatus=NOT_PROBED`; legacy `ready` is explicitly documented as artifact-only. |
| 8. Test side effects | `pipeline test` resolves only already-registered artifacts and never creates or refreshes project/model metadata. Missing models produce a read-only provisioning diagnostic. |
| 9. Media mismatch | `VLM_DOCUMENT` is explicitly `application/pdf`-only and rejects standalone raster paths before model initialization. |
| 10. Weak observability | Dry-run returns `metadata.preview` with the effective request/documents, per-file route, loader/chunker, resolved pipeline, model resolution or error, warnings, target paths, and persistent-write flags. |

The findings below retain the original observations as the audit evidence that motivated these changes.

## Canonical VLM definition

The following definition passed `pipeline(action="validate")` with no warnings or errors:

```json
{
  "schemaVersion": 1,
  "pipelineId": "vlm-mcp-usability-audit",
  "displayName": "VLM MCP usability audit",
  "kind": "VLM",
  "topology": "SEQUENCE",
  "modelBindings": {
    "default": "smoldocling-256m"
  },
  "pipelineSpec": {
    "@class": "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
    "id": "vlm-mcp-usability-audit",
    "steps": [
      {
        "@class": "ai.kompile.pipelines.framework.core.config.GenericStepConfig",
        "runnerClassName": "ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner",
        "parameters": {
          "outputFormat": "DOCTAGS",
          "pdfRenderDpi": 300,
          "pageBatchSize": 1
        }
      }
    ]
  }
}
```

Validation result:

```json
{
  "warnings": [],
  "errors": [],
  "valid": true
}
```

## Advertised request-scoped crawl shape

The following MCP-only dry-run request was accepted:

```json
{
  "name": "vlm-mcp-usability-audit",
  "dryRun": true,
  "documents": [
    {
      "path": "kompile-vlm-demo/sample-pdfs/sample.pdf",
      "pipelineId": "vlm-mcp-usability-audit"
    }
  ],
  "pipelines": [
    {
      "pipelineId": "vlm-mcp-usability-audit",
      "pipelineType": "VLM",
      "modelId": "smoldocling-256m"
    }
  ],
  "defaultPipelineId": "vlm-mcp-usability-audit",
  "modelRuntime": {
    "autoBootstrap": false
  }
}
```

## Findings

### 1. Inline VLM execution fails without actionable diagnostics

The canonical definition was tested with:

```json
{
  "action": "test",
  "definition": "<canonical definition above>",
  "input": {
    "filePath": "/home/agibsonccc/Documents/GitHub/kompile/kompile-vlm-demo/sample-pdfs/sample.pdf"
  },
  "timeoutMinutes": 5
}
```

Exact MCP result:

```text
Pipeline test failed: java.lang.ExceptionInInitializerError
```

The result contained no nested cause, stack trace, run ID, status handle, or log path. No current pipeline-runtime log containing this failure was found in the MCP, project, or managed-subprocess log locations. Historical initializer errors exist elsewhere, but they are unrelated and were not attributed to this test.

This makes the execution failure impossible to diagnose through the exposed MCP pipeline workflow.

### 2. Pipeline discovery and lifecycle management expose different registries

Observed results:

- `pipeline(action="list")` returned `[]`.
- `crawl_discover(section="pipelines")` advertised an active, ready project pipeline named `vlm-ocr-pdf`.
- `pipeline(action="get", pipelineId="vlm-ocr-pdf")` returned:

```text
Pipeline get failed: Unknown pipeline vlm-ocr-pdf
```

An MCP user cannot tell whether a project-registered crawl pipeline can be managed by the versioned `pipeline` lifecycle, nor how to import or reference it there.

### 3. Dry-run did not isolate the explicitly requested document

The dry-run request supplied one PDF, but the preview reported two sources:

```text
/home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/pathfinder-mythic.pdf
/home/agibsonccc/Documents/GitHub/kompile/kompile-vlm-demo/sample-pdfs/sample.pdf
```

The extra Pathfinder PDF came from existing project crawl configuration. This conflicts with the MCP tool contract stating that explicit `documents` replace configured sources. It makes it difficult to trust that a dry run validates only the submitted request.

### 4. The pipeline capability step catalog is empty

`pipeline(action="capabilities")` returned:

```json
"stepCatalog": []
```

The response does include a `builtinDocumentPipeline`, so a basic VLM can be copied from it. However, custom authoring still requires source-code or external documentation knowledge because available runners, input contracts, parameter types, defaults, and constraints are not discoverable from the step catalog.

### 5. The two authoring surfaces use different schemas

The persisted `pipeline` lifecycle expects:

- `kind: "VLM"`
- A complete `UnifiedPipelineDefinition`
- A concrete `pipelineSpec.@class`

The request-scoped `crawl_documents` surface expects:

- `pipelineType: "VLM"`
- `modelId` or another model selector
- An optional generated or supplied unified definition

The `pipeline` tool exposes `definition`, `input`, and `modelRuntime` only as generic objects. In particular, the required VLM test input key `filePath` is not visible in the MCP schema.

### 6. Model-selection forms and precedence are unclear

The available schemas and examples expose all of the following:

- `modelId`
- `vlmModel`
- `modelSetId`
- `modelBindings`
- `modelRefs`
- `options.vlmModel`

The precedence and intended use of these alternatives are not explained consistently. Some examples supply more than one form simultaneously.

### 7. Model readiness does not establish runtime health

Both `crawl_discover(section="models")` and `model_runtime(action="status")` reported `smoldocling-256m` as ready with a resolved artifact:

```text
/home/agibsonccc/Documents/GitHub/kompile/data/models/vlm-pipelines/smoldocling-256m/decoder.opt.sdz
```

Despite that, inline execution immediately failed with `ExceptionInInitializerError`. The readiness status appears to mean artifact presence/resolution, not successful runtime initialization. The distinction is not surfaced clearly.

### 8. Inline `pipeline test` has filesystem side effects

Although no `create` or `update` action was called, the test refreshed timestamps in:

- `kompile.project.json`
- `data/pipelines/project-pipelines.json`
- The SmolDocling model entry in project metadata

The timestamps aligned with the inline test call. No tracked VLM source diff was produced, but `pipeline test` is not filesystem read-only.

### 9. PDF and image support are described inconsistently

The project pipeline is named “VLM OCR PDF and image extraction,” but discovery reports `application/pdf` as the supported input type, and the concrete runner invokes a PDF-specific processing path. It is unclear whether standalone image files are truly supported by this pipeline.

### 10. Dry-run and test observability are insufficient

The dry-run result reports only the composed profile, sources, and output locations. It does not return the fully resolved pipeline definition, selected model binding, model artifact, route decision, loader/chunker resolution, or warnings about added project sources.

The test failure similarly provides no structured diagnostic payload. Useful fields would include a run ID, resolved definition, selected model, failure stage, exception chain, and log handle.

## Validation

The remediation was validated with 44 focused and module-level tests:

- 12 pipeline-serving tests, including 3 protocol tests covering nested initializer diagnostics.
- 2 VLM step contract tests, including rejection of standalone raster input before model initialization.
- 29 CLI tests covering lifecycle/discovery registry sharing, read-only test resolution, selector conflicts, readiness semantics, isolated dry-run behavior, and preview contents.
- 1 MCP-to-subprocess integration probe using the canonical sample PDF and the configured SmolDocling artifact directory.

The E2E test JVM resolves its test backend through the single overrideable `backend.artifactId` property. Building `kompile-pipeline-serving` with the existing distribution settings (`-Dnd4j.backend=nd4j-cuda-12.9 -Dkompile.backend=cuda-12.9`) embeds that configured backend in the executable JAR; running the canonical sample through that subprocess reached VLM execution without `NoAvailableBackendException`. The integration test still preserves a structured `EXECUTE_PIPELINE` diagnostic for fixture/output failures and verifies the project manifest and pipeline registry remain unchanged.

All focused Maven invocations and `git diff --check` completed successfully.

## Relevant implementation and documentation locations

- `docs/cli/pipelines.md` — persisted pipeline lifecycle and VLM definition example.
- `docs/mcp/README.md` — request-scoped VLM crawl example.
- `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/tools/grounding/PipelineTool.java` — pipeline MCP actions and generic object fields.
- `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/tools/grounding/CrawlDocumentsTool.java` — crawl pipeline schema.
- `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/project/LocalCrawlCapabilities.java` — VLM/OCR compatibility adapter and generated definition.
- `kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/src/main/java/ai/kompile/pipeline/serving/definition/UnifiedPipelineDefinition.java` — canonical definition model.
- `kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/src/main/java/ai/kompile/pipeline/serving/definition/PipelineDefinitionValidator.java` — validation requirements.
- `kompile-app/kompile-data/kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-vlm/src/main/java/ai/kompile/pipelines/steps/vlm/VlmDocumentStepRunner.java` — VLM runner input and model binding behavior.
- `kompile-app/kompile-data/kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-vlm/src/main/java/ai/kompile/pipelines/steps/vlm/VlmDocumentStepRunnerFactory.java` — advertised VLM parameters.
- `kompile-e2e-tests/src/test/java/ai/kompile/e2e/LocalMcpVlmPdfIT.java` — concrete MCP VLM crawl integration test.

## Repository state

The observations in the findings were collected before remediation. The subsequent implementation changed the pipeline-serving protocol, CLI pipeline/crawl/model surfaces, the VLM step contract, regression tests, and the related guides. It did not create, promote, or persist a user pipeline, and it preserved unrelated pre-existing worktree changes.

The regression suite includes protocol round-tripping of initializer failures, read-only inline model resolution, shared lifecycle/discovery visibility, isolated dry-run source selection and filesystem stability, enriched preview resolution, readiness semantics, and the VLM PDF schema.
