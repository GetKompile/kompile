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
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-process SameDiff-backed {@link LanguageModel} and {@link ChatModel} implementation.
 *
 * <p>Runs Hugging Face-tokenized models through DL4J's lifecycle-aware
 * {@link GenerationPipeline}. The pipeline owns the SameDiff model, KV cache,
 * recurrent state, and DSP plan. Legacy WordPiece configurations retain the
 * pipeline-step runner for compatibility. This class is used <b>only in the
 * serving subprocess</b> (port 8091) where the actual model is loaded. It is
 * never used in app-main or model-staging.</p>
 *
 * <p>Model lifecycle (load/unload) and observability (DSP phase, Triton stats) are
 * exposed as public methods consumed by {@link LlmObservabilityService} and
 * {@link LlmModelController} in the subprocess context.</p>
 *
 * <p>Implements {@link ChatModel} so that Spring AI consumers can call
 * {@link #call(Prompt)} directly.</p>
 */
@Service
@ConditionalOnProperty(name = "kompile.llm.direct-serving.enabled", havingValue = "true", matchIfMissing = false)
public class SameDiffLanguageModelImpl implements LanguageModel, ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(SameDiffLanguageModelImpl.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    /**
     * Minimal ChatML marker template understood by DL4J's ChatTemplate adapter.
     *
     * <p>Some staged model bundles contain only tokenizer.json even though the
     * source Hugging Face repository publishes chat_template.jinja separately.
     * When that tokenizer exposes the ChatML marker tokens, this template
     * preserves instruction formatting without coupling serving to a model ID.</p>
     */
    static final String CHATML_TEMPLATE =
            "{% for message in messages %}<|im_start|>{{ message.role }}\n"
                    + "{{ message.content }}<|im_end|>\n{% endfor %}"
                    + "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}";

    private final Object loadLock = new Object();
    private volatile LoadedModel loaded; // null until first successful load
    private volatile boolean loading;
    private volatile String loadingModelId;
    private volatile long loadStartedAtMs = -1;
    private volatile String loadingPhase;
    private volatile String dspPlanPhase;
    private volatile int dspFrozenCount = -1;
    private volatile String dspPlanReport;
    private volatile Map<String, Object> dspCompilationStats;
    private final Metrics metrics;
    private final Profiler profiler;

    @Autowired
    public SameDiffLanguageModelImpl(
            Optional<Metrics> metricsOpt,
            Optional<Profiler> profilerOpt) {
        this.metrics = metricsOpt.orElse(null);
        this.profiler = profilerOpt.orElse(NoOpProfiler.INSTANCE);
        logger.info("SameDiffLanguageModelImpl initialized (direct mode). " +
                "Use POST /api/llm/load to load a model.");
    }

    // ==================== ChatModel impl ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        LoadedModel current = this.loaded;
        if (current == null) {
            throw new IllegalStateException(
                    "No SameDiff language model loaded. POST /api/llm/load first.");
        }
        String composedPrompt = extractPromptText(prompt);
        return execDirect(current, composedPrompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    // ==================== LanguageModel impl ====================

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        return textualResponse(generateResponseWithPotentialToolCalls(userQuery, context));
    }

    /**
     * Generate with a request-scoped output-token budget without reloading or replacing the model.
     */
    public String generateResponse(String userQuery, List<String> context, int maxNewTokens) {
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
        LoadedModel current = this.loaded;
        if (current == null) {
            throw new IllegalStateException(
                    "No SameDiff language model loaded. POST /api/llm/load first.");
        }
        String prompt = composePrompt(userQuery, context);
        return textualResponse(execDirect(current, prompt, maxNewTokens));
    }

    private static String textualResponse(ChatResponse response) {
        if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null) {
            String text = response.getResult().getOutput().getText();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        throw new IllegalStateException("SameDiff language model did not produce a textual response");
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        LoadedModel current = this.loaded;
        if (current == null) {
            throw new IllegalStateException(
                    "No SameDiff language model loaded. POST /api/llm/load first.");
        }
        String prompt = composePrompt(userQuery, context);
        return execDirect(current, prompt);
    }

    // ==================== Direct inference ====================

    private ChatResponse execDirect(LoadedModel current, String prompt) {
        return execDirect(current, prompt, null);
    }

    private ChatResponse execDirect(LoadedModel current, String prompt, Integer maxNewTokens) {
        try {
            String text = maxNewTokens != null
                    ? current.backend.generate(prompt, maxNewTokens)
                    : current.backend.generate(prompt);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("SameDiff LLM did not produce textual output");
            }
            AssistantMessage assistant = new AssistantMessage(text);
            return new ChatResponse(List.of(
                    new Generation(assistant, ChatGenerationMetadata.NULL)));
        } catch (RuntimeException e) {
            logger.error("Generation failed for modelId='{}'", current.modelId, e);
            throw e;
        } catch (Exception e) {
            logger.error("Generation failed for modelId='{}'", current.modelId, e);
            throw new IllegalStateException("SameDiff LLM generation failed for modelId='" + current.modelId + "'", e);
        }
    }

    // ==================== Model lifecycle ====================

    /**
     * Load (or reload) a SameDiff LLM, replacing any previously loaded model.
     */
    public void loadModel(String modelId, Path modelFile, Path tokenizerFile,
                          Map<String, Object> configOpts) throws Exception {
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
        int maxPrefillLength = intOpt(opts, "maxPrefillLength", 0);
        String chatTemplate = stringOpt(opts, "chatTemplate", null);
        String inputIdsName = stringOpt(opts, "inputIdsPlaceholderName", "input_ids");
        String attentionMaskName = stringOpt(opts, "attentionMaskPlaceholderName", "attention_mask");
        String logitsName = stringOpt(opts, "logitsOutputName", "logits");

        long start = System.currentTimeMillis();
        this.loadingModelId = modelId;
        this.loadStartedAtMs = start;
        this.loading = true;
        this.dspPlanPhase = null;
        this.dspFrozenCount = -1;
        this.dspPlanReport = null;
        this.dspCompilationStats = null;

        ScheduledExecutorService dspPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsp-phase-poller");
            t.setDaemon(true);
            return t;
        });
        dspPoller.scheduleAtFixedRate(this::pollDspPhase, 3, 5, TimeUnit.SECONDS);

        InferenceBackend backend = null;
        try {
            this.loadingPhase = "Loading tokenizer and lifecycle-managed SameDiff generation pipeline";
            backend = createInferenceBackend(
                    modelId, modelFile, tokenizerFile, opts, tokenizerType,
                    maxNewTokens, temperature, topK, maxPrefillLength, chatTemplate,
                    inputIdsName, attentionMaskName, logitsName);
            this.loadingPhase = "Model and generation pipeline loaded";
        } catch (Exception e) {
            this.loading = false;
            this.loadingModelId = null;
            this.loadStartedAtMs = -1;
            this.loadingPhase = null;
            this.dspPlanPhase = null;
            this.dspFrozenCount = -1;
            this.dspPlanReport = null;
            this.dspCompilationStats = null;
            closeBackendQuietly(backend, "partially loaded model '" + modelId + "'");
            throw e;
        } finally {
            dspPoller.shutdownNow();
        }

        long durationMs = System.currentTimeMillis() - start;
        synchronized (loadLock) {
            LoadedModel previous = this.loaded;
            this.loaded = new LoadedModel(modelId, backend, durationMs);
            this.loading = false;
            this.loadingModelId = null;
            this.loadStartedAtMs = -1;
            this.loadingPhase = null;
            this.dspPlanPhase = null;
            this.dspFrozenCount = -1;
            this.dspPlanReport = null;
            this.dspCompilationStats = null;
            if (previous != null) {
                logger.info("Replaced previously loaded model '{}' with '{}'",
                        previous.modelId, modelId);
                closeBackendQuietly(previous.backend, "previous model '" + previous.modelId + "'");
            } else {
                logger.info("Loaded model '{}' in {} ms", modelId, durationMs);
            }
        }
    }

    public void unloadModel() {
        synchronized (loadLock) {
            LoadedModel current = this.loaded;
            this.loaded = null;
            if (current != null) {
                closeBackendQuietly(current.backend, "model '" + current.modelId + "'");
                logger.info("Unloaded model '{}'", current.modelId);
            }
        }
    }

    // ==================== Status getters ====================

    public boolean isLoaded() { return this.loaded != null; }

    public String getLoadedModelId() {
        LoadedModel current = this.loaded;
        return current != null ? current.modelId : null;
    }

    public long getLoadDurationMs() {
        LoadedModel current = this.loaded;
        return current != null ? current.loadDurationMs : -1L;
    }

    public boolean isLoading() { return this.loading; }
    public String getLoadingModelId() { return this.loadingModelId; }

    public long getLoadElapsedMs() {
        long started = this.loadStartedAtMs;
        return started > 0 ? System.currentTimeMillis() - started : -1L;
    }

    public String getLoadingPhase() { return this.loadingPhase; }
    public String getDspPlanPhase() { return this.dspPlanPhase; }
    public int getDspFrozenCount() { return this.dspFrozenCount; }
    public String getDspPlanReport() { return this.dspPlanReport; }
    public Map<String, Object> getDspCompilationStats() { return this.dspCompilationStats; }

    // ==================== DSP diagnostics polling ====================

    private void pollDspPhase() {
        try {
            String report = org.nd4j.autodiff.samediff.diagnostics.DspDiagnostics.getPlanReport();
            if (report != null && !report.isBlank()) {
                this.dspPlanReport = report.length() > 4096
                        ? report.substring(0, 4096) + "..." : report;

                if (report.contains("REPLAYING") || report.contains("replay")) {
                    this.dspPlanPhase = "REPLAYING";
                } else if (report.contains("SHAPES_FROZEN") || report.contains("frozen")) {
                    this.dspPlanPhase = "SHAPES_FROZEN";
                } else if (report.contains("SLOT_BY_SLOT") || report.contains("slot-by-slot")) {
                    this.dspPlanPhase = "SLOT_BY_SLOT";
                }
            }

            String json = org.nd4j.autodiff.samediff.diagnostics.DspDiagnostics.getJsonReport();
            if (json != null && !json.isBlank()) {
                try {
                    JsonNode node = MAPPER.readTree(json);
                    if (node.has("frozenExecutionCount")) {
                        this.dspFrozenCount = node.get("frozenExecutionCount").asInt(-1);
                    }

                    Map<String, Object> stats = new java.util.LinkedHashMap<>();

                    JsonNode planInfo = node.get("planInfo");
                    if (planInfo != null) {
                        stats.put("numSlots", planInfo.path("numSlots").asInt(0));
                        stats.put("numSegments", planInfo.path("numSegments").asInt(0));
                        stats.put("stepsExecuted", planInfo.path("stepsExecuted").asInt(0));
                        stats.put("totalTimeMs", planInfo.path("totalTimeMs").asDouble(0));
                    }

                    JsonNode catStats = node.get("categoryStats");
                    if (catStats != null) {
                        JsonNode compile = catStats.get("COMPILE");
                        if (compile != null) {
                            stats.put("compileEvents", compile.path("events").asInt(0));
                            if (compile.has("totalTimeUs")) {
                                stats.put("compileTotalMs", compile.path("totalTimeUs").asLong(0) / 1000.0);
                                stats.put("compileMaxMs", compile.path("maxTimeUs").asLong(0) / 1000.0);
                            }
                        }
                        JsonNode jit = catStats.get("JIT");
                        if (jit != null) {
                            stats.put("jitEvents", jit.path("events").asInt(0));
                            if (jit.has("totalTimeUs")) {
                                stats.put("jitTotalMs", jit.path("totalTimeUs").asLong(0) / 1000.0);
                            }
                        }
                        JsonNode segment = catStats.get("SEGMENT");
                        if (segment != null) {
                            stats.put("segmentEvents", segment.path("events").asInt(0));
                        }
                        JsonNode timing = catStats.get("TIMING");
                        if (timing != null) {
                            stats.put("timingEvents", timing.path("events").asInt(0));
                        }
                    }

                    JsonNode events = node.get("events");
                    int cacheHits = 0, cacheStored = 0, cacheMisses = 0, cacheStale = 0;
                    String currentKernel = null;
                    List<String> recentCompilations = new ArrayList<>();
                    if (events != null && events.isArray()) {
                        for (JsonNode ev : events) {
                            String msg = ev.path("message").asText("");
                            if (msg.contains("cache HIT")) {
                                cacheHits++;
                            } else if (msg.contains("cache STORED")) {
                                cacheStored++;
                                currentKernel = extractSubSegmentRange(msg);
                                if (currentKernel != null) recentCompilations.add(currentKernel);
                            } else if (msg.contains("cache MISS") || msg.contains("cache miss")) {
                                cacheMisses++;
                            } else if (msg.contains("stale")) {
                                cacheStale++;
                            }
                        }
                    }

                    Map<String, Object> tritonInfo = new java.util.LinkedHashMap<>();
                    tritonInfo.put("cacheHits", cacheHits);
                    tritonInfo.put("newCompilations", cacheStored);
                    tritonInfo.put("cacheMisses", cacheMisses);
                    tritonInfo.put("cacheStale", cacheStale);
                    boolean isTritonCompiling = cacheStored > 0;
                    tritonInfo.put("isCompiling", isTritonCompiling);
                    if (currentKernel != null) tritonInfo.put("currentKernel", currentKernel);
                    if (!recentCompilations.isEmpty()) {
                        int fromIdx = Math.max(0, recentCompilations.size() - 10);
                        tritonInfo.put("recentCompilations",
                                recentCompilations.subList(fromIdx, recentCompilations.size()));
                    }
                    String cacheDir = System.getProperty("nd4j.triton.cacheDir");
                    if (cacheDir == null) cacheDir = System.getenv("ND4J_TRITON_CACHE_DIR");
                    if (cacheDir == null) cacheDir = System.getProperty("user.home")
                            + "/.kompile/cache/triton/triton_cache";
                    tritonInfo.put("cacheDir", cacheDir);
                    stats.put("tritonCompilation", tritonInfo);

                    if ("SHAPES_FROZEN".equals(this.dspPlanPhase)) {
                        if (isTritonCompiling) {
                            this.loadingPhase = String.format(
                                    "Triton kernel compilation: %d compiled, %d from cache%s",
                                    cacheStored, cacheHits,
                                    currentKernel != null ? " (current: " + currentKernel + ")" : "");
                        } else if (cacheHits > 0 && cacheStored == 0) {
                            this.loadingPhase = String.format(
                                    "DSP warmup: loading %d cached Triton kernels", cacheHits);
                        } else {
                            this.loadingPhase = "DSP: shapes frozen, compiling kernels";
                        }
                    } else if ("REPLAYING".equals(this.dspPlanPhase)) {
                        this.loadingPhase = "DSP: replaying compiled plan";
                    } else if ("SLOT_BY_SLOT".equals(this.dspPlanPhase)) {
                        this.loadingPhase = "DSP: slot-by-slot warmup";
                    }

                    this.dspCompilationStats = stats;
                } catch (Exception e) {
                    logger.trace("Failed to parse DSP compilation stats from status: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.trace("DSP phase poll skipped: {}", e.getMessage());
        }
    }

    private static String extractSubSegmentRange(String message) {
        int bracketStart = message.indexOf('[');
        int bracketEnd = message.indexOf(']', bracketStart);
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return "segment " + message.substring(bracketStart + 1, bracketEnd);
        }
        int hashIdx = message.indexOf("hash ");
        if (hashIdx >= 0) {
            String rest = message.substring(hashIdx + 5).trim();
            int spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) return "hash " + rest.substring(0, Math.min(spaceIdx, 12));
            return "hash " + rest.substring(0, Math.min(rest.length(), 12));
        }
        return null;
    }

    // ==================== Helpers ====================

    private static String extractPromptText(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null
                || prompt.getInstructions().isEmpty()) {
            throw new IllegalArgumentException("SameDiff language model prompt must contain at least one instruction");
        }
        StringBuilder sb = new StringBuilder();
        String userText = null;
        List<String> systemParts = new ArrayList<>();

        for (Message message : prompt.getInstructions()) {
            if (message instanceof SystemMessage) systemParts.add(message.getText());
            else if (message instanceof UserMessage) userText = message.getText();
        }
        if (!systemParts.isEmpty()) {
            sb.append("System:\n");
            for (String sys : systemParts) sb.append(sys).append("\n");
            sb.append("\n");
        }
        if (userText != null) sb.append(userText);
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("SameDiff language model prompt did not contain textual content");
        }
        return text;
    }

    private static String composePrompt(String userQuery, List<String> context) {
        if (userQuery == null || userQuery.isBlank()) {
            throw new IllegalArgumentException("SameDiff language model user query cannot be empty");
        }
        if (context == null || context.isEmpty()) return userQuery;
        StringBuilder sb = new StringBuilder("Context:\n");
        List<String> filtered = new ArrayList<>();
        for (String c : context) { if (c != null && !c.isBlank()) filtered.add(c); }
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
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) {
                logger.debug("Option '{}' has non-integer value '{}', using default {}: {}", key, v, defaultValue, e.getMessage());
            }
        }
        return defaultValue;
    }

    private static double doubleOpt(Map<String, Object> opts, String key, double defaultValue) {
        Object v = opts.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) {
                logger.debug("Option '{}' has non-double value '{}', using default {}: {}", key, v, defaultValue, e.getMessage());
            }
        }
        return defaultValue;
    }

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

    private InferenceBackend createInferenceBackend(
            String modelId,
            Path modelFile,
            Path tokenizerFile,
            Map<String, Object> opts,
            String tokenizerType,
            int maxNewTokens,
            double temperature,
            int topK,
            int maxPrefillLength,
            String chatTemplate,
            String inputIdsName,
            String attentionMaskName,
            String logitsName) throws Exception {
        if (usesGenerationPipeline(tokenizerType)) {
            Tokenizer tokenizer = Files.isDirectory(tokenizerFile)
                    ? HuggingFaceTokenizer.fromDirectory(tokenizerFile.toFile())
                    : HuggingFaceTokenizer.fromFile(tokenizerFile.toFile());
            try {
                String effectiveChatTemplate = resolveChatTemplate(tokenizer, chatTemplate);
                boolean doSample = booleanOpt(
                        opts, "doSample", temperature > 0.0d && topK != 1);
                double topP = doubleOpt(opts, "topP", 1.0d);
                SamplingConfig sampling = doSample
                        ? SamplingConfig.sample(temperature, topK, topP)
                        : SamplingConfig.greedy();
                SamplingConfig.SamplingConfigBuilder samplingBuilder = sampling.toBuilder()
                        .maxNewTokens(maxNewTokens);
                int eosTokenId = resolveEosTokenId(tokenizer, effectiveChatTemplate, opts);
                String eosTokenText = resolveEosTokenText(tokenizer, eosTokenId);
                if (eosTokenId >= 0) {
                    samplingBuilder.eosTokenId(eosTokenId);
                }
                int padTokenId = resolvePadTokenId(tokenizer, opts);
                if (padTokenId >= 0) {
                    samplingBuilder.padTokenId(padTokenId);
                }

                GenerationPipelineConfig pipelineConfig = GenerationPipelineConfig.builder()
                        .decoderPath(modelFile.toString())
                        .tokenizer(tokenizer)
                        .samplingConfig(samplingBuilder.build())
                        .maxNewTokens(maxNewTokens)
                        .maxPrefillLength(maxPrefillLength)
                        .maxKvCacheLength(intOpt(opts, "maxKvCacheLength", 0))
                        .kvCacheStrategy(kvCacheStrategyOpt(opts))
                        .graphOptimizerEnabled(booleanOpt(opts, "graphOptimizerEnabled", true))
                        .dspEnabled(booleanOpt(opts, "dspEnabled", true))
                        .chatTemplate(effectiveChatTemplate)
                        .build();
                GenerationPipeline pipeline = GenerationPipeline.create(pipelineConfig);
                logger.info(
                        "Loaded model '{}' with GenerationPipeline "
                                + "(KV={}, DSP={}, maxNewTokens={}, chatTemplate={}, eosTokenId={})",
                        modelId, pipelineConfig.getKvCacheStrategy(),
                        pipelineConfig.isDspEnabled(), maxNewTokens,
                        effectiveChatTemplate == null ? "none" : "configured",
                        eosTokenId);
                return new GenerationPipelineBackend(
                        pipeline, tokenizer, maxNewTokens, eosTokenText);
            } catch (Exception e) {
                try {
                    tokenizer.close();
                } catch (Exception closeException) {
                    e.addSuppressed(closeException);
                }
                throw e;
            }
        }

        logger.warn(
                "Tokenizer type '{}' is not supported by GenerationPipeline; "
                        + "using the legacy SameDiff runner for model '{}'",
                tokenizerType, modelId);
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
                .generationParameterEntry("maxPrefillLength", maxPrefillLength)
                .generationParameterEntry("inputIdsPlaceholderName", inputIdsName)
                .generationParameterEntry("attentionMaskPlaceholderName", attentionMaskName)
                .generationParameterEntry("logitsOutputName", logitsName);
        if (chatTemplate != null) {
            builder.generationParameterEntry("chatTemplate", chatTemplate);
        }
        for (String tokenKey : new String[]{
                "padTokenId", "eosTokenId", "bosTokenId", "unkTokenId",
                "clsTokenId", "sepTokenId", "maskTokenId"}) {
            if (opts.containsKey(tokenKey) && opts.get(tokenKey) != null) {
                builder.tokenizerConfigEntry(tokenKey, String.valueOf(opts.get(tokenKey)));
            }
        }

        LLMStepConfig config = builder.build();
        SameDiffLanguageModelStepRunner runner = new SameDiffLanguageModelStepRunner();
        Context initContext = new DefaultContext(
                Data.empty(), "init-" + modelId, "ctx-llm-init-" + modelId,
                null, this.metrics, this.profiler);
        try {
            runner.init(config, initContext);
            return new LegacyRunnerBackend(runner, config, this.metrics, this.profiler);
        } catch (Exception e) {
            try {
                runner.close();
            } catch (Exception closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    static boolean usesGenerationPipeline(String tokenizerType) {
        String normalized = tokenizerType == null
                ? "huggingface"
                : tokenizerType.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("huggingface")
                || normalized.equals("hf")
                || normalized.equals("bpe");
    }

    static String resolveChatTemplate(Tokenizer tokenizer, String configuredTemplate) {
        if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            return configuredTemplate;
        }

        try {
            String tokenizerTemplate = tokenizer.getChatTemplate();
            if (tokenizerTemplate != null && !tokenizerTemplate.isBlank()) {
                return tokenizerTemplate;
            }
        } catch (RuntimeException e) {
            logger.debug("Tokenizer chat-template metadata was unavailable: {}", e.getMessage());
        }

        Integer chatStart = tokenId(tokenizer, "<|im_start|>");
        Integer chatEnd = tokenId(tokenizer, "<|im_end|>");
        if (chatStart != null && chatStart >= 0 && chatEnd != null && chatEnd >= 0) {
            logger.info(
                    "Tokenizer metadata omits a chat template but exposes ChatML markers; "
                            + "using the built-in ChatML template");
            return CHATML_TEMPLATE;
        }
        return null;
    }

    static int resolveEosTokenId(
            Tokenizer tokenizer,
            String effectiveChatTemplate,
            Map<String, Object> opts) {
        if (opts.containsKey("eosTokenId")) {
            return intOpt(opts, "eosTokenId", -1);
        }

        if (CHATML_TEMPLATE.equals(effectiveChatTemplate)) {
            Integer chatEnd = tokenId(tokenizer, "<|im_end|>");
            if (chatEnd != null && chatEnd >= 0) {
                return chatEnd;
            }
        }

        try {
            return tokenizer.getEosTokenId();
        } catch (RuntimeException e) {
            logger.debug("Tokenizer EOS metadata was unavailable: {}", e.getMessage());
            return -1;
        }
    }

    static int resolvePadTokenId(Tokenizer tokenizer, Map<String, Object> opts) {
        if (opts.containsKey("padTokenId")) {
            return intOpt(opts, "padTokenId", -1);
        }
        try {
            return tokenizer.getPadTokenId();
        } catch (RuntimeException e) {
            logger.debug("Tokenizer PAD metadata was unavailable: {}", e.getMessage());
            return -1;
        }
    }

    static String resolveEosTokenText(Tokenizer tokenizer, int eosTokenId) {
        if (eosTokenId < 0) {
            return null;
        }
        try {
            String tokenText = tokenizer.decode(new int[]{eosTokenId}, false);
            return tokenText == null || tokenText.isEmpty() ? null : tokenText;
        } catch (RuntimeException e) {
            logger.debug("Tokenizer EOS text was unavailable: {}", e.getMessage());
            return null;
        }
    }

    static String stripTrailingEosToken(String text, String eosTokenText) {
        if (text == null || text.isEmpty()
                || eosTokenText == null || eosTokenText.isEmpty()) {
            return text;
        }

        int markerEnd = text.length();
        while (markerEnd > 0 && Character.isWhitespace(text.charAt(markerEnd - 1))) {
            markerEnd--;
        }
        int markerStart = markerEnd - eosTokenText.length();
        if (markerStart >= 0
                && text.regionMatches(markerStart, eosTokenText, 0, eosTokenText.length())) {
            return text.substring(0, markerStart) + text.substring(markerEnd);
        }
        return text;
    }

    private static Integer tokenId(Tokenizer tokenizer, String token) {
        try {
            return tokenizer.getTokenId(token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static KvCacheStrategy kvCacheStrategyOpt(Map<String, Object> opts) {
        String configured = stringOpt(opts, "kvCacheType", "STATIC");
        try {
            return KvCacheStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported kvCacheType '" + configured + "'. Expected one of "
                            + java.util.Arrays.toString(KvCacheStrategy.values()),
                    e);
        }
    }

    private static boolean booleanOpt(Map<String, Object> opts, String key, boolean defaultValue) {
        Object value = opts.get(key);
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue)) return true;
            if ("false".equalsIgnoreCase(stringValue)) return false;
        }
        return defaultValue;
    }

    private static void closeBackendQuietly(InferenceBackend backend, String description) {
        if (backend == null) return;
        try {
            backend.close();
        } catch (Exception e) {
            logger.warn("Failed to close {}: {}", description, e.getMessage());
        }
    }

    interface InferenceBackend {
        String generate(String prompt) throws Exception;

        default String generate(String prompt, int maxNewTokens) throws Exception {
            return generate(prompt);
        }

        void close() throws Exception;
    }

    private static final class GenerationPipelineBackend implements InferenceBackend {
        private final GenerationPipeline pipeline;
        private final Tokenizer tokenizer;
        private final int maxNewTokens;
        private final String eosTokenText;
        private boolean closed;

        private GenerationPipelineBackend(
                GenerationPipeline pipeline,
                Tokenizer tokenizer,
                int maxNewTokens,
                String eosTokenText) {
            this.pipeline = pipeline;
            this.tokenizer = tokenizer;
            this.maxNewTokens = maxNewTokens;
            this.eosTokenText = eosTokenText;
        }

        @Override
        public synchronized String generate(String prompt) {
            return generate(prompt, maxNewTokens);
        }

        @Override
        public synchronized String generate(String prompt, int requestedMaxNewTokens) {
            requireOpen();
            GenerationResult result = pipeline.generate(prompt, requestedMaxNewTokens);
            if (result == null) {
                throw new IllegalStateException("GenerationPipeline returned no result");
            }
            if (result.getFinishReason() == GenerationResult.FinishReason.ERROR) {
                throw new IllegalStateException("GenerationPipeline reported an error");
            }
            return stripTrailingEosToken(result.getText(), eosTokenText);
        }

        @Override
        public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            Exception failure = null;
            try {
                pipeline.close();
            } catch (Exception e) {
                failure = e;
            }
            try {
                tokenizer.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("GenerationPipeline backend is closed");
            }
        }
    }

    private static final class LegacyRunnerBackend implements InferenceBackend {
        private final SameDiffLanguageModelStepRunner runner;
        private final LLMStepConfig config;
        private final Metrics metrics;
        private final Profiler profiler;
        private boolean closed;

        private LegacyRunnerBackend(
                SameDiffLanguageModelStepRunner runner,
                LLMStepConfig config,
                Metrics metrics,
                Profiler profiler) {
            this.runner = runner;
            this.config = config;
            this.metrics = metrics;
            this.profiler = profiler;
        }

        @Override
        public synchronized String generate(String prompt) throws Exception {
            if (closed) {
                throw new IllegalStateException("Legacy SameDiff runner is closed");
            }
            String executionId = UUID.randomUUID().toString();
            Data input = Data.empty();
            input.put(config.getPromptInputName(), prompt);
            Context context = new DefaultContext(
                    Data.empty(), executionId, "ctx-llm-" + executionId,
                    null, metrics, profiler);
            Data output = runner.exec(input, context);
            return output.getString(config.getResponseOutputName(), "");
        }

        @Override
        public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            runner.close();
        }
    }

    private static final class LoadedModel {
        final String modelId;
        final InferenceBackend backend;
        final long loadDurationMs;

        LoadedModel(String modelId, InferenceBackend backend, long loadDurationMs) {
            this.modelId = modelId;
            this.backend = backend;
            this.loadDurationMs = loadDurationMs;
        }
    }
}
