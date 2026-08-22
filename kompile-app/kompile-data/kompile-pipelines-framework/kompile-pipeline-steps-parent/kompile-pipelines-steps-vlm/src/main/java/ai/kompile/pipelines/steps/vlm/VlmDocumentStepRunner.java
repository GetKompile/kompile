/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.steps.vlm;

import ai.kompile.ocr.OcrPipelineConfig;
import ai.kompile.ocr.VlmOutputFormat;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.models.pipeline.VlmDocumentPipeline;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Standard pipeline step for reusable, end-to-end VLM document extraction. */
public final class VlmDocumentStepRunner implements PipelineStepRunner {
    public static final String FILE_PATH = "filePath";
    public static final String TEXT = "text";
    public static final String MARKDOWN = "markdown";
    public static final String RESOLVED_MODELS = "resolvedModels";

    private Data parameters;
    private VlmDocumentPipeline pipeline;
    private String loadedIdentity;

    @Override
    public void init(StepConfig stepConfig, Context context) {
        this.parameters = stepConfig.getParameters();
    }

    @Override
    public synchronized Data exec(Data input, Context context) throws Exception {
        String filePath = input.getString(FILE_PATH, input.getString("path"));
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("VLM document step requires filePath");
        }
        File document = Path.of(filePath).toAbsolutePath().normalize().toFile();
        if (!document.isFile()) {
            throw new IllegalArgumentException("VLM document does not exist: " + document);
        }
        if (!document.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException(
                    "VLM_DOCUMENT supports application/pdf (.pdf) input only; direct raster-image "
                            + "paths are not supported: " + document);
        }

        ResolvedModel model = resolvedModel(input);
        ensureLoaded(model);
        OcrPipelineConfig config = OcrPipelineConfig.builder()
                .useVlm(true)
                .vlmModelId(model.modelId())
                .vlmOutputFormat(outputFormat(input))
                .sourceId(document.getAbsolutePath())
                .maxNewTokens(intOption(input, "maxNewTokens", 4096))
                .temperature(doubleOption(input, "temperature", 0.0))
                .topP(doubleOption(input, "topP", 1.0))
                .beamSize(intOption(input, "beamSize", 1))
                .doSample(booleanOption(input, "doSample", false))
                .pdfRenderDpi(intOption(input, "pdfRenderDpi", 300))
                .pageBatchSize(intOption(input, "pageBatchSize", 1))
                .maxPages(intOption(input, "maxPages", 0))
                .failFastOnPageError(booleanOption(input, "failFastOnPageError", true))
                .pageRange(stringOption(input, "pageRange", null))
                .includeAuditTrail(booleanOption(input, "includeAuditTrail", true))
                .build();

