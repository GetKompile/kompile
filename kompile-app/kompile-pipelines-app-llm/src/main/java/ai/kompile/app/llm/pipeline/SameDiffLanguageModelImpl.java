/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.llm.pipeline;

import ai.kompile.core.llm.LanguageModel;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Profiler;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.pipelines.steps.samediff.llm.SameDiffLanguageModelStepRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * On-demand SameDiff-backed {@link LanguageModel} implementation.
 *
 * <p>This bean starts up with no model loaded — calls to {@link #generateResponse(String, List)}
 * fail until {@link #loadModel(String, Path, Path, Map)} is invoked (typically via the
 * {@code POST /api/llm/load} endpoint exposed by {@link LlmModelController}).</p>
 *
 * <p>Loading constructs a fresh {@link SameDiffLanguageModelStepRunner}, initializes it
 * against the supplied SameDiff model and tokenizer files, and then atomically swaps it
 * into place. A subsequent load call cleanly closes the previous runner.</p>
 *
 * <p>Marked {@code @Primary} so that this bean wins over the legacy
 * {@code KompilePipelineLanguageModelImpl} (which is now opt-in via
 * {@code kompile.langmodel.pipeline.enabled=true}) and the
 * {@code NoOpLanguageModelImpl} fallback in {@code kompile-app-core}.</p>
 */
@Service
@Primary
public class SameDiffLanguageModelImpl implements LanguageModel {

    private static final Logger logger = LoggerFactory.getLogger(SameDiffLanguageModelImpl.class);

    private final Object loadLock = new Object();
    private volatile LoadedModel loaded; // null until first successful load
    private final Metrics metrics;
    private final Profiler profiler;

    @Autowired
    public SameDiffLanguageModelImpl(Optional<Metrics> metricsOpt, Optional<Profiler> profilerOpt) {
        this.metrics = metricsOpt.orElse(null);
        this.profiler = profilerOpt.orElse(NoOpProfiler.INSTANCE);
        logger.info("SameDiffLanguageModelImpl initialized (no model loaded). Use POST /api/llm/load to load a model.");
    }

    // ==================== LanguageModel impl ====================

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        ChatResponse response = generateResponseWithPotentialToolCalls(userQuery, context);
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            return response.getResult().getOutput().getText();
        }
        return "";
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        LoadedModel current = this.loaded;
        if (current == null) {
            throw new IllegalStateException(
                    "No SameDiff language model loaded. POST /api/llm/load with a modelId before invoking the LLM.");
        }

        String prompt = composePrompt(userQuery, context);
        String executionId = UUID.randomUUID().toString();
        Data input = Data.empty();
        input.put(current.config.getPromptInputName(), prompt);

        Context ctx = new DefaultContext(
                Data.empty(),
                executionId,
                "ctx-llm-" + executionId,
                null,
                this.metrics,
                this.profiler);

        try {
            Data output = current.runner.exec(input, ctx);
            String text = output.getString(current.config.getResponseOutputName(), "");
            AssistantMessage assistant = new AssistantMessage(text);
            Generation generation = new Generation(assistant, ChatGenerationMetadata.NULL);
            return new ChatResponse(List.of(generation));
        } catch (Exception e) {
            logger.error("SameDiffLanguageModelImpl: generation failed for modelId='{}'", current.modelId, e);
            AssistantMessage error = new AssistantMessage("Error generating response: " + e.getMessage());
            return new ChatResponse(List.of(new Generation(error, ChatGenerationMetadata.NULL)));
        }
    }

    // ==================== Lifecycle / loading ====================

    /**
     * Load (or reload) a SameDiff LLM, replacing any previously loaded model atomically.
     *
     * @param modelId       a logical identifier for status/logging (e.g. the staging registry id)
     * @param modelFile     path to the SameDiff model file (e.g. {@code model.sdz})
     * @param tokenizerFile path to the tokenizer file or directory (HuggingFace tokenizer.json
     *                      or a WordPiece vocab.txt)
     * @param configOpts    optional config knobs:
     *                      <ul>
     *                          <li>{@code tokenizerType} (default {@code huggingface})</li>
     *                          <li>{@code maxNewTokens} (default 256)</li>
     *                          <li>{@code temperature} (default 0.7)</li>
     *                          <li>{@code topK} (default 0)</li>
     *                          <li>{@code inputIdsPlaceholderName} / {@code attentionMaskPlaceholderName} / {@code logitsOutputName}</li>
     *                      </ul>
     */
    public void loadModel(String modelId, Path modelFile, Path tokenizerFile, Map<String, Object> configOpts)
            throws Exception {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelFile, "modelFile");
        Objects.requireNonNull(tokenizerFile, "tokenizerFile");

        if (!Files.exists(modelFile) && !hasShardFiles(modelFile)) {
            throw new IOException("SameDiff model file does not exist: " + modelFile);
        }
        if (!Files.exists(tokenizerFile)) {
            throw new IOException("Tokenizer file/directory does not exist: " + tokenizerFile);
        }
        Map<String, Object> opts = configOpts != null ? configOpts : new HashMap<>();

        String tokenizerType = stringOpt(opts, "tokenizerType", "huggingface");
        int maxNewTokens = intOpt(opts, "maxNewTokens", 256);
        double temperature = doubleOpt(opts, "temperature", 0.7d);
        int topK = intOpt(opts, "topK", 0);
        String inputIdsName = stringOpt(opts, "inputIdsPlaceholderName", "input_ids");
        String attentionMaskName = stringOpt(opts, "attentionMaskPlaceholderName", "attention_mask");
        String logitsName = stringOpt(opts, "logitsOutputName", "logits");

        LLMStepConfig.LLMStepConfigBuilder builder = LLMStepConfig.builder()
                .name("samediff-llm-" + modelId)
                .type("SAMEDIFF_LANGUAGE_MODEL")
                .runnerClassName(SameDiffLanguageModelStepRunner.class.getName())
                .modelUri(modelFile.toUri().toString())
                .tokenizerUri(tokenizerFile.toUri().toString())
                .tokenizerType(tokenizerType)
                .promptInputName("prompt")
                .responseOutputName("llm_response")
                .conversationContextName("llm_conversation_context")
                .toolChoice(LLMStepConfig.ToolChoiceMode.NONE)
                .generationParameterEntry("maxNewTokens", maxNewTokens)
                .generationParameterEntry("temperature", (float) temperature)
                .generationParameterEntry("topK", topK)
                .generationParameterEntry("inputIdsPlaceholderName", inputIdsName)
                .generationParameterEntry("attentionMaskPlaceholderName", attentionMaskName)
                .generationParameterEntry("logitsOutputName", logitsName);

        // Pass through any tokenizer-specific overrides like padTokenId/eosTokenId/etc.
        // Keys recognised by SameDiffHuggingFaceTokenizer.
        for (String tkKey : new String[] {
                "padTokenId", "eosTokenId", "bosTokenId", "unkTokenId", "clsTokenId", "sepTokenId", "maskTokenId" }) {
            if (opts.containsKey(tkKey) && opts.get(tkKey) != null) {
                builder.tokenizerConfigEntry(tkKey, String.valueOf(opts.get(tkKey)));
            }
        }

        LLMStepConfig config = builder.build();
        SameDiffLanguageModelStepRunner runner = new SameDiffLanguageModelStepRunner();
        Context initCtx = new DefaultContext(
                Data.empty(),
                "init-" + modelId,
                "ctx-llm-init-" + modelId,
                null,
                this.metrics,
                this.profiler);

        long start = System.currentTimeMillis();
        try {
            runner.init(config, initCtx);
        } catch (Exception e) {
            // Best-effort cleanup if init partially succeeded.
            try {
                runner.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
        long durationMs = System.currentTimeMillis() - start;

        synchronized (loadLock) {
            LoadedModel previous = this.loaded;
            this.loaded = new LoadedModel(modelId, runner, config, durationMs);
            if (previous != null) {
                logger.info("SameDiffLanguageModelImpl: replaced previously loaded model '{}' with '{}'", previous.modelId, modelId);
                try {
                    previous.runner.close();
                } catch (Exception e) {
                    logger.warn("Failed to close previous SameDiff runner '{}': {}", previous.modelId, e.getMessage(), e);
                }
            } else {
                logger.info("SameDiffLanguageModelImpl: loaded model '{}' in {} ms", modelId, durationMs);
            }
        }
    }

    /** Unload any currently loaded model. Safe to call when nothing is loaded. */
    public void unloadModel() {
        synchronized (loadLock) {
            LoadedModel current = this.loaded;
            this.loaded = null;
            if (current != null) {
                try {
                    current.runner.close();
                    logger.info("SameDiffLanguageModelImpl: unloaded model '{}'", current.modelId);
                } catch (Exception e) {
                    logger.warn("Failed to close SameDiff runner '{}': {}", current.modelId, e.getMessage(), e);
                }
            }
        }
    }

    public boolean isLoaded() {
        return this.loaded != null;
    }

    public String getLoadedModelId() {
        LoadedModel current = this.loaded;
        return current != null ? current.modelId : null;
    }

    public long getLoadDurationMs() {
        LoadedModel current = this.loaded;
        return current != null ? current.loadDurationMs : -1L;
    }

    // ==================== Helpers ====================

    private static String composePrompt(String userQuery, List<String> context) {
        if (userQuery == null) {
            userQuery = "";
        }
        if (context == null || context.isEmpty()) {
            return userQuery;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Context:\n");
        List<String> filtered = new ArrayList<>();
        for (String c : context) {
            if (c != null && !c.isBlank()) {
                filtered.add(c);
            }
        }
        for (int i = 0; i < filtered.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(filtered.get(i)).append("\n");
        }
        sb.append("\nUser: ").append(userQuery);
        return sb.toString();
    }

    private static String stringOpt(Map<String, Object> opts, String key, String defaultValue) {
        Object v = opts.get(key);
        return v != null ? String.valueOf(v) : defaultValue;
    }

    private static int intOpt(Map<String, Object> opts, String key, int defaultValue) {
        Object v = opts.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }

    /**
     * Check if shard files exist for a model base path.
     * SameDiff.load() strips the extension and looks for {baseName}.shard*-of-*.sdnb
     */
    private static boolean hasShardFiles(Path modelFile) {
        Path parent = modelFile.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) return false;
        String baseName = modelFile.getFileName().toString();
        int dotIdx = baseName.lastIndexOf('.');
        if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
        String prefix = baseName + ".shard0-of-";
        try (java.util.stream.Stream<Path> entries = Files.list(parent)) {
            return entries.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".sdnb");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static double doubleOpt(Map<String, Object> opts, String key, double defaultValue) {
        Object v = opts.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }

    /** Holder for an active runner + the config it was initialised with. */
    private static final class LoadedModel {
        final String modelId;
        final SameDiffLanguageModelStepRunner runner;
        final LLMStepConfig config;
        final long loadDurationMs;

        LoadedModel(String modelId, SameDiffLanguageModelStepRunner runner, LLMStepConfig config, long loadDurationMs) {
            this.modelId = modelId;
            this.runner = runner;
            this.config = config;
            this.loadDurationMs = loadDurationMs;
        }
    }
}
