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

package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.chat.LocalServingRuntimePool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct LLM client that calls provider APIs without requiring a kompile-app server.
 * Supports OpenAI Chat Completions and Responses, Anthropic Messages, and Pi Messages.
 * <p>
 * Handles streaming, tool calling, and multi-turn conversations.
 */
public class DirectLlmClient implements AutoCloseable {

    static final int OPENAI_INSTRUCTIONS_MAX_CHARS = 1_048_576;
    static final int OPENAI_PROMPT_CACHE_KEY_MAX_CHARS = 64;
    private static final String OPENAI_INSTRUCTIONS_OMISSION =
            "\n\n[OpenAI instructions limit reached. Middle content was omitted; "
                    + "project instructions and saved tool results remain available through "
                    + "the read and glob tools.]\n\n";

    public interface ProviderActivityListener {
        void onToolStart(String callId, String name, String input);
        void onToolComplete(String callId, String name, String output,
                            int exitCode, boolean error);
        default void onTokenUsage(long input, long output,
                                  long cacheRead, long cacheCreation) { }
    }

    /** Provider-neutral terminal failure categories used by the chat coordinator. */
    public enum FailureKind {
        NONE,
        CONTEXT_OVERFLOW,
        AUTHENTICATION,
        CREDENTIAL_UNAVAILABLE,
        RATE_LIMITED,
        TEMPORARY,
        PERMISSION_DENIED,
        QUOTA_EXHAUSTED,
        REFUSAL,
        TRUNCATED,
        PROVIDER_ERROR
    }

    private final ChatConfig config;
    private final HttpClient httpClient;
    private final ProviderConnectivityPolicy connectivityPolicy;
    private final ObjectMapper objectMapper;
    private final Path workingDirectory;
    private final List<ObjectNode> conversationHistory;
    private final Object historyLock = new Object();
    private volatile AtomicBoolean cancelSignal;
    private volatile java.util.function.BooleanSupplier cancellationCheck;
    private volatile java.util.function.Consumer<String> outputConsumer;
    private volatile java.util.function.Consumer<ConnectivityEvent> connectivityEventConsumer;
    private volatile ProviderActivityListener providerActivityListener;
    private volatile RadiusGatewayConfig radiusGatewayConfig;
    private volatile String radiusGatewayConfigSource;
    private volatile OpenCodeServeClient openCodeServeClient;
    private volatile int nativeCompactionTriggerTokens;
    private volatile String promptCacheSessionId;
    private volatile JsonOutputSpec requestedJsonOutput;
    private final Set<ProviderCompactionCapabilities.TokenCounting> unavailableTokenCounters =
            new LinkedHashSet<>();
    private final Set<String> unavailableNativeCompactionRoutes = new LinkedHashSet<>();
    private volatile boolean openCodeNeedsSeed = true;

    private record JsonOutputSpec(String name, JsonNode schema, boolean strict) { }

    // The native Codex structured-output contract rejects these validation-only keywords.
    // Keep the full schema (including defaults and object-valued enum/const payloads) for local
    // validation; only the transport copy for this selected route is reduced.
    private static final Set<String> NATIVE_CODEX_STRICT_UNSUPPORTED_KEYWORDS = Set.of(
            "format", "maxItems", "maxLength", "minItems", "minLength", "maximum",
            "minimum", "multipleOf", "pattern", "uniqueItems");
    private static final Set<String> SCHEMA_MAP_KEYWORDS = Set.of(
            "properties", "patternProperties", "$defs", "definitions", "dependentSchemas",
            "dependencies");
    private static final Set<String> SCHEMA_ARRAY_KEYWORDS = Set.of(
            "allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SCHEMA_VALUE_KEYWORDS = Set.of(
            "items", "additionalItems", "contains", "additionalProperties", "propertyNames",
            "unevaluatedItems", "unevaluatedProperties", "contentSchema", "if", "then", "else",
            "not");

