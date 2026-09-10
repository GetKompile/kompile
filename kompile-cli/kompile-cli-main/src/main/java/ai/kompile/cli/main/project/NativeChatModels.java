/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatProvider;
import ai.kompile.cli.main.chat.config.ChatProviderRegistry;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscoveryHttp;
import ai.kompile.cli.main.chat.config.ProviderConnectivityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Native chat boundary shared by MCP pipelines and graph extraction. No CLI or artifact fallback. */
public final class NativeChatModels {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final java.util.concurrent.ConcurrentHashMap<Thread, Integer> ACTIVE_REQUESTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<String> OPERATIONS = List.of(
            "text", "graph_extraction", "image", "pdf", "json_schema",
            "tools", "embedding", "tensor", "learning");
    private static final Set<String> SECRET_KEYS = Set.of(
            "apikey", "accesstoken", "refreshtoken", "authorization", "credentials",
            "password", "secret", "clientsecret", "headers", "cookie", "cookies", "token");

    private NativeChatModels() { }

    public static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        String value = provider.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("chat:")) value = value.substring(5);
        return switch (value) {
            case "chat", "active-chat", "configured-chat" -> null;
            case "codex" -> "openai-codex";
            case "claude", "claude-code" -> "anthropic";
            case "google" -> "gemini";
            default -> value;
        };
    }

    /** Reads settings only: no migration, save, credential refresh, catalog lookup, or inference. */
    public static Selection resolve(Path root, String provider, String model) throws IOException {
        return resolve(root, provider, model, null);
    }

    /**
     * Resolve a native chat selection with an optional request-scoped thinking override.
     * A blank/omitted override retains the inherited project/global policy; nonblank values
     * are applied only to the detached in-memory configuration used by this request.
     */
    public static Selection resolve(Path root, String provider, String model, String thinking) throws IOException {
        ChatConfig selected = configuration(root, provider);
        if (model != null && !model.isBlank()) selected.setModel(model.strip());
        if (thinking != null && !thinking.isBlank()) selected.setThinking(thinking.strip());
        if (selected.getModel() == null || selected.getModel().isBlank()) throw new IOException(
                "CHAT_MODEL requires an explicit model when no matching provider model is configured");
        try (DirectLlmClient client = new DirectLlmClient(selected, MAPPER, root)) {
            return new Selection(selected, client.resolveRoute(selected.getModel()).protocol());
        }
    }

    private static ChatConfig configuration(Path root, String provider) throws IOException {
        String requested = normalizeProvider(provider);
        ChatConfig selected = null;
        for (Path path : List.of(ChatConfig.projectConfigPath(root), ChatConfig.globalConfigPath())) {
            if (!Files.isRegularFile(path)) continue;
            ChatConfig candidate;
            try {
                candidate = MAPPER.readValue(path.toFile(), ChatConfig.class);
            } catch (IOException invalid) {
                throw new IOException("Cannot read native chat settings; repair chat-config.json before running a pipeline");
            }
            if (requested == null || requested.equals(normalizeProvider(candidate.getProvider()))) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) {
            if (requested == null) throw new IOException(
                    "CHAT_MODEL requires project/global chat settings or an explicit provider and model");
            // Never inherit another provider's endpoint, model, reasoning policy, or credentials.
            selected = new ChatConfig(requested, null, null, null);
        }
        if (requested == null && "passthrough".equalsIgnoreCase(selected.getChatMode())) {
            throw new IOException("CHAT_MODEL requires standard native chat; select an explicit provider to avoid passthrough mode");
        }
        String canonical = requested == null ? normalizeProvider(selected.getProvider()) : requested;
        if (canonical == null) throw new IOException("CHAT_MODEL has no configured provider");
        selected.setProvider(canonical);
        selected.setChatMode("standard");
        ChatProvider descriptor = ChatProviderRegistry.find(canonical);
        // The configurable OpenAI-compatible endpoint is a ChatConfig built-in, not an SPI provider.
        if (descriptor == null && !"custom".equals(canonical)) throw new IOException("Unknown native chat provider: " + canonical);
        if (selected.isKompileLocalServing()) throw new IOException(
                "CHAT_MODEL cannot run local artifacts. Use UNIFIED_PIPELINE for local serving models");
        if (selected.isKompileServer() || selected.isOpenCodeNative()) throw new IOException(
                "CHAT_MODEL requires a direct native chat provider, not a managed server or CLI runtime");
        if ((selected.getBaseUrl() == null || selected.getBaseUrl().isBlank())
                && (ChatConfig.getDefaultBaseUrl(canonical) == null || ChatConfig.getDefaultBaseUrl(canonical).isBlank())) {
            throw new IOException("CHAT_MODEL provider requires an endpoint in its matching chat configuration");
        }
        return selected;
    }

    /** Explicit catalog lookup using native chat discovery/auth, not an inference probe or a model allowlist. */
    public static Map<String, Object> listModels(Path root, String provider) throws IOException {
        ChatConfig selected = configuration(root, provider);
        ModelDiscovery.Result catalog;
        try {
            catalog = ModelDiscoveryHttp.discoverResultWithAuth(
                    selected.getProvider(), selected.resolveRequestAuth(), selected.getBaseUrl());
        } catch (RuntimeException unavailable) {
            // Auth/transport exceptions may contain credentials or endpoint query strings.
            catalog = ModelDiscovery.Result.failure(ModelDiscovery.Status.UNAVAILABLE, "", List.of());
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (var model : catalog.models()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", model.id());
            entry.put("thinkingVariants", model.variants());
            if (model.defaultVariant() != null) entry.put("defaultThinkingVariant", model.defaultVariant());
            entry.put("reasoningMandatory", model.reasoningMandatory());
            entry.put("modelSupport", "UNKNOWN");
            models.add(Map.copyOf(entry));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", selected.getProvider());
        result.put("catalogStatus", catalog.status().name());
        result.put("catalogSource", "native-chat-discovery (may use a recent provider catalog cache)");
        result.put("models", List.copyOf(models));
        if (selected.getModel() != null && !selected.getModel().isBlank()) result.put("configuredModel", selected.getModel());
        result.put("manualModelSelectionAllowed", true);
        result.put("readiness", "UNKNOWN");
        result.put("note", "Catalog discovery may contact the provider and refresh host authentication; it does not run inference. "
                + "Select any exact model id, including unlisted ids. A missing/failed catalog is not proof a model is unsupported. "
                + "Probe text, image, PDF-page images, graph_extraction or json_schema separately for that provider/model; "
                + "thinking variants and catalog membership do not establish modality support.");
        // Never expose catalog messages/endpoints: they may echo credentials or remote response bodies.
        return Map.copyOf(result);
    }

    /** Selection deliberately does not serialize or print its private, possibly credential-bearing config. */
    public static final class Selection {
        private final ChatConfig config;
        private final DirectLlmClient.WireProtocol protocol;

        private Selection(ChatConfig config, DirectLlmClient.WireProtocol protocol) {
            this.config = config;
            this.protocol = protocol;
        }

        public String provider() { return config.getProvider(); }
        public String model() { return config.getModel(); }
        public String thinking() { return config.getThinking(); }

        private boolean supports(String operation) {
            return switch (operation) {
                case "text", "graph_extraction" -> true;
                case "image", "pdf" -> protocol == DirectLlmClient.WireProtocol.OPENAI_CHAT
                        || protocol == DirectLlmClient.WireProtocol.OPENAI_RESPONSES
                        || protocol == DirectLlmClient.WireProtocol.ANTHROPIC_MESSAGES;
                // DirectLlmClient does not emit strict schemas on Anthropic/custom chat routes.
                case "json_schema" -> protocol == DirectLlmClient.WireProtocol.OPENAI_RESPONSES
                        || (protocol == DirectLlmClient.WireProtocol.OPENAI_CHAT && "openai".equals(provider()));
                default -> false;
            };
        }

        public void requireSupported(String operation) throws IOException {
            if (operation == null || !supports(operation)) throw new IOException(
                    "CHAT_MODEL does not support operation '" + operation + "' on " + protocol
                            + "; chat is not an embedding, tensor, native-tool, or learning executor");
        }

        public Map<String, Object> preview() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "RESOLVED");
            result.put("execution", "CHAT_MODEL");
            result.put("provider", provider());
            result.put("model", model());
            if (thinking() != null && !thinking().isBlank()) result.put("thinking", thinking());
            result.put("protocol", protocol.name());
            result.put("readiness", "UNKNOWN");
            result.put("authentication", "NOT_CHECKED");
            result.put("credentialSource", "project/global chat configuration and provider credential store");
            result.put("credentialsPersistedInCrawl", false);
            result.put("localModelArtifactRequired", false);
            Map<String, Object> capabilities = new LinkedHashMap<>();
            for (String operation : OPERATIONS) capabilities.put(operation, Map.of(
                    "adapterSupport", supports(operation) ? "SUPPORTED" : "UNSUPPORTED",
                    "modelSupport", supports(operation) ? "UNKNOWN" : "NOT_APPLICABLE"));
            result.put("capabilities", capabilities);
            result.put("note", "Adapter support is not model or authentication proof. Live probes are opt-in, synthetic smoke tests only. "
                    + "Graph extraction uses validated text, not native tool choice. PDF pages use image attachments.");
            return Map.copyOf(result);
        }
    }

    public static List<Map<String, Object>> providers() {
        Map<String, ChatProvider> descriptors = new LinkedHashMap<>();
        for (ChatProvider provider : ChatProviderRegistry.all()) descriptors.put(provider.id(), provider);
        descriptors.put("openai-codex", ChatProviderRegistry.find("openai-codex"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatProvider provider : descriptors.values()) {
            if (provider == null) continue;
            boolean direct = !Set.of("kompile", "kompile-local", "opencode").contains(provider.id())
                    && (provider.defaultBaseUrl() != null || "custom".equals(provider.id()));
            result.add(Map.of("provider", provider.id(), "displayName", provider.displayName(),
                    "nativeChatAdapter", direct ? "AVAILABLE" : "UNSUPPORTED",
                    "readiness", "UNKNOWN", "modelCapabilities", "NOT_PROBED"));
        }
        if (!descriptors.containsKey("custom")) {
            result.add(Map.of("provider", "custom", "displayName", "OpenAI-compatible endpoint",
                    "nativeChatAdapter", "AVAILABLE", "readiness", "UNKNOWN", "modelCapabilities", "NOT_PROBED"));
        }
        return List.copyOf(result);
    }

    public static String complete(Path root, String provider, String model, String prompt,
                                  String systemPrompt, Duration timeout) throws Exception {
        return complete(root, provider, model, null, prompt, systemPrompt, timeout);
    }

    public static String complete(Path root, String provider, String model, String thinking,
                                  String prompt, String systemPrompt, Duration timeout) throws Exception {
        return call(root, resolve(root, provider, model, thinking), prompt, systemPrompt, List.of(), null,
                timeout, 4_000_000);
    }

    /** Bounded isolated call; no tool definitions/history and no credentials in returned diagnostics. */
    public static String call(Path root, Selection selection, String prompt, String systemPrompt,
                              List<DirectLlmClient.AttachmentInput> attachments, JsonNode schema,
                              Duration timeout, int maxResponseChars) throws Exception {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive chat timeout required");
        if (maxResponseChars < 1) throw new IllegalArgumentException("Positive response limit required");
        selection.requireSupported(schema != null ? "json_schema" : attachments.isEmpty() ? "text" : "image");
        if (schema != null && (!schema.isObject() || !attachments.isEmpty())) throw new IOException(
                "CHAT_MODEL strict JSON requires a schema object and text-only input; combined image/schema requests are unsupported");
        Thread owner = Thread.currentThread();
        if (owner.isInterrupted()) throw new InterruptedException("CHAT_MODEL cancelled");
        AtomicLong streamedChars = new AtomicLong();
        // Thread-pool owners may be reused and blocking reads may clear interrupts.
        // Retain cancellation until the underlying request has actually exited.
        AtomicBoolean cancelled = new AtomicBoolean();
        FutureTask<String> request = new FutureTask<>(() -> {
            ProviderConnectivityPolicy defaults = selection.config.connectivityPolicy();
            ProviderConnectivityPolicy policy = new ProviderConnectivityPolicy(
                    min(defaults.connectTimeout(), timeout), timeout, min(defaults.streamIdleTimeout(), timeout),
                    timeout, 1, defaults.initialBackoff(), defaults.maxBackoff());
            try (DirectLlmClient client = DirectLlmClient.withConnectivityPolicy(selection.config, MAPPER, policy, root)) {
                Thread worker = Thread.currentThread();
                client.setCancellationCheck(() -> cancelled.get() || owner.isInterrupted() || worker.isInterrupted()
                        || streamedChars.get() > maxResponseChars);
                client.setOutputConsumer(chunk -> {
                    if (chunk != null && streamedChars.addAndGet(chunk.length()) > maxResponseChars)
                        throw new IllegalStateException("CHAT_MODEL response exceeds maxResponseChars=" + maxResponseChars);
                });
                if (!selection.config.isValid()) throw new IOException(
                        "Native chat provider authentication/configuration is unavailable; configure this provider with kompile chat --setup");
                DirectLlmClient.StreamResult response = schema == null
                        ? client.streamChat(prompt, systemPrompt, null, null, selection.model(), attachments)
                        : client.streamOneShotJson(prompt, systemPrompt, selection.model(), "pipeline_output", schema, true);
                if (streamedChars.get() > maxResponseChars) throw new IOException("CHAT_MODEL response exceeds maxResponseChars=" + maxResponseChars);
                if (cancelled.get() || worker.isInterrupted() || owner.isInterrupted() || (response != null && response.cancelled))
                    throw new InterruptedException("CHAT_MODEL cancelled");
                if (response == null) throw new IOException("Native chat provider returned no result");
                if (response.failed) {
                    String detail = safeFailureDetail(response.failureMessage);
                    String suffix = detail == null ? "" : ", detail=" + detail;
                    throw new IOException("Native chat request failed: provider=" + selection.provider()
                            + ", kind=" + response.failureKind + ", HTTP=" + response.failureStatusCode + suffix);
                }
                if (response.toolCalls != null && !response.toolCalls.isEmpty()) throw new IOException(
                        "CHAT_MODEL received unexpected tool calls; this operation accepts only text");
                String text = response.text == null ? "" : response.text.strip();
                if (text.isEmpty()) throw new IOException("Native chat provider returned empty text");
                if (text.length() > maxResponseChars) throw new IOException("CHAT_MODEL response exceeds maxResponseChars=" + maxResponseChars);
                if (schema != null) {
                    try { MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text); }
                    catch (IOException invalid) { throw new IOException("Native chat provider returned invalid JSON for strict schema output"); }
                }
                return text;
            }
        });
        ACTIVE_REQUESTS.merge(owner, 1, Integer::sum);
        Thread worker = new Thread(() -> {
            try { request.run(); }
            finally { ACTIVE_REQUESTS.computeIfPresent(owner, (key, count) -> count <= 1 ? null : count - 1); }
        }, "mcp-native-chat");
        worker.setDaemon(true);
        worker.start();
        try {
            return request.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            throw new TimeoutException("CHAT_MODEL request timed out after " + timeout);
        } catch (InterruptedException interrupted) {
            owner.interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw new IOException("Native chat execution failed");
        } finally {
            cancelled.set(true);
            request.cancel(true);
        }
    }

    /** No network by default. Live=true spends one bounded request using synthetic data, never project content. */
    public static Map<String, Object> probe(Path root, String provider, String model, String operation,
                                            boolean live, Duration timeout) throws Exception {
        return probe(root, provider, model, null, operation, live, timeout);
    }

    public static Map<String, Object> probe(Path root, String provider, String model, String thinking,
                                            String operation, boolean live, Duration timeout) throws Exception {
        Selection selection = resolve(root, provider, model, thinking);
        String requested = operation == null ? "text" : operation;
        selection.requireSupported(requested);
        Map<String, Object> result = new LinkedHashMap<>(selection.preview());
        result.put("operation", requested);
        result.put("live", live);
        result.put("probeStatus", "NOT_PROBED");
        if (!live) return Map.copyOf(result);
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Live probe timeout must be positive and at most 60 seconds");
        String marker = "probe-" + UUID.randomUUID();
        List<DirectLlmClient.AttachmentInput> attachments = List.of();
        JsonNode schema = null;
        JsonNode expectedGraph = null;
        String prompt = "Return exactly this text and nothing else: " + marker;
        if ("image".equals(requested) || "pdf".equals(requested)) {
            // The answer exists only in randomized pixels, not in the prompt or a fixed fixture.
            String[] names = {"RED", "GREEN", "BLUE", "YELLOW", "BLACK", "WHITE"};
            int[] colors = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x000000, 0xFFFFFF};
            SecureRandom random = new SecureRandom();
            List<String> expectedColors = new ArrayList<>();
            BufferedImage image = new BufferedImage(432, 96, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                graphics.setColor(Color.GRAY);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                for (int tile = 0; tile < 6; tile++) {
                    int index = random.nextInt(colors.length);
                    expectedColors.add(names[index]);
                    graphics.setColor(new Color(colors[index]));
                    graphics.fillRect(tile * 72 + 4, 8, 64, 80);
                }
                ImageIO.write(image, "png", bytes);
                attachments = List.of(new DirectLlmClient.AttachmentInput("probe.png", "image/png", true,
                        Base64.getEncoder().encodeToString(bytes.toByteArray()), null));
            } finally {
                graphics.dispose();
                image.flush();
            }
            prompt = "Identify the colors of the six squares from left to right, ignoring the gray background. "
                    + "Use RED, GREEN, BLUE, YELLOW, BLACK or WHITE. Colors may repeat. "
                    + "Return only six uppercase color names separated by commas, without spaces.";
            marker = String.join(",", expectedColors);
        } else if ("graph_extraction".equals(requested)) {
            var graph = MAPPER.createObjectNode();
            graph.putArray("entities").addObject().put("name", "Ada").put("type", "PERSON");
            graph.withArray("entities").addObject().put("name", marker).put("type", "ORGANIZATION");
            graph.putArray("relations").addObject().put("source", "Ada").put("target", marker).put("type", "WORKS_AT");
            expectedGraph = graph;
            prompt = "Extract the graph from this synthetic sentence: Ada works at " + marker
                    + ". Return only JSON with entities (name,type) and relations (source,target,type). "
                    + "Use PERSON, ORGANIZATION, WORKS_AT. Order the person before the organization; no extra fields.";
        } else if ("json_schema".equals(requested)) {
            schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}");
            prompt = "Return an object with ok set to true.";
        }
        String output;
        try {
            output = call(root, selection, prompt, "Follow the probe output contract exactly.", attachments, schema, timeout, 4096);
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failed) {
            result.put("probeStatus", "FAILED");
            result.put("failureKind", failed instanceof TimeoutException ? "TIMEOUT" : "REQUEST_FAILED");
            result.put("authentication", "NOT_ESTABLISHED");
            result.put("evidence", "The synthetic request did not complete successfully. Authentication, transport or output failures "
                    + "do not establish that this model lacks the requested capability; support remains UNKNOWN.");
            return Map.copyOf(result);
        }
        boolean matched;
        if (expectedGraph != null || schema != null) {
            try {
                JsonNode parsed = MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
                matched = expectedGraph != null ? expectedGraph.equals(parsed)
                        : parsed.isObject() && parsed.size() == 1 && parsed.path("ok").isBoolean() && parsed.path("ok").asBoolean();
            } catch (IOException invalid) { matched = false; }
        } else {
            matched = marker.equals(output);
        }
        result.put("probeStatus", matched ? "PASSED" : "INCONCLUSIVE");
        result.put("readiness", matched ? "PROBED" : "UNKNOWN");
        result.put("authentication", "REQUEST_ACCEPTED");
        Map<String, Object> capabilities = new LinkedHashMap<>();
        for (String candidate : OPERATIONS) capabilities.put(candidate, Map.of(
                "adapterSupport", selection.supports(candidate) ? "SUPPORTED" : "UNSUPPORTED",
                "modelSupport", matched && candidate.equals(requested) ? "PROBED"
                        : selection.supports(candidate) ? "UNKNOWN" : "NOT_APPLICABLE"));
        result.put("capabilities", capabilities);
        result.put("evidence", "One synthetic " + requested + " request for this exact provider/model; "
                + "readiness applies only to this operation. No guarantee for other models, operations or future requests. "
                + "PDF probes test rendered-page image understanding, not native PDF parsing.");
        return Map.copyOf(result);
    }

    /** Native credentials are host-owned, including on the document-crawl entry point. */
    public static void rejectInlineCredentials(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SECRET_KEYS.contains(key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT)))
                    throw new IOException("CHAT_MODEL credentials belong in host chat configuration, not pipeline JSON");
                if (!"jsonSchema".equals(key)) rejectInlineCredentials(entry.getValue());
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) rejectInlineCredentials(item);
        }
    }

    /** Cancellation acknowledgement is not proof that the native HTTP/auth worker has stopped. */
    public static boolean hasActiveRequest(Thread owner) {
        return owner != null && ACTIVE_REQUESTS.containsKey(owner);
    }

    private static String safeFailureDetail(String failureMessage) {
        if (failureMessage == null || failureMessage.isBlank()) return null;
        String lower = failureMessage.toLowerCase(Locale.ROOT);
        for (String sensitive : List.of("secret", "private source", "api_key", "apikey",
                "authorization", "bearer ", "password", "credential", "cookie", "token")) {
            if (lower.contains(sensitive)) return null;
        }
        String compact = failureMessage.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (compact.length() > 600) compact = compact.substring(0, 600) + "...";
        return compact;
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
}
