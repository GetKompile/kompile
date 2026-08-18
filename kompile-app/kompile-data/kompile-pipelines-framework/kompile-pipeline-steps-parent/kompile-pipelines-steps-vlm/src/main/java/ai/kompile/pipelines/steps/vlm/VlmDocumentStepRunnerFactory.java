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
                .description("Reusable end-to-end VLM extraction for image-based PDFs")
                .parameter(parameter("outputFormat", ValueType.STRING,
                        "DOCTAGS, MARKDOWN, JSON, or plain text"))
                .parameter(parameter("pdfRenderDpi", ValueType.INT64, "PDF render DPI"))
                .parameter(parameter("pageBatchSize", ValueType.INT64, "Pages per inference batch"))
                .parameter(parameter("maxPages", ValueType.INT64, "Maximum pages, 0 for all"))
                .parameter(parameter("maxNewTokens", ValueType.INT64, "Generation token limit"))
                .parameter(parameter("temperature", ValueType.DOUBLE, "Generation temperature"))
                .build();
    }

    private ParameterSchema parameter(String name, ValueType type, String description) {
        return ParameterSchema.builder().name(name).type(type).required(false)
                .description(description).build();
    }
}