    public DirectLlmClient(ChatConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, config.connectivityPolicy(),
                Path.of(System.getProperty("user.dir")));
    }

    /** Create a direct client scoped to the same project as the owning chat. */
    public DirectLlmClient(ChatConfig config, ObjectMapper objectMapper,
                           Path workingDirectory) {
        this(config, objectMapper, config.connectivityPolicy(), workingDirectory);
    }

    DirectLlmClient(ChatConfig config, ObjectMapper objectMapper,
                    ProviderConnectivityPolicy connectivityPolicy) {
        this(config, objectMapper, connectivityPolicy,
                Path.of(System.getProperty("user.dir")));
    }

    DirectLlmClient(ChatConfig config, ObjectMapper objectMapper,
                    ProviderConnectivityPolicy connectivityPolicy,
                    Path workingDirectory) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.connectivityPolicy = connectivityPolicy;
        this.workingDirectory = (workingDirectory == null
                ? Path.of(System.getProperty("user.dir")) : workingDirectory)
                .toAbsolutePath().normalize();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectivityPolicy.connectTimeout())
                .build();
        this.conversationHistory = new ArrayList<>();
    }

    /**
     * Create a client with a caller-owned retry/deadline policy. The normal constructors retain
     * provider defaults; bounded utility lanes use this factory to avoid nesting retries beneath
     * their own hard deadline.
     */
    public static DirectLlmClient withConnectivityPolicy(
            ChatConfig config, ObjectMapper objectMapper,
            ProviderConnectivityPolicy connectivityPolicy, Path workingDirectory) {
        return new DirectLlmClient(config, objectMapper, connectivityPolicy, workingDirectory);
    }

    /**
     * Sets the cancel signal that can be used to interrupt streaming.
     */
    public void setCancelSignal(AtomicBoolean cancelSignal) {
        this.cancelSignal = cancelSignal;
        this.cancellationCheck = cancelSignal == null ? null : cancelSignal::get;
    }

    public void setCancellationCheck(java.util.function.BooleanSupplier cancellationCheck) {
        this.cancellationCheck = cancellationCheck;
    }

    protected boolean isCancelled() {
        java.util.function.BooleanSupplier check = cancellationCheck;
        try {
            return check != null && check.getAsBoolean();
        } catch (RuntimeException ignored) {
            AtomicBoolean signal = this.cancelSignal;
            return signal != null && signal.get();
        }
    }

    /**
     * Sets a consumer that receives all streamed output text.
     */
    public void setOutputConsumer(java.util.function.Consumer<String> consumer) {
        this.outputConsumer = consumer;
    }

    /**
     * Returns the currently installed streamed-output consumer, if any.
     */
    public java.util.function.Consumer<String> getOutputConsumer() {
        return outputConsumer;
    }

    /** Receives retry/reconnect lifecycle events without mixing them into model text. */
    public void setConnectivityEventConsumer(
            java.util.function.Consumer<ConnectivityEvent> consumer) {
        this.connectivityEventConsumer = consumer;
    }

    public java.util.function.Consumer<ConnectivityEvent> getConnectivityEventConsumer() {
        return connectivityEventConsumer;
    }

    public void setProviderActivityListener(ProviderActivityListener listener) {
        this.providerActivityListener = listener;
    }

    public ProviderActivityListener getProviderActivityListener() {
        return providerActivityListener;
    }

    /** Project scope inherited by isolated clients such as judges. */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /** Stable conversation affinity used by provider prompt-cache routing. */
    public void setPromptCacheSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            promptCacheSessionId = null;
            return;
        }
        String normalized = sessionId.strip();
        promptCacheSessionId = normalized.length() <= OPENAI_PROMPT_CACHE_KEY_MAX_CHARS
                ? normalized : normalized.substring(0, OPENAI_PROMPT_CACHE_KEY_MAX_CHARS);
    }

    public String getPromptCacheSessionId() {
        return promptCacheSessionId;
    }

    /**
     * Print a streaming text chunk to the terminal.
     * Subclasses may override to intercept/redirect streaming output.
     */
    protected void printStreamingChunk(String chunk) {
        if (chunk != null) {
            java.util.function.Consumer<String> consumer = this.outputConsumer;
            if (consumer != null) {
                consumer.accept(chunk);
            } else {
                System.out.print(chunk);
                System.out.flush();
            }
        }
    }

    /**
     * Stream a chat completion turn.
     * Returns a StreamResult with the text response and any tool call requests.
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults) {
        return streamChat(userMessage, systemPrompt, toolDefs, toolResults, null);
    }

    /**
     * Stream a chat completion turn with optional model override and attachments.
     * Images and text attachments are encoded using the selected provider's native content-block
     * format. Protocols without a verified media contract fail explicitly instead of silently
     * dropping attachment bytes.
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride, List<AttachmentInput> attachments) {
        synchronized (historyLock) {
            List<AttachmentInput> requestAttachments = attachments == null
                    ? List.of() : new ArrayList<>(attachments);
            String attachmentError = validateAttachments(requestAttachments);
            if (attachmentError != null) {
                return attachmentFailure(attachmentError);
            }
            requestAttachments = List.copyOf(requestAttachments);

            String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                    ? modelOverride : config.getModel();
            ResolvedRoute route = resolveRoute(effectiveModel);
            if (!requestAttachments.isEmpty() && !supportsAttachments(route.protocol())) {
                return attachmentFailure("Provider protocol " + route.protocol()
                        + " does not support structured attachments in Kompile chat. "
                        + "Use OpenAI Chat Completions, OpenAI Responses, or Anthropic Messages.");
            }
            int connectivityAttempt = 1;
            boolean authenticationRefreshAttempted = false;
            OAuthProviderFlow.RequestAuth retryAuth = null;
            while (true) {
                StreamResult result = streamAttempt(
                        route, userMessage, systemPrompt, toolDefs, toolResults,
                        effectiveModel, requestAttachments, retryAuth);
                finishGenerationOutcome(result);
                if (result.authenticationFailure) {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
                        result.cancelled = true;
                        result.authenticationFailure = false;
                        result.rejectedAuth = null;
                        return result;
                    }
                    OAuthProviderFlow.RequestAuth rejectedAuth = result.rejectedAuth;
                    result.rejectedAuth = null;
                    try {
                        if (!authenticationRefreshAttempted && result.isReplaySafe() && rejectedAuth != null) {
                            authenticationRefreshAttempted = true;
                            retryAuth = config.refreshRequestAuthAfterUnauthorized(rejectedAuth);
                            if (retryAuth != null) continue;
                        }
                    } catch (ChatConfig.AuthenticationException e) {
                        return finishCredentialFailure(result, e);
                    }
                    return finishAuthenticationFailure(result);
                }
                if (!result.retryableConnectivityFailure) {
                    return result;
                }

                boolean replaySafe = result.isReplaySafe();
                if (!replaySafe || connectivityAttempt == connectivityPolicy.maxAttempts()) {
                    return finishConnectivityFailure(result, replaySafe);
                }

                Duration delay = connectivityPolicy.retryDelay(
                        connectivityAttempt, result.connectivityHeaders);
                emitConnectivityEvent(new ConnectivityEvent(
                        config.getProvider(), connectivityAttempt + 1,
                        connectivityPolicy.maxAttempts(),
                        delay, result.connectivityFailure));
                if (!waitForRetry(delay)) {
                    result.cancelled = true;
                    result.retryableConnectivityFailure = false;
                    return result;
                }
                if (route.protocol() == WireProtocol.OPENCODE) {
                    resetOpenCodeClient();
                }
                connectivityAttempt++;
            }
        }
    }

    /**
     * Stream a chat completion turn with optional model override.
     *
     * @param modelOverride if non-null, use this model instead of the configured default
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride) {
        return streamChat(
                userMessage, systemPrompt, toolDefs, toolResults, modelOverride, List.of());
    }

    private StreamResult streamAttempt(
            ResolvedRoute route, String userMessage, String systemPrompt,
            ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
            String effectiveModel, List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        return switch (route.protocol()) {
            case KOMPILE_LOCAL -> streamKompileServing(
                    userMessage, systemPrompt, toolDefs, toolResults);
            case OPENCODE -> streamOpenCode(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
            case OPENAI_RESPONSES -> streamOpenAiResponses(
                    userMessage, systemPrompt, toolDefs, toolResults,
                    effectiveModel, route.codexBackend(), attachments, retryAuth);
            case PI_MESSAGES -> streamPiMessages(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, retryAuth);
            case ANTHROPIC_MESSAGES -> streamAnthropic(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, attachments, retryAuth);
            case OPENAI_CHAT -> streamOpenAi(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, attachments, retryAuth);
        };
    }

    public ResolvedRoute resolveRoute(String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? config.getModel() : modelOverride.strip();
        if (config.isKompileLocalServing()) {
            return new ResolvedRoute(WireProtocol.KOMPILE_LOCAL, false,
                    ProviderCompactionCapabilities.generic());
        }
        if (config.isOpenCodeNative()) {
            return new ResolvedRoute(WireProtocol.OPENCODE, false,
                    ProviderCompactionCapabilities.forProvider("opencode"));
        }
        if (config.isOpenAiCodexFormat()) {
            return new ResolvedRoute(WireProtocol.OPENAI_RESPONSES, true,
                    ProviderCompactionCapabilities.forProvider("openai-codex"));
        }
        if (config.isPiMessagesFormat()) {
            return new ResolvedRoute(WireProtocol.PI_MESSAGES, false,
                    ProviderCompactionCapabilities.generic());
        }
        if (config.isAnthropicFormat()) {
            return new ResolvedRoute(WireProtocol.ANTHROPIC_MESSAGES, false,
                    ProviderCompactionCapabilities.forProvider("anthropic"));
        }
        // GitHub is a protocol-translating proxy. Its model family selects the
        // payload shape, but beta count/compaction endpoints are not assumed.
        if (usesGitHubAnthropicMessages(effectiveModel)) {
            return new ResolvedRoute(WireProtocol.ANTHROPIC_MESSAGES, false,
                    ProviderCompactionCapabilities.generic());
        }
        if (usesGitHubOpenAiResponses(effectiveModel)) {
            return new ResolvedRoute(WireProtocol.OPENAI_RESPONSES, false,
                    ProviderCompactionCapabilities.generic());
        }
        ProviderCompactionCapabilities capabilities =
                ProviderCompactionCapabilities.forProvider(config.getProvider());
        return new ResolvedRoute(WireProtocol.OPENAI_CHAT, false, capabilities);
    }

    public ProviderCompactionCapabilities compactionCapabilities(String modelOverride) {
        return resolveRoute(modelOverride).capabilities();
    }

    /** Whether the selected wire protocol can carry structured file/image attachments. */
    public boolean supportsAttachments(String modelOverride) {
        return supportsAttachments(resolveRoute(modelOverride).protocol());
    }

    public void setNativeCompactionTriggerTokens(int tokens) {
        nativeCompactionTriggerTokens = Math.max(0, tokens);
    }

    /** Attempt an explicit provider-native compaction without generic fallback. */
    public NativeCompactionResult tryNativeCompact(String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? config.getModel() : modelOverride.strip();
        ResolvedRoute route = resolveRoute(effectiveModel);
        synchronized (historyLock) {
            try {
                return switch (route.capabilities().nativeCompaction()) {
                    case OPENCODE_SESSION -> {
                        OpenCodeServeClient.NativeSummary result =
                                openCodeClient().summarize(effectiveModel);
                        yield new NativeCompactionResult(
                                true, result.applied(), result.summary(),
                                null, result.diagnostic());
                    }
                    case OPENAI_RESPONSES -> compactResponses(effectiveModel, route.codexBackend());
                    case ANTHROPIC_MESSAGES, NONE -> NativeCompactionResult.unsupported();
                };
            } catch (Exception e) {
                return new NativeCompactionResult(
                        true, false, null, null, formatExceptionMessage(e));
            }
        }
    }

    private NativeCompactionResult compactResponses(String model, boolean codex) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        String baseUrl = config.resolveBaseUrl(auth);
        String url = codex
                ? trimTrailingSlashes(baseUrl) + "/codex/responses/compact"
                : trimTrailingSlashes(baseUrl) + "/responses/compact";
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        ArrayNode input = request.putArray("input");
        conversationHistory.forEach(item -> input.add(item.deepCopy()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyBearerAuthentication(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404 || response.statusCode() == 405
                || response.statusCode() == 501) {
            return NativeCompactionResult.unsupported();
        }
        if (response.statusCode() / 100 != 2) {
            return new NativeCompactionResult(
                    true, false, null, null, "HTTP " + response.statusCode());
        }
        JsonNode output = objectMapper.readTree(response.body()).path("output");
        if (!output.isArray() || output.isEmpty()) {
            return new NativeCompactionResult(
                    true, false, null, null, "Responses compact returned no output");
        }
        // Do not mutate retained history yet. The ConversationLedger commits the
        // matching portable/native checkpoint first, then reprojects this payload.
        return new NativeCompactionResult(true, true, null, output.deepCopy(), null);
    }

    /** Count the complete pending request when the resolved provider supports it. */
    public TokenCountResult countInputTokens(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? config.getModel() : modelOverride.strip();
        ResolvedRoute route = resolveRoute(effectiveModel);
        synchronized (historyLock) {
            ProviderCompactionCapabilities.TokenCounting counter =
                    route.capabilities().tokenCounting();
            if (unavailableTokenCounters.contains(counter)) {
                return TokenCountResult.unsupported();
            }
            List<ObjectNode> saved = deepCopyHistory();
            try {
                TokenCountResult result = switch (counter) {
                    case ANTHROPIC_MESSAGES -> countAnthropicTokens(
                            userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
                    case OPENAI_RESPONSES -> countResponsesTokens(
                            userMessage, systemPrompt, toolDefs, toolResults,
                            effectiveModel, route.codexBackend());
                    case GEMINI -> countGeminiTokens(
                            userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
                    case NONE -> TokenCountResult.unsupported();
                };
                if (!result.supported()
                        && ("HTTP 404".equals(result.diagnostic())
                        || "HTTP 405".equals(result.diagnostic())
                        || "HTTP 501".equals(result.diagnostic()))) {
                    unavailableTokenCounters.add(counter);
                }
                return result;
            } catch (Exception e) {
                return new TokenCountResult(false, false, 0L,
                        counter.name(), formatExceptionMessage(e));
            } finally {
                conversationHistory.clear();
                conversationHistory.addAll(saved);
            }
        }
    }

    private TokenCountResult countAnthropicTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String model) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        String baseUrl = trimTrailingSlashes(config.resolveBaseUrl(auth));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        String effectiveSystem = effectiveAnthropicSystemPrompt(auth, systemPrompt);
        if (!effectiveSystem.isBlank()) request.put("system", effectiveSystem);
        request.set("messages", buildAnthropicMessages(userMessage, toolResults));
        if (toolDefs != null && !toolDefs.isEmpty()) {
            request.set("tools", convertToolDefsToAnthropic(toolDefs));
        }
        boolean nativeCompaction = nativeCompactionTriggerTokens >= 50_000
                && !unavailableNativeCompactionRoutes.contains("anthropic:" + model);
        if (nativeCompaction) {
            request.putObject("context_management").putArray("edits").addObject()
                    .put("type", "compact_20260112")
                    .putObject("trigger")
                    .put("type", "input_tokens")
                    .put("value", nativeCompactionTriggerTokens);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/messages/count_tokens"))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyAnthropicAuthentication(builder, auth, false);
        if (nativeCompaction) {
            builder.setHeader("anthropic-beta",
                    mergeHeaderValue(auth, "anthropic-beta", "compact-2026-01-12"));
        }
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            return TokenCountResult.unavailable("anthropic", response.statusCode());
        }
        return TokenCountResult.exact(
                objectMapper.readTree(response.body()).path("input_tokens").asLong(0L),
                "anthropic");
    }

    private TokenCountResult countResponsesTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String model, boolean codex) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        String baseUrl = config.resolveBaseUrl(auth);
        ResponsesHistoryLinks historyLinks = sanitizeResponsesHistory(toolResults);
        List<ObjectNode> staged = prepareResponsesToolResultItems(toolResults, historyLinks);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.set("input", buildResponsesInput(userMessage, systemPrompt, staged, codex));
        if (codex) {
            request.put("instructions", boundedOpenAiInstructions(systemPrompt));
        }
        if (toolDefs != null && !toolDefs.isEmpty()) {
            request.set("tools", convertToolDefsToResponses(toolDefs, codex));
        }
        String createUrl = resolveResponsesUrl(baseUrl, codex);
        String countUrl = createUrl.substring(0, createUrl.length() - "/responses".length())
                + "/responses/input_tokens";
        if (codex) {
            countUrl = trimTrailingSlashes(baseUrl) + "/codex/responses/input_tokens";
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(countUrl))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyBearerAuthentication(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            return TokenCountResult.unavailable("openai-responses", response.statusCode());
        }
        return TokenCountResult.exact(
                objectMapper.readTree(response.body()).path("input_tokens").asLong(0L),
                "openai-responses");
    }

    private TokenCountResult countGeminiTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String model) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        URI configured = URI.create(config.resolveBaseUrl(auth));
        String origin = configured.getScheme() + "://" + configured.getAuthority();
        String encodedModel = java.net.URLEncoder.encode(model, StandardCharsets.UTF_8);
        String url = origin + "/v1beta/models/" + encodedModel + ":countTokens";

        ArrayNode openAiMessages = buildOpenAiMessages(userMessage, systemPrompt, toolResults);
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode contents = request.putArray("contents");
        // Only skip the system message injected by buildOpenAiMessages; it is
        // represented below. This adapter is text-only, so never label a count
        // that discarded tool envelopes or multimodal parts as exact.
        int firstMessage = systemPrompt != null && !systemPrompt.isEmpty() ? 1 : 0;
        for (int i = firstMessage; i < openAiMessages.size(); i++) {
            JsonNode message = openAiMessages.get(i);
            String role = message.path("role").asText();
            JsonNode text = message.path("content");
            if (!("user".equals(role) || "assistant".equals(role))
                    || !text.isTextual() || message.hasNonNull("tool_calls")
                    || message.hasNonNull("function_call")) {
                return new TokenCountResult(false, false, 0L, "gemini",
                        "Structured history requires a lossless Gemini count adapter");
            }
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(role) ? "model" : "user");
            content.putArray("parts").addObject().put("text", text.asText());
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            request.putObject("systemInstruction").putArray("parts")
                    .addObject().put("text", systemPrompt);
        }
        if (toolDefs != null && !toolDefs.isEmpty()) {
            ObjectNode tools = request.putArray("tools").addObject();
            ArrayNode declarations = tools.putArray("functionDeclarations");
            for (JsonNode tool : toolDefs) {
                ObjectNode declaration = declarations.addObject();
                declaration.put("name", tool.path("name").asText());
                declaration.put("description", tool.path("description").asText(""));
                JsonNode parameters = tool.path("inputSchema");
                if (parameters.isMissingNode()) parameters = tool.path("parameters");
                if (!parameters.isMissingNode()) declaration.set("parameters", parameters.deepCopy());
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyTokenAuthentication(builder, auth, "x-goog-api-key");
        applyHeaders(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            return TokenCountResult.unavailable("gemini", response.statusCode());
        }
        return TokenCountResult.exact(
                objectMapper.readTree(response.body()).path("totalTokens").asLong(0L),
                "gemini");
    }

    private List<ObjectNode> deepCopyHistory() {
        return conversationHistory.stream().map(ObjectNode::deepCopy).toList();
    }

    public enum WireProtocol {
        KOMPILE_LOCAL,
        OPENCODE,
        OPENAI_RESPONSES,
        PI_MESSAGES,
        ANTHROPIC_MESSAGES,
        OPENAI_CHAT
    }

    public record ResolvedRoute(
            WireProtocol protocol,
            boolean codexBackend,
            ProviderCompactionCapabilities capabilities) {
    }

    public record NativeCompactionResult(
            boolean supported,
            boolean applied,
            String portableSummary,
            JsonNode nativePayload,
            String diagnostic) {

        public static NativeCompactionResult unsupported() {
            return new NativeCompactionResult(false, false, null, null, null);
        }
    }

    public record TokenCountResult(
            boolean supported,
            boolean exact,
            long inputTokens,
            String source,
            String diagnostic) {

        public static TokenCountResult unsupported() {
            return new TokenCountResult(false, false, 0L, "none", null);
        }

        static TokenCountResult unavailable(String source, int status) {
            return new TokenCountResult(false, false, 0L, source,
                    "HTTP " + status);
        }

        static TokenCountResult exact(long inputTokens, String source) {
            return new TokenCountResult(true, true, Math.max(0L, inputTokens), source, null);
        }
    }

    private StreamResult streamOpenCode(String userMessage, String systemPrompt,
                                         ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                         String effectiveModel) {
        StreamResult result = new StreamResult();
        StringBuilder streamed = new StringBuilder();
        try {
            OpenCodeServeClient client = openCodeClient();
            String effectiveSystemPrompt = systemPrompt;
            if (openCodeNeedsSeed && !conversationHistory.isEmpty()) {
                effectiveSystemPrompt = (systemPrompt == null ? "" : systemPrompt + "\n\n")
                        + "[Portable conversation context restored by Kompile]\n"
                        + portableHistoryText();
            }
            // OpenCode owns a durable native session. A turn that actually ran may
            // have mutated that provider-side history even when it failed, so such
            // results must not be replayed as if this were a stateless HTTP request.
            // Failures before the turn begins (server boot, session creation, turn
            // spawn) surface as TurnNotStartedException instead: the provider never
            // saw the prompt, so the connectivity retry loop may replay it against
            // the fresh transport installed by resetOpenCodeClient().
            String text = client.send(effectiveModel, config.getThinking(),
                    effectiveSystemPrompt, userMessage,
                    chunk -> {
                        streamed.append(chunk);
                        printStreamingChunk(chunk);
                    }, new OpenCodeServeClient.ActivityListener() {
                        @Override
                        public void onToolStart(String callId, String name, String input) {
                            result.providerSideEffectsObserved = true;
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) {
                                listener.onToolStart(callId, name, input);
                            }
                        }

                        @Override
                        public void onToolComplete(String callId, String name, String output,
                                                   int exitCode, boolean error) {
                            result.providerSideEffectsObserved = true;
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) {
                                listener.onToolComplete(
                                        callId, name, output, exitCode, error);
                            }
                        }

                        @Override
                        public void onTokenUsage(long input, long output,
                                                 long cacheRead, long cacheCreation) {
                            result.inputTokens += Math.max(0, input);
                            result.outputTokens += Math.max(0, output);
                            result.cacheReadTokens += Math.max(0, cacheRead);
                            result.cacheCreationTokens += Math.max(0, cacheCreation);
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) {
                                listener.onTokenUsage(
                                        input, output, cacheRead, cacheCreation);
                            }
                        }
                    });
            result.text = text;
            if (streamed.length() == 0) {
                printStreamingChunk(text);
            }
            appendOpenCodeHistory(userMessage, text);
            openCodeNeedsSeed = false;
        } catch (OpenCodeServeClient.TurnNotStartedException e) {
            // The turn never reached the provider: no native session history was
            // touched, so replay-safe stays true and the retry loop can reconnect.
            recordStreamFailure(result, e, "[Error: ");
        } catch (Exception e) {
            // The turn ran, so the native session may hold partial provider-side
            // state; keep the result non-replayable.
            result.providerSideEffectsObserved = true;
            if (streamed.length() > 0) result.text = streamed.toString();
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    private OpenCodeServeClient openCodeClient() {
        OpenCodeServeClient client = openCodeServeClient;
        if (client == null) {
            synchronized (this) {
                client = openCodeServeClient;
                if (client == null) {
                    client = new OpenCodeServeClient(objectMapper, workingDirectory);
                    openCodeServeClient = client;
                }
            }
        }
        return client;
    }

    private void appendOpenCodeHistory(String userMessage, String assistantText) {
        if (userMessage != null) {
            ObjectNode user = objectMapper.createObjectNode();
            user.put("role", "user");
            user.put("content", userMessage);
            conversationHistory.add(user);
        }
        if (assistantText != null && !assistantText.isBlank()) {
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", assistantText);
            conversationHistory.add(assistant);
        }
    }

    @Override
    public void close() {
        resetOpenCodeClient();
    }

    /**
     * Clear conversation history (for new sessions).
     */
    public void clearHistory() {
        synchronized (historyLock) {
            conversationHistory.clear();
            resetOpenCodeClient();
        }
    }

    /**
     * Add a message to history (for replay/resume support).
     */
    public void addToHistory(String role, String content) {
        synchronized (historyLock) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", content);
            conversationHistory.add(msg);
        }
    }

    /**
     * Replay one executed tool call into wire history as a protocol-correct
     * assistant envelope instead of prose. Models imitate the message shapes
     * they see: replaying tool calls as plain "[Tool call ...]" text teaches
     * them to emit tool calls as text, which the loop cannot execute.
     * Formats that cannot carry tool-call envelopes (the flattened serving and
     * legacy routes) deliberately fall back to the portable text form.
     */
    public void addReplayedToolCall(String toolName, String callId, String argumentsJson) {
        addReplayedToolCalls(
                List.of(new ReplayedToolCallInput(toolName, callId, argumentsJson)), null);
    }

    /** Replay one provider assistant turn containing one or more parallel tool calls. */
    public void addReplayedToolCalls(
            List<ReplayedToolCallInput> calls, String modelOverride) {
        if (calls == null || calls.isEmpty()) return;
        synchronized (historyLock) {
            switch (routeHistoryFormat(modelOverride)) {
                case OPENAI_CHAT_ENVELOPE -> {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("role", "assistant");
                    msg.put("content", "");
                    ArrayNode toolCalls = msg.putArray("tool_calls");
                    for (ReplayedToolCallInput replayed : calls) {
                        ObjectNode call = toolCalls.addObject();
                        call.put("id", replayed.callId());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", replayed.toolName());
                        fn.put("arguments", replayed.argumentsJson() == null
                                ? "{}" : replayed.argumentsJson());
                    }
                    conversationHistory.add(msg);
                }
                case RESPONSES_ENVELOPE -> {
                    // Responses-native item; the sanitizer retains it, and staged
                    // live outputs dedupe against history outputs by call id.
                    for (ReplayedToolCallInput replayed : calls) {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("type", "function_call");
                        item.put("id", "fc_" + UUID.randomUUID().toString().replace("-", ""));
                        item.put("call_id", replayed.callId());
                        item.put("name", replayed.toolName());
                        item.put("arguments", replayed.argumentsJson() == null
                                ? "{}" : replayed.argumentsJson());
                        item.put("status", "completed");
                        conversationHistory.add(item);
                    }
                }
                case ANTHROPIC_ENVELOPE -> {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("role", "assistant");
                    ArrayNode content = msg.putArray("content");
                    for (ReplayedToolCallInput replayed : calls) {
                        ObjectNode block = content.addObject();
                        block.put("type", "tool_use");
                        block.put("id", replayed.callId());
                        block.put("name", replayed.toolName());
                        block.set("input", parseArgumentsOrEmpty(replayed.argumentsJson()));
                    }
                    conversationHistory.add(msg);
                }
                case TEXT -> {
                    for (ReplayedToolCallInput replayed : calls) {
                        addToHistory("assistant", "[Tool call " + replayed.toolName() + " "
                                + replayed.callId() + "]\n"
                                + (replayed.argumentsJson() == null
                                ? "{}" : replayed.argumentsJson()));
                    }
                }
            }
        }
    }

    /**
     * Replay one tool result into wire history, pairing it with its call
     * envelope when the active route supports them.
     */
    public void addReplayedToolResult(String toolName, String callId, String output) {
        addReplayedToolResults(
                List.of(new ToolCallResultInput(callId, toolName, output, false)), null);
    }

    /** Replay one provider result turn for one or more parallel tool calls. */
    public void addReplayedToolResults(
            List<ToolCallResultInput> results, String modelOverride) {
        if (results == null || results.isEmpty()) return;
        synchronized (historyLock) {
            switch (routeHistoryFormat(modelOverride)) {
                case OPENAI_CHAT_ENVELOPE -> {
                    for (ToolCallResultInput replayed : results) {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("role", "tool");
                        msg.put("tool_call_id", replayed.callId);
                        msg.put("content", replayed.output == null ? "" : replayed.output);
                        if (replayed.name != null) msg.put("name", replayed.name);
                        conversationHistory.add(msg);
                    }
                }
                case RESPONSES_ENVELOPE -> {
                    // Pair with the replayed function_call item so the sanitizer
                    // retains both; a live re-submission of the same output is
                    // deduped by prepareResponsesToolResultItems.
                    for (ToolCallResultInput replayed : results) {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("type", "function_call_output");
                        item.put("call_id", replayed.callId);
                        item.put("output", replayed.output == null ? "" : replayed.output);
                        conversationHistory.add(item);
                    }
                }
                case ANTHROPIC_ENVELOPE -> {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("role", "user");
                    ArrayNode content = msg.putArray("content");
                    for (ToolCallResultInput replayed : results) {
                        ObjectNode block = content.addObject();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", replayed.callId);
                        block.put("content", replayed.output == null ? "" : replayed.output);
                        if (replayed.isError) block.put("is_error", true);
                    }
                    conversationHistory.add(msg);
                }
                case TEXT -> {
                    for (ToolCallResultInput replayed : results) {
                        addToHistory("user", "[Tool result " + replayed.name + " "
                                + replayed.callId + "]\n"
                                + (replayed.output == null ? "" : replayed.output));
                    }
                }
            }
        }
    }

    enum RouteHistoryFormat { OPENAI_CHAT_ENVELOPE, RESPONSES_ENVELOPE, ANTHROPIC_ENVELOPE, TEXT }

    private RouteHistoryFormat routeHistoryFormat(String modelOverride) {
        try {
            return switch (resolveRoute(modelOverride).protocol()) {
                case OPENAI_CHAT -> RouteHistoryFormat.OPENAI_CHAT_ENVELOPE;
                case OPENAI_RESPONSES -> RouteHistoryFormat.RESPONSES_ENVELOPE;
                case ANTHROPIC_MESSAGES -> RouteHistoryFormat.ANTHROPIC_ENVELOPE;
                default -> RouteHistoryFormat.TEXT;
            };
        } catch (Exception ignored) {
            return RouteHistoryFormat.TEXT;
        }
    }

    private JsonNode parseArgumentsOrEmpty(String argumentsJson) {
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                return objectMapper.readTree(argumentsJson);
            } catch (Exception ignored) {
                // Fall through to the empty object below.
            }
        }
        return objectMapper.createObjectNode();
    }

    /**
     * One-shot streaming completion that does NOT mutate conversation history.
     * Used for utility calls like summarization where we want the model's
     * output but must not pollute the ongoing chat with the request/response.
     * <p>
     * Internally: snapshot history, clear, call streamChat (which would add
     * the request turn), then restore the snapshot — yielding a clean call.
     */
    public StreamResult streamOneShot(String prompt, String systemPrompt, String modelOverride) {
        if (getClass() == DirectLlmClient.class) {
            // Utility calls must not clear or lock the live chat history while a
            // judge/summary network request is in flight. A fresh client also gives
            // provider-owned OpenCode a genuinely isolated native session.
            try (DirectLlmClient isolated = new DirectLlmClient(
                    config, objectMapper, connectivityPolicy, workingDirectory)) {
                isolated.setCancelSignal(cancelSignal);
                String cacheSessionId = activePromptCacheSessionId();
                if (cacheSessionId != null) {
                    // Keep utility calls on a distinct affinity shard even when the
                    // original key already occupies the provider's 64-char limit.
                    isolated.setPromptCacheSessionId("utility:" + cacheSessionId);
                }
                if (cancellationCheck != null) {
                    isolated.setCancellationCheck(cancellationCheck);
                }
                isolated.setOutputConsumer(outputConsumer);
                isolated.setConnectivityEventConsumer(connectivityEventConsumer);
                return isolated.streamChat(prompt, systemPrompt, null, null, modelOverride);
            }
        }
        // Preserve the interception seam used by specialized/subprocess clients
        // and test doubles. Their override executes under a re-entrant lock.
        synchronized (historyLock) {
            List<ObjectNode> saved = new ArrayList<>(conversationHistory);
            conversationHistory.clear();
            try {
                return streamChat(prompt, systemPrompt, null, null, modelOverride);
            } finally {
                conversationHistory.clear();
                conversationHistory.addAll(saved);
            }
        }
    }

    /**
     * One-shot completion with provider-enforced JSON Schema on known OpenAI protocol paths.
     * Unsupported providers fall back to the ordinary one-shot request; caller-side validation
     * and bounded repair remain authoritative.
     */
    public StreamResult streamOneShotJson(
            String prompt, String systemPrompt, String modelOverride,
            String schemaName, JsonNode schema, boolean strict) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("Structured output schema must be a JSON object");
        }
        if (getClass() != DirectLlmClient.class) {
            return streamOneShot(prompt, systemPrompt, modelOverride);
        }
        try (DirectLlmClient isolated = new DirectLlmClient(
                config, objectMapper, connectivityPolicy, workingDirectory)) {
            isolated.setCancelSignal(cancelSignal);
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) {
                isolated.setPromptCacheSessionId("utility:" + cacheSessionId);
            }
            if (cancellationCheck != null) {
                isolated.setCancellationCheck(cancellationCheck);
            }
            isolated.setOutputConsumer(outputConsumer);
            isolated.setConnectivityEventConsumer(connectivityEventConsumer);
            isolated.requestedJsonOutput = new JsonOutputSpec(
                    normalizeJsonSchemaName(schemaName), schema.deepCopy(), strict);
            return isolated.streamChat(prompt, systemPrompt, null, null, modelOverride);
        }
    }

    /**
     * Replace the entire conversation history with a summary of prior turns.
     * The summary is injected as a user/assistant exchange so the next real
     * user turn continues normally. Used by the /compact command.
     */
    public void replaceHistoryWithSummary(String summary) {
        if (summary == null || summary.isBlank()) return;
        synchronized (historyLock) {
            conversationHistory.clear();
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content",
                    "This session was compacted. Below is a structured summary of our "
                            + "prior conversation. Treat it as authoritative context for "
                            + "continuing the work:\n\n" + summary);
            conversationHistory.add(userMsg);

            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content",
                    "Understood. I have the compacted summary and will continue from here.");
            conversationHistory.add(assistantMsg);
        }
    }

    public void replaceHistoryWithNativeCheckpoint(JsonNode nativePayload) {
        if (nativePayload == null || nativePayload.isNull()) return;
        synchronized (historyLock) {
            conversationHistory.clear();
            if (nativePayload.isArray()) {
                for (JsonNode item : nativePayload) {
                    if (item.isObject()) conversationHistory.add(((ObjectNode) item).deepCopy());
                }
            } else if (nativePayload.isObject()) {
                conversationHistory.add(((ObjectNode) nativePayload).deepCopy());
            }
        }
    }

    private String portableHistoryText() {
        StringBuilder text = new StringBuilder();
        for (ObjectNode message : conversationHistory) {
            String role = message.path("role").asText("context");
            JsonNode content = message.get("content");
            text.append('[').append(role).append("]\n");
            text.append(content != null && content.isTextual()
                    ? content.asText() : String.valueOf(content)).append("\n\n");
        }
        return text.toString().strip();
    }

    public int getHistorySize() {
        synchronized (historyLock) {
            return conversationHistory.size();
        }
    }

    /** Read-only diagnostic view of the effective connection/retry policy. */
    public ProviderConnectivityPolicy getConnectivityPolicy() {
        return connectivityPolicy;
    }

    /** Live chat configuration shared with the standard-chat REPL. */
    public ChatConfig getChatConfig() {
        return config;
    }

    /** The provider configured for this client. */
    public String getConfiguredProvider() {
        return config.getProvider();
    }

    /** The model configured for this client (before any per-agent override). */
    public String getConfiguredModel() {
        return config.getModel();
    }

    /** The resolved provider base URL this client sends requests to. */
    public String getResolvedBaseUrl() {
        return config.resolveBaseUrl();
    }

    /**
     * In-place mid-conversation pruning of the wire history: collapse the content of
     * old {@code tool} messages to short summaries and clip old assistant text, while
     * leaving message roles, ids, and {@code tool_calls} wiring untouched — so the
     * assistant/tool pairing the provider validates stays intact. Safe to call between
     * agentic steps, unlike a full summary rewrite.
     *
     * @param preserveRecentMessages number of newest messages left untouched
     * @param toolResultSummarizer   maps (toolName, content) → summary text
     * @return number of messages shrunk
     */
    public int compactToolHistory(int preserveRecentMessages,
                                  java.util.function.BinaryOperator<String> toolResultSummarizer) {
        synchronized (historyLock) {
            int lastPrunable = conversationHistory.size() - Math.max(0, preserveRecentMessages);
            int shrunk = 0;
            for (int i = 0; i < lastPrunable; i++) {
                ObjectNode msg = conversationHistory.get(i);
                String role = msg.path("role").asText("");
                String content = msg.path("content").asText("");
                if ("tool".equals(role) && content.length() > 600) {
                    String toolName = msg.path("name").asText(null);
                    msg.put("content", toolResultSummarizer.apply(toolName, content));
                    shrunk++;
                } else if ("assistant".equals(role) && content.length() > 2_000) {
                    msg.put("content", content.substring(0, 2_000)
                            + "\n... (truncated during compaction)");
                    shrunk++;
                }
            }
            return shrunk;
        }
    }

    private boolean usesGitHubAnthropicMessages(String model) {
        return "github-copilot".equals(config.getProvider())
                && model != null
                && model.startsWith("claude-")
                && !model.startsWith("claude-fable-");
    }

    private boolean usesGitHubOpenAiResponses(String model) {
        if (!"github-copilot".equals(config.getProvider()) || model == null) {
            return false;
        }
        return model.startsWith("gpt-5")
                || model.startsWith("grok-")
                || model.startsWith("mai-code-");
    }

    private void applyReasoningEffort(ObjectNode request, boolean responsesFormat) {
        String effort = ProviderThinkingConfig.wireValue(
                config.getProvider(), config.getThinking());
        if (effort == null || effort.isBlank()) {
            return;
        }
        if (responsesFormat) {
            ObjectNode reasoning = objectMapper.createObjectNode();
            reasoning.put("effort", effort);
            request.set("reasoning", reasoning);
        } else {
            request.put("reasoning_effort", effort);
        }
    }

    private void applyOpenAiFastMode(ObjectNode request, String model) {
        if (("openai".equals(config.getProvider()) || config.isOpenAiCodexFormat())
                && config.fastModeCapabilities().supports(model)) {
            // Codex's service_tier="fast" maps to "priority" on the wire.
            // Explicit default also overrides an OpenAI project's paid default.
            request.put("service_tier", config.useFastMode(model) ? "priority" : "default");
        }
    }

    private void applyResponsesJsonOutput(ObjectNode request, boolean codex) {
        JsonOutputSpec output = requestedJsonOutput;
        if (output == null) {
            return;
        }
        JsonNode existingText = request.get("text");
        ObjectNode text = existingText instanceof ObjectNode object
                ? object : objectMapper.createObjectNode();
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", output.name());
        format.put("strict", output.strict());
        format.set("schema", output.strict() && codex
                ? normalizeNativeCodexStrictSchema(output.schema()) : output.schema().deepCopy());
        text.set("format", format);
        request.set("text", text);
    }

    private void applyChatCompletionsJsonOutput(ObjectNode request) {
        JsonOutputSpec output = requestedJsonOutput;
        if (output == null || !"openai".equalsIgnoreCase(config.getProvider())) {
            return;
        }
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", output.name());
        jsonSchema.put("strict", output.strict());
        // Do not apply the native Codex compatibility reduction to generic OpenAI models.
        jsonSchema.set("schema", output.schema().deepCopy());
        request.set("response_format", responseFormat);
    }

    private static JsonNode normalizeNativeCodexStrictSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return schema == null ? null : schema.deepCopy();
        }
        return normalizeSchemaObject((ObjectNode) schema);
    }

    private static ObjectNode normalizeSchemaObject(ObjectNode schema) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        schema.fields().forEachRemaining(entry -> {
            String keyword = entry.getKey();
            if (NATIVE_CODEX_STRICT_UNSUPPORTED_KEYWORDS.contains(keyword)) return;
            JsonNode value = entry.getValue();
            if (SCHEMA_MAP_KEYWORDS.contains(keyword)) {
                result.set(keyword, normalizeSchemaMap(value));
            } else if (SCHEMA_ARRAY_KEYWORDS.contains(keyword)) {
                result.set(keyword, normalizeSchemaArray(value));
            } else if (SCHEMA_VALUE_KEYWORDS.contains(keyword)) {
                result.set(keyword, normalizeSchemaValue(value));
            } else {
                // required/type/const/enum/default/examples and extension payloads are data,
                // not schema objects. In particular, preserve property names and literal maps.
                result.set(keyword, value.deepCopy());
            }
        });
        return result;
    }

    private static JsonNode normalizeSchemaMap(JsonNode value) {
        if (!value.isObject()) return value.deepCopy();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        value.fields().forEachRemaining(entry -> {
            JsonNode child = entry.getValue();
            result.set(entry.getKey(), child.isObject()
                    ? normalizeSchemaObject((ObjectNode) child) : child.deepCopy());
        });
        return result;
    }

    private static JsonNode normalizeSchemaArray(JsonNode value) {
        if (!value.isArray()) return value.isObject()
                ? normalizeSchemaObject((ObjectNode) value) : value.deepCopy();
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (JsonNode child : value) {
            result.add(child.isObject()
                    ? normalizeSchemaObject((ObjectNode) child) : child.deepCopy());
        }
        return result;
    }

    private static JsonNode normalizeSchemaValue(JsonNode value) {
        if (value.isObject()) return normalizeSchemaObject((ObjectNode) value);
        return value.isArray() ? normalizeSchemaArray(value) : value.deepCopy();
    }

    private static String normalizeJsonSchemaName(String value) {
        String source = value == null || value.isBlank() ? "kompile_judge_verdict" : value.strip();
        String normalized = source.replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized.isBlank() ? "kompile_judge_verdict" : normalized;
    }

    private String activePromptCacheSessionId() {
        return config.promptCacheRetention()
                == ProviderPromptCacheCapabilities.Retention.NONE
                ? null : promptCacheSessionId;
    }

    private void applyAnthropicPromptCacheControl(ObjectNode request) {
        ProviderPromptCacheCapabilities capabilities = config.promptCacheCapabilities();
        ProviderPromptCacheCapabilities.Retention retention = config.promptCacheRetention();
        if (capabilities.activation()
                != ProviderPromptCacheCapabilities.Activation.EXPLICIT
                || retention == ProviderPromptCacheCapabilities.Retention.NONE) {
            return;
        }
        ObjectNode cacheControl = request.putObject("cache_control");
        cacheControl.put("type", "ephemeral");
        if (retention == ProviderPromptCacheCapabilities.Retention.LONG) {
            cacheControl.put("ttl", "1h");
        }
    }

    private void applyOpenAiPromptCacheControls(ObjectNode request, String model) {
        ProviderPromptCacheCapabilities capabilities = config.promptCacheCapabilities();
        if (capabilities.sessionAffinity()
                == ProviderPromptCacheCapabilities.SessionAffinity.OPENAI_PROMPT_CACHE_KEY) {
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) request.put("prompt_cache_key", cacheSessionId);
        }
        if (capabilities.retentionControl()
                == ProviderPromptCacheCapabilities.RetentionControl.OPENAI) {
            applyOpenAiRetentionControls(request, model, config.promptCacheRetention());
        }
    }

    private void applyOpenAiCompatiblePromptCacheControls(
            ObjectNode request, String model) {
        ProviderPromptCacheCapabilities capabilities = config.promptCacheCapabilities();
        ProviderPromptCacheCapabilities.Retention retention = config.promptCacheRetention();
        if (capabilities.sessionAffinity()
                == ProviderPromptCacheCapabilities.SessionAffinity.OPENAI_PROMPT_CACHE_KEY) {
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) request.put("prompt_cache_key", cacheSessionId);
        }
        if (capabilities.retentionControl()
                == ProviderPromptCacheCapabilities.RetentionControl.OPENAI) {
            applyOpenAiRetentionControls(request, model, retention);
        } else if (capabilities.retentionControl()
                == ProviderPromptCacheCapabilities.RetentionControl.OPENROUTER
                && retention != ProviderPromptCacheCapabilities.Retention.NONE
                && isOpenRouterAnthropicModel(model)) {
            ObjectNode cacheControl = request.putObject("cache_control");
            cacheControl.put("type", "ephemeral");
            if (retention == ProviderPromptCacheCapabilities.Retention.LONG) {
                cacheControl.put("ttl", "1h");
            }
        }
    }

    private static boolean isOpenRouterAnthropicModel(String model) {
        if (model == null) return false;
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("anthropic/")
                || normalized.startsWith("~anthropic/");
    }

    private static void applyOpenAiRetentionControls(
            ObjectNode request,
            String model,
            ProviderPromptCacheCapabilities.Retention retention) {
        boolean promptCacheOptions = usesOpenAiPromptCacheOptions(model);
        if (retention == ProviderPromptCacheCapabilities.Retention.NONE) {
            if (promptCacheOptions) {
                request.putObject("prompt_cache_options").put("mode", "explicit");
            }
            return;
        }
        if (promptCacheOptions) {
            ObjectNode options = request.putObject("prompt_cache_options");
            options.put("mode", "implicit");
            if (retention == ProviderPromptCacheCapabilities.Retention.LONG) {
                options.put("ttl", "30m");
            }
        } else {
            boolean useExtendedRetention = usesOpenAi24hOnlyRetention(model)
                    || (retention == ProviderPromptCacheCapabilities.Retention.LONG
                    && supportsOpenAi24hRetention(model));
            request.put("prompt_cache_retention",
                    useExtendedRetention ? "24h" : "in_memory");
        }
    }

    /** GPT-5.5 currently exposes only the extended retention value. */
    private static boolean usesOpenAi24hOnlyRetention(String model) {
        if (model == null) return false;
        return model.strip().toLowerCase(Locale.ROOT).startsWith("gpt-5.5");
    }

    /** Families documented by OpenAI as accepting prompt_cache_retention=24h. */
    private static boolean supportsOpenAi24hRetention(String model) {
        if (model == null) return false;
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("gpt-5.5")
                || normalized.startsWith("gpt-5.4")
                || normalized.startsWith("gpt-5.2")
                || normalized.startsWith("gpt-5.1")) {
            return true;
        }
        if (normalized.equals("gpt-5") || normalized.startsWith("gpt-5-")) {
            return true;
        }
        return normalized.equals("gpt-4.1") || normalized.startsWith("gpt-4.1-20");
    }

    /** GPT 5.6+ moved retention and disable controls under prompt_cache_options. */
    private static boolean usesOpenAiPromptCacheOptions(String model) {
        if (model == null) return false;
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("gpt-")) return false;
        int end = normalized.indexOf('-', 4);
        String version = end < 0 ? normalized.substring(4) : normalized.substring(4, end);
        String[] parts = version.split("\\.", 3);
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 5 || (major == 5 && minor >= 6);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    // ========================================================================
    // OpenAI Responses and ChatGPT Codex Responses
    // ========================================================================

    private StreamResult streamOpenAiResponses(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String effectiveModel,
            boolean codex,
            List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();
        ResponsesStreamState state = new ResponsesStreamState();

        try {
            ResponsesHistoryLinks historyLinks = sanitizeResponsesHistory(toolResults);
            List<ObjectNode> stagedToolResultItems =
                    prepareResponsesToolResultItems(toolResults, historyLinks);
            ArrayNode input = buildResponsesInput(
                    userMessage, systemPrompt, stagedToolResultItems, codex, attachments);
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.set("input", input);
            request.put("stream", true);
            request.put("store", false);
            applyReasoningEffort(request, true);
            applyOpenAiFastMode(request, effectiveModel);
            applyOpenAiPromptCacheControls(request, effectiveModel);
            String nativeRouteKey = "responses:" + effectiveModel;
            boolean nativeCompaction = compactionCapabilities(effectiveModel).nativeCompaction()
                    == ProviderCompactionCapabilities.NativeCompaction.OPENAI_RESPONSES
                    && nativeCompactionTriggerTokens > 0
                    && !unavailableNativeCompactionRoutes.contains(nativeRouteKey);
            if (nativeCompaction) {
                request.putArray("context_management").addObject()
                        .put("type", "compaction")
                        .put("compact_threshold", nativeCompactionTriggerTokens);
            }

            if (codex) {
                request.put("instructions", boundedOpenAiInstructions(systemPrompt));
                ObjectNode text = objectMapper.createObjectNode();
                text.put("verbosity", "low");
                request.set("text", text);
                ArrayNode include = objectMapper.createArrayNode();
                include.add("reasoning.encrypted_content");
                request.set("include", include);
                request.put("tool_choice", "auto");
                request.put("parallel_tool_calls", true);
            }
            applyResponsesJsonOutput(request, codex);

            if (toolDefs != null && !toolDefs.isEmpty()) {
                request.set("tools", convertToolDefsToResponses(toolDefs, codex));
            }

            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            String baseUrl = config.resolveBaseUrl(auth);
            String url = resolveResponsesUrl(baseUrl, codex);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "kompile")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .timeout(connectivityPolicy.requestTimeout());
            applyBearerAuthentication(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body)) return result;
                if (nativeCompaction && (response.statusCode() == 400
                        || response.statusCode() == 404 || response.statusCode() == 422)
                        && !isContextOverflowFailure(response.statusCode(), body)) {
                    unavailableNativeCompactionRoutes.add(nativeRouteKey);
                    return streamOpenAiResponses(
                            userMessage, systemPrompt, toolDefs, toolResults,
                            effectiveModel, codex, attachments, auth);
                }
                String label = codex ? "OpenAI Codex" : "OpenAI Responses";
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[" + label + " API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        label + " HTTP " + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, true);
                return result;
            }

            parseResponsesStream(guardResponseStream(response.body()), result, state);
            if (!result.cancelled && isGenerationStopped(result)) {
                // The input was accepted even though output generation was refused/truncated.
                conversationHistory.addAll(stagedToolResultItems);
            }
            if (!state.failed && !result.cancelled) {
                // Commit submitted tool results only after the provider accepts the
                // request. A rejected request must not poison every later turn.
                conversationHistory.addAll(stagedToolResultItems);
                appendResponsesHistory(userMessage, attachments, result, state);
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    /**
     * OpenAI validates the Responses {@code instructions} field independently of
     * model context compaction. Preserve the stable agent prefix and the most
     * specific project/tool tail while keeping every request within that hard API
     * boundary. The Java UTF-16 length is conservative for non-BMP code points.
     */
    static String boundedOpenAiInstructions(String systemPrompt) {
        String instructions = systemPrompt == null || systemPrompt.isBlank()
                ? "You are a helpful assistant."
                : systemPrompt;
        if (instructions.length() <= OPENAI_INSTRUCTIONS_MAX_CHARS) {
            return instructions;
        }

        int contentBudget = OPENAI_INSTRUCTIONS_MAX_CHARS
                - OPENAI_INSTRUCTIONS_OMISSION.length();
        int prefixEnd = safePrefixEnd(instructions, contentBudget * 3 / 5);
        int suffixStart = safeSuffixStart(
                instructions, instructions.length() - (contentBudget - prefixEnd));
        return instructions.substring(0, prefixEnd)
                + OPENAI_INSTRUCTIONS_OMISSION
                + instructions.substring(suffixStart);
    }

    private static int safePrefixEnd(String value, int end) {
        int bounded = Math.max(0, Math.min(end, value.length()));
        if (bounded > 0 && bounded < value.length()
                && Character.isHighSurrogate(value.charAt(bounded - 1))
                && Character.isLowSurrogate(value.charAt(bounded))) {
            bounded--;
        }
        return bounded;
    }

    private static int safeSuffixStart(String value, int start) {
        int bounded = Math.max(0, Math.min(start, value.length()));
        if (bounded > 0 && bounded < value.length()
                && Character.isHighSurrogate(value.charAt(bounded - 1))
                && Character.isLowSurrogate(value.charAt(bounded))) {
            bounded++;
        }
        return bounded;
    }

    private ArrayNode buildResponsesInput(
            String userMessage,
            String systemPrompt,
            List<ObjectNode> stagedToolResultItems,
            boolean codex) {
        return buildResponsesInput(
                userMessage, systemPrompt, stagedToolResultItems, codex, List.of());
    }

    private ArrayNode buildResponsesInput(
            String userMessage,
            String systemPrompt,
            List<ObjectNode> stagedToolResultItems,
            boolean codex,
            List<AttachmentInput> attachments) {
        ArrayNode input = objectMapper.createArrayNode();
        if (!codex && systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", "developer");
            system.put("content", systemPrompt);
            input.add(system);
        }
        conversationHistory.forEach(input::add);
        if (stagedToolResultItems != null) {
            stagedToolResultItems.forEach(input::add);
        }
        if (userMessage != null || !attachments.isEmpty()) {
            input.add(createResponsesUserMessage(userMessage, attachments));
        }
        return input;
    }

    /**
     * Remove malformed/duplicate Responses linkage left by an interrupted older
     * client. Function outputs are valid only when the matching function call is
     * present earlier in the same retained request history. A function call with
     * neither a retained output nor a currently pending executor result is also
     * incomplete and must not be sent on the next request.
     */
    private ResponsesHistoryLinks sanitizeResponsesHistory(
            List<ToolCallResultInput> pendingToolResults) {
        Set<String> pendingCalls = new LinkedHashSet<>();
        if (pendingToolResults != null) {
            for (ToolCallResultInput toolResult : pendingToolResults) {
                if (toolResult != null && toolResult.callId != null
                        && !toolResult.callId.isBlank()) {
                    pendingCalls.add(toolResult.callId.trim());
                }
            }
        }

        Set<String> calls = new LinkedHashSet<>();
        Set<String> outputs = new LinkedHashSet<>();
        List<ObjectNode> sanitized = new ArrayList<>(conversationHistory.size());
        for (ObjectNode item : conversationHistory) {
            String type = item.path("type").asText("");
            if ("function_call".equals(type)) {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank() || !calls.add(callId)) {
                    continue;
                }
            } else if ("function_call_output".equals(type)) {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank() || !calls.contains(callId) || !outputs.add(callId)) {
                    continue;
                }
            }
            sanitized.add(item);
        }

        List<ObjectNode> complete = new ArrayList<>(sanitized.size());
        Set<String> retainedCalls = new LinkedHashSet<>();
        for (ObjectNode item : sanitized) {
            if ("function_call".equals(item.path("type").asText(""))) {
                String callId = item.path("call_id").asText("");
                if (!outputs.contains(callId) && !pendingCalls.contains(callId)) {
                    continue;
                }
                retainedCalls.add(callId);
            }
            complete.add(item);
        }

        if (complete.size() != conversationHistory.size()) {
            conversationHistory.clear();
            conversationHistory.addAll(complete);
        }
        return new ResponsesHistoryLinks(retainedCalls, outputs);
    }

    /**
     * Stage current tool results without mutating retained history. When a full
     * compaction or resume lost the provider-owned function-call item, preserve
     * the useful result as ordinary user context instead of emitting an orphaned
     * function_call_output that OpenAI rejects with HTTP 400.
     */
    private List<ObjectNode> prepareResponsesToolResultItems(
            List<ToolCallResultInput> toolResults,
            ResponsesHistoryLinks historyLinks) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        Set<String> submittedOutputs = new LinkedHashSet<>(historyLinks.outputs());
        List<ObjectNode> staged = new ArrayList<>();
        for (ToolCallResultInput toolResult : toolResults) {
            String callId = toolResult.callId == null ? "" : toolResult.callId.trim();
            if (!callId.isBlank() && historyLinks.calls().contains(callId)) {
                if (!submittedOutputs.add(callId)) {
                    continue;
                }
                ObjectNode output = objectMapper.createObjectNode();
                output.put("type", "function_call_output");
                output.put("call_id", callId);
                output.put("output", toolResult.output == null ? "" : toolResult.output);
                staged.add(output);
                continue;
            }

            String toolName = toolResult.name == null || toolResult.name.isBlank()
                    ? "unknown tool" : toolResult.name;
            StringBuilder recovered = new StringBuilder()
                    .append("[Recovered tool result for ").append(toolName)
                    .append("; the provider function-call link was unavailable after compaction/resume]");
            if (toolResult.isError) {
                recovered.append(" [tool reported an error]");
            }
            if (toolResult.output != null && !toolResult.output.isBlank()) {
                recovered.append('\n').append(toolResult.output);
            }
            staged.add(createResponsesUserMessage(recovered.toString()));
        }
        return staged;
    }

    private record ResponsesHistoryLinks(Set<String> calls, Set<String> outputs) {}

    private ObjectNode createResponsesUserMessage(String text) {
        return createResponsesUserMessage(text, List.of());
    }

    private ObjectNode createResponsesUserMessage(
            String text, List<AttachmentInput> attachments) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.set("content", buildResponsesContentArray(text, attachments));
        return message;
    }

    private ArrayNode convertToolDefsToResponses(ArrayNode toolDefs, boolean codex) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode responseTool = objectMapper.createObjectNode();
            responseTool.put("type", "function");
            responseTool.put("name", tool.path("name").asText());
            responseTool.put("description", tool.path("description").asText());
            JsonNode parameters = tool.path("inputSchema");
            if (parameters == null || parameters.isMissingNode()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", objectMapper.createObjectNode());
                parameters = empty;
            }
            responseTool.set("parameters", parameters);
            if (codex) {
                responseTool.putNull("strict");
            }
            tools.add(responseTool);
        }
        return tools;
    }

    private void parseResponsesStream(
            java.io.InputStream inputStream,
            StreamResult result,
            ResponsesStreamState state) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    return;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("type").asText("");
                int outputIndex = event.path("output_index").asInt(0);
                switch (type) {
                    case "response.output_item.added" -> {
                        JsonNode item = event.path("item");
                        result.refusalDetected |= responsesContainRefusal(item.path("content"));
                        captureResponsesCompaction(result, item);
                        updateResponsesReasoningItem(state, outputIndex, item);
                        updateResponsesToolAccumulator(state, outputIndex, item, false);
                    }
                    case "response.output_text.delta", "response.refusal.delta" -> {
                        if ("response.refusal.delta".equals(type)) result.refusalDetected = true;
                        String delta = event.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            printStreamingChunk(delta);
                            result.text += delta;
                        }
                    }
                    case "response.function_call_arguments.delta" -> {
                        ResponsesToolCallAccumulator accumulator =
                                state.toolCalls.computeIfAbsent(
                                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.arguments.append(event.path("delta").asText(""));
                    }
                    case "response.function_call_arguments.done" -> {
                        ResponsesToolCallAccumulator accumulator =
                                state.toolCalls.computeIfAbsent(
                                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
                        String arguments = event.path("arguments").asText(null);
                        if (arguments != null) {
                            accumulator.arguments.setLength(0);
                            accumulator.arguments.append(arguments);
                        }
                    }
                    case "response.output_item.done" -> {
                        JsonNode item = event.path("item");
                        result.refusalDetected |= responsesContainRefusal(item.path("content"));
                        captureResponsesCompaction(result, item);
                        updateResponsesReasoningItem(state, outputIndex, item);
                        updateResponsesToolAccumulator(state, outputIndex, item, true);
                        if ("message".equals(item.path("type").asText()) && result.text.isEmpty()) {
                            String completedText = responsesMessageText(item);
                            if (!completedText.isEmpty()) {
                                printStreamingChunk(completedText);
                                result.text = completedText;
                            }
                        }
                    }
                    case "response.completed", "response.incomplete" -> {
                        state.terminal = true;
                        JsonNode response = event.path("response");
                        for (JsonNode item : response.path("output")) {
                            result.refusalDetected |= responsesContainRefusal(item.path("content"));
                        }
                        if ("response.incomplete".equals(type)) {
                            String reason = response.path("incomplete_details").path("reason").asText("");
                            result.refusalDetected |= "content_filter".equals(reason);
                            result.truncatedDetected = !result.refusalDetected;
                        }
                        captureResponsesCompactionFromOutput(result, response.path("output"));
                        backfillResponsesReasoning(state, response.path("output"));
                        readResponsesUsage(response.path("usage"), result);
                    }
                    case "response.failed" -> {
                        state.terminal = true;
                        state.failed = true;
                        JsonNode error = event.path("response").path("error");
                        String message = error.path("message").asText("Response failed");
                        appendProtocolError(result, message, error.toString());
                    }
                    case "error" -> {
                        state.failed = true;
                        JsonNode error = event.path("error");
                        appendProtocolError(
                                result,
                                event.path("message").asText(
                                        error.path("message").asText("Unknown response error")),
                                event.toString());
                    }
                    default -> {
                        // Other Responses events carry reasoning/status metadata.
                    }
                }
                // A terminal Responses event completes the request; the server need not
                // close its transport before we deliver usage and accumulated tool calls.
                if (state.terminal) {
                    break;
                }
            }
        }

        if (finishGenerationOutcome(result)) {
            state.failed = true;
            return;
        }
        for (ResponsesToolCallAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            if (accumulator.callId == null || accumulator.callId.isBlank()) {
                accumulator.callId = "call_" + result.toolCalls.size();
            }
            if (accumulator.itemId == null || accumulator.itemId.isBlank()) {
                accumulator.itemId = "fc_" + UUID.randomUUID().toString().replace("-", "");
            }
            ToolCallOutput toolCall = new ToolCallOutput();
            toolCall.id = accumulator.callId;
            toolCall.name = accumulator.name;
            toolCall.arguments = parseToolArguments(accumulator.arguments.toString());
            result.toolCalls.add(toolCall);
        }
        if (!state.terminal && !state.failed && !result.cancelled) {
            state.failed = true;
            appendProtocolError(result, "Responses stream ended without a terminal event");
        }
    }

    private void captureResponsesCompaction(StreamResult result, JsonNode item) {
        if (item != null && "compaction".equals(item.path("type").asText())
                && item.isObject()) {
            result.nativeCompactionPayload = item.deepCopy();
            result.nativeCompactionStrategy = "openai-responses";
        }
    }

    private void captureResponsesCompactionFromOutput(StreamResult result, JsonNode output) {
        if (output == null || !output.isArray()) return;
        for (JsonNode item : output) captureResponsesCompaction(result, item);
    }

    private void updateResponsesReasoningItem(
            ResponsesStreamState state,
            int outputIndex,
            JsonNode item) {
        if ("reasoning".equals(item.path("type").asText()) && item.isObject()) {
            state.reasoningItems.put(outputIndex, ((ObjectNode) item).deepCopy());
        }
    }

    private void backfillResponsesReasoning(ResponsesStreamState state, JsonNode output) {
        if (!output.isArray()) {
            return;
        }
        for (int index = 0; index < output.size(); index++) {
            JsonNode item = output.get(index);
            if ("reasoning".equals(item.path("type").asText()) && item.isObject()) {
                state.reasoningItems.put(index, ((ObjectNode) item).deepCopy());
            }
        }
    }

    private void updateResponsesToolAccumulator(
            ResponsesStreamState state,
            int outputIndex,
            JsonNode item,
            boolean finalItem) {
        if (!"function_call".equals(item.path("type").asText())) {
            return;
        }
        ResponsesToolCallAccumulator accumulator =
                state.toolCalls.computeIfAbsent(
                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
        String value = item.path("id").asText(null);
        if (value != null) accumulator.itemId = value;
        value = item.path("call_id").asText(null);
        if (value != null) accumulator.callId = value;
        value = item.path("name").asText(null);
        if (value != null) accumulator.name = value;
        value = item.path("arguments").asText(null);
        if (value != null && (finalItem || accumulator.arguments.isEmpty())) {
            accumulator.arguments.setLength(0);
            accumulator.arguments.append(value);
        }
    }

    private String responsesMessageText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : item.path("content")) {
            if ("output_text".equals(content.path("type").asText())) {
                text.append(content.path("text").asText(""));
            } else if ("refusal".equals(content.path("type").asText())) {
                text.append(content.path("refusal").asText(""));
            }
        }
        return text.toString();
    }

    private void readResponsesUsage(JsonNode usage, StreamResult result) {
        if (usage == null || usage.isMissingNode()) {
            return;
        }
        long cacheRead = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        long cacheWrite = usage.path("input_tokens_details").path("cache_write_tokens").asLong(0);
        long totalInput = usage.path("input_tokens").asLong(0);
        result.inputTokens = Math.max(0, totalInput - cacheRead - cacheWrite);
        result.outputTokens = usage.path("output_tokens").asLong(0);
        result.cacheReadTokens = cacheRead;
        result.cacheCreationTokens = cacheWrite;
    }

    static void readOpenAiCompatibleUsage(JsonNode usage, StreamResult result) {
        if (usage == null || usage.isMissingNode()) return;
        JsonNode details = usage.path("prompt_tokens_details");
        long cacheRead = firstPresentLong(
                details, "cached_tokens",
                usage, "prompt_cache_hit_tokens",
                usage, "cached_tokens");
        long cacheWrite = details.path("cache_write_tokens").asLong(0L);
        long ordinaryInput;
        if (usage.has("prompt_cache_miss_tokens")) {
            ordinaryInput = usage.path("prompt_cache_miss_tokens").asLong(0L);
        } else {
            long totalInput = usage.has("prompt_tokens")
                    ? usage.path("prompt_tokens").asLong(0L)
                    : usage.path("input_tokens").asLong(0L);
            ordinaryInput = Math.max(0L, totalInput - cacheRead - cacheWrite);
        }
        result.inputTokens = ordinaryInput;
        result.outputTokens = usage.has("completion_tokens")
                ? usage.path("completion_tokens").asLong(0L)
                : usage.path("output_tokens").asLong(0L);
        result.cacheReadTokens = cacheRead;
        result.cacheCreationTokens = cacheWrite;
    }

    private static long firstPresentLong(
            JsonNode first, String firstField,
            JsonNode second, String secondField,
            JsonNode third, String thirdField) {
        if (first != null && first.has(firstField)) return first.path(firstField).asLong(0L);
        if (second != null && second.has(secondField)) return second.path(secondField).asLong(0L);
        return third != null && third.has(thirdField)
                ? third.path(thirdField).asLong(0L) : 0L;
    }

    private void appendResponsesHistory(
            String userMessage,
            List<AttachmentInput> attachments,
            StreamResult result,
            ResponsesStreamState state) {
        if (result.nativeCompactionPayload != null) {
            // The native item replaces every request item that preceded it.
            conversationHistory.clear();
        } else if (userMessage != null || !attachments.isEmpty()) {
            conversationHistory.add(createResponsesUserMessage(userMessage, attachments));
        }
        if (result.nativeCompactionPayload != null && result.nativeCompactionPayload.isObject()) {
            conversationHistory.add(((ObjectNode) result.nativeCompactionPayload).deepCopy());
        }
        state.reasoningItems.values().forEach(conversationHistory::add);
        if (!result.text.isEmpty()) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("status", "completed");
            message.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "output_text");
            text.put("text", result.text);
            text.set("annotations", objectMapper.createArrayNode());
            content.add(text);
            message.set("content", content);
            conversationHistory.add(message);
        }
        for (ResponsesToolCallAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call");
            item.put("id", accumulator.itemId);
            item.put("call_id", accumulator.callId);
            item.put("name", accumulator.name);
            item.put("arguments", accumulator.arguments.isEmpty()
                    ? "{}" : accumulator.arguments.toString());
            item.put("status", "completed");
            conversationHistory.add(item);
        }
    }

    private String resolveResponsesUrl(String baseUrl, boolean codex) {
        String normalized = trimTrailingSlashes(baseUrl);
        if (!codex) {
            return normalized.endsWith("/responses") ? normalized : normalized + "/responses";
        }
        if (normalized.endsWith("/codex/responses")) return normalized;
        if (normalized.endsWith("/codex")) return normalized + "/responses";
        return normalized + "/codex/responses";
    }

    // ========================================================================
    // Radius / Pi Messages
    // ========================================================================

    private StreamResult streamPiMessages(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String effectiveModel, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();
        try {
            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            String gateway = config.resolveBaseUrl(auth);
            RadiusGatewayConfig gatewayConfig = loadRadiusGatewayConfig(gateway, auth);
            if (!gatewayConfig.modelIds().isEmpty()
                    && !gatewayConfig.modelIds().contains(effectiveModel)) {
                result.text = "[Radius model is not present in the gateway catalog: "
                        + effectiveModel + "]";
                printStreamingChunk(result.text);
                return result;
            }

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            ObjectNode context = objectMapper.createObjectNode();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                context.put("systemPrompt", systemPrompt);
            }
            context.set("messages", buildPiMessages(userMessage, toolResults));
            if (toolDefs != null && !toolDefs.isEmpty()) {
                context.set("tools", convertToolDefsToPi(toolDefs));
            }
            request.set("context", context);
            ObjectNode options = objectMapper.createObjectNode();
            options.put("maxTokens", 8192);
            options.put("cacheRetention", config.promptCacheRetention().wireValue());
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) {
                options.put("sessionId", cacheSessionId);
            }
            request.set("options", options);

            String url = appendPath(gatewayConfig.baseUrl(), "/messages");
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .timeout(connectivityPolicy.requestTimeout());
            applyBearerAuthentication(requestBuilder, auth);

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body)) return result;
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[Radius API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        "Radius HTTP " + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, true);
                return result;
            }

            PiMessagesStreamState state = new PiMessagesStreamState();
            parsePiMessagesStream(guardResponseStream(response.body()), result, state);
            if (!state.failed && !result.cancelled) {
                appendPiToolResultHistory(toolResults);
                appendPiMessagesHistory(userMessage, effectiveModel, result, state);
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    private RadiusGatewayConfig loadRadiusGatewayConfig(
            String gateway,
            OAuthProviderFlow.RequestAuth auth) throws Exception {
        String normalizedGateway = trimTrailingSlashes(gateway);
        RadiusGatewayConfig cached = radiusGatewayConfig;
        if (cached != null && normalizedGateway.equals(radiusGatewayConfigSource)) {
            return cached;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(appendPath(normalizedGateway, "/v1/config")))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30));
        applyBearerAuthentication(builder, auth);
        HttpResponse<java.io.InputStream> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401) {
            throw new UnauthorizedRequestException(auth,
                    ProviderResponseFailure.classify(401, ProviderResponseFailure.authenticationBody(response.body())));
        }
        String body = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Could not load Radius config (HTTP "
                    + response.statusCode() + "): " + extractErrorMessage(body));
        }
        JsonNode root = objectMapper.readTree(body);
        String messagesBaseUrl = root.path("baseUrl").asText(null);
        if (messagesBaseUrl == null || messagesBaseUrl.isBlank()) {
            throw new IllegalStateException("Invalid Radius config: missing baseUrl");
        }
        List<String> modelIds = new ArrayList<>();
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("Invalid Radius config: models must be an array");
        }
        for (JsonNode model : models) {
            String id = model.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                modelIds.add(id);
            }
        }
        RadiusGatewayConfig loaded = new RadiusGatewayConfig(
                trimTrailingSlashes(messagesBaseUrl), List.copyOf(modelIds));
        radiusGatewayConfigSource = normalizedGateway;
        radiusGatewayConfig = loaded;
        return loaded;
    }

    private ArrayNode buildPiMessages(
            String userMessage,
            List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();
        conversationHistory.forEach(messages::add);
        if (toolResults != null) {
            for (ToolCallResultInput toolResult : toolResults) {
                ObjectNode message = objectMapper.createObjectNode();
                message.put("role", "toolResult");
                message.put("toolCallId", toolResult.callId);
                message.put("toolName", toolResult.name == null ? "" : toolResult.name);
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode text = objectMapper.createObjectNode();
                text.put("type", "text");
                text.put("text", toolResult.output == null ? "" : toolResult.output);
                content.add(text);
                message.set("content", content);
                message.put("isError", toolResult.isError);
                message.put("timestamp", System.currentTimeMillis());
                messages.add(message);
            }
        }
        if (userMessage != null) {
            messages.add(createPiUserMessage(userMessage));
        }
        return messages;
    }

    private void appendPiToolResultHistory(List<ToolCallResultInput> toolResults) {
        if (toolResults == null) return;
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "toolResult");
            message.put("toolCallId", toolResult.callId);
            message.put("toolName", toolResult.name == null ? "" : toolResult.name);
            ArrayNode content = message.putArray("content");
            content.addObject().put("type", "text")
                    .put("text", toolResult.output == null ? "" : toolResult.output);
            message.put("isError", toolResult.isError);
            message.put("timestamp", System.currentTimeMillis());
            conversationHistory.add(message);
        }
    }

    private ObjectNode createPiUserMessage(String userMessage) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", userMessage);
        message.put("timestamp", System.currentTimeMillis());
        return message;
    }

    private ArrayNode convertToolDefsToPi(ArrayNode toolDefs) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode piTool = objectMapper.createObjectNode();
            piTool.put("name", tool.path("name").asText());
            piTool.put("description", tool.path("description").asText());
            JsonNode parameters = tool.path("inputSchema");
            if (parameters == null || parameters.isMissingNode()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", objectMapper.createObjectNode());
                parameters = empty;
            }
            piTool.set("parameters", parameters);
            tools.add(piTool);
        }
        return tools;
    }

    private void parsePiMessagesStream(
            java.io.InputStream inputStream,
            StreamResult result,
            PiMessagesStreamState state) throws Exception {
        Map<Integer, ResponsesToolCallAccumulator> accumulators = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    return;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("type").asText("");
                int contentIndex = event.path("contentIndex").asInt(0);
                switch (type) {
                    case "text_start" ->
                            piContentBlock(state, contentIndex, "text", "text");
                    case "text_delta" -> {
                        String delta = event.path("delta").asText("");
                        ObjectNode block = piContentBlock(state, contentIndex, "text", "text");
                        block.put("text", block.path("text").asText("") + delta);
                        if (!delta.isEmpty()) {
                            printStreamingChunk(delta);
                            result.text += delta;
                        }
                    }
                    case "text_end" -> {
                        ObjectNode block = piContentBlock(state, contentIndex, "text", "text");
                        String content = event.path("content").asText(block.path("text").asText(""));
                        block.put("text", content);
                        String signature = event.path("contentSignature").asText(null);
                        if (signature != null) block.put("textSignature", signature);
                        if (result.text.isEmpty() && !content.isEmpty()) {
                            printStreamingChunk(content);
                            result.text = content;
                        }
                    }
                    case "thinking_start" ->
                            piContentBlock(state, contentIndex, "thinking", "thinking");
                    case "thinking_delta" -> {
                        String delta = event.path("delta").asText("");
                        ObjectNode block = piContentBlock(state, contentIndex, "thinking", "thinking");
                        block.put("thinking", block.path("thinking").asText("") + delta);
                    }
                    case "thinking_end" -> {
                        ObjectNode block = piContentBlock(state, contentIndex, "thinking", "thinking");
                        block.put("thinking",
                                event.path("content").asText(block.path("thinking").asText("")));
                        String signature = event.path("contentSignature").asText(null);
                        if (signature != null) block.put("thinkingSignature", signature);
                        if (event.has("redacted")) block.put("redacted", event.path("redacted").asBoolean());
                    }
                    case "toolcall_start" -> {
                        ResponsesToolCallAccumulator accumulator =
                                accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.callId = event.path("id").asText(null);
                        accumulator.name = event.path("toolName").asText(null);
                    }
                    case "toolcall_delta" ->
                            accumulators.computeIfAbsent(
                                            contentIndex, ignored -> new ResponsesToolCallAccumulator())
                                    .arguments.append(event.path("delta").asText(""));
                    case "toolcall_end" -> {
                        JsonNode toolCall = event.path("toolCall");
                        ResponsesToolCallAccumulator accumulator =
                                accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.callId = toolCall.path("id").asText(accumulator.callId);
                        accumulator.name = toolCall.path("name").asText(accumulator.name);
                        JsonNode arguments = toolCall.path("arguments");
                        if (!arguments.isMissingNode()) {
                            accumulator.arguments.setLength(0);
                            accumulator.arguments.append(objectMapper.writeValueAsString(arguments));
                        }
                        if (toolCall.isObject()) {
                            ObjectNode block = toolCall.deepCopy();
                            block.put("type", "toolCall");
                            state.contentBlocks.put(contentIndex, block);
                        }
                    }
                    case "done" -> {
                        state.terminal = true;
                        readPiUsage(event.path("usage"), result);
                    }
                    case "error" -> {
                        state.terminal = true;
                        state.failed = true;
                        readPiUsage(event.path("usage"), result);
                        appendProtocolError(
                                result, event.path("errorMessage").asText("Radius request failed"));
                    }
                    default -> {
                        // The start event carries no content.
                    }
                }
            }
        }

        for (Map.Entry<Integer, ResponsesToolCallAccumulator> entry : accumulators.entrySet()) {
            ResponsesToolCallAccumulator accumulator = entry.getValue();
            if (accumulator.name == null || accumulator.name.isBlank()) continue;
            ToolCallOutput toolCall = new ToolCallOutput();
            toolCall.id = accumulator.callId == null || accumulator.callId.isBlank()
                    ? "call_" + result.toolCalls.size()
                    : accumulator.callId;
            toolCall.name = accumulator.name;
            toolCall.arguments = parseToolArguments(accumulator.arguments.toString());
            result.toolCalls.add(toolCall);
            state.contentBlocks.computeIfAbsent(entry.getKey(), ignored -> {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "toolCall");
                block.put("id", toolCall.id);
                block.put("name", toolCall.name);
                block.set("arguments", toolCall.arguments);
                return block;
            });
        }
        if (!state.terminal && !state.failed && !result.cancelled) {
            state.failed = true;
            appendProtocolError(result, "Radius stream ended without a terminal event");
        }
    }

    private ObjectNode piContentBlock(
            PiMessagesStreamState state,
            int contentIndex,
            String type,
            String valueField) {
        return state.contentBlocks.computeIfAbsent(contentIndex, ignored -> {
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", type);
            block.put(valueField, "");
            return block;
        });
    }

    private void readPiUsage(JsonNode usage, StreamResult result) {
        if (usage == null || usage.isMissingNode()) return;
        result.inputTokens = usage.path("input").asLong(0);
        result.outputTokens = usage.path("output").asLong(0);
        result.cacheReadTokens = usage.path("cacheRead").asLong(0);
        result.cacheCreationTokens = usage.path("cacheWrite").asLong(0);
    }

    private void appendPiMessagesHistory(
            String userMessage,
            String effectiveModel,
            StreamResult result,
            PiMessagesStreamState state) {
        if (userMessage != null) {
            conversationHistory.add(createPiUserMessage(userMessage));
        }
        if (result.text.isEmpty() && result.toolCalls.isEmpty() && state.contentBlocks.isEmpty()) return;

        ObjectNode assistant = objectMapper.createObjectNode();
        assistant.put("role", "assistant");
        ArrayNode content = objectMapper.createArrayNode();
        state.contentBlocks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.add(entry.getValue()));
        boolean hasText = state.contentBlocks.values().stream()
                .anyMatch(block -> "text".equals(block.path("type").asText()));
        boolean hasToolCall = state.contentBlocks.values().stream()
                .anyMatch(block -> "toolCall".equals(block.path("type").asText()));
        if (!hasText && !result.text.isEmpty()) {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", result.text);
            content.add(text);
        }
        if (!hasToolCall) {
            for (ToolCallOutput toolCall : result.toolCalls) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("type", "toolCall");
                call.put("id", toolCall.id);
                call.put("name", toolCall.name);
                call.set("arguments", toolCall.arguments);
                content.add(call);
            }
        }
        assistant.set("content", content);
        assistant.put("api", "pi-messages");
        assistant.put("provider", config.getProvider());
        assistant.put("model", effectiveModel);
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input", result.inputTokens);
        usage.put("output", result.outputTokens);
        usage.put("cacheRead", result.cacheReadTokens);
        usage.put("cacheWrite", result.cacheCreationTokens);
        usage.put("totalTokens", result.inputTokens + result.outputTokens
                + result.cacheReadTokens + result.cacheCreationTokens);
        ObjectNode cost = objectMapper.createObjectNode();
        cost.put("input", 0);
        cost.put("output", 0);
        cost.put("cacheRead", 0);
        cost.put("cacheWrite", 0);
        cost.put("total", 0);
        usage.set("cost", cost);
        assistant.set("usage", usage);
        assistant.put("stopReason", result.toolCalls.isEmpty() ? "stop" : "toolUse");
        assistant.put("timestamp", System.currentTimeMillis());
        conversationHistory.add(assistant);
    }

    private void appendProtocolError(StreamResult result, String message) {
        appendProtocolError(result, message, message);
    }

    private void appendProtocolError(
            StreamResult result, String message, String classificationDetail) {
        if (recordKnownProviderFailure(result, 0, classificationDetail)) return;
        result.failed = true;
        String formatted = "[Error: " + message + "]";
        if (isContextOverflowFailure(0, classificationDetail)) {
            appendProviderFailure(result, 0, classificationDetail, formatted, true);
            return;
        }
        // Typed error envelopes can carry the real signal in their type/code rather
        // than the human message, so classify against both.
        String classificationText = classificationDetail == null || classificationDetail.isBlank()
                ? message : message + " " + classificationDetail;
        if (connectivityPolicy.isRetryableFailure(new IOException(classificationText))) {
            result.retryableConnectivityFailure = true;
            result.connectivityFailure = message;
            result.connectivityFinalMessage = formatted;
            return;
        }
        appendProviderFailure(result, 0, classificationDetail, formatted, true);
    }

    private JsonNode parseToolArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String appendPath(String baseUrl, String path) {
        String normalized = trimTrailingSlashes(baseUrl);
        return normalized.endsWith(path) ? normalized : normalized + path;
    }

    private String trimTrailingSlashes(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider base URL is missing");
        }
        return value.trim().replaceAll("/+$", "");
    }

    // ========================================================================
    // Kompile first-party serving subprocess
    // ========================================================================

    /**
     * Send canonical structured chat requests to the private loopback endpoint
     * owned by the first-party {@code ServingSubprocessMain} child.
     */
    private StreamResult streamKompileServing(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults) {
        StreamResult result = new StreamResult();
        try {
            if (isCancelled()) {
                result.cancelled = true;
                return result;
            }
            ObjectNode request = objectMapper.createObjectNode();
            ObjectNode structured = request.putObject("request");
            structured.set("messages", buildKompileServingMessages(
                    userMessage, systemPrompt, toolResults));
            ArrayNode tools = buildKompileServingTools(toolDefs);
            structured.set("tools", tools);
            structured.put("addGenerationPrompt", true);
            structured.put("toolDefinitionFormat", "STANDARD");
            structured.put("toolCallFormat", "NATIVE");
            structured.put("toolChoice", tools.isEmpty() ? "NONE" : "AUTO");
            request.put("maxTokens", 1024);

            HttpResponse<String> response = sendKompileServing(request);

            if (response.statusCode() != 200) {
                String responseBody = response.body();
                String error = extractErrorMessage(responseBody)
                        + ProviderResponseFailure.diagnostics(responseBody, response.headers());
                String detail = "Kompile serving HTTP " + response.statusCode() + ": " + error;
                if (isContextOverflowFailure(response.statusCode(), responseBody)) {
                    appendProviderFailure(
                            result, response.statusCode(), responseBody,
                            "[Error: " + detail + "]", true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(), detail,
                        "[Error: " + detail + "]")) {
                    return result;
                }
                appendProtocolError(result, detail);
                return result;
            }

            JsonNode payload = objectMapper.readTree(response.body());
            String finishReason = payload.path("finishReason").asText("");
            if (finishReason.startsWith("error")) {
                appendProtocolError(result, finishReason);
                return result;
            }

            String rawText = payload.path("rawText").asText("");
            result.text = payload.path("content").asText("");
            JsonNode encodedCalls = payload.path("toolCalls");
            if (encodedCalls.isArray()) {
                for (JsonNode call : encodedCalls) {
                    String name = call.path("name").asText("");
                    if (name.isBlank()) continue;
                    ToolCallOutput output = new ToolCallOutput();
                    output.id = call.path("id").asText("");
                    if (output.id.isBlank()) {
                        output.id = "call_" + result.toolCalls.size();
                    }
                    output.name = name;
                    JsonNode arguments = call.path("arguments");
                    output.arguments = arguments.isObject()
                            ? arguments.deepCopy() : objectMapper.createObjectNode();
                    result.toolCalls.add(output);
                }
            }

            if (result.text.isBlank() && result.toolCalls.isEmpty() && !rawText.isBlank()) {
                result.text = rawText;
            }
            // Rescue last: only fires when the serving child returned no native
            // tool calls and the text echoed the replayed "[Tool call ...]" shape.
            result.toolCalls.addAll(rescueTextEncodedToolCalls(result, toolDefs));
            if (!result.text.isEmpty() && !isCancelled()) {
                printStreamingChunk(result.text);
            } else if (isCancelled()) {
                result.cancelled = true;
            }

            if (!result.cancelled && !result.failed) {
                appendKompileToolResultHistory(toolResults);
                if (userMessage != null) {
                    ObjectNode user = objectMapper.createObjectNode();
                    user.put("role", "user");
                    user.put("content", userMessage);
                    conversationHistory.add(user);
                }
                boolean rescued = !result.toolCalls.isEmpty()
                        && !payload.path("content").asText("").equals(result.text);
                if (!rawText.isBlank() || !result.text.isBlank() || !result.toolCalls.isEmpty()) {
                    ObjectNode assistant = objectMapper.createObjectNode();
                    assistant.put("role", "assistant");
                    // After a rescue, record the stripped text so the echoed
                    // "[Tool call ...]" shape is not replayed back to the model.
                    assistant.put("content", rescued || rawText.isBlank()
                            ? result.text : rawText);
                    if (!result.toolCalls.isEmpty()) {
                        ArrayNode calls = assistant.putArray("tool_calls");
                        for (ToolCallOutput call : result.toolCalls) {
                            ObjectNode encoded = calls.addObject();
                            encoded.put("id", call.id);
                            encoded.put("name", call.name);
                            encoded.set("arguments", call.arguments);
                        }
                    }
                    conversationHistory.add(assistant);
                }
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Kompile serving error: ");
            if (!result.retryableConnectivityFailure && !result.cancelled) {
                printStreamingChunk(result.text);
            }
        }
        return result;
    }

    private HttpResponse<String> sendKompileServing(ObjectNode request) throws Exception {
        LocalServingRuntimePool.Binding binding = config.getLocalServingBinding();
        if (binding == null) {
            // Explicit, caller-owned endpoints retain their existing lifecycle.
            return sendKompileServing(config.getBaseUrl(), request);
        }
        try (LocalServingRuntimePool.Lease runtime = binding.acquire()) {
            synchronized (runtime.coordinationLock()) {
                return sendKompileServing(runtime.baseUrl().toString(), request);
            }
        }
    }

    private HttpResponse<String> sendKompileServing(String baseUrl, ObjectNode request)
            throws IOException, InterruptedException {
        // Startup or another request may have occupied the runtime since the initial check.
        if (isCancelled()) throw new InterruptedException("Local serving request cancelled");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Kompile serving subprocess endpoint was not prepared");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(appendPath(baseUrl, "/api/llm/chat")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(connectivityPolicy.requestTimeout())
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private ArrayNode buildKompileServingMessages(
            String userMessage,
            String systemPrompt,
            List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        for (ObjectNode historyMessage : conversationHistory) {
            ObjectNode message = messages.addObject();
            message.put("role", historyMessage.path("role").asText("user"));
            message.put("content", historyMessage.path("content").asText(""));
        }
        if (toolResults != null) {
            for (ToolCallResultInput toolResult : toolResults) {
                ObjectNode message = messages.addObject();
                message.put("role", "tool");
                message.put("content", toolResult.output);
            }
        }
        if (userMessage != null) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userMessage);
        }
        return messages;
    }

    private void appendKompileToolResultHistory(List<ToolCallResultInput> toolResults) {
        if (toolResults == null) return;
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode history = objectMapper.createObjectNode();
            history.put("role", "tool");
            history.put("content", toolResult.output == null ? "" : toolResult.output);
            if (toolResult.callId != null) history.put("tool_call_id", toolResult.callId);
            if (toolResult.name != null) history.put("name", toolResult.name);
            conversationHistory.add(history);
        }
    }

    private ArrayNode buildKompileServingTools(ArrayNode toolDefs) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (toolDefs != null) {
            for (JsonNode toolDef : toolDefs) {
                String name = toolDef.path("name").asText("");
                if (name.isBlank()) continue;
                ObjectNode tool = tools.addObject();
                tool.put("name", name);
                tool.put("description", toolDef.path("description").asText(""));
                JsonNode parameters = toolDef.path("inputSchema");
                tool.set("parameters", parameters.isObject()
                        ? parameters.deepCopy()
                        : objectMapper.createObjectNode());
            }
        }
        return tools;
    }

    // ========================================================================
    // OpenAI-compatible Chat Completions
    // ========================================================================

    private StreamResult streamOpenAi(String userMessage, String systemPrompt,
                                       ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                       String effectiveModel,
                                       List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();

        try {
            ArrayNode messages = buildOpenAiMessages(
                    userMessage, systemPrompt, toolResults, attachments);

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.set("messages", messages);
            request.put("stream", true);
            applyReasoningEffort(request, false);
            applyOpenAiFastMode(request, effectiveModel);
            applyOpenAiCompatiblePromptCacheControls(request, effectiveModel);
            applyChatCompletionsJsonOutput(request);

            // Request token usage in streamed response
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            request.set("stream_options", streamOptions);

            if (toolDefs != null && toolDefs.size() > 0) {
                ArrayNode openAiTools = convertToolDefsToOpenAi(toolDefs);
                if (openAiTools.size() > 0) {
                    request.set("tools", openAiTools);
                }
            }

            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            String baseUrl = config.resolveBaseUrl(auth);
            String url = appendPath(baseUrl, "/chat/completions");

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(connectivityPolicy.requestTimeout());
            applyBearerAuthentication(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body)) return result;
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[LLM API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        ChatProviderRegistry.label(config.getProvider()) + " HTTP "
                                + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, true);
                return result;
            }

            parseOpenAiStream(guardResponseStream(response.body()), result);
            result.toolCalls.addAll(rescueTextEncodedToolCalls(result, toolDefs));

            if (!result.cancelled && (!result.failed || isGenerationStopped(result))) {
                appendOpenAiToolResultHistory(toolResults);
            }
            if (!result.cancelled && !result.failed) {
                if (userMessage != null || !attachments.isEmpty()) {
                    ObjectNode userMsg = objectMapper.createObjectNode();
                    userMsg.put("role", "user");
                    if (attachments.isEmpty()) {
                        userMsg.put("content", userMessage);
                    } else {
                        userMsg.set("content", buildOpenAiContentArray(userMessage, attachments));
                    }
                    conversationHistory.add(userMsg);
                }
            }

            if (!result.cancelled && !result.failed
                    && (!result.text.isEmpty() || !result.toolCalls.isEmpty())) {
                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", result.text);
                if (!result.toolCalls.isEmpty()) {
                    ArrayNode toolCallsArray = objectMapper.createArrayNode();
                    for (ToolCallOutput tc : result.toolCalls) {
                        ObjectNode tcNode = objectMapper.createObjectNode();
                        tcNode.put("id", tc.id);
                        tcNode.put("type", "function");
                        ObjectNode fn = objectMapper.createObjectNode();
                        fn.put("name", tc.name);
                        fn.put("arguments", tc.arguments != null ? tc.arguments.toString() : "{}");
                        tcNode.set("function", fn);
                        toolCallsArray.add(tcNode);
                    }
                    assistantMsg.set("tool_calls", toolCallsArray);
                }
                conversationHistory.add(assistantMsg);
            }

        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }

        return result;
    }

    private ArrayNode buildOpenAiMessages(String userMessage, String systemPrompt,
                                           List<ToolCallResultInput> toolResults) {
        return buildOpenAiMessages(userMessage, systemPrompt, toolResults, List.of());
    }

    private ArrayNode buildOpenAiMessages(String userMessage, String systemPrompt,
                                           List<ToolCallResultInput> toolResults,
                                           List<AttachmentInput> attachments) {
        ArrayNode messages = objectMapper.createArrayNode();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sysMsg = objectMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }

        // Previous conversation history
        for (ObjectNode msg : conversationHistory) {
            messages.add(msg);
        }

        // Tool results from previous tool calls
        if (toolResults != null && !toolResults.isEmpty()) {
            for (ToolCallResultInput tr : toolResults) {
                ObjectNode toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tr.callId);
                toolMsg.put("content", tr.output);
                messages.add(toolMsg);
            }
        }

        // Current user message
        if (userMessage != null || !attachments.isEmpty()) {
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            if (attachments.isEmpty()) {
                userMsg.put("content", userMessage);
            } else {
                userMsg.set("content", buildOpenAiContentArray(userMessage, attachments));
            }
            messages.add(userMsg);
        }

        return messages;
    }

    private void appendOpenAiToolResultHistory(List<ToolCallResultInput> toolResults) {
        if (toolResults == null) return;
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode history = objectMapper.createObjectNode();
            history.put("role", "tool");
            history.put("content", toolResult.output);
            if (toolResult.callId != null) history.put("tool_call_id", toolResult.callId);
            if (toolResult.name != null) history.put("name", toolResult.name);
            conversationHistory.add(history);
        }
    }

    private ArrayNode convertToolDefsToOpenAi(ArrayNode toolDefs) {
        ArrayNode openAiTools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode oaiTool = objectMapper.createObjectNode();
            oaiTool.put("type", "function");

            ObjectNode fn = objectMapper.createObjectNode();
            fn.put("name", tool.path("name").asText());
            fn.put("description", tool.path("description").asText());

            JsonNode params = tool.path("inputSchema");
            if (params != null && !params.isMissingNode()) {
                fn.set("parameters", params);
            } else {
                ObjectNode emptyParams = objectMapper.createObjectNode();
                emptyParams.put("type", "object");
                emptyParams.set("properties", objectMapper.createObjectNode());
                fn.set("parameters", emptyParams);
            }

            oaiTool.set("function", fn);
            openAiTools.add(oaiTool);
        }
        return openAiTools;
    }

    /**
     * Recover structured tool calls that a model emitted as plain text in the
     * replayed "[Tool call <name> <id>] {json}" shape. Text-form imitation
     * leaves result.toolCalls empty, so the agentic loop ends the turn silently
     * with no visible work done. The rescue only fires when the text parses,
     * the tool name is actually offered in the current tool list, and no real
     * structured calls arrived — and it strips the echoed text from the
     * response so it is not re-recorded as assistant prose (which would teach
     * the model to keep imitating the shape).
     *
     * @return rescued calls, or an empty list when nothing qualifies
     */
    List<ToolCallOutput> rescueTextEncodedToolCalls(StreamResult result, ArrayNode toolDefs) {
        List<ToolCallOutput> rescued = new ArrayList<>();
        if (result == null || result.failed || result.cancelled || !result.toolCalls.isEmpty()
                || result.text == null || result.text.isBlank()
                || toolDefs == null || toolDefs.isEmpty()) {
            return rescued;
        }
        Set<String> offeredTools = new LinkedHashSet<>();
        for (JsonNode toolDef : toolDefs) {
            String name = toolDef.path("name").asText("");
            if (!name.isBlank()) offeredTools.add(name);
        }
        if (offeredTools.isEmpty()) return rescued;

        String text = result.text;
        int searchFrom = 0;
        int firstRescueStart = -1;
        while (true) {
            int lineStart = text.indexOf("[Tool call ", searchFrom);
            if (lineStart < 0) break;
            int idStart = lineStart + "[Tool call ".length();
            int space = text.indexOf(' ', idStart);
            int bracketEnd = text.indexOf(']', idStart);
            if (space < 0 || bracketEnd < 0 || space >= bracketEnd) break;
            String toolName = text.substring(idStart, space).trim();
            String callId = text.substring(space + 1, bracketEnd).trim();
            if (toolName.isEmpty() || !offeredTools.contains(toolName)) {
                searchFrom = idStart;
                continue;
            }
            int braceStart = text.indexOf('{', bracketEnd);
            if (braceStart < 0) break;
            int braceEnd = matchingJsonEnd(text, braceStart);
            if (braceEnd < 0) break;
            try {
                JsonNode arguments = objectMapper.readTree(text.substring(braceStart, braceEnd));
                ToolCallOutput call = new ToolCallOutput();
                call.id = callId.isEmpty() ? "call_" + rescued.size() : callId;
                call.name = toolName;
                call.arguments = arguments;
                rescued.add(call);
                if (firstRescueStart < 0) firstRescueStart = lineStart;
            } catch (Exception ignored) {
                // Not a parseable tool call — leave the text alone.
            }
            searchFrom = braceEnd;
        }
        if (!rescued.isEmpty() && firstRescueStart >= 0) {
            // Keep any genuine prose before the first echoed call; drop the echo.
            result.text = text.substring(0, firstRescueStart).stripTrailing();
        }
        return rescued;
    }

    /** Index just past the JSON object starting at {@code openBrace}, or -1. */
    private static int matchingJsonEnd(String text, int openBrace) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private void parseOpenAiStream(java.io.InputStream inputStream, StreamResult result) throws Exception {
        // Track tool calls being assembled from deltas
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
        boolean terminal = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    terminal = true;
                    break;
                }

                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode errorNode = chunk.get("error");
                    if (errorNode != null && !errorNode.isNull()) {
                        appendProtocolError(
                                result,
                                errorNode.path("message").asText("Unknown provider error"),
                                errorNode.toString());
                        terminal = true;
                        break;
                    }
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    String refusal = delta.path("refusal").asText("");
                    if (!refusal.isEmpty()) {
                        result.refusalDetected = true;
                        printStreamingChunk(refusal);
                        result.text += refusal;
                    }

                    // Text content
                    String content = delta.path("content").asText(null);
                    if (content != null) {
                        printStreamingChunk(content);
                        result.text += content;
                    }

                    // Tool calls (streamed as deltas)
                    JsonNode toolCallsNode = delta.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tcDelta : toolCallsNode) {
                            int index = tcDelta.path("index").asInt(0);
                            while (toolAccumulators.size() <= index) {
                                toolAccumulators.add(new ToolCallAccumulator());
                            }
                            ToolCallAccumulator acc = toolAccumulators.get(index);

                            String id = tcDelta.path("id").asText(null);
                            if (id != null) acc.id = id;

                            String name = tcDelta.path("function").path("name").asText(null);
                            if (name != null) acc.name = name;

                            String args = tcDelta.path("function").path("arguments").asText(null);
                            if (args != null) acc.arguments.append(args);
                        }
                    }

                    // Extract token usage if present (final chunk in OpenAI streaming)
                    JsonNode usageNode = chunk.path("usage");
                    if (!usageNode.isMissingNode()) {
                        readOpenAiCompatibleUsage(usageNode, result);
                    }

                    // Check for finish_reason
                    String finishReason = chunk.path("choices").path(0).path("finish_reason").asText(null);
                    if (finishReason != null && !finishReason.isBlank()) terminal = true;
                    result.refusalDetected |= "content_filter".equals(finishReason);
                    result.truncatedDetected |= "length".equals(finishReason);
                    if (terminal && finishGenerationOutcome(result)) return;
                    if ("tool_calls".equals(finishReason) || "stop".equals(finishReason)) {
                        // Finalize any accumulated tool calls
                        for (ToolCallAccumulator acc : toolAccumulators) {
                            if (acc.name != null) {
                                ToolCallOutput tc = new ToolCallOutput();
                                tc.id = acc.id != null ? acc.id : "call_" + result.toolCalls.size();
                                tc.name = acc.name;
                                try {
                                    tc.arguments = objectMapper.readTree(acc.arguments.toString());
                                } catch (Exception e) {
                                    tc.arguments = objectMapper.createObjectNode();
                                }
                                result.toolCalls.add(tc);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable chunks
                }
            }
        }

        if (!result.cancelled && !result.failed && !terminal) {
            throw new EOFException("OpenAI stream ended before a terminal event");
        }

        if (finishGenerationOutcome(result)) return;

        // If tool calls were accumulated but finish_reason wasn't caught
        if (result.toolCalls.isEmpty() && !toolAccumulators.isEmpty()) {
            for (ToolCallAccumulator acc : toolAccumulators) {
                if (acc.name != null) {
                    ToolCallOutput tc = new ToolCallOutput();
                    tc.id = acc.id != null ? acc.id : "call_" + result.toolCalls.size();
                    tc.name = acc.name;
                    try {
                        tc.arguments = objectMapper.readTree(acc.arguments.toString());
                    } catch (Exception e) {
                        tc.arguments = objectMapper.createObjectNode();
                    }
                    result.toolCalls.add(tc);
                }
            }
        }
    }

    // ========================================================================
    // Anthropic Messages API
    // ========================================================================

    private StreamResult streamAnthropic(String userMessage, String systemPrompt,
                                          ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                          String effectiveModel,
                                          List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();

        try {
            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.put("max_tokens", 8192);
            request.put("stream", true);
            boolean fastMode = config.useFastMode(effectiveModel);
            if (fastMode) request.put("speed", "fast");

            String nativeRouteKey = "anthropic:" + effectiveModel;
            boolean nativeCompaction = compactionCapabilities(effectiveModel).nativeCompaction()
                    == ProviderCompactionCapabilities.NativeCompaction.ANTHROPIC_MESSAGES
                    && nativeCompactionTriggerTokens >= 50_000
                    && !unavailableNativeCompactionRoutes.contains(nativeRouteKey);
            if (nativeCompaction) {
                ObjectNode edit = objectMapper.createObjectNode();
                edit.put("type", "compact_20260112");
                edit.putObject("trigger")
                        .put("type", "input_tokens")
                        .put("value", nativeCompactionTriggerTokens);
                edit.put("instructions",
                        "Summarize the conversation for continuing the task. Preserve code, "
                                + "file paths, decisions, pending work, and tool outcomes. "
                                + "Do not call tools while compacting; return summary text only.");
                request.putObject("context_management").putArray("edits").add(edit);
            }

            String effectiveSystem = effectiveAnthropicSystemPrompt(auth, systemPrompt);
            if (!effectiveSystem.isBlank()) request.put("system", effectiveSystem);

            ArrayNode messages = buildAnthropicMessages(userMessage, toolResults, attachments);
            request.set("messages", messages);

            if (toolDefs != null && toolDefs.size() > 0) {
                ArrayNode anthropicTools = convertToolDefsToAnthropic(toolDefs);
                if (anthropicTools.size() > 0) {
                    request.set("tools", anthropicTools);
                }
            }
            applyAnthropicPromptCacheControl(request);

            String baseUrl = config.resolveBaseUrl(auth);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(connectivityPolicy.requestTimeout());
            applyAnthropicAuthentication(
                    requestBuilder, auth, "github-copilot".equals(config.getProvider()));
            if (nativeCompaction || fastMode) {
                String beta = nativeCompaction ? "compact-2026-01-12" : "";
                if (fastMode) beta += (beta.isEmpty() ? "" : ",") + "fast-mode-2026-02-01";
                requestBuilder.setHeader("anthropic-beta",
                        mergeHeaderValue(auth, "anthropic-beta", beta));
            }
            applyProviderRequestHeaders(requestBuilder, userMessage);
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body)) return result;
                if (nativeCompaction && (response.statusCode() == 400
                        || response.statusCode() == 404 || response.statusCode() == 422)
                        && !isContextOverflowFailure(response.statusCode(), body)) {
                    unavailableNativeCompactionRoutes.add(nativeRouteKey);
                    return streamAnthropic(
                            userMessage, systemPrompt, toolDefs, toolResults,
                            effectiveModel, attachments, auth);
                }
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[Anthropic API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, false);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        "Anthropic HTTP " + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, false);
                return result;
            }

            parseAnthropicStream(guardResponseStream(response.body()), result);
            result.toolCalls.addAll(rescueTextEncodedToolCalls(result, toolDefs));

            if (!result.cancelled && !result.failed && result.nativeCompactionSummary != null
                    && !result.nativeCompactionSummary.isBlank()) {
                // Anthropic ignores all messages before the latest compaction
                // block; remove them locally so the next HTTP request is small too.
                conversationHistory.clear();
            } else if (!result.cancelled && (!result.failed || isGenerationStopped(result))) {
                ObjectNode toolResultMessage = createAnthropicToolResultMessage(toolResults);
                if (toolResultMessage != null) conversationHistory.add(toolResultMessage);
            }

            // Track in conversation history only after a completed stream.
            if (!result.cancelled && !result.failed && result.nativeCompactionSummary == null
                    && (userMessage != null || !attachments.isEmpty())) {
                ObjectNode userMsg = objectMapper.createObjectNode();
                userMsg.put("role", "user");
                userMsg.set("content", buildAnthropicContentArray(userMessage, attachments));
                conversationHistory.add(userMsg);
            }

            if (!result.cancelled && !result.failed
                    && (!result.text.isEmpty() || !result.toolCalls.isEmpty()
                    || (result.nativeCompactionSummary != null
                    && !result.nativeCompactionSummary.isBlank()))) {
                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                ArrayNode content = objectMapper.createArrayNode();
                if (result.nativeCompactionSummary != null
                        && !result.nativeCompactionSummary.isBlank()) {
                    ObjectNode compactionBlock = objectMapper.createObjectNode();
                    compactionBlock.put("type", "compaction");
                    compactionBlock.put("content", result.nativeCompactionSummary);
                    content.add(compactionBlock);
                }
                if (!result.text.isEmpty()) {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", result.text);
                    content.add(textBlock);
                }
                for (ToolCallOutput tc : result.toolCalls) {
                    ObjectNode toolUseBlock = objectMapper.createObjectNode();
                    toolUseBlock.put("type", "tool_use");
                    toolUseBlock.put("id", tc.id);
                    toolUseBlock.put("name", tc.name);
                    toolUseBlock.set("input", tc.arguments);
                    content.add(toolUseBlock);
                }
                assistantMsg.set("content", content);
                conversationHistory.add(assistantMsg);
            }

        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }

        return result;
    }

    private String effectiveAnthropicSystemPrompt(
            OAuthProviderFlow.RequestAuth auth, String systemPrompt) {
        boolean anthropicOAuth = auth != null && auth.oauth()
                && "anthropic".equalsIgnoreCase(config.getProvider());
        if (!anthropicOAuth) return systemPrompt == null ? "" : systemPrompt;
        String identity = "You are Claude Code, Anthropic's official CLI for Claude.";
        return systemPrompt == null || systemPrompt.isBlank()
                ? identity : identity + "\n\n" + systemPrompt;
    }

    private static void applyHeaders(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth) {
        if (auth != null) {
            auth.headers().forEach(builder::setHeader);
        }
    }

    private static void applyBearerAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth) {
        applyTokenAuthentication(builder, auth, "Authorization", "Bearer ");
        applyHeaders(builder, auth);
    }

    private static void applyAnthropicAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth,
            boolean bearerFallback) {
        if (auth != null
                && !hasHeader(auth.headers(), "Authorization")
                && !hasHeader(auth.headers(), "x-api-key")) {
            applyTokenAuthentication(builder, auth,
                    bearerFallback ? "Authorization" : "x-api-key",
                    bearerFallback ? "Bearer " : "");
        }
        applyHeaders(builder, auth);
    }

    private static void applyTokenAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth,
            String headerName) {
        applyTokenAuthentication(builder, auth, headerName, "");
    }

    private static void applyTokenAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth,
            String headerName,
            String prefix) {
        if (auth == null || auth.token() == null || auth.token().isBlank()
                || hasHeader(auth.headers(), headerName)) {
            return;
        }
        builder.header(headerName, prefix + auth.token());
    }

    private static String mergeHeaderValue(
            OAuthProviderFlow.RequestAuth auth, String headerName, String value) {
        String existing = auth == null ? null : auth.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(headerName))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (existing == null || existing.isBlank()) return value;
        return java.util.Arrays.stream(existing.split(","))
                .map(String::trim)
                .anyMatch(value::equalsIgnoreCase)
                ? existing : existing + "," + value;
    }

    private static boolean hasHeader(Map<String, String> headers, String expectedName) {
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(expectedName));
    }

    private void applyProviderRequestHeaders(
            HttpRequest.Builder builder,
            String userMessage) {
        String cacheSessionId = activePromptCacheSessionId();
        if (cacheSessionId != null) {
            switch (config.promptCacheCapabilities().sessionAffinity()) {
                case XAI_CONVERSATION_HEADER ->
                        builder.setHeader("x-grok-conv-id", cacheSessionId);
                case OPENROUTER_SESSION_HEADER ->
                        builder.setHeader("x-session-id", cacheSessionId);
                default -> {
                    // Body-based affinity and provider-owned sessions are handled elsewhere.
                }
            }
        }
        if ("github-copilot".equals(config.getProvider())) {
            builder.setHeader("User-Agent", "GitHubCopilotChat/0.35.0");
            builder.setHeader("Editor-Version", "vscode/1.107.0");
            builder.setHeader("Editor-Plugin-Version", "copilot-chat/0.35.0");
            builder.setHeader("Copilot-Integration-Id", "vscode-chat");
            builder.setHeader("X-Initiator", userMessage == null ? "agent" : "user");
            builder.setHeader("Openai-Intent", "conversation-edits");
        }
    }

    private ArrayNode buildAnthropicMessages(
            String userMessage, List<ToolCallResultInput> toolResults) {
        return buildAnthropicMessages(userMessage, toolResults, List.of());
    }

    private ArrayNode buildAnthropicMessages(
            String userMessage, List<ToolCallResultInput> toolResults,
            List<AttachmentInput> attachments) {
        ArrayNode messages = objectMapper.createArrayNode();

        // Previous history
        for (ObjectNode msg : conversationHistory) {
            messages.add(msg);
        }

        // Tool results
        ObjectNode toolResultMessage = createAnthropicToolResultMessage(toolResults);
        if (toolResultMessage != null) messages.add(toolResultMessage);

        // Current user message
        if (userMessage != null || !attachments.isEmpty()) {
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.set("content", buildAnthropicContentArray(userMessage, attachments));
            messages.add(userMsg);
        }

        return messages;
    }

    private ObjectNode createAnthropicToolResultMessage(
            List<ToolCallResultInput> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) return null;
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", "tool_result");
            block.put("tool_use_id", toolResult.callId);
            block.put("content", toolResult.output);
            if (toolResult.isError) block.put("is_error", true);
            content.add(block);
        }
        userMsg.set("content", content);
        return userMsg;
    }

    private ArrayNode convertToolDefsToAnthropic(ArrayNode toolDefs) {
        ArrayNode anthropicTools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode at = objectMapper.createObjectNode();
            at.put("name", tool.path("name").asText());
            at.put("description", tool.path("description").asText());

            JsonNode params = tool.path("inputSchema");
            if (params != null && !params.isMissingNode()) {
                at.set("input_schema", params);
            } else {
                ObjectNode emptySchema = objectMapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", objectMapper.createObjectNode());
                at.set("input_schema", emptySchema);
            }

            anthropicTools.add(at);
        }
        return anthropicTools;
    }

    private void parseAnthropicStream(java.io.InputStream inputStream, StreamResult result) throws Exception {
        String currentToolId = null;
        String currentToolName = null;
        StringBuilder currentToolArgs = new StringBuilder();
        boolean currentCompaction = false;
        boolean terminal = false;
        StringBuilder compactionSummary = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                try {
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText("");

                    switch (type) {
                        case "message_start": {
                            // Anthropic sends input token count in message_start
                            JsonNode msgUsage = event.path("message").path("usage");
                            if (!msgUsage.isMissingNode()) {
                                result.inputTokens = msgUsage.path("input_tokens").asLong(0);
                                result.cacheReadTokens = msgUsage.path("cache_read_input_tokens").asLong(0);
                                result.cacheCreationTokens = msgUsage.path("cache_creation_input_tokens").asLong(0);
                            }
                            break;
                        }

                        case "content_block_start": {
                            JsonNode contentBlock = event.path("content_block");
                            String blockType = contentBlock.path("type").asText("");
                            if ("tool_use".equals(blockType)) {
                                currentToolId = contentBlock.path("id").asText("call_" + result.toolCalls.size());
                                currentToolName = contentBlock.path("name").asText("");
                                currentToolArgs.setLength(0);
                            } else if ("compaction".equals(blockType)) {
                                currentCompaction = true;
                                compactionSummary.setLength(0);
                                String initial = contentBlock.path("content").asText("");
                                if (!initial.isBlank()) compactionSummary.append(initial);
                            }
                            break;
                        }

                        case "content_block_delta": {
                            JsonNode delta = event.path("delta");
                            String deltaType = delta.path("type").asText("");

                            if ("text_delta".equals(deltaType)) {
                                String text = delta.path("text").asText("");
                                printStreamingChunk(text);
                                result.text += text;
                            } else if ("input_json_delta".equals(deltaType)) {
                                String partial = delta.path("partial_json").asText("");
                                currentToolArgs.append(partial);
                            } else if ("compaction_delta".equals(deltaType)) {
                                compactionSummary.append(delta.path("content").asText(""));
                            }
                            break;
                        }

                        case "content_block_stop": {
                            if (currentToolName != null) {
                                ToolCallOutput tc = new ToolCallOutput();
                                tc.id = currentToolId;
                                tc.name = currentToolName;
                                try {
                                    tc.arguments = objectMapper.readTree(currentToolArgs.toString());
                                } catch (Exception e) {
                                    tc.arguments = objectMapper.createObjectNode();
                                }
                                result.toolCalls.add(tc);
                                currentToolId = null;
                                currentToolName = null;
                                currentToolArgs.setLength(0);
                            }
                            if (currentCompaction) {
                                result.nativeCompactionSummary = compactionSummary.toString();
                                result.nativeCompactionStrategy = "anthropic-messages";
                                currentCompaction = false;
                                compactionSummary.setLength(0);
                            }
                            break;
                        }

                        case "message_stop":
                            terminal = true;
                            break;

                        case "message_delta": {
                            String stopReason = event.path("delta").path("stop_reason").asText("");
                            result.refusalDetected |= "refusal".equals(stopReason);
                            result.truncatedDetected |= "max_tokens".equals(stopReason)
                                    || "model_context_window_exceeded".equals(stopReason);
                            // Anthropic sends output token count in message_delta
                            JsonNode deltaUsage = event.path("usage");
                            if (!deltaUsage.isMissingNode()) {
                                result.outputTokens = deltaUsage.path("output_tokens").asLong(0);
                                JsonNode iterations = deltaUsage.path("iterations");
                                if (iterations.isArray()) {
                                    for (JsonNode iteration : iterations) {
                                        if ("compaction".equals(iteration.path("type").asText())) {
                                            result.compactionInputTokens +=
                                                    iteration.path("input_tokens").asLong(0L);
                                            result.compactionOutputTokens +=
                                                    iteration.path("output_tokens").asLong(0L);
                                        }
                                    }
                                }
                            }
                            if (result.refusalDetected || result.truncatedDetected) {
                                finishGenerationOutcome(result);
                                return;
                            }
                            break;
                        }

                        case "error": {
                            JsonNode error = event.path("error");
                            String msg = error.path("message").asText(data);
                            appendProtocolError(result, msg, error.toString());
                            terminal = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable events
                }
            }
        }
        if (!result.cancelled && !result.failed && !terminal) {
            throw new EOFException("Anthropic stream ended before a terminal event");
        }
        finishGenerationOutcome(result);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Build an OpenAI-compatible content array for a message with optional attachments.
     * Images become image_url blocks; text files become text blocks with embedded content.
     * The user message text is appended as the final text block.
     */
    private ArrayNode buildOpenAiContentArray(String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput att : attachments) {
                if (att.isImage()) {
                    ObjectNode imageBlock = objectMapper.createObjectNode();
                    imageBlock.put("type", "image_url");
                    ObjectNode imageUrl = objectMapper.createObjectNode();
                    imageUrl.put("url", "data:" + att.mimeType() + ";base64," + att.base64Data());
                    imageBlock.set("image_url", imageUrl);
                    content.add(imageBlock);
                } else {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.path() + "]\n" + att.textContent());
                    content.add(textBlock);
                }
            }
        }
        if (text != null) {
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.add(textBlock);
        }
        return content;
    }

    /** OpenAI Responses uses input_text/input_image rather than Chat Completions blocks. */
    private ArrayNode buildResponsesContentArray(
            String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput attachment : attachments) {
                if (attachment.isImage()) {
                    ObjectNode image = content.addObject();
                    image.put("type", "input_image");
                    image.put("image_url", dataUrl(attachment));
                } else {
                    ObjectNode file = content.addObject();
                    file.put("type", "input_text");
                    file.put("text", attachmentText(attachment));
                }
            }
        }
        if (text != null) {
            content.addObject().put("type", "input_text").put("text", text);
        }
        return content;
    }

    /**
     * Build an Anthropic-compatible content array for a message with optional attachments.
     * Images become base64 image source blocks; text files become text blocks with embedded content.
     * The user message text is appended as the final text block.
     */
    private ArrayNode buildAnthropicContentArray(String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput att : attachments) {
                if (att.isImage()) {
                    ObjectNode imageBlock = objectMapper.createObjectNode();
                    imageBlock.put("type", "image");
                    ObjectNode source = objectMapper.createObjectNode();
                    source.put("type", "base64");
                    source.put("media_type", att.mimeType());
                    source.put("data", att.base64Data());
                    imageBlock.set("source", source);
                    content.add(imageBlock);
                } else {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.path() + "]\n" + att.textContent());
                    content.add(textBlock);
                }
            }
        }
        if (text != null) {
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.add(textBlock);
        }
        return content;
    }

    private static String validateAttachments(List<AttachmentInput> attachments) {
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentInput attachment = attachments.get(i);
            if (attachment == null) {
                return "Attachment " + i + " is null";
            }
            if (attachment.isImage()) {
                if (attachment.mimeType() == null
                        || !attachment.mimeType().toLowerCase(Locale.ROOT).startsWith("image/")) {
                    return "Image attachment '" + attachment.path()
                            + "' must declare an image MIME type";
                }
                if (isBlank(attachment.base64Data())) {
                    return "Image attachment '" + attachment.path()
                            + "' has no base64 payload";
                }
            } else if (attachment.textContent() == null) {
                return "Attachment '" + attachment.path()
                        + "' is neither an encoded image nor readable text";
            }
        }
        return null;
    }

    private static boolean supportsAttachments(WireProtocol protocol) {
        return protocol == WireProtocol.OPENAI_CHAT
                || protocol == WireProtocol.OPENAI_RESPONSES
                || protocol == WireProtocol.ANTHROPIC_MESSAGES;
    }

    private StreamResult attachmentFailure(String detail) {
        StreamResult result = new StreamResult();
        String message = "[Attachment error: " + detail + "]";
        appendProviderFailure(result, 0, detail, message, true);
        return result;
    }

    private static String dataUrl(AttachmentInput attachment) {
        return "data:" + attachment.mimeType() + ";base64," + attachment.base64Data();
    }

    private static String attachmentText(AttachmentInput attachment) {
        return "[File: " + attachment.path() + "]\n" + attachment.textContent();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private java.io.InputStream guardResponseStream(java.io.InputStream stream) {
        return new IdleTimeoutInputStream(stream, connectivityPolicy.streamIdleTimeout(), this::isCancelled);
    }

    private String readResponseBody(java.io.InputStream stream) throws Exception {
        try (java.io.InputStream guarded = guardResponseStream(stream)) {
            return new String(guarded.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class UnauthorizedRequestException extends java.io.IOException {
        private final OAuthProviderFlow.RequestAuth auth;
        private final FailureKind kind;

        private UnauthorizedRequestException(OAuthProviderFlow.RequestAuth auth, FailureKind kind) {
            super("Unauthorized");
            this.auth = auth;
            this.kind = kind;
        }
    }

    private void recordStreamFailure(StreamResult result, Exception failure, String prefix) {
        if (markCancelled(result, failure)) return;
        if (failure instanceof UnauthorizedRequestException rejected) {
            if (rejected.kind != FailureKind.NONE) recordTerminalOutcome(result, rejected.kind, 401);
            else recordAuthenticationFailure(result, 401, "[Radius config error 401: Unauthorized]", rejected.auth);
            return;
        }
        if (failure instanceof ChatConfig.AuthenticationException credentialFailure) {
            finishCredentialFailure(result, credentialFailure);
            return;
        }
        if (failure instanceof OpenCodeServeClient.TurnNotStartedException turnNotStarted) {
            // Pre-turn transport failure: the provider never received the prompt and
            // its session holds no new state, so the connectivity loop may replay it.
            result.failed = true;
            result.retryableConnectivityFailure = true;
            result.failureKind = FailureKind.PROVIDER_ERROR;
            result.connectivityFailure = formatExceptionMessage(turnNotStarted);
            result.connectivityFinalMessage = prefix + result.connectivityFailure + "]";
            return;
        }
        if (connectivityPolicy.isRetryableFailure(failure)) {
            result.retryableConnectivityFailure = true;
            result.connectivityFailure = formatExceptionMessage(failure);
            result.connectivityFinalMessage = prefix + result.connectivityFailure + "]";
            return;
        }
        String detail = formatExceptionMessage(failure);
        appendProviderFailure(result, 0, detail, prefix + detail + "]", false);
    }

    private StreamResult finishCredentialFailure(StreamResult result, ChatConfig.AuthenticationException error) {
        result.failed = true;
        result.authenticationFailure = false;
        result.rejectedAuth = null;
        result.failureStatusCode = error.failure().statusCode();
        result.failureKind = switch (error.failure().kind()) {
            case REAUTH_REQUIRED -> FailureKind.AUTHENTICATION;
            case PERMISSION_DENIED -> FailureKind.PERMISSION_DENIED;
            case RATE_LIMITED -> FailureKind.RATE_LIMITED;
            case TEMPORARY -> FailureKind.TEMPORARY;
            case LOCAL_OR_PROTOCOL, INTERRUPTED -> FailureKind.CREDENTIAL_UNAVAILABLE;
        };
        result.failureMessage = error.getMessage();
        String rendered = (result.text.isEmpty() ? "" : "\n") + error.getMessage();
        result.text += rendered;
        printStreamingChunk(rendered);
        return result;
    }

    private void recordUnauthorizedResponse(StreamResult result, java.io.InputStream body,
                                             OAuthProviderFlow.RequestAuth auth) {
        String diagnostic = ProviderResponseFailure.authenticationBody(body);
        if (!recordKnownProviderFailure(result, 401, diagnostic)) {
            recordAuthenticationFailure(result, 401, "[Provider API error 401: Unauthorized]", auth);
        }
    }

    private boolean recordKnownProviderFailure(StreamResult result, int status, String body) {
        FailureKind kind = ProviderResponseFailure.classify(status, body);
        if (kind == FailureKind.NONE) return false;
        recordTerminalOutcome(result, kind, status);
        return true;
    }

    private static boolean responsesContainRefusal(JsonNode content) {
        for (JsonNode block : content) {
            if ("refusal".equals(block.path("type").asText())) return true;
        }
        return false;
    }

    private static boolean isGenerationStopped(StreamResult result) {
        return result.failureKind == FailureKind.REFUSAL || result.failureKind == FailureKind.TRUNCATED;
    }

    private boolean finishGenerationOutcome(StreamResult result) {
        if (result.cancelled) return false;
        if (result.refusalDetected || result.truncatedDetected) {
            recordTerminalOutcome(result, result.refusalDetected ? FailureKind.REFUSAL : FailureKind.TRUNCATED, 200);
            return true;
        }
        return false;
    }

    private void recordTerminalOutcome(StreamResult result, FailureKind kind, int status) {
        result.retryableConnectivityFailure = false;
        result.authenticationFailure = false;
        result.rejectedAuth = null;
        result.responseStarted |= !result.text.isEmpty() || !result.toolCalls.isEmpty();
        result.toolCalls.clear();
        if (result.failed && result.failureKind == kind) return;
        result.failed = true;
        result.failureKind = kind;
        result.failureStatusCode = status;
        result.failureMessage = ProviderResponseFailure.message(kind);
        result.toolCalls.clear();
        String rendered = (result.text.isEmpty() ? "" : "\n") + result.failureMessage;
        result.text += rendered;
        printStreamingChunk(rendered);
    }

    private void recordAuthenticationFailure(
            StreamResult result,
            int statusCode,
            String finalMessage,
            OAuthProviderFlow.RequestAuth rejectedAuth) {
        result.failed = true;
        result.failureKind = FailureKind.AUTHENTICATION;
        result.failureStatusCode = statusCode;
        result.failureMessage = finalMessage;
        result.authenticationFailure = true;
        result.rejectedAuth = rejectedAuth != null && rejectedAuth.oauth()
                ? rejectedAuth : null;
    }

    private void appendProviderFailure(
            StreamResult result, int statusCode, String classificationDetail,
            String finalMessage, boolean render) {
        boolean hadResponse = !result.text.isEmpty() || !result.toolCalls.isEmpty();
        result.responseStarted |= hadResponse;
        result.failed = true;
        result.failureStatusCode = statusCode;
        result.failureMessage = finalMessage;
        result.failureKind = isContextOverflowFailure(statusCode, classificationDetail)
                ? FailureKind.CONTEXT_OVERFLOW : FailureKind.PROVIDER_ERROR;
        if (!result.text.isEmpty()) result.text += "\n";
        result.text += finalMessage;
        // Keep a replay-safe context rejection off the terminal. AgenticChatLoop
        // either compacts and retries transparently or reports it exactly once.
        if (render && (result.failureKind != FailureKind.CONTEXT_OVERFLOW
                || result.responseStarted)) {
            printStreamingChunk(finalMessage);
        }
    }

    static boolean isContextOverflowFailure(int statusCode, String detail) {
        if (detail == null || detail.isBlank()) return false;
        String normalized = detail.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.contains("context_length_exceeded")) return true;
        if (statusCode == 429 || normalized.contains("rate_limit")
                || normalized.contains("per minute")
                || normalized.contains("per second")) {
            return false;
        }
        if (normalized.contains("context window exceeded")
                || normalized.contains("maximum context length")
                || normalized.contains("prompt is too long")
                || normalized.contains("input is too long")
                || (normalized.contains("too many tokens")
                && (normalized.contains("context") || normalized.contains("prompt")
                || normalized.contains("input") || normalized.contains("message")))) {
            return true;
        }
        boolean exceeds = normalized.contains("exceed")
                || normalized.contains("too long")
                || normalized.contains("maximum");
        if (exceeds && (normalized.contains("context limit")
                || normalized.contains("token limit")
                || normalized.contains("input token")
                || normalized.contains("input length")
                || ((normalized.contains("max length")
                || normalized.contains("maximum length"))
                && (normalized.contains("prompt") || normalized.contains("input")
                || normalized.contains("message") || normalized.contains("context")))
                || (normalized.contains("max_tokens")
                && normalized.contains("context")))) {
            return true;
        }
        if (normalized.contains("reduce the length")
                && (normalized.contains("message") || normalized.contains("prompt"))) {
            return true;
        }
        if (normalized.contains("input length")
                && (normalized.contains("allowed range")
                || normalized.contains("range of input")
                || normalized.contains("should be"))) {
            return true;
        }
        return statusCode == 413
                && (normalized.contains("context")
                || normalized.contains("token")
                || normalized.contains("prompt"));
    }

    private boolean recordHttpConnectivityFailure(
            StreamResult result, int statusCode, HttpHeaders headers,
            String reason, String finalMessage) {
        if (!connectivityPolicy.isRetryableStatus(statusCode)) return false;
        result.failed = true;
        result.retryableConnectivityFailure = true;
        result.failureStatusCode = statusCode;
        result.failureKind = FailureKind.PROVIDER_ERROR;
        result.failureMessage = finalMessage;
        result.connectivityFailure = reason;
        result.connectivityFinalMessage = finalMessage;
        result.connectivityHeaders = headers;
        return true;
    }

    private StreamResult finishConnectivityFailure(StreamResult result, boolean replaySafe) {
        String message = result.connectivityFinalMessage;
        if (message == null || message.isBlank()) {
            message = "[" + ChatProviderRegistry.label(config.getProvider())
                    + " connection error: " + result.connectivityFailure + "]";
        }
        if (!replaySafe) {
            message += "\n[Response was not replayed because the provider had already streamed output.]";
        }
        result.failureMessage = message;
        String rendered = result.text.isEmpty() ? message : "\n" + message;
        result.text += rendered;
        result.failed = true;
        result.retryableConnectivityFailure = false;
        if (!ai.kompile.cli.main.chat.ChatCompleter.showAlert(message)) printStreamingChunk(rendered);
        return result;
    }

    private StreamResult finishAuthenticationFailure(StreamResult result) {
        String provider = config.getProvider() == null || config.getProvider().isBlank()
                ? "provider" : config.getProvider().strip();
        String detail = result.failureMessage;
        if (detail == null || detail.isBlank()) {
            detail = "[" + ChatProviderRegistry.label(provider)
                    + " authentication failed (HTTP " + result.failureStatusCode + ")]";
        }
        String guidance = "[Provider authentication was rejected. Check the selected credential and account access. "
                + "If the credential is expired or revoked, use `kompile auth login " + provider
                + "`. Signing in will not fix permission or IP restrictions.]";
        String message = detail + "\n" + guidance;
        result.failureMessage = message;
        result.authenticationFailure = false;
        result.rejectedAuth = null;
        result.text = result.text.isEmpty() ? message : result.text + "\n" + message;
        if (!ai.kompile.cli.main.chat.ChatCompleter.showAlert(message)) printStreamingChunk(result.text);
        return result;
    }

    private boolean waitForRetry(Duration delay) {
        long remainingNanos = Math.max(0L, delay.toNanos());
        long deadline = System.nanoTime() + remainingNanos;
        while (remainingNanos > 0L) {
            if (isCancelled()) return false;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // The stream watchdog interrupts the reader as part of unblocking a
                // stalled read; that interrupt can land here after the retry loop
                // has already recovered. Only a real cancel signal may abort the
                // backoff — an interrupt without one is spurious, so clear it and
                // keep waiting instead of silently dropping the reconnect.
                if (!isCancelled()) {
                    Thread.interrupted();
                    continue;
                }
                return false;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        return !isCancelled();
    }

    private void emitConnectivityEvent(ConnectivityEvent event) {
        java.util.function.Consumer<ConnectivityEvent> consumer = connectivityEventConsumer;
        if (consumer != null) {
            consumer.accept(event);
            return;
        }
        String warning = "[" + ChatProviderRegistry.label(event.provider())
                + " connection lost: " + event.reason() + "; reconnecting attempt "
                + event.attempt() + "/" + event.maxAttempts() + " in "
                + event.delay().toMillis() + " ms]";
        if (!ai.kompile.cli.main.chat.ChatCompleter.showAlert(warning)) System.err.println(warning);
    }

    private void resetOpenCodeClient() {
        OpenCodeServeClient client = openCodeServeClient;
        if (client != null) client.close();
        openCodeServeClient = null;
        openCodeNeedsSeed = true;
    }

    private boolean markCancelled(StreamResult result, Exception error) {
        if (isCancelled() || error instanceof InterruptedException) {
            result.cancelled = true;
            return true;
        }
        result.failed = true;
        return false;
    }

    /**
     * Format an exception message for display. If the exception has no message,
     * returns the simple class name of the exception type.
     */
    private String formatExceptionMessage(Exception e) {
        String msg = e.getMessage();
        return (msg != null) ? msg : e.getClass().getSimpleName();
    }

    private String extractErrorMessage(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            // OpenAI format
            String msg = json.path("error").path("message").asText(null);
            if (msg != null) return msg;
            // Anthropic format
            msg = json.path("error").path("message").asText(null);
            if (msg != null) return msg;
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    public static class StreamResult {
        public String text = "";
        public List<ToolCallOutput> toolCalls = new ArrayList<>();
        public boolean cancelled = false;
        public boolean failed = false;
        public FailureKind failureKind = FailureKind.NONE;
        private boolean refusalDetected;
        private boolean truncatedDetected;
        public int failureStatusCode;
        public String failureMessage;
        public boolean responseStarted;
        public boolean providerSideEffectsObserved;
        private boolean authenticationFailure;
        private OAuthProviderFlow.RequestAuth rejectedAuth;
        private boolean retryableConnectivityFailure;
        private String connectivityFailure;
        private String connectivityFinalMessage;
        private HttpHeaders connectivityHeaders;
        public String nativeCompactionSummary;
        public String nativeCompactionStrategy;
        public JsonNode nativeCompactionPayload;
        public long compactionInputTokens;
        public long compactionOutputTokens;
        // Token usage from API response (when available)
        public long inputTokens = 0;
        public long outputTokens = 0;
        public long cacheReadTokens = 0;
        public long cacheCreationTokens = 0;

        /**
         * Tokens occupying the provider context. Cached tokens are cheaper, not absent;
         * every provider adapter reports them separately but they still consume context.
         */
        public long contextInputTokens() {
            long total = Math.max(0L, inputTokens);
            total = saturatingAdd(total, Math.max(0L, cacheReadTokens));
            return saturatingAdd(total, Math.max(0L, cacheCreationTokens));
        }

        public boolean isContextOverflow() {
            return failed && failureKind == FailureKind.CONTEXT_OVERFLOW;
        }

        public boolean isReplaySafe() {
            return !responseStarted && !providerSideEffectsObserved
                    && toolCalls.isEmpty() && (text.isEmpty() || isContextOverflow());
        }

        public boolean canRetryAfterContextOverflow() {
            return isContextOverflow() && isReplaySafe();
        }

        private static long saturatingAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }

        // Enforcer monitor fields
        public boolean monitorInterrupted = false;
        public String correctionPrompt = null;
    }

    public record ConnectivityEvent(
            String provider, int attempt, int maxAttempts, Duration delay, String reason) {
    }

    public static class ToolCallOutput {
        public String id;
        public String name;
        public JsonNode arguments;
    }

    public record ReplayedToolCallInput(
            String toolName, String callId, String argumentsJson) {
    }

    public static class ToolCallResultInput {
        public String callId;
        public String name;
        public String output;
        public boolean isError;

        public ToolCallResultInput(String callId, String name, String output, boolean isError) {
            this.callId = callId;
            this.name = name;
            this.output = output;
            this.isError = isError;
        }
    }

    /**
     * An attachment (file, image, etc.) to include with a chat message.
     */
    public record AttachmentInput(String path, String mimeType, boolean isImage, String base64Data, String textContent) {
        public AttachmentInput(String path, String mimeType) {
            this(path, mimeType, false, null, null);
        }
    }

    private static class ToolCallAccumulator {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class ResponsesToolCallAccumulator {
        String itemId;
        String callId;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class ResponsesStreamState {
        final Map<Integer, ObjectNode> reasoningItems = new LinkedHashMap<>();
        final Map<Integer, ResponsesToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        boolean terminal;
        boolean failed;
    }

    private static class PiMessagesStreamState {
        final Map<Integer, ObjectNode> contentBlocks = new LinkedHashMap<>();
        boolean terminal;
        boolean failed;
    }

    private record RadiusGatewayConfig(String baseUrl, List<String> modelIds) {
    }
}