        List<ParsedDocument> pages = pipeline.processPdf(document, config, null);
        String text = pages.stream()
                .filter(ParsedDocument::isSuccess)
                .map(ParsedDocument::getText)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        if (text.isBlank()) {
            throw new IllegalStateException(noTextDiagnostic(document, model, config, pages));
        }
        Data output = input.dup();
        output.put(TEXT, text);
        output.put(MARKDOWN, text);
        output.put("pageCount", (long) pages.size());
        output.put("modelId", model.modelId());
        return output;
    }

    private void ensureLoaded(ResolvedModel model) throws Exception {
        String identity = model.modelId() + "@" + model.directory();
        if (pipeline != null && identity.equals(loadedIdentity) && pipeline.isReady()) return;
        if (pipeline != null) pipeline.unloadModels();
        pipeline = new VlmDocumentPipeline();
        // This runner owns a reusable model session. Per-document encoder release would close
        // the shared vision SameDiff instance while VlmDocumentPipeline still reports ready,
        // leaving the next pooled request with a stale frozen plan and missing external inputs.
        pipeline.setReleaseEncoderAfterEncoding(false);
        pipeline.loadModelsFromDirectory(model.modelId(), model.directory().toFile());
        loadedIdentity = identity;
    }

    private ResolvedModel resolvedModel(Data input) {
        Object raw = input.toMap().get(RESOLVED_MODELS);
        if (!(raw instanceof Map<?, ?> models) || models.isEmpty()) {
            throw new IllegalArgumentException(
                    "VLM document step requires a resolved model binding");
        }
        Object preferred = models.get("vlm");
        if (preferred == null) preferred = models.get("vision");
        if (preferred == null) preferred = models.get("default");
        if (preferred == null) preferred = models.values().iterator().next();
        if (!(preferred instanceof Map<?, ?> descriptor)) {
            throw new IllegalArgumentException("Resolved VLM model descriptor is invalid");
        }
        String modelId = stringValue(descriptor.get("modelId"));
        String modelPath = stringValue(descriptor.get("modelPath"));
        if (modelId == null || modelPath == null) {
            throw new IllegalArgumentException("Resolved VLM model requires modelId and modelPath");
        }
        Path path = Path.of(modelPath).toAbsolutePath().normalize();
        Path directory = Files.isDirectory(path) ? path : path.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Resolved VLM model directory does not exist: " + path);
        }
        return new ResolvedModel(modelId, directory);
    }

    private VlmOutputFormat outputFormat(Data input) {
        String value = stringOption(input, "outputFormat", "DOCTAGS");
        try {
            return VlmOutputFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception invalidFormat) {
            throw new IllegalArgumentException(
                    "Unsupported VLM outputFormat '" + value + "'. Expected one of "
                            + java.util.Arrays.toString(VlmOutputFormat.values())
                            + ". Set it under pipelines[].options.outputFormat or the registered pipeline parameters.",
                    invalidFormat);
        }
    }

    private String noTextDiagnostic(File document,
                                    ResolvedModel model,
                                    OcrPipelineConfig config,
                                    List<ParsedDocument> pages) {
        int successfulPages = 0;
        int blankPages = 0;
        int failedPages = 0;
        StringBuilder pageDetails = new StringBuilder();
        if (pages != null) {
            for (ParsedDocument page : pages) {
                if (page.isSuccess()) {
                    successfulPages++;
                    if (page.getText() == null || page.getText().isBlank()) {
                        blankPages++;
                    }
                } else {
                    failedPages++;
                }
                if (pageDetails.length() > 0) pageDetails.append("; ");
                pageDetails.append("page ").append(page.getPageNumber()).append(": ")
                        .append(page.isSuccess() ? "success" : "failed");
                if (page.getText() == null || page.getText().isBlank()) {
                    pageDetails.append(" (blank)");
                }
                if (page.getErrorMessage() != null && !page.getErrorMessage().isBlank()) {
                    pageDetails.append(" - ").append(page.getErrorMessage().trim());
                }
            }
        }

        StringBuilder diagnostic = new StringBuilder("VLM returned no text for ")
                .append(document)
                .append(". modelId=").append(model.modelId())
                .append(", outputFormat=").append(config.getVlmOutputFormat())
                .append(", pagesReturned=").append(pages == null ? 0 : pages.size())
                .append(", successfulPages=").append(successfulPages)
                .append(", blankPages=").append(blankPages)
                .append(", failedPages=").append(failedPages)
                .append(", generation={maxNewTokens=").append(config.getMaxNewTokens())
                .append(", temperature=").append(config.getTemperature())
                .append(", topP=").append(config.getTopP())
                .append(", beamSize=").append(config.getBeamSize())
                .append(", doSample=").append(config.isDoSample())
                .append(", pdfRenderDpi=").append(config.getPdfRenderDpi())
                .append(", pageBatchSize=").append(config.getPageBatchSize())
                .append(", maxPages=").append(config.getMaxPages())
                .append(", pageRange=").append(config.getPageRange())
                .append("}");
        if (pageDetails.length() > 0) {
            diagnostic.append(". pageDiagnostics=[").append(pageDetails).append(']');
        }
        diagnostic.append(
                ". If this request inherited a registeredPipelineId, run crawl_documents with dryRun=true and explicitly set "
                        + "pipelines[].modelId plus pipelines[].options.outputFormat/maxNewTokens/pdfRenderDpi/pageBatchSize/temperature/doSample.");
        return diagnostic.toString();
    }

    private int intOption(Data input, String key, int fallback) {
        Object value = option(input, key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private double doubleOption(Data input, String key, double fallback) {
        Object value = option(input, key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private boolean booleanOption(Data input, String key, boolean fallback) {
        Object value = option(input, key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private String stringOption(Data input, String key, String fallback) {
        Object value = option(input, key);
        return value == null ? fallback : String.valueOf(value);
    }

    private Object option(Data input, String key) {
        Object request = input.toMap().get("option." + key);
        if (request != null) return request;
        return parameters == null ? null : parameters.toMap().get(key);
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @Override
    public boolean isInitialized() {
        return parameters != null;
    }

    @Override
    public synchronized void close() {
        if (pipeline != null) pipeline.unloadModels();
        pipeline = null;
        loadedIdentity = null;
    }

    private record ResolvedModel(String modelId, Path directory) {
    }
}
