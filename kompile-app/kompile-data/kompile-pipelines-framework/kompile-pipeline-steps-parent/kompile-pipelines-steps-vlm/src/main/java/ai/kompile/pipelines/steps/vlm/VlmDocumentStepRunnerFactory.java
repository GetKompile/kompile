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
                        "DOCTAGS, MARKDOWN, JSON, or TEXT", "DOCTAGS"))
                .parameter(parameter("pdfRenderDpi", ValueType.INT64, "PDF render DPI", 300L))
                .parameter(parameter("pageBatchSize", ValueType.INT64,
                        "Pages per inference batch", 1L))
                .parameter(parameter("maxPages", ValueType.INT64,
                        "Maximum pages, 0 for all", 0L))
                .parameter(parameter("failFastOnPageError", ValueType.BOOLEAN,
                        "Stop at the first page/inference error", true))
                .parameter(parameter("maxNewTokens", ValueType.INT64,
                        "Generation token limit", 4096L))
                .parameter(parameter("temperature", ValueType.DOUBLE,
                        "Generation temperature", 0.0))
                .parameter(parameter("topP", ValueType.DOUBLE, "Nucleus sampling threshold", 1.0))
                .parameter(parameter("beamSize", ValueType.INT64, "Beam search width", 1L))
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
