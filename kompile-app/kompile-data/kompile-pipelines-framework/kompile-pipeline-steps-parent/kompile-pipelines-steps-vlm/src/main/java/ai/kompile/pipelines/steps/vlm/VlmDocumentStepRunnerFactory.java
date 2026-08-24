/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.steps.vlm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;

/** Factory and authoring schema for the standard VLM document step. */
public final class VlmDocumentStepRunnerFactory implements PipelineStepRunnerFactory {
    public static final String RUNNER_FQCN =
            "ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner";

    @Override
    public String stepTypeName() {
        return "VLM_DOCUMENT";
    }

    @Override
    public String getRunnerType() {
        return RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new VlmDocumentStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name("VLM_DOCUMENT")
                .runnerClassName(RUNNER_FQCN)
                .description("Reusable end-to-end extraction for application/pdf documents. "
                        + "Direct raster-image paths are not supported.")
                .parameter(parameter("outputFormat", ValueType.STRING,
                        "Requested rendering: RAW/DOCTAGS, PLAIN_TEXT, MARKDOWN, HTML, or JSON",
                        "RAW"))
                .parameter(parameter("outputProtocol", ValueType.STRING,
                        "Optional model-package protocol ID; omitted uses vlm-output-protocol.json", null))
                .parameter(parameter("task", ValueType.STRING,
                        "Protocol task name or alias, such as ocr, document, formatted_ocr, or vqa", null))
                .parameter(parameter("prompt", ValueType.STRING,
                        "Optional prompt override layered over the selected protocol task", null))
                .parameter(parameter("pdfRenderDpi", ValueType.INT64, "PDF render DPI", 300L))
                .parameter(parameter("pageBatchSize", ValueType.INT64,
                        "Pages per inference batch", 1L))
                .parameter(parameter("maxPages", ValueType.INT64,
                        "Maximum pages, 0 for all", 0L))
                .parameter(parameter("failFastOnPageError", ValueType.BOOLEAN,
                        "Stop at the first page/inference error", true))
                .parameter(parameter("maxNewTokens", ValueType.INT64,
                        "Optional diagnostic token limit; 0 generates to EOS/context", 0L))
                .parameter(parameter("maxResponseBytes", ValueType.INT64,
                        "Maximum UTF-8 generated document bytes", 16L * 1024L * 1024L))
                .parameter(parameter("adaptiveRegionFallbackEnabled", ValueType.BOOLEAN,
                        "Split pathological full-page output into bounded independent regions", false))
                .parameter(parameter("adaptiveFullPageMaxNewTokens", ValueType.INT64,
                        "Safety cap for the initial full-page attempt when adaptive fallback is enabled", 3584L))
                .parameter(parameter("adaptiveRegionMaxNewTokens", ValueType.INT64,
                        "Per-region token cap before recursively splitting an adaptive crop", 1024L))
                .parameter(parameter("adaptiveRegionRepetitionPenalty", ValueType.DOUBLE,
                        "Minimum repetition penalty for adaptive crops", 1.1))
                .parameter(parameter("adaptiveNativeRepetitionMaxPeriod", ValueType.INT64,
                        "Maximum periodic token-tail length checked by native adaptive termination", 64L))
                .parameter(parameter("adaptiveNativeRepetitionMaxRepeats", ValueType.INT64,
                        "Exact repeats required by native adaptive termination", 4L))
                .parameter(parameter("temperature", ValueType.DOUBLE,
                        "Generation temperature", 0.0))
                .parameter(parameter("topP", ValueType.DOUBLE, "Nucleus sampling threshold", 1.0))
                .parameter(parameter("topK", ValueType.INT64, "Top-k sampling cutoff; 0 disables", 0L))
                .parameter(parameter("samplingPreset", ValueType.STRING,
                        "Optional creative/precise sampling preset", null))
                .parameter(parameter("repetitionPenalty", ValueType.DOUBLE,
                        "Generation repetition penalty", 1.0))
                .parameter(parameter("beamSize", ValueType.INT64, "Beam search width", 1L))
                .parameter(parameter("maxKvLen", ValueType.INT64,
                        "Optional context/KV cap; 0 uses the model context", 0L))
                .parameter(parameter("doSample", ValueType.BOOLEAN,
                        "Enable sampling", false))
                .parameter(parameter("pageRange", ValueType.STRING,
                        "Optional page range understood by the PDF processor", null))
                .parameter(parameter("includeAuditTrail", ValueType.BOOLEAN,
                        "Include page-level audit metadata", true))
                .input(ParameterSchema.builder().name("filePath").type(ValueType.STRING)
                        .required(true).description("Path to an application/pdf document").build())
                .input(ParameterSchema.builder().name("resolvedModels").type(ValueType.OBJECT)
                        .required(true).description("Resolved model descriptors keyed by binding role").build())
                .output(parameter("text", ValueType.STRING, "Combined extracted page text", null))
                .output(parameter("markdown", ValueType.STRING, "Markdown-compatible extracted text", null))
                .output(parameter("pageCount", ValueType.INT64, "Processed PDF page count", null))
                .output(parameter("modelId", ValueType.STRING, "Resolved model id", null))
                .build();
    }

    private ParameterSchema parameter(
            String name, ValueType type, String description, Object defaultValue) {
        return ParameterSchema.builder().name(name).type(type).required(false)
                .description(description).defaultValue(defaultValue).build();
    }
}
