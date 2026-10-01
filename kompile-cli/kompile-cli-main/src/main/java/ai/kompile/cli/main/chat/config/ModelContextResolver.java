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

import ai.kompile.core.llm.ModelContextWindows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Resolves the real context window, and whether images are accepted, for whatever model
 * the chat is talking to.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>Explicit overrides from the chat config.</li>
 *   <li>For a model served on a loopback endpoint — Kompile's own serving child
 *       ({@code kompile-local}) always, any other local server only when the catalogs
 *       don't know the model — the serving origin's {@code /api/llm/status} (the kompile
 *       staging/serving convention): {@code maxContextLength}, {@code maxOutputTokens},
 *       {@code supportsImageInput}. A staged 4K model must NOT inherit the 128K default,
 *       and a staged model whose name matches a catalog entry is still the model the
 *       child serves.</li>
 *   <li>{@link ModelContextWindows} — dynamic per-model metadata from the CLI agents'
 *       on-disk catalogs first, then the static fallback table. Covers every hosted
 *       provider model (Claude, GPT, Gemini, DeepSeek…).</li>
 *   <li>Otherwise the {@link ModelContextWindows} defaults.</li>
 * </ol>
 *
 * <p>A kompile-local status only counts when it names the chat's model: the port may
 * belong to another serving child by now. Probe results are cached per (baseUrl, model)
 * for a short TTL so the REPL does not hit the status endpoint on every agentic step.</p>
 */
public class ModelContextResolver {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final long CACHE_TTL_MS = 30_000L;
    private static final String KOMPILE_LOCAL = "kompile-local";

    private final Function<URI, Optional<JsonNode>> statusFetcher;
    private final Map<String, CachedStatus> cache = new ConcurrentHashMap<>();
    // The last status each model's server answered with. An idle-evicted kompile-local
    // child restarts on a new port, so a failed probe does not mean the model changed.
    private final Map<String, ServedModel> lastServed = new ConcurrentHashMap<>();

    /** Effective input/output limits for one provider/model pair. */
    public record ModelLimits(int contextWindow, int maxOutputTokens) {}

    /** What a serving status reported for its loaded model; imageInput is null when it didn't say. */
    private record ServedModel(ModelLimits limits, Boolean imageInput) {}

    private record CachedStatus(ServedModel served, long expiresAtMs) {}

    public ModelContextResolver() {
        this.statusFetcher = defaultStatusFetcher();
    }

    /** Test seam: inject a fake status fetcher instead of live HTTP. */
    public ModelContextResolver(Function<URI, Optional<JsonNode>> statusFetcher) {
        this.statusFetcher = statusFetcher;
    }

    /**
     * Resolve the context window (tokens) for the effective model of a chat turn.
     *
     * @param config        the chat config (provider, base URL, default model)
     * @param modelOverride optional per-agent model override; null/blank uses the config model
     */
    public int resolveContextWindow(ChatConfig config, String modelOverride) {
        return resolveLimits(config, modelOverride).contextWindow();
    }

    /** Resolve both limits using explicit overrides, provider-qualified metadata, and local status. */
    public ModelLimits resolveLimits(ChatConfig config, String modelOverride) {
        return resolveLimits(
                config != null ? config.getProvider() : null,
                effectiveModel(config, modelOverride),
                config != null ? config.resolveBaseUrl() : null,
                config != null ? config.getContextWindowTokens() : 0,
                config != null ? config.getMaxOutputTokens() : 0);
    }

    /**
     * {@link #resolveLimits(ChatConfig, String)} with the limits the provider reported
     * enforcing for this model. Only explicit overrides outrank a report; without a
     * report, or for a limit it left at 0, the catalogs decide.
     */
    public ModelLimits resolveLimits(ChatConfig config, String modelOverride, ModelLimits reported) {
        ModelLimits resolved = resolveLimits(config, modelOverride);
        if (reported == null) return resolved;
        boolean contextSet = config != null && config.getContextWindowTokens() > 0;
        boolean outputSet = config != null && config.getMaxOutputTokens() > 0;
        return new ModelLimits(
                contextSet || reported.contextWindow() <= 0 ? resolved.contextWindow() : reported.contextWindow(),
                outputSet || reported.maxOutputTokens() <= 0 ? resolved.maxOutputTokens() : reported.maxOutputTokens());
    }

    /** Backward-compatible context-only lookup. */
    public int resolveContextWindow(String model, String baseUrl) {
        return resolveLimits(null, model, baseUrl, 0, 0).contextWindow();
    }

