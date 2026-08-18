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

Definitions are immutable versions under `data/pipelines/unified/<pipelineId>/`. Promotion changes an atomic active-version pointer; it does not overwrite prior versions.

All model-backed runs use the same pooled stdio runtime. Callers provide pipeline definitions, model bindings, and input data—not executable paths, ports, subprocess modes, or worker configuration.

## Validate a definition from the CLI

The human-facing CLI retains schema inspection and offline validation:

```bash
kompile pipeline validate --file=pipeline.json
kompile pipeline list-steps --verbose
```

Execution and lifecycle mutation intentionally remain MCP operations so agents, crawls, and direct pipeline runs share the same versioning, model staging, pooling, cancellation, and audit behavior.

## Canonical definition shape

```json
{
  "schemaVersion": 1,
  "pipelineId": "document-pipeline",
  "displayName": "Document extraction",
  "kind": "VLM",
  "topology": "SEQUENCE",
  "modelBindings": {"default": "smoldocling-256m"},
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

Use `pipeline action=capabilities` for the live step catalog and parameter schemas.
