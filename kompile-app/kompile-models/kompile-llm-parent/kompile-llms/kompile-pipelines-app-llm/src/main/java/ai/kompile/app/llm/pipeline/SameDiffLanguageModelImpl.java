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
import ai.kompile.core.llm.StructuredChatLanguageModel;
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
import org.eclipse.deeplearning4j.llm.generation.ChatGenerationResult;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.ModelSamplingDefaults;
import org.eclipse.deeplearning4j.llm.generation.sampling.ModelSamplingDefaults.GenerationMode;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.data.LLMModelDownloader.ModelFamily;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
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

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.nd4j.ggml.GGMLModelImport;
import org.nd4j.ggml.convert.ConversionOptions;
import org.nd4j.ggml.format.GGUFReader;
import org.nd4j.linalg.api.device.DeviceMemoryManager;
import org.nd4j.linalg.factory.Nd4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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
public class SameDiffLanguageModelImpl implements LanguageModel, StructuredChatLanguageModel, ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(SameDiffLanguageModelImpl.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int CONTINUATION_CHUNK_TOKENS_DEFAULT = 384;

    private final Object loadLock = new Object();
    private final ExecutorService modelExecutionLane;
    private final ModelDeviceContext modelDeviceContext;
    private volatile Thread modelExecutionThread;
    private volatile Integer modelExecutionDevice;
    private volatile long modelDeviceRestorations;
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
        this(metricsOpt, profilerOpt, new Nd4jModelDeviceContext());
    }

    SameDiffLanguageModelImpl(
            Optional<Metrics> metricsOpt,
            Optional<Profiler> profilerOpt,
            ModelDeviceContext modelDeviceContext) {
        this.metrics = metricsOpt.orElse(null);
        this.profiler = profilerOpt.orElse(NoOpProfiler.INSTANCE);
        this.modelDeviceContext = Objects.requireNonNull(modelDeviceContext, "modelDeviceContext");
        this.modelExecutionLane = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "samediff-model-execution");
            thread.setDaemon(true);
            this.modelExecutionThread = thread;
            return thread;
        });
        logger.info("SameDiffLanguageModelImpl initialized (direct mode). " +
                "Use POST /api/llm/load to load a model.");
    }

    // ==================== ChatModel impl ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        String composedPrompt = extractPromptText(prompt);
        return executeModelOperation(() ->
                execDirect(requireLoadedModel(), composedPrompt));
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
        String prompt = composePrompt(userQuery, context);
        return textualResponse(executeModelOperation(() ->
                execDirect(requireLoadedModel(), prompt, maxNewTokens)));
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
        String prompt = composePrompt(userQuery, context);
        return executeModelOperation(() ->
                execDirect(requireLoadedModel(), prompt));
    }

    /**
     * Generate through the model-owned chat template with role-preserving
     * history and native structured tool-call parsing.
     */
    public ChatGenerationResult generateChat(ChatTemplate.Request request) {
        return executeModelOperation(() ->
                generateChat(requireLoadedModel(), request, null));
    }

    public ChatGenerationResult generateChat(ChatTemplate.Request request, int maxNewTokens) {
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
        return executeModelOperation(() ->
                generateChat(requireLoadedModel(), request, maxNewTokens));
    }

    /**
     * Portable crawl/serving adapter for the SameDiff model-owned chat template. No prompt
     * serialization or raw-text tool parsing occurs outside samediff-llm.
     */
    @Override
    public StructuredChatLanguageModel.Response generateChat(
            StructuredChatLanguageModel.Request request, int maxNewTokens) {
        Objects.requireNonNull(request, "request");
        List<ChatTemplate.Message> messages = new ArrayList<>();
        for (StructuredChatLanguageModel.Message message : request.messages()) {
            messages.add(new ChatTemplate.Message(message.role(), message.content()));
        }
        List<ChatTemplate.Tool> tools = new ArrayList<>();
        for (StructuredChatLanguageModel.Tool tool : request.tools()) {
            tools.add(ChatTemplate.Tool.function(
                    tool.name(), tool.description(), tool.parameters()));
        }
        ChatTemplate.Request nativeRequest = ChatTemplate.Request.builder()
                .messages(messages)
                .tools(tools)
                .addGenerationPrompt(request.addGenerationPrompt())
                .toolDefinitionFormat(request.toolDefinitionFormat()
                        == StructuredChatLanguageModel.ToolDefinitionFormat.FLAT
                        ? ChatTemplate.ToolDefinitionFormat.FLAT
                        : ChatTemplate.ToolDefinitionFormat.STANDARD)
                .toolCallFormat(switch (request.toolCallFormat()) {
                    case MODEL -> null;
                    case NATIVE -> ChatTemplate.ToolCallFormat.NATIVE;
                    case JSON -> ChatTemplate.ToolCallFormat.JSON;
                })
                .toolChoice(request.toolChoice()
                        == StructuredChatLanguageModel.ToolChoice.REQUIRED
                        ? ChatTemplate.ToolChoice.REQUIRED
                        : request.toolChoice() == StructuredChatLanguageModel.ToolChoice.NONE
                        ? ChatTemplate.ToolChoice.NONE
                        : ChatTemplate.ToolChoice.AUTO)
                .templateArguments(request.templateArguments())
                .build();
        ChatGenerationResult result = generateChat(nativeRequest, maxNewTokens);
        List<StructuredChatLanguageModel.ToolCall> calls = new ArrayList<>();
        for (ChatTemplate.ToolCall call : result.getToolCalls()) {
            calls.add(new StructuredChatLanguageModel.ToolCall(
                    call.getId(), call.getName(), call.getArguments()));
        }
        List<StructuredChatLanguageModel.OutputBlock> outputBlocks = new ArrayList<>();
        for (ChatTemplate.OutputBlock block : result.getOutputBlocks()) {
            outputBlocks.add(new StructuredChatLanguageModel.OutputBlock(
                    block.getType(), block.getContent()));
        }
        return new StructuredChatLanguageModel.Response(
                result.getRawText(),
                result.getContent(),
                result.getReasoningContent(),
                outputBlocks,
                calls,
                result.getParseErrors());
    }

    private ChatGenerationResult generateChat(LoadedModel current,
                                              ChatTemplate.Request request,
                                              Integer maxNewTokens) {
        if (request == null) {
            throw new IllegalArgumentException("Chat request must not be null");
        }
        try {
            if (!(current.backend instanceof StructuredChatInferenceBackend)) {
                throw new UnsupportedOperationException(
                        "Loaded inference backend does not support structured chat generation");
            }
            StructuredChatInferenceBackend backend =
                    (StructuredChatInferenceBackend) current.backend;
            return maxNewTokens == null
                    ? backend.generateChat(request)
                    : backend.generateChat(request, maxNewTokens);
        } catch (RuntimeException e) {
            logger.error("Chat generation failed for modelId='{}'", current.modelId, e);
            throw e;
        } catch (Exception e) {
            logger.error("Chat generation failed for modelId='{}'", current.modelId, e);
            throw new IllegalStateException(
                    "SameDiff LLM chat generation failed for modelId='" + current.modelId + "'", e);
        }
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

    public int countPromptTokens(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException(
                    "Prompt cannot be null or blank"
            );
        }

        return executeModelOperation(() -> {
            LoadedModel current = requireLoadedModel();
            try {
                return current.backend.countPromptTokens(prompt);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "Failed to count prompt tokens for model '"
                                + current.modelId
                                + "'",
                        failure
                );
            }
        });
    }

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

        Map<String, Object> opts = configOpts != null
                ? new HashMap<>(configOpts)
                : new HashMap<>();
        executeOnModelLane(() -> {
            loadModelOnExecutionLane(modelId, modelFile, tokenizerFile, opts);
            return null;
        });
    }

    private void loadModelOnExecutionLane(String modelId, Path modelFile, Path tokenizerFile,
                                          Map<String, Object> opts) throws Exception {
        Integer existingExecutionDevice = this.modelExecutionDevice;
        int executionDevice = existingExecutionDevice != null
                ? existingExecutionDevice
                : modelDeviceContext.selectDeviceForModel();
        modelDeviceContext.switchTo(executionDevice, "model-load-start");

        String tokenizerType = stringOpt(opts, "tokenizerType", "huggingface");
        int maxNewTokens = intOpt(opts, "maxNewTokens", 256);
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
                    maxNewTokens, maxPrefillLength, chatTemplate,
                    inputIdsName, attentionMaskName, logitsName, executionDevice);
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
            try {
                modelDeviceContext.switchTo(executionDevice, "failed-model-load-cleanup");
            } catch (RuntimeException deviceFailure) {
                e.addSuppressed(deviceFailure);
            }
            closeBackendQuietly(backend, "partially loaded model '" + modelId + "'");
            throw e;
        } finally {
            dspPoller.shutdownNow();
        }

        modelDeviceContext.switchTo(executionDevice, "model-load-complete");
        long durationMs = System.currentTimeMillis() - start;
        synchronized (loadLock) {
            LoadedModel previous = this.loaded;
            this.modelExecutionDevice = executionDevice;
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
        executeModelOperation(() -> {
            synchronized (loadLock) {
                LoadedModel current = this.loaded;
                this.loaded = null;
                if (current != null) {
                    closeBackendQuietly(current.backend, "model '" + current.modelId + "'");
                    logger.info("Unloaded model '{}'", current.modelId);
                }
                this.modelExecutionDevice = null;
            }
            return null;
        });
    }

    @PreDestroy
    public void shutdown() {
        if (modelExecutionLane.isShutdown()) {
            return;
        }
        try {
            unloadModel();
        } finally {
            modelExecutionLane.shutdownNow();
        }
    }

    private LoadedModel requireLoadedModel() {
        LoadedModel current = this.loaded;
        if (current == null) {
            throw new IllegalStateException(
                    "No SameDiff language model loaded. POST /api/llm/load first.");
        }
        return current;
    }

    private <T> T executeModelOperation(Callable<T> operation) {
        try {
            return executeOnModelLane(operation);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("SameDiff model execution failed", failure);
        }
    }

    private <T> T executeOnModelLane(Callable<T> operation) throws Exception {
        Callable<T> deviceBoundOperation = () -> executeOnBoundModelDevice(operation);
        if (Thread.currentThread() == modelExecutionThread) {
            return deviceBoundOperation.call();
        }
        try {
            return modelExecutionLane.submit(deviceBoundOperation).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for SameDiff model execution", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("SameDiff model execution failed", cause);
        }
    }

    private <T> T executeOnBoundModelDevice(Callable<T> operation) throws Exception {
        restoreModelExecutionDevice("operation-start", true);
        Throwable operationFailure = null;
        try {
            return operation.call();
        } catch (Exception | Error failure) {
            operationFailure = failure;
            throw failure;
        } finally {
            try {
                restoreModelExecutionDevice("operation-complete", false);
            } catch (RuntimeException restoreFailure) {
                if (operationFailure != null) {
                    operationFailure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
        }
    }

    private void restoreModelExecutionDevice(String reason, boolean unexpectedAtBoundary) {
        Integer expectedDevice = this.modelExecutionDevice;
        if (expectedDevice == null) {
            return;
        }
        int currentDevice = modelDeviceContext.currentDevice();
        if (currentDevice == expectedDevice) {
            return;
        }

        modelDeviceContext.switchTo(expectedDevice, reason);
        this.modelDeviceRestorations++;
        if (unexpectedAtBoundary) {
            logger.warn(
                    "Restored pooled model execution device from {} to {} at {}",
                    currentDevice, expectedDevice, reason);
        } else {
            logger.debug(
                    "Restored pooled model execution device from {} to {} after internal execution",
                    currentDevice, expectedDevice);
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
    public Integer getModelExecutionDevice() { return this.modelExecutionDevice; }
    public long getModelDeviceRestorations() { return this.modelDeviceRestorations; }
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

        for (org.springframework.ai.chat.messages.Message message : prompt.getInstructions()) {
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

    private static Long nullableLongOpt(Map<String, Object> opts, String key) {
        Object value = opts.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Option '" + key + "' must be a long integer: " + value, e);
            }
        }
        throw new IllegalArgumentException(
                "Option '" + key + "' must be a long integer: " + value);
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

    /**
     * Retained-KV continuation is implicit only when the decoder being executed is GGUF.
     *
     * <p>A staged SDNB commonly sits beside the GGUF it was converted from. That sibling is useful
     * provenance (and may supply the chat template), but it does not change the decoder path or
     * prove that the converted graph supports {@link GenerationPipeline.GenerationSession}. This
     * intentionally matches model staging's execution contract, which checks the decoder path
     * itself before selecting continuation.</p>
     */
    static boolean isDirectGgufDecoder(Path modelFile) {
        if (modelFile == null || modelFile.getFileName() == null) {
            return false;
        }
        return modelFile.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT)
                .endsWith(".gguf");
    }

    private static GenerationPipeline.ModelLoader directGgufModelLoader(
            Path modelFile, int executionDevice) {
        if (!isDirectGgufDecoder(modelFile)) {
            return null;
        }
        Map<String, GenerationPipeline.ModelMetadata> metadataByPath =
                new java.util.concurrent.ConcurrentHashMap<>();
        return new GenerationPipeline.ModelLoader() {
            @Override
            public org.nd4j.autodiff.samediff.SameDiff load(String path) throws IOException {
                try {
                    GGMLModelImport.ImportedModel imported =
                            GGMLModelImport.importModelWithMetadata(
                                    Path.of(path).toFile(),
                                    ConversionOptions.forInference(),
                                    Nd4j.getAffinityManager().getDeviceDescriptor(executionDevice));
                    metadataByPath.put(path, generationMetadata(
                            imported.getMetadata().getTokenizerInfo()));
                    return imported.getModel();
                } catch (Exception failure) {
                    throw new IOException("Failed to import GGUF decoder: " + path, failure);
                }
            }

            @Override
            public GenerationPipeline.ModelMetadata getModelMetadata(String path) {
                return metadataByPath.getOrDefault(
                        path, GenerationPipeline.ModelMetadata.empty());
            }
        };
    }

    static GenerationPipeline.ModelMetadata generationMetadata(
            org.nd4j.ggml.format.GGMLMetadata.TokenizerInfo tokenizerInfo) {
        if (tokenizerInfo == null) {
            return GenerationPipeline.ModelMetadata.empty();
        }
        int eosTokenId = tokenizerInfo.getEosTokenId();
        Set<Integer> stopTokenIds = eosTokenId >= 0
                ? Set.of(eosTokenId) : Set.of();
        return GenerationPipeline.ModelMetadata.of(
                tokenizerInfo.getBosTokenId(),
                eosTokenId,
                tokenizerInfo.getPadTokenId(),
                tokenizerInfo.getChatTemplate(),
                stopTokenIds,
                Set.of());
    }

    static int validateContinuationChunkTokens(int chunkTokens) {
        if (chunkTokens <= 0) {
            throw new IllegalArgumentException(
                    "continuationChunkTokens must be positive: " + chunkTokens);
        }
        return chunkTokens;
    }

    private InferenceBackend createInferenceBackend(
            String modelId,
            Path modelFile,
            Path tokenizerFile,
            Map<String, Object> opts,
            String tokenizerType,
            int maxNewTokens,
            int maxPrefillLength,
            String chatTemplate,
            String inputIdsName,
            String attentionMaskName,
            String logitsName,
            int executionDevice) throws Exception {
        SamplingConfig resolvedSampling = configuredSampling(
                opts, maxNewTokens,
                modelSamplingDefaults(modelId, modelFile, GenerationMode.NON_THINKING_TEXT));
        SamplingConfig thinkingSampling = configuredSampling(
                opts, maxNewTokens,
                modelSamplingDefaults(modelId, modelFile, GenerationMode.THINKING_TEXT));
        if (usesGenerationPipeline(tokenizerType, opts)) {
            Tokenizer tokenizer = Files.isDirectory(tokenizerFile)
                    ? HuggingFaceTokenizer.fromDirectory(tokenizerFile.toFile())
                    : HuggingFaceTokenizer.fromFile(tokenizerFile.toFile());
            try {
                String effectiveChatTemplate = resolveChatTemplate(tokenizer, chatTemplate, modelFile);
                SamplingConfig.SamplingConfigBuilder samplingBuilder =
                        resolvedSampling.toBuilder();
                int eosTokenId = resolveEosTokenId(tokenizer, opts);
                if (eosTokenId >= 0) {
                    samplingBuilder.eosTokenId(eosTokenId);
                }
                int padTokenId = resolvePadTokenId(tokenizer, opts);
                if (padTokenId >= 0) {
                    samplingBuilder.padTokenId(padTokenId);
                }
                boolean continuationEnabled = booleanOpt(
                        opts, "continuationEnabled", isDirectGgufDecoder(modelFile));
                int continuationChunkTokens = validateContinuationChunkTokens(
                        intOpt(opts, "continuationChunkTokens",
                                CONTINUATION_CHUNK_TOKENS_DEFAULT));

                GenerationPipelineConfig pipelineConfig = GenerationPipelineConfig.builder()
                        .decoderPath(modelFile.toString())
                        .tokenizer(tokenizer)
                        .samplingConfig(samplingBuilder.build())
                        .additionalStopTokenIds(additionalStopTokenIdsOpt(opts))
                        .maxNewTokens(maxNewTokens)
                        .maxPrefillLength(maxPrefillLength)
                        .maxKvCacheLength(intOpt(opts, "maxKvCacheLength", 0))
                        .kvCacheStrategy(kvCacheStrategyOpt(opts))
                        .graphOptimizerEnabled(booleanOpt(opts, "graphOptimizerEnabled", true))
                        .dspEnabled(booleanOpt(opts, "dspEnabled", true))
                        .prefillLastPositionLogitsEnabled(prefillLastPositionLogitsEnabled(opts))
                        .modelLoader(directGgufModelLoader(modelFile, executionDevice))
                        .chatTemplate(effectiveChatTemplate)
                        .toolDefinitionFormat(toolDefinitionFormatOpt(opts))
                        .toolCallFormat(toolCallFormatOpt(opts))
                        .build();
                GenerationPipeline pipeline = GenerationPipeline.create(pipelineConfig);
                logger.info(
                        "Loaded model '{}' with GenerationPipeline "
                                + "(KV={}, DSP={}, maxNewTokens={}, chatTemplate={}, "
                                + "toolDefinitionFormat={}, toolCallFormat={}, "
                                + "doSample={}, temperature={}, topK={}, topP={}, "
                                + "repetitionPenalty={}, seed={}, eosTokenId={}, "
                                + "maxOutputBlockTokens={}, structuredOutputTokenReserve={}, "
                                + "continuation={}, continuationChunkTokens={})",
                        modelId, pipelineConfig.getKvCacheStrategy(),
                        pipelineConfig.isDspEnabled(), maxNewTokens,
                        effectiveChatTemplate == null ? "none" : "configured",
                        pipelineConfig.getToolDefinitionFormat(),
                        pipelineConfig.getToolCallFormat(),
                        pipelineConfig.getSamplingConfig().isDoSample(),
                        pipelineConfig.getSamplingConfig().getTemperature(),
                        pipelineConfig.getSamplingConfig().getTopK(),
                        pipelineConfig.getSamplingConfig().getTopP(),
                        pipelineConfig.getSamplingConfig().getRepetitionPenalty(),
                        pipelineConfig.getSamplingConfig().getSeed(),
                        eosTokenId,
                        pipelineConfig.getSamplingConfig().getMaxOutputBlockTokens(),
                        pipelineConfig.getSamplingConfig().getStructuredOutputTokenReserve(),
                        continuationEnabled, continuationChunkTokens);
                logger.info(
                        "Thinking-mode sampling for '{}': doSample={}, temperature={}, topK={}, topP={}, "
                                + "presencePenalty={}, repetitionPenalty={}",
                        modelId, thinkingSampling.isDoSample(), thinkingSampling.getTemperature(),
                        thinkingSampling.getTopK(), thinkingSampling.getTopP(),
                        thinkingSampling.getPresencePenalty(), thinkingSampling.getRepetitionPenalty());
                return new GenerationPipelineBackend(
                        pipeline, tokenizer, effectiveChatTemplate, maxNewTokens,
                        continuationEnabled, continuationChunkTokens, thinkingSampling);
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
                .generationParameterEntry("temperature", (float) resolvedSampling.getTemperature())
                .generationParameterEntry("topK", resolvedSampling.getTopK())
                .generationParameterEntry("topP", (float) resolvedSampling.getTopP())
                .generationParameterEntry("doSample", resolvedSampling.isDoSample())
                .generationParameterEntry(
                        "repetitionPenalty", (float) resolvedSampling.getRepetitionPenalty())
                .generationParameterEntry("maxPrefillLength", maxPrefillLength)
                .generationParameterEntry("dspEnabled", booleanOpt(opts, "dspEnabled", true))
                .generationParameterEntry("inputIdsPlaceholderName", inputIdsName)
                .generationParameterEntry("attentionMaskPlaceholderName", attentionMaskName)
                .generationParameterEntry("logitsOutputName", logitsName);
        if (resolvedSampling.getSeed() != null) {
            builder.generationParameterEntry("seed", resolvedSampling.getSeed());
        }
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
        return usesGenerationPipeline(tokenizerType, Map.of());
    }

    static boolean usesGenerationPipeline(String tokenizerType, Map<String, Object> opts) {
        if (booleanOpt(opts, "legacyGeneration", false)) {
            return false;
        }
        String normalized = tokenizerType == null
                ? "huggingface"
                : tokenizerType.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("huggingface")
                || normalized.equals("hf")
                || normalized.equals("bpe");
    }

    /**
     * Resolves the template that frames every prompt, most specific source first.
     *
     * <p>Order matters, because a model served with no template is fed its prompt verbatim — no
     * turn markers, no generation prompt — and answers as a base completion model: on an
     * instruction that ends in a directive the likeliest continuation is the end of the document,
     * so it emits end-of-sequence and says nothing. Serving a model with the <em>wrong</em>
     * template is subtler and worse: it answers, plausibly, in a frame its training never used.
     *
     * <ol>
     *   <li>the configured template — an explicit operator choice outranks anything inferred;</li>
     *   <li>the tokenizer's own, from {@code tokenizer_config.json} beside {@code tokenizer.json};
     *   </li>
     *   <li>the GGUF the graph was converted from, which declares {@code tokenizer.chat_template}
     *       in its metadata. A model staged before staging carried that file forward has this and
     *       nothing else, and it is the model's real template.</li>
     * </ol>
     * No marker-based template is invented when model metadata is absent.
     */
    static String resolveChatTemplate(Tokenizer tokenizer, String configuredTemplate, Path modelFile) {
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

        String ggufTemplate = chatTemplateFromGguf(modelFile);
        if (ggufTemplate != null) {
            logger.info(
                    "Tokenizer metadata omits a chat template; using the one declared by the "
                            + "source GGUF beside '{}' ({} chars)", modelFile, ggufTemplate.length());
            return ggufTemplate;
        }

        return null;
    }

    /**
     * The chat template declared by the GGUF the staged graph was converted from, or null if there
     * is no GGUF beside the model or it declares none.
     */
    static String chatTemplateFromGguf(Path modelFile) {
        if (modelFile == null) {
            return null;
        }
        Path gguf = ggufBeside(modelFile);
        if (gguf == null) {
            return null;
        }
        try (GGUFReader reader = new GGUFReader(gguf.toFile())) {
            String template = reader.getHeader().getChatTemplate();
            return template == null || template.isBlank() ? null : template;
        } catch (Exception e) {
            logger.debug("Could not read a chat template from '{}': {}", gguf, e.getMessage());
            return null;
        }
    }

    private static Path ggufBeside(Path modelFile) {
        if (modelFile.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
            return Files.isRegularFile(modelFile) ? modelFile : null;
        }
        Path parent = modelFile.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(parent)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".gguf"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.debug("Could not list '{}' for a source GGUF: {}", parent, e.getMessage());
            return null;
        }
    }

    static int resolveEosTokenId(
            Tokenizer tokenizer,
            Map<String, Object> opts) {
        if (opts.containsKey("eosTokenId")) {
            return intOpt(opts, "eosTokenId", -1);
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

    static SamplingConfig configuredSampling(
            Map<String, Object> opts,
            int maxNewTokens,
            SamplingConfig defaults) {
        double temperature = doubleOpt(opts, "temperature", defaults.getTemperature());
        int topK = intOpt(opts, "topK", defaults.getTopK());
        double topP = doubleOpt(opts, "topP", defaults.getTopP());
        boolean doSample;
        if (opts.containsKey("doSample")) {
            doSample = booleanOpt(opts, "doSample", defaults.isDoSample());
        } else if (opts.containsKey("temperature") || opts.containsKey("topK")) {
            doSample = temperature > 0.0d && topK != 1;
        } else {
            doSample = defaults.isDoSample();
        }
        SamplingConfig base = doSample
                ? SamplingConfig.sample(temperature, topK, topP)
                : SamplingConfig.greedy();
        SamplingConfig.SamplingConfigBuilder builder = base.toBuilder()
                .temperature(temperature)
                .topK(topK)
                .topP(topP)
                .minP(doubleOpt(opts, "minP", defaults.getMinP()))
                .doSample(doSample)
                .maxNewTokens(maxNewTokens)
                .maxOutputBlockTokens(intOpt(
                        opts, "maxOutputBlockTokens", defaults.getMaxOutputBlockTokens()))
                .structuredOutputTokenReserve(intOpt(
                        opts, "structuredOutputTokenReserve",
                        defaults.getStructuredOutputTokenReserve()))
                .repetitionPenalty(doubleOpt(
                        opts, "repetitionPenalty", defaults.getRepetitionPenalty()))
                .frequencyPenalty(doubleOpt(
                        opts, "frequencyPenalty", defaults.getFrequencyPenalty()))
                .presencePenalty(doubleOpt(
                        opts, "presencePenalty", defaults.getPresencePenalty()));
        Long seed = nullableLongOpt(opts, "seed");
        if (seed != null) {
            builder.seed(seed);
        }
        return builder.build();
    }

    static SamplingConfig modelSamplingDefaults(String modelId, Path modelFile) {
        return modelSamplingDefaults(modelId, modelFile, GenerationMode.NON_THINKING_TEXT);
    }

    static SamplingConfig modelSamplingDefaults(
            String modelId,
            Path modelFile,
            GenerationMode mode) {
        String architecture = null;
        String artifactName = null;
        Path gguf = modelFile == null ? null : ggufBeside(modelFile);
        if (gguf != null) {
            artifactName = gguf.getFileName().toString();
            try (GGUFReader reader = new GGUFReader(gguf.toFile())) {
                architecture = reader.getHeader().getArchitecture();
                String declaredName = reader.getHeader().getModelName();
                if (declaredName != null && !declaredName.isBlank()) {
                    artifactName = artifactName + " " + declaredName;
                }
            } catch (Exception e) {
                logger.debug("Could not read model-family metadata from '{}': {}", gguf, e.getMessage());
            }
        }
        return modelSamplingDefaults(modelId, architecture, artifactName, mode);
    }

    static SamplingConfig modelSamplingDefaults(
            String modelId,
            String architecture,
            String artifactName) {
        return modelSamplingDefaults(
                modelId, architecture, artifactName, GenerationMode.NON_THINKING_TEXT);
    }

    static SamplingConfig modelSamplingDefaults(
            String modelId,
            String architecture,
            String artifactName,
            GenerationMode mode) {
        String identity = String.join(" ",
                modelId == null ? "" : modelId,
                artifactName == null ? "" : artifactName);
        String normalizedArchitecture = architecture == null ? "" : architecture.trim();
        Optional<ModelFamily> family = Arrays.stream(ModelFamily.values())
                .filter(candidate -> candidate.getFamilyId().equalsIgnoreCase(normalizedArchitecture))
                .findFirst();
        if (family.isEmpty() && !identity.isBlank()) {
            String normalizedIdentity = identity.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            family = Arrays.stream(ModelFamily.values())
                    .filter(candidate -> normalizedIdentity.contains(
                            candidate.getFamilyId().toLowerCase(java.util.Locale.ROOT)
                                    .replaceAll("[^a-z0-9]", "")))
                    .findFirst();
        }
        return family
                .flatMap(candidate -> ModelSamplingDefaults.forModel(candidate, identity, mode))
                .orElseGet(() -> SamplingConfig.sample(0.7d, 0, 1.0d));
    }

    static Set<Integer> additionalStopTokenIdsOpt(Map<String, Object> opts) {
        Object configured = opts.get("additionalStopTokenIds");
        if (configured == null) {
            return Set.of();
        }
        if (!(configured instanceof Iterable<?> values)) {
            throw new IllegalArgumentException(
                    "additionalStopTokenIds must be an iterable of non-negative integers");
        }

        Set<Integer> tokenIds = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(
                        "additionalStopTokenIds contains a non-numeric value: " + value);
            }
            int tokenId = number.intValue();
            if (tokenId < 0 || number.doubleValue() != tokenId) {
                throw new IllegalArgumentException(
                        "additionalStopTokenIds contains an invalid token ID: " + value);
            }
            tokenIds.add(tokenId);
        }
        return Set.copyOf(tokenIds);
    }

    static ChatTemplate.ToolDefinitionFormat toolDefinitionFormatOpt(
            Map<String, Object> opts) {
        String configured = stringOpt(opts, "toolDefinitionFormat", "STANDARD");
        String normalized = configured.trim()
                .replace('-', '_')
                .toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "STANDARD", "OPENAI", "OPENAI_FUNCTION" ->
                    ChatTemplate.ToolDefinitionFormat.STANDARD;
            case "FLAT", "FLAT_FUNCTION" ->
                    ChatTemplate.ToolDefinitionFormat.FLAT;
            default -> throw new IllegalArgumentException(
                    "Unsupported toolDefinitionFormat '" + configured
                            + "'. Expected STANDARD or FLAT");
        };
    }

    static ChatTemplate.ToolCallFormat toolCallFormatOpt(Map<String, Object> opts) {
        String configured = stringOpt(opts, "toolCallFormat", "MODEL");
        String normalized = configured.trim()
                .replace('-', '_')
                .toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "MODEL", "AUTO" -> null;
            case "NATIVE", "MODEL_NATIVE" ->
                    ChatTemplate.ToolCallFormat.NATIVE;
            case "JSON", "JSON_MARKERS", "OPENAI_JSON" ->
                    ChatTemplate.ToolCallFormat.JSON;
            default -> throw new IllegalArgumentException(
                    "Unsupported toolCallFormat '" + configured
                            + "'. Expected MODEL, NATIVE, or JSON");
        };
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

    static boolean prefillLastPositionLogitsEnabled(Map<String, Object> opts) {
        return booleanOpt(opts, "prefillLastPositionLogitsEnabled", true);
    }

    static boolean thinkingEnabled(ChatTemplate.Request request) {
        return request != null
                && booleanOpt(request.getTemplateArguments(), "enable_thinking", false);
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

    interface ModelDeviceContext {
        int selectDeviceForModel();

        int currentDevice();

        void switchTo(int deviceId, String reason);
    }

    private static final class Nd4jModelDeviceContext implements ModelDeviceContext {
        @Override
        public int selectDeviceForModel() {
            // On CUDA, first access dynamically selects the available device with the most
            // free memory and performs the authoritative native context switch.
            return Nd4j.getAffinityManager().getDeviceForCurrentThread();
        }

        @Override
        public int currentDevice() {
            return DeviceMemoryManager.getInstance().getCurrentDeviceId();
        }

        @Override
        public void switchTo(int deviceId, String reason) {
            DeviceMemoryManager.getInstance().switchDevice(
                    deviceId, SameDiffLanguageModelImpl.class.getName(), reason);
        }
    }

    interface InferenceBackend {
        String generate(String prompt) throws Exception;

        default String generate(String prompt, int maxNewTokens) throws Exception {
            return generate(prompt);
        }

        default int countPromptTokens(String prompt) {
            throw new UnsupportedOperationException(
                    "Loaded inference backend does not expose prompt token counting"
            );
        }

        void close() throws Exception;
    }

    interface StructuredChatInferenceBackend extends InferenceBackend {
        ChatGenerationResult generateChat(ChatTemplate.Request request) throws Exception;

        default ChatGenerationResult generateChat(
                ChatTemplate.Request request, int maxNewTokens) throws Exception {
            return generateChat(request);
        }
    }

    private static final class GenerationPipelineBackend
            implements StructuredChatInferenceBackend {
        private final GenerationPipeline pipeline;
        private final Tokenizer tokenizer;
        private final String chatTemplate;
        private final int maxNewTokens;
        private final boolean continuationEnabled;
        private final int continuationChunkTokens;
        private final SamplingConfig thinkingSampling;
        private boolean closed;

        private GenerationPipelineBackend(
                GenerationPipeline pipeline,
                Tokenizer tokenizer,
                String chatTemplate,
                int maxNewTokens,
                boolean continuationEnabled,
                int continuationChunkTokens,
                SamplingConfig thinkingSampling) {
            this.pipeline = pipeline;
            this.tokenizer = tokenizer;
            this.chatTemplate = chatTemplate;
            this.maxNewTokens = maxNewTokens;
            this.continuationEnabled = continuationEnabled;
            this.continuationChunkTokens =
                    validateContinuationChunkTokens(continuationChunkTokens);
            this.thinkingSampling = Objects.requireNonNull(
                    thinkingSampling, "thinkingSampling");
        }

        @Override
        public synchronized int countPromptTokens(String prompt) {
            requireOpen();

            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException(
                        "Prompt cannot be null or blank"
                );
            }

            int[] tokenIds =
                    tokenizer
                            .encodePrompt(
                                    prompt,
                                    chatTemplate
                            )
                            .getIds();

            return tokenIds == null
                    ? 0
                    : tokenIds.length;
        }

        @Override
        public synchronized String generate(String prompt) {
            return generate(prompt, maxNewTokens);
        }

        @Override
        public synchronized String generate(String prompt, int requestedMaxNewTokens) {
            requireOpen();
            if (requestedMaxNewTokens <= 0) {
                throw new IllegalArgumentException(
                        "requestedMaxNewTokens must be positive: " + requestedMaxNewTokens);
            }
            if (continuationEnabled
                    && requestedMaxNewTokens > continuationChunkTokens) {
                return generateWithContinuation(prompt, requestedMaxNewTokens);
            }

            GenerationResult result = pipeline.generate(prompt, requestedMaxNewTokens);
            validateGenerationResult(result);
            return result.getText();
        }

        @Override
        public synchronized ChatGenerationResult generateChat(ChatTemplate.Request request) {
            return generateChat(request, maxNewTokens);
        }

        @Override
        public synchronized ChatGenerationResult generateChat(
                ChatTemplate.Request request, int requestedMaxNewTokens) {
            requireOpen();
            if (requestedMaxNewTokens <= 0) {
                throw new IllegalArgumentException(
                        "requestedMaxNewTokens must be positive: " + requestedMaxNewTokens);
            }
            SamplingConfig requestSampling = thinkingEnabled(request)
                    ? thinkingSampling : pipeline.getSamplingConfig();
            return pipeline.generateChat(
                    request, requestedMaxNewTokens, requestSampling);
        }

        private String generateWithContinuation(
                String prompt,
                int requestedMaxNewTokens) {
            try (GenerationPipeline.GenerationSession session =
                         pipeline.startSession(prompt, requestedMaxNewTokens)) {
                int firstBudget = Math.min(
                        continuationChunkTokens, session.getRemainingCapacity());
                GenerationResult result = session.generate(firstBudget);
                int chunks = 1;
                while (result.isTruncated()
                        && !session.isEosReached()
                        && session.getRemainingCapacity() > 0) {
                    int nextBudget = Math.min(
                            continuationChunkTokens,
                            session.getRemainingCapacity());
                    result = session.continueGeneration(nextBudget);
                    chunks++;
                }
                validateGenerationResult(result);
                logger.info(
                        "Retained-KV generation completed in {} chunk(s): "
                                + "generatedTokens={}, finishReason={}, remainingCapacity={}",
                        chunks, session.getAllTokens().length,
                        result.getFinishReason(), session.getRemainingCapacity());
                return session.getFullText();
            }
        }

        private static void validateGenerationResult(GenerationResult result) {
            if (result == null) {
                throw new IllegalStateException("GenerationPipeline returned no result");
            }
            if (result.getFinishReason() == GenerationResult.FinishReason.ERROR) {
                throw new IllegalStateException("GenerationPipeline reported an error");
            }
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