    /**
     * Resolve the provider/model limits. Provider qualification matters because the
     * same model alias can have different limits in different live CLI catalogs.
     */
    public ModelLimits resolveLimits(String provider, String model, String baseUrl,
                                     int contextOverride, int outputOverride) {
        int context = contextOverride > 0 ? contextOverride : 0;
        int output = outputOverride > 0 ? outputOverride : 0;

        if ((context == 0 || output == 0) && servedStatusDecides(provider, model, baseUrl)) {
            Optional<ModelLimits> probed = probeLocal(baseUrl, provider, model)
                    .map(ServedModel::limits)
                    .filter(limits -> limits.contextWindow() > 0);
            if (probed.isPresent()) {
                if (context == 0) context = probed.get().contextWindow();
                if (output == 0) output = probed.get().maxOutputTokens();
            }
        }

        // Pass provider and literal model id separately: flattening them loses provenance when
        // an aggregator publishes ids such as "openai/gpt-6-astra" in its own namespace.
        if (context <= 0) context = ModelContextWindows.getContextWindow(provider, model);
        if (output <= 0) output = ModelContextWindows.getMaxOutputTokens(provider, model);
        return new ModelLimits(context, output);
    }

    /**
     * Whether the effective model accepts image input: the serving status for a model a
     * loopback server answers for (the same rule as the limits), else the catalogs.
     * Empty when nothing knows; callers must not read that as "no".
     */
    public Optional<Boolean> resolveImageInput(ChatConfig config, String modelOverride) {
        if (config == null) return Optional.empty();
        String provider = config.getProvider();
        String model = effectiveModel(config, modelOverride);
        String baseUrl = config.resolveBaseUrl();
        if (servedStatusDecides(provider, model, baseUrl)) {
            Optional<Boolean> served = probeLocal(baseUrl, provider, model).map(ServedModel::imageInput);
            if (served.isPresent()) return served;
        }
        return ModelContextWindows.supportsVision(provider, model);
    }

    private static String effectiveModel(ChatConfig config, String modelOverride) {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride;
        return config != null ? config.getModel() : null;
    }

    /**
     * Kompile's serving child always answers for its model, even when the name matches a
     * catalog entry; any other loopback server only for models the catalogs don't know.
     */
    private static boolean servedStatusDecides(String provider, String model, String baseUrl) {
        return isLocalEndpoint(baseUrl)
                && (KOMPILE_LOCAL.equals(provider) || !ModelContextWindows.isKnown(provider, model));
    }

    private static String qualify(String provider, String model) {
        if (model == null || model.isBlank() || model.contains("/")
                || provider == null || provider.isBlank()) {
            return model;
        }
        return provider.trim() + "/" + model.trim();
    }

    private Optional<ServedModel> probeLocal(String baseUrl, String provider, String model) {
        String qualified = qualify(provider, model);
        String modelKey = qualified == null ? "" : qualified;
        String key = baseUrl + "|" + modelKey;
        long now = System.currentTimeMillis();
        CachedStatus cached = cache.get(key);
        ServedModel served;
        if (cached != null && now < cached.expiresAtMs()) {
            served = cached.served();
        } else {
            // Negative results are cached too, so a dead port is not re-probed every step.
            served = fetchServedModel(baseUrl, provider, model);
            cache.put(key, new CachedStatus(served, now + CACHE_TTL_MS));
            if (served != null) lastServed.put(modelKey, served);
        }
        return Optional.ofNullable(served != null ? served : lastServed.get(modelKey));
    }

    /** The loaded model's status at the serving origin, or null when it can't answer for this model. */
    private ServedModel fetchServedModel(String baseUrl, String provider, String model) {
        try {
            URI statusUri = URI.create(originOf(baseUrl) + "/api/llm/status");
            JsonNode status = statusFetcher.apply(statusUri).orElse(null);
            if (status == null || !status.path("loaded").asBoolean(false)) return null;
            if (KOMPILE_LOCAL.equals(provider) && model != null && !model.isBlank()
                    && !model.trim().equals(status.path("modelId").asText("").trim())) {
                return null;
            }
            int output = status.path("maxOutputTokens").asInt(0);
            if (output <= 0) output = status.path("maxOutputLength").asInt(0);
            JsonNode imageInput = status.path("supportsImageInput");
            return new ServedModel(
                    new ModelLimits(status.path("maxContextLength").asInt(0), output),
                    imageInput.isBoolean() ? imageInput.booleanValue() : null);
        } catch (Exception ignored) {
            // Unreachable/foreign local server.
            return null;
        }
    }

    /**
     * The kompile staging/serving status endpoint lives at the server origin, not under
     * the OpenAI-compatible {@code /v1} path: {@code http://localhost:8090/v1} →
     * {@code http://localhost:8090}.
     */
    static String originOf(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static boolean isLocalEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host != null && (host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.equals("0.0.0.0"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Function<URI, Optional<JsonNode>> defaultStatusFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        return uri -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(PROBE_TIMEOUT)
                        .GET()
                        .build();
                CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(
                        request, HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> response;
                try {
                    response = pending.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } catch (Exception failure) {
                    // HttpRequest.timeout does not reliably cover a response body that
                    // sent headers/partial bytes and then stalled. Cancel the async
                    // exchange at the explicit probe deadline so chat can continue.
                    pending.cancel(true);
                    return Optional.empty();
                }
                if (response.statusCode() >= 400) {
                    return Optional.empty();
                }
                return Optional.of(mapper.readTree(response.body()));
            } catch (Exception e) {
                return Optional.empty();
            }
        };
    }
}
