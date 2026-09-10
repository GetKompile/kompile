/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.llm.pipeline;

import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.llm.StructuredChatLanguageModel;

import java.util.List;
import java.util.Objects;

/**
 * In-process crawl backend for a first-party SameDiff language model.
 *
 * <p>This adapter deliberately has no Spring stereotype and owns no model lifecycle. The caller
 * loads and unloads the supplied model, while crawl orchestration uses the same local-backend port
 * as a packaged deployment without HTTP, an executable JAR, or a child JVM.</p>
 */
public final class SameDiffInProcessBackend implements LocalServingBackend {
    private final SameDiffLanguageModelImpl languageModel;

    public SameDiffInProcessBackend(SameDiffLanguageModelImpl languageModel) {
        this.languageModel = Objects.requireNonNull(languageModel, "languageModel");
    }

    @Override
    public boolean isAvailable() {
        return languageModel.isLoaded();
    }

    @Override
    public boolean matchesModel(String modelId) {
        return Objects.equals(languageModel.getLoadedModelId(), modelId);
    }

    @Override
    public boolean supportsStructuredChat() {
        return true;
    }

    @Override
    public String generate(String prompt) {
        return languageModel.generateResponse(prompt, List.of());
    }

    @Override
    public String generate(String prompt, int maxNewTokens) {
        return languageModel.generateResponse(prompt, List.of(), maxNewTokens);
    }

    @Override
    public StructuredChatLanguageModel.Response generateChat(
            StructuredChatLanguageModel.Request request, int maxNewTokens) {
        return languageModel.generateChat(request, maxNewTokens);
    }
}
