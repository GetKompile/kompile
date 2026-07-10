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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resolves the real context window for whatever model the chat is talking to.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>{@link ModelContextWindows} — dynamic per-model metadata from the CLI agents'
 *       on-disk catalogs first, then the static fallback table. Covers every hosted
 *       provider model (Claude, GPT, Gemini, DeepSeek, Ollama library models…).</li>
 *   <li>For models the catalogs don't know — typically a kompile-staged local GGUF
 *       served on a loopback OpenAI endpoint — probe the serving origin's
 *       {@code /api/llm/status} (the kompile staging/serving convention) and use its
 *       {@code maxContextLength}. A staged 4K model must NOT inherit the 128K default.</li>
 *   <li>Otherwise fall back to {@link ModelContextWindows#getContextWindow(String)}'s
 *       default.</li>
 * </ol>
 *
 * <p>Probe results are cached per (baseUrl, model) for a short TTL so the REPL does not
 * hit the status endpoint on every agentic step.</p>
 */
public class ModelContextResolver {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final long CACHE_TTL_MS = 30_000L;

    private final Function<URI, Optional<JsonNode>> statusFetcher;
    private final Map<String, CachedWindow> cache = new ConcurrentHashMap<>();

    private record CachedWindow(int contextWindow, long expiresAtMs) {}

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
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride
                : (config != null ? config.getModel() : null);
        return resolveContextWindow(model, config != null ? config.resolveBaseUrl() : null);
    }

    /**
     * Resolve the context window (tokens) for a model served at {@code baseUrl}.
     */
    public int resolveContextWindow(String model, String baseUrl) {
        // Catalog-known models (dynamic CLI catalog first, static table second) are authoritative.
        if (ModelContextWindows.isKnown(model)) {
            return ModelContextWindows.getContextWindow(model);
        }

        // Unknown model on a local endpoint: likely a kompile-staged GGUF — ask the server.
        if (isLocalEndpoint(baseUrl)) {
            Optional<Integer> probed = probeLocalContextWindow(baseUrl, model);
            if (probed.isPresent()) {
                return probed.get();
            }
        }

        return ModelContextWindows.getContextWindow(model);
    }

    private Optional<Integer> probeLocalContextWindow(String baseUrl, String model) {
        String key = baseUrl + "|" + (model == null ? "" : model);
        long now = System.currentTimeMillis();
        CachedWindow cached = cache.get(key);
        if (cached != null && now < cached.expiresAtMs()) {
            return cached.contextWindow() > 0 ? Optional.of(cached.contextWindow()) : Optional.empty();
        }

        int window = 0;
        try {
            URI statusUri = URI.create(originOf(baseUrl) + "/api/llm/status");
            JsonNode status = statusFetcher.apply(statusUri).orElse(null);
            if (status != null && status.path("loaded").asBoolean(false)) {
                int reported = status.path("maxContextLength").asInt(0);
                if (reported > 0) {
                    window = reported;
                }
            }
        } catch (Exception ignored) {
            // Unreachable/foreign local server — negative result is cached below.
        }

        cache.put(key, new CachedWindow(window, now + CACHE_TTL_MS));
        return window > 0 ? Optional.of(window) : Optional.empty();
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
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
