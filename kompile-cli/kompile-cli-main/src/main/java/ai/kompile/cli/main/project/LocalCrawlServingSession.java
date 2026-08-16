/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.KompileLocalServingBootstrap;
import ai.kompile.cli.main.chat.LocalServingRuntimePool;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Request-scoped bridge from a local crawl to Kompile's pooled serving subprocess.
 *
 * <p>The project model is resolved or bootstrapped through the standalone model-staging
 * component, then the standalone model-serving executable/native image or executable JAR is
 * launched directly. Closing this session releases its lease; a compatible serving child remains
 * warm for bounded reuse and is terminated by idle eviction or pool shutdown.</p>
 */
public final class LocalCrawlServingSession implements LocalServingBackend, AutoCloseable {
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private final LocalServingRuntimePool.Lease runtime;
    private final HttpClient client;
    private final Duration requestTimeout;

    private LocalCrawlServingSession(
            LocalServingRuntimePool.Lease runtime,
            int requestTimeoutSeconds) {
        this.runtime = runtime;
        this.requestTimeout = Duration.ofSeconds(Math.max(30, requestTimeoutSeconds));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static LocalCrawlServingSession start(String modelId, int timeoutSeconds)
            throws KompileLocalServingBootstrap.BootstrapException {
        ChatConfig config = new ChatConfig("kompile-local", null, modelId, null);
        LocalServingRuntimePool.Lease runtime =
                LocalServingRuntimePool.acquire(config, timeoutSeconds);
        return new LocalCrawlServingSession(runtime, timeoutSeconds);
    }

    public static LocalCrawlServingSession start(
            Path projectRoot,
            String modelId,
            Map<String, Object> runtimeOptions,
            int timeoutSeconds) throws Exception {
        LocalProjectModelBootstrap.ResolvedProjectModel model =
                LocalProjectModelBootstrap.ensure(projectRoot, modelId, runtimeOptions);
        LocalServingRuntimePool.Lease runtime =
                LocalServingRuntimePool.acquire(
                        model.modelId(),
                        model.modelPath(),
                        model.tokenizerPath(),
                        runtimeOptions,
                        timeoutSeconds);
        return new LocalCrawlServingSession(runtime, timeoutSeconds);
    }

    public String modelId() {
        return runtime.modelId();
    }

    public String runtimePath() {
        return runtime.launcher().path().toString();
    }

    @Override
    public boolean isAvailable() {
        return runtime.isAlive();
    }

    @Override
    public boolean matchesModel(String requestedModelId) {
        return requestedModelId != null && requestedModelId.trim().equals(runtime.modelId());
    }

    @Override
    public boolean supportsStructuredChat() {
        return true;
    }

    @Override
    public String generate(String prompt) throws Exception {
        return generatedText(post("/api/llm/generate", Map.of("prompt", prompt)));
    }

    @Override
    public String generate(String prompt, int maxNewTokens) throws Exception {
        requirePositive(maxNewTokens);
        return generatedText(post("/api/llm/generate",
                Map.of("prompt", prompt, "maxTokens", maxNewTokens)));
    }

    @Override
    public String generateForModel(String requestedModelId, String prompt) throws Exception {
        requireModel(requestedModelId);
        return generate(prompt);
    }

    @Override
    public String generateForModel(String requestedModelId, String prompt, int maxNewTokens)
            throws Exception {
        requireModel(requestedModelId);
        return generate(prompt, maxNewTokens);
    }

    @Override
    public StructuredChatLanguageModel.Response generateChat(
            StructuredChatLanguageModel.Request request,
            int maxNewTokens) throws Exception {
        requirePositive(maxNewTokens);
        return structuredResponse(post("/api/llm/chat",
                Map.of("request", request, "maxTokens", maxNewTokens)));
    }

    @Override
    public StructuredChatLanguageModel.Response generateChatForModel(
            String requestedModelId,
            StructuredChatLanguageModel.Request request,
            int maxNewTokens) throws Exception {
        requireModel(requestedModelId);
        return generateChat(request, maxNewTokens);
    }

    private JsonNode post(String path, Object body)
            throws IOException, InterruptedException {
        if (!isAvailable()) {
            throw new IOException("Kompile serving subprocess is not running");
        }
        synchronized (runtime.coordinationLock()) {
            URI endpoint = runtime.baseUrl().resolve(path);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Serving subprocess " + path + " returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        }
    }

    private String generatedText(JsonNode response) throws IOException {
        String finishReason = response.path("finishReason").asText("");
        if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new IOException("Serving subprocess generation failed: " + finishReason);
        }
        return response.path("generatedText").asText("");
    }

    static StructuredChatLanguageModel.Response structuredResponse(JsonNode response)
            throws IOException {
        String finishReason = response.path("finishReason").asText("");
        if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new IOException("Serving subprocess structured generation failed: "
                    + finishReason);
        }
        List<StructuredChatLanguageModel.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : response.path("toolCalls")) {
            Map<String, Object> arguments = MAPPER.convertValue(
                    call.path("arguments"), new TypeReference<Map<String, Object>>() { });
            calls.add(new StructuredChatLanguageModel.ToolCall(
                    call.path("id").asText(""),
                    call.path("name").asText(""),
                    arguments));
        }
        List<StructuredChatLanguageModel.OutputBlock> outputBlocks = new ArrayList<>();
        for (JsonNode block : response.path("outputBlocks")) {
            outputBlocks.add(new StructuredChatLanguageModel.OutputBlock(
                    block.path("type").asText(""),
                    block.path("content").asText("")));
        }
        List<String> parseErrors = response.path("parseErrors").isArray()
                ? MAPPER.convertValue(response.path("parseErrors"),
                        new TypeReference<List<String>>() { })
                : List.of();
        return new StructuredChatLanguageModel.Response(
                response.path("rawText").asText(""),
                response.path("content").asText(""),
                response.path("reasoningContent").asText(""),
                outputBlocks,
                calls,
                parseErrors);
    }

    private void requireModel(String requestedModelId) {
        if (!matchesModel(requestedModelId)) {
            throw new IllegalStateException("Requested serving model '" + requestedModelId
                    + "' is not active (active=" + runtime.modelId() + ")");
        }
    }

    private void requirePositive(int maxNewTokens) {
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
    }

    @Override
    public void close() {
        runtime.close();
    }
}
