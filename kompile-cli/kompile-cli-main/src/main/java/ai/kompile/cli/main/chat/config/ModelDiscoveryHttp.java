/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelDiscoveryHttp {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PAGES = 20;
    private static final ObjectMapper MAPPER = ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final ModelDiscoveryCache CACHE = new ModelDiscoveryCache();
    private ModelDiscoveryHttp() {}
    public static ModelDiscovery.Result discoverResult(String provider, String key, String baseOverride) {
        return discoverResult(provider, key, baseOverride, false);
    }

    /**
     * Forces a live discovery request and refreshes the provider cache.
     */
    public static ModelDiscovery.Result refreshResult(String provider, String key, String baseOverride) {
        return discoverResult(provider, key, baseOverride, true);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static ModelDiscovery.Result discoverResult(
            String provider, String key, String baseOverride, boolean forceRefresh) {
        AgentProvider agent = findAgent(provider);
        if (agent != null && agent.getModelListCommand() != null
                && !agent.getModelListCommand().isEmpty()) {
            return ModelDiscovery.Result.success(
                    LiveModelDiscovery.discoverNative(provider),
                    List.of("native:" + provider));
        }

        ChatProvider descriptor = ChatProviderRegistry.find(provider);
        if (descriptor != null && !descriptor.modelDiscoveryRequiresBaseUrl()) {
            try {
                ChatConfig authProbe = new ChatConfig(
                        provider,
                        descriptor.supportsApiKey() ? key : null,
                        "model-discovery",
                        null);
                OAuthProviderFlow.RequestAuth auth = "openai-codex".equals(normalize(provider))
                        ? authProbe.resolveRequestAuth()
                        : null;
                return descriptor.modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                        provider, null, descriptor.supportsApiKey() ? key : null,
                        auth, null, TIMEOUT));
            } catch (RuntimeException error) {
                return ModelDiscovery.Result.failure(
                        ModelDiscovery.Status.UNAVAILABLE,
                        message(error),
                        List.of("provider-runtime:" + provider));
            }
        }

        ChatConfig probe = new ChatConfig(provider, key, "model-discovery", baseOverride);
        OAuthProviderFlow.RequestAuth auth = probe.resolveRequestAuth();
        String base = probe.resolveBaseUrl(auth);
        if (base == null || base.isBlank()) {
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNSUPPORTED,
                    "Provider has no configured model discovery endpoint", List.of());
        }
        String cacheIdentity = authIdentity(key, auth);
        if (!forceRefresh) {
            Optional<ModelDiscovery.Result> fresh = CACHE.fresh(provider, base, cacheIdentity);
            if (fresh.isPresent()) {
                return fresh.get();
            }
        }
        ModelDiscovery.Strategy strategy = descriptor == null ? ModelDiscoveryHttp::legacy
                : descriptor.modelDiscoveryStrategy();
        try {
            ModelDiscovery.Result live = strategy.discover(new ModelDiscovery.Context(
                    provider, base, key, auth, null, TIMEOUT));
            if (live.isUsable()) {
                CACHE.put(provider, base, cacheIdentity, live);
                return live;
            }
            Optional<ModelDiscovery.Result> stale = CACHE.stale(provider, base, cacheIdentity);
            if (stale.isPresent()) {
                String detail = live.message().isBlank() ? live.status().name().toLowerCase(Locale.ROOT)
                        : live.message();
                return new ModelDiscovery.Result(ModelDiscovery.Status.SUCCESS, stale.get().models(),
                        "Live discovery failed; using cached models: " + detail,
                        live.attemptedEndpoints());
            }
            return live;
        } catch (RuntimeException error) {
            Optional<ModelDiscovery.Result> stale = CACHE.stale(provider, base, cacheIdentity);
            if (stale.isPresent()) {
                return new ModelDiscovery.Result(ModelDiscovery.Status.SUCCESS, stale.get().models(),
                        "Live discovery failed; using cached models: " + message(error), List.of());
            }
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNAVAILABLE,
                    message(error), List.of());
        }
    }

    static ModelDiscovery.Result legacy(ModelDiscovery.Context context) {
        return discover(context, candidateUrls(context.providerId(), context.baseUrl()));
    }

    static ModelDiscovery.Result discover(ModelDiscovery.Context context, List<String> endpoints) {
        List<String> routes = endpoints == null ? List.of() : endpoints.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (routes.isEmpty()) {
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNSUPPORTED,
                    "Provider has no model discovery endpoint", List.of());
        }
        ModelDiscovery.Status last = ModelDiscovery.Status.UNAVAILABLE;
        String detail = "Model discovery endpoint was unavailable";
        List<String> attempted = new ArrayList<>();
        Map<String, LiveModelDiscovery.Model> models = new LinkedHashMap<>();
        for (String route : routes) {
            String pageEndpoint = route;
            for (int page = 0; page < MAX_PAGES && pageEndpoint != null; page++) {
                attempted.add(pageEndpoint);
                try {
                    HttpResult result = request(context, pageEndpoint);
                    if (result.status() == ModelDiscovery.Status.SUCCESS
                            || result.status() == ModelDiscovery.Status.SUCCESS_EMPTY) {
                        result.models().forEach(model ->
                                models.merge(model.id(), model, LiveModelDiscovery::merge));
                        String token = result.nextPageToken();
                        if (token == null || token.isBlank()) {
                            return ModelDiscovery.Result.success(new ArrayList<>(models.values()), attempted);
                        }
                        String next = nextPageEndpoint(context.providerId(), route, token);
                        if (next == null || next.equals(pageEndpoint)) {
                            detail = "Provider returned a repeating pagination token";
                            break;
                        }
                        pageEndpoint = next;
                        continue;
                    }
                    last = result.status();
                    detail = result.message();
                    if (!models.isEmpty()) {
                        return new ModelDiscovery.Result(ModelDiscovery.Status.SUCCESS,
                                new ArrayList<>(models.values()),
                                "Pagination stopped after a provider error: " + detail, attempted);
                    }
                    if (last == ModelDiscovery.Status.AUTH_REQUIRED
                            || last == ModelDiscovery.Status.FORBIDDEN
                            || last == ModelDiscovery.Status.RATE_LIMITED
                            || last == ModelDiscovery.Status.INVALID_RESPONSE) {
                        break;
                    }
                } catch (RuntimeException error) {
                    last = ModelDiscovery.Status.UNAVAILABLE;
                    detail = message(error);
                    if (!models.isEmpty()) {
                        return new ModelDiscovery.Result(ModelDiscovery.Status.SUCCESS,
                                new ArrayList<>(models.values()),
                                "Pagination stopped after a provider error: " + detail, attempted);
                    }
                }
                pageEndpoint = null;
            }
            if (!models.isEmpty()) {
                return ModelDiscovery.Result.success(new ArrayList<>(models.values()), attempted);
            }
        }
        return ModelDiscovery.Result.failure(last, detail, attempted);
    }

    static String nextPageEndpoint(String provider, String base, String token) {
        if (base == null || base.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        String parameter = switch (normalize(provider)) {
            case "gemini" -> "pageToken";
            case "anthropic" -> "after_id";
            case "openai", "openrouter", "xai", "deepseek", "groq" -> "after";
            default -> null;
        };
        return parameter == null ? null : addQuery(base, parameter, token);
    }

    private static String addQuery(String endpoint, String name, String value) {
        return endpoint + (endpoint.contains("?") ? "&" : "?")
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String endpoint(String base, String path) {
        if (base == null || base.isBlank() || path == null || path.isBlank()) return null;
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    static String endpointWithoutSuffix(String base, String suffix, String path) {
        if (base == null || base.isBlank()) return null;
        String value = base.replaceAll("/+$", "");
        if (suffix != null && !suffix.isBlank() && value.endsWith(suffix)) {
            value = value.substring(0, value.length() - suffix.length());
        }
        return endpoint(value, path);
    }

    static List<String> candidateUrls(String provider, String base) {
        if (base == null || base.isBlank()) return List.of();
        String value = base.trim().replaceAll("/+$", "");
        String vendor = normalize(provider);
        Set<String> routes = new LinkedHashSet<>();
        if ("ollama".equals(vendor)) {
            routes.add(endpointWithoutSuffix(value, "/v1", "/api/tags"));
        } else if ("gemini".equals(vendor)) {
            routes.add(endpointWithoutSuffix(value, "/openai", "/models"));
            routes.add(endpoint(rootUrl(value), "/v1beta/models"));
        } else if ("radius".equals(vendor)) {
            routes.add(versioned(value, "/config"));
        } else {
            routes.add(versioned(value, "/models"));
        }
        return routes.stream().filter(item -> item != null && !item.isBlank()).toList();
    }

    private static String versioned(String base, String route) {
        return base.endsWith("/v1") || base.endsWith("/openai/v1") || base.endsWith("/v1beta")
                ? endpoint(base, route) : endpoint(base, "/v1" + route);
    }

    private static HttpResult request(ModelDiscovery.Context context, String endpoint) {
        try {
            String url = endpoint;
            OAuthProviderFlow.RequestAuth auth = context.auth();
            if (auth == null && context.transientApiKey() != null
                    && !context.transientApiKey().isBlank()) {
                auth = OAuthProviderFlow.RequestAuth.apiKey(context.transientApiKey());
            }
            String vendor = normalize(context.providerId());
            if (auth != null && !auth.oauth() && "gemini".equals(vendor)
                    && auth.token() != null && !auth.token().isBlank()) {
                url += (url.contains("?") ? "&" : "?")
                        + "key=" + URLEncoder.encode(auth.token(), StandardCharsets.UTF_8);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(context.timeout()).GET();
            if (auth != null) {
                auth.headers().forEach(request::header);
                if (auth.token() != null && !auth.token().isBlank() && !auth.oauth()) {
                    switch (vendor) {
                        case "anthropic" -> {
                            request.header("x-api-key", auth.token());
                            request.header("anthropic-version", "2023-06-01");
                        }
                        case "gemini" -> request.header("x-goog-api-key", auth.token());
                        default -> request.header("Authorization", "Bearer " + auth.token());
                    }
                }
            }
            HttpResponse<String> response = context.httpClient().send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401) return fail(ModelDiscovery.Status.AUTH_REQUIRED, "Provider credentials were rejected");
            if (code == 403) return fail(ModelDiscovery.Status.FORBIDDEN, "Provider credentials cannot list models");
            if (code == 429) return fail(ModelDiscovery.Status.RATE_LIMITED, "Provider rate limited discovery");
            if (code / 100 != 2) return fail(ModelDiscovery.Status.UNAVAILABLE,
                    "Model discovery returned HTTP " + code);
            String body = response.body();
            if (body == null || body.isBlank()) return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                    "Provider returned an empty model response");
            JsonNode json = MAPPER.readTree(body);
            if (json == null || json.isNull() || json.isMissingNode()) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE, "Provider returned invalid model JSON");
            }
            List<LiveModelDiscovery.Model> models = LiveModelDiscovery.parseHttpModels(body, vendor);
            return new HttpResult(models.isEmpty() ? ModelDiscovery.Status.SUCCESS_EMPTY
                    : ModelDiscovery.Status.SUCCESS, models, "", pageToken(vendor, json));
        } catch (HttpTimeoutException error) {
            return fail(ModelDiscovery.Status.TIMEOUT, "Timed out while discovering provider models");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return fail(ModelDiscovery.Status.TIMEOUT, "Interrupted while discovering provider models");
        } catch (Exception error) {
            return fail(ModelDiscovery.Status.UNAVAILABLE, message(error));
        }
    }

    private static HttpResult fail(ModelDiscovery.Status status, String message) {
        return new HttpResult(status, List.of(), message, null);
    }

    private static String pageToken(String vendor, JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        if ("anthropic".equals(vendor) && !json.path("has_more").asBoolean(false)) {
            return null;
        }
        String value = switch (vendor) {
            case "gemini" -> json.path("nextPageToken").asText(null);
            case "anthropic" -> json.path("last_id").asText(null);
            default -> firstText(json, "nextPageToken", "next_page_token", "next", "after");
        };
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String authIdentity(String transientApiKey,
                                         OAuthProviderFlow.RequestAuth auth) {
        String token = auth != null ? auth.token() : transientApiKey;
        if (token == null || token.isBlank()) {
            return "";
        }
        String material = (auth != null && auth.oauth() ? "oauth:" : "key:") + token;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private static AgentProvider findAgent(String provider) {
        if (provider == null || provider.isBlank()) return null;
        return CliAgentRegistry.loadAll().stream()
                .filter(agent -> provider.equalsIgnoreCase(agent.getCommand())
                        || provider.equalsIgnoreCase(agent.getName())).findFirst().orElse(null);
    }

    private static String rootUrl(String base) {
        try {
            URI uri = URI.create(base);
            return uri.getScheme() == null || uri.getRawAuthority() == null
                    ? base : uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException ignored) {
            return base;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String message(Exception error) {
        return error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "Model discovery failed" : error.getMessage();
    }

    private record HttpResult(ModelDiscovery.Status status,
                              List<LiveModelDiscovery.Model> models, String message,
                              String nextPageToken) {
        private HttpResult {
            models = models == null ? List.of() : List.copyOf(models);
            message = message == null ? "" : message;
            nextPageToken = nextPageToken == null ? "" : nextPageToken;
        }
    }

}

final class ModelDiscoveryCache {
    static final Duration DEFAULT_FRESH_AGE = Duration.ofMinutes(5);
    static final Duration DEFAULT_STALE_AGE = Duration.ofHours(24);
    private static final int MAX_ENTRIES = 128;

    private final Clock clock;
    private final Duration freshAge;
    private final Duration staleAge;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    ModelDiscoveryCache() {
        this(Clock.systemUTC(), DEFAULT_FRESH_AGE, DEFAULT_STALE_AGE);
    }

    ModelDiscoveryCache(Clock clock, Duration freshAge, Duration staleAge) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.freshAge = requirePositive(freshAge, "freshAge");
        this.staleAge = requireAtLeast(freshAge, staleAge, "staleAge");
    }

    Optional<ModelDiscovery.Result> fresh(String provider, String baseUrl) {
        return fresh(provider, baseUrl, "");
    }

    Optional<ModelDiscovery.Result> fresh(String provider, String baseUrl, String authIdentity) {
        return find(provider, baseUrl, authIdentity, freshAge);
    }

    Optional<ModelDiscovery.Result> stale(String provider, String baseUrl) {
        return stale(provider, baseUrl, "");
    }

    Optional<ModelDiscovery.Result> stale(String provider, String baseUrl, String authIdentity) {
        return find(provider, baseUrl, authIdentity, staleAge);
    }

    void put(String provider, String baseUrl, ModelDiscovery.Result result) {
        put(provider, baseUrl, "", result);
    }

    void put(String provider, String baseUrl, String authIdentity, ModelDiscovery.Result result) {
        if (provider == null || provider.isBlank() || baseUrl == null || baseUrl.isBlank()
                || result == null || !result.isUsable()) {
            return;
        }
        Key key = new Key(provider, baseUrl, authIdentity);
        if (!entries.containsKey(key) && entries.size() >= MAX_ENTRIES) {
            entries.keySet().stream().findFirst().ifPresent(entries::remove);
        }
        entries.put(key, new Entry(result, Instant.now(clock)));
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private Optional<ModelDiscovery.Result> find(
            String provider, String baseUrl, String authIdentity, Duration maxAge) {
        if (provider == null || provider.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        Entry entry = entries.get(new Key(provider, baseUrl, authIdentity));
        if (entry == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(entry.createdAt(), Instant.now(clock));
        if (age.isNegative() || age.compareTo(maxAge) > 0) {
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requireAtLeast(Duration lower, Duration value, String name) {
        if (value == null || value.compareTo(lower) < 0) {
            throw new IllegalArgumentException(name + " must be at least freshAge");
        }
        return value;
    }

    private record Key(String provider, String baseUrl, String authIdentity) {
        private Key {
            provider = normalize(provider);
            baseUrl = normalizeBase(baseUrl);
            authIdentity = authIdentity == null ? "" : authIdentity;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }

        private static String normalizeBase(String value) {
            return value == null ? "" : value.trim().replaceAll("/+$", "");
        }
    }

    private record Entry(ModelDiscovery.Result result, Instant createdAt) {
    }
}
