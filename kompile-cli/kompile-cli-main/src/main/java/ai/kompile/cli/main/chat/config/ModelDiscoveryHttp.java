/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.CredentialFailure;
import java.util.concurrent.ExecutionException;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ModelDiscoveryHttp {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PAGES = 20;
    private static final int MAX_HTTP_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper MAPPER = ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final ModelDiscoveryCache CACHE = new ModelDiscoveryCache();
    private ModelDiscoveryHttp() {}
    public static ModelDiscovery.Result discoverResult(String provider, String key, String baseOverride) {
        return discoverResult(provider, key, null, baseOverride, false);
    }

    public static ModelDiscovery.Result discoverResultWithAuth(
            String provider,
            OAuthProviderFlow.RequestAuth auth,
            String baseOverride) {
        return discoverResult(provider, null, auth, baseOverride, false);
    }

    /**
     * Forces a live discovery request and refreshes the provider cache.
     */
    public static ModelDiscovery.Result refreshResult(String provider, String key, String baseOverride) {
        return discoverResult(provider, key, null, baseOverride, true);
    }

    public static ModelDiscovery.Result refreshResultWithAuth(String provider,
            OAuthProviderFlow.RequestAuth auth, String baseOverride) {
        return discoverResult(provider, null, auth, baseOverride, true);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static ModelDiscovery.Result discoverResult(
            String provider,
            String key,
            OAuthProviderFlow.RequestAuth providedAuth,
            String baseOverride,
            boolean forceRefresh) {
        ChatProvider descriptor = ChatProviderRegistry.find(provider);
        AgentProvider agent = findAgent(provider);
        if (ProviderModelCatalogs.find(provider) == null
                && agent != null && agent.isChatProvider()
                && agent.getModelListCommand() != null
                && !agent.getModelListCommand().isEmpty()) {
            List<LiveModelDiscovery.Model> models = LiveModelDiscovery.discoverNative(provider);
            return models.isEmpty()
                    ? ModelDiscovery.Result.failure(
                            ModelDiscovery.Status.UNAVAILABLE,
                            "Model discovery for the " + agent.getDisplayName()
                                    + " meta-provider requires the installed '" + agent.getCommand()
                                    + "' CLI. Install it, authenticate/configure it, and retry; '"
                                    + String.join(" ", agent.getModelListCommand())
                                    + "' failed or returned no models",
                            List.of("native:" + provider))
                    : ModelDiscovery.Result.success(models, List.of("native:" + provider));
        }
        String effectiveKey = providedAuth == null ? key : null;
        ChatConfig probe = new ChatConfig(provider, effectiveKey, "model-discovery", baseOverride);
        OAuthProviderFlow.RequestAuth auth;
        try {
            auth = providedAuth != null ? providedAuth : probe.resolveRequestAuth();
        } catch (ChatConfig.AuthenticationException e) {
            CredentialFailure failure = e.failure();
            return ModelDiscovery.Result.failure(credentialStatus(failure),
                    failure.message(), List.of());
        }
        if (descriptor != null && !descriptor.modelDiscoveryRequiresBaseUrl()) {
            String runtimeBase = auth != null && auth.baseUrl() != null
                    ? auth.baseUrl() : "provider-runtime:" + provider;
            String runtimeCacheIdentity = authIdentity(effectiveKey, auth);
            if (!forceRefresh) {
                Optional<ModelDiscovery.Result> fresh =
                        CACHE.fresh(provider, runtimeBase, runtimeCacheIdentity);
                if (fresh.isPresent()) {
                    ModelDiscovery.Result cached = fresh.get();
                    return new ModelDiscovery.Result(
                            ModelDiscovery.Status.SUCCESS,
                            cached.models(),
                            "Using a recently verified provider catalog cache; refresh to contact the provider now",
                            cached.attemptedEndpoints());
                }
            }
            try {
                ModelDiscovery.Result live = descriptor.modelDiscoveryStrategy().discover(
                        new ModelDiscovery.Context(
                                provider, null, descriptor.supportsApiKey() ? key : null,
                                auth, null, TIMEOUT));
                if (live.isUsable()) {
                    CACHE.put(provider, runtimeBase, runtimeCacheIdentity, live);
                } else if (live.status() == ModelDiscovery.Status.SUCCESS_EMPTY) {
                    CACHE.remove(provider, runtimeBase, runtimeCacheIdentity);
                }
                return live;
            } catch (RuntimeException error) {
                return ModelDiscovery.Result.failure(
                        ModelDiscovery.Status.UNAVAILABLE,
                        message(error),
                        List.of("provider-runtime:" + provider));
            }
        }

        String trustedBase = auth != null && auth.baseUrl() != null
                ? auth.baseUrl()
                : descriptor == null ? null : descriptor.defaultBaseUrl();
        if (auth != null && descriptor != null && baseOverride != null && !baseOverride.isBlank()
                && trustedBase != null && !sameOrigin(baseOverride, trustedBase)) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.FORBIDDEN,
                    "Refusing to send " + ChatProviderRegistry.label(provider)
                            + " credentials to an untrusted model catalog origin; use the custom provider for a different endpoint",
                    List.of());
        }
        String base = probe.resolveBaseUrl(auth);
        if (base == null || base.isBlank()) {
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNSUPPORTED,
                    "Provider has no configured model discovery endpoint", List.of());
        }
        String cacheIdentity = authIdentity(effectiveKey, auth);
        if (!forceRefresh) {
            Optional<ModelDiscovery.Result> fresh =
                    CACHE.fresh(provider, base, cacheIdentity);
            if (fresh.isPresent()) {
                ModelDiscovery.Result cached = fresh.get();
                return new ModelDiscovery.Result(
                        ModelDiscovery.Status.SUCCESS,
                        cached.models(),
                        "Using a recently verified provider catalog cache; refresh to contact the provider now",
                        cached.attemptedEndpoints());
            }
        }
        ModelDiscovery.Strategy strategy = descriptor == null ? ModelDiscoveryHttp::legacy
                : descriptor.modelDiscoveryStrategy();
        try {
            ModelDiscovery.Result live = strategy.discover(new ModelDiscovery.Context(
                    provider, base, effectiveKey, auth, null, TIMEOUT));
            if (live.isUsable()) {
                CACHE.put(provider, base, cacheIdentity, live);
            } else if (live.status() == ModelDiscovery.Status.SUCCESS_EMPTY) {
                CACHE.remove(provider, base, cacheIdentity);
            }
            return live;
        } catch (RuntimeException error) {
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNAVAILABLE,
                    "Unable to discover models from the live provider catalog: " + message(error),
                    List.of());
        }
    }

    static ModelDiscovery.Result legacy(ModelDiscovery.Context context) {
        return discover(context, candidateUrls(context.providerId(), context.baseUrl()));
    }

    static ModelDiscovery.Result discover(ModelDiscovery.Context context, List<String> endpoints) {
        return discover(context, endpoints,
                context == null ? null : ProviderModelCatalogs.find(context.providerId()));
    }

    static ModelDiscovery.Result discover(
            ModelDiscovery.Context context,
            List<String> endpoints,
            ProviderModelCatalogs.Descriptor descriptor) {
        return discover(context, endpoints, descriptor,
                (provider, rejected) -> OAuthCredentialManager.create()
                        .refreshAfterUnauthorized(provider, rejected));
    }

    @FunctionalInterface
    interface RefreshAuth {
        OAuthProviderFlow.RequestAuth refresh(String provider, OAuthProviderFlow.RequestAuth rejected)
                throws IOException;
    }

    private static final class Recovery {
        private OAuthProviderFlow.RequestAuth auth;
        private boolean attempted;
        private final RefreshAuth refresh;

        private Recovery(OAuthProviderFlow.RequestAuth auth, RefreshAuth refresh) {
            this.auth = auth;
            this.refresh = refresh;
        }
    }

    // Request-scoped injection: tests never touch the credential store or token endpoint.
    static ModelDiscovery.Result discover(
            ModelDiscovery.Context context, List<String> endpoints,
            ProviderModelCatalogs.Descriptor descriptor, RefreshAuth refresh) {
        Recovery recovery = new Recovery(context == null ? null : context.auth(), refresh);
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
        long deadlineNanos = deadlineNanos(context == null ? TIMEOUT : context.timeout());
        for (String route : routes) {
            String pageEndpoint = route;
            for (int page = 0; page < MAX_PAGES && pageEndpoint != null; page++) {
                if (remaining(deadlineNanos).isZero()) {
                    return ModelDiscovery.Result.failure(
                            ModelDiscovery.Status.TIMEOUT,
                            "Timed out before the provider model catalog was complete",
                            attempted);
                }
                attempted.add(pageEndpoint);
                try {
                    HttpResult result = request(context, pageEndpoint, descriptor, deadlineNanos, recovery);
                    if (remaining(deadlineNanos).isZero()) {
                        return ModelDiscovery.Result.failure(
                                ModelDiscovery.Status.TIMEOUT,
                                "Timed out before the provider model catalog was complete",
                                attempted);
                    }
                    if (result.status() == ModelDiscovery.Status.SUCCESS
                            || result.status() == ModelDiscovery.Status.SUCCESS_EMPTY) {
                        result.models().forEach(model ->
                                models.merge(model.id(), model, LiveModelDiscovery::merge));
                        if (remaining(deadlineNanos).isZero()) {
                            return ModelDiscovery.Result.failure(
                                    ModelDiscovery.Status.TIMEOUT,
                                    "Timed out while aggregating the provider model catalog",
                                    attempted);
                        }
                        String token = result.nextPageToken();
                        if (token == null || token.isBlank()) {
                            List<LiveModelDiscovery.Model> complete = new ArrayList<>(models.values());
                            if (remaining(deadlineNanos).isZero()) {
                                return ModelDiscovery.Result.failure(
                                        ModelDiscovery.Status.TIMEOUT,
                                        "Timed out while completing the provider model catalog",
                                        attempted);
                            }
                            return ModelDiscovery.Result.success(complete, attempted);
                        }
                        String next = nextPageEndpoint(context.providerId(), route, token, descriptor);
                        if (next == null || next.equals(pageEndpoint)) {
                            last = ModelDiscovery.Status.INVALID_RESPONSE;
                            detail = "Provider returned a repeating pagination token";
                            pageEndpoint = null;
                            break;
                        }
                        pageEndpoint = next;
                        continue;
                    }
                    last = result.status();
                    detail = result.message();
                    if (recovery.attempted || last == ModelDiscovery.Status.AUTH_REQUIRED
                            || last == ModelDiscovery.Status.FORBIDDEN
                            || last == ModelDiscovery.Status.RATE_LIMITED
                            || last == ModelDiscovery.Status.INVALID_RESPONSE
                            || last == ModelDiscovery.Status.TIMEOUT) {
                        return ModelDiscovery.Result.failure(last, detail, attempted);
                    }
                } catch (RuntimeException error) {
                    last = ModelDiscovery.Status.UNAVAILABLE;
                    detail = message(error);
                }
                pageEndpoint = null;
            }
            if (pageEndpoint != null) {
                return ModelDiscovery.Result.failure(
                        ModelDiscovery.Status.INVALID_RESPONSE,
                        "Provider model catalog exceeded the " + MAX_PAGES
                                + "-page discovery limit",
                        attempted);
            }
            if (!models.isEmpty()) break;
        }
        if (!models.isEmpty()) {
            return ModelDiscovery.Result.failure(last,
                    "Live model discovery was incomplete: " + detail, attempted);
        }
        return ModelDiscovery.Result.failure(last, detail, attempted);
    }

    static String nextPageEndpoint(String provider, String base, String token) {
        return nextPageEndpoint(provider, base, token, ProviderModelCatalogs.find(provider));
    }

    private static String nextPageEndpoint(
            String provider,
            String base,
            String token,
            ProviderModelCatalogs.Descriptor descriptor) {
        if (base == null || base.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        String parameter = descriptor == null ? null : descriptor.paginationQuery();
        return parameter == null || parameter.isBlank()
                ? null : addQuery(base, parameter, token);
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
        ProviderModelCatalogs.Descriptor descriptor = ProviderModelCatalogs.find(provider);
        if (descriptor != null) {
            String configured = descriptor.endpoint(base);
            return configured == null || configured.isBlank()
                    ? List.of() : List.of(configured);
        }
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

    private static HttpResult request(
            ModelDiscovery.Context context,
            String endpoint,
            ProviderModelCatalogs.Descriptor descriptor,
            long deadlineNanos, Recovery recovery) {
        try {
            if (context == null) {
                return fail(ModelDiscovery.Status.UNAVAILABLE,
                        "Model discovery has no provider context");
            }
            String url = endpoint;
            OAuthProviderFlow.RequestAuth auth = recovery.auth;
            if (auth == null && context.transientApiKey() != null
                    && !context.transientApiKey().isBlank()) {
                auth = OAuthProviderFlow.RequestAuth.apiKey(context.transientApiKey());
            }
            String vendor = normalize(context.providerId());
            if ("openai-codex".equals(vendor)
                    && (auth == null || !auth.oauth())) {
                return fail(ModelDiscovery.Status.AUTH_REQUIRED,
                        "Unable to discover OpenAI subscription models: sign in with the selected ChatGPT subscription and retry");
            }
            Duration requestBudget = remaining(deadlineNanos);
            if (requestBudget.isZero()) {
                return fail(ModelDiscovery.Status.TIMEOUT,
                        "Timed out before requesting the provider model catalog");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestBudget).GET();
            if ("anthropic".equals(vendor)) {
                request.header("anthropic-version", "2023-06-01");
            }
            if (auth != null) {
                auth.headers().forEach(request::header);
                if (auth.token() != null && !auth.token().isBlank() && !auth.oauth()) {
                    switch (vendor) {
                        case "anthropic" -> {
                            request.header("x-api-key", auth.token());
                        }
                        case "gemini" -> request.header("x-goog-api-key", auth.token());
                        default -> request.header("Authorization", "Bearer " + auth.token());
                    }
                }
            }
            HttpResponse<InputStream> response = context.httpClient().send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream input = response.body()) {
                int code = response.statusCode();
                if (code == 401) {
                    // Diagnostics are optional, bounded by both the call budget and 250 ms.
                    String diagnostic = "";
                    Duration budget = remaining(deadlineNanos);
                    if (!budget.isZero()) {
                        try {
                            byte[] bytes = readBodyWithDeadline(input,
                                    budget.compareTo(Duration.ofMillis(250)) < 0
                                            ? budget : Duration.ofMillis(250), 8192);
                            if (bytes.length <= 8192) diagnostic = new String(bytes, StandardCharsets.UTF_8);
                        } catch (InterruptedException interrupted) {
                            throw interrupted;
                        } catch (Exception ignored) { /* HTTP status remains authoritative. */ }
                    }
                    try { input.close(); } catch (IOException ignored) { }
                    DirectLlmClient.FailureKind kind = ProviderResponseFailure.classify(code, diagnostic);
                    if (kind == DirectLlmClient.FailureKind.PERMISSION_DENIED
                            || kind == DirectLlmClient.FailureKind.REFUSAL) {
                        return fail(ModelDiscovery.Status.FORBIDDEN, ProviderResponseFailure.message(kind));
                    }
                    if (kind == DirectLlmClient.FailureKind.QUOTA_EXHAUSTED) {
                        return fail(ModelDiscovery.Status.RATE_LIMITED, ProviderResponseFailure.message(kind));
                    }
                    if (recovery.attempted || auth == null || !auth.oauth()
                            || auth.credentialName() == null || auth.credentialName().isBlank()
                            || recovery.refresh == null) {
                        return fail(ModelDiscovery.Status.AUTH_REQUIRED, "Provider credentials were rejected");
                    }
                    recovery.attempted = true;
                    if (auth.credentialIdentity() == null || auth.credentialIdentity().isBlank()) {
                        return fail(ModelDiscovery.Status.UNAVAILABLE,
                                "Credential recovery could not verify the selected account");
                    }
                    OAuthProviderFlow.RequestAuth refreshed;
                    try {
                        refreshed = refreshWithDeadline(context.providerId(), auth, recovery.refresh, deadlineNanos);
                    } catch (IOException error) {
                        CredentialFailure failure = CredentialFailure.classify(error);
                        return fail(error instanceof HttpTimeoutException
                                ? ModelDiscovery.Status.TIMEOUT : credentialStatus(failure), failure.message());
                    }
                    if (refreshed == null) {
                        return fail(ModelDiscovery.Status.AUTH_REQUIRED, "Provider credentials were rejected");
                    }
                    if (!refreshed.oauth()
                            || !Objects.equals(auth.credentialName(), refreshed.credentialName())
                            || !Objects.equals(auth.credentialIdentity(), refreshed.credentialIdentity())
                            || !Objects.equals(auth.baseUrl(), refreshed.baseUrl())) {
                        return fail(ModelDiscovery.Status.UNAVAILABLE,
                                "Credential recovery could not preserve the selected account and endpoint");
                    }
                    recovery.auth = refreshed;
                    return request(context, endpoint, descriptor, deadlineNanos, recovery);
                }
                if (code == 403) return fail(ModelDiscovery.Status.FORBIDDEN, "Provider credentials cannot list models");
                if (code == 429) return fail(ModelDiscovery.Status.RATE_LIMITED, "Provider rate limited discovery");
                if (code / 100 != 2) return fail(ModelDiscovery.Status.UNAVAILABLE,
                        "Model discovery returned HTTP " + code);
                Duration bodyBudget = remaining(deadlineNanos);
                if (bodyBudget.isZero()) {
                    return fail(ModelDiscovery.Status.TIMEOUT,
                            "Timed out while discovering provider models");
                }
                byte[] bytes = readBodyWithDeadline(input, bodyBudget);
                if (bytes.length > MAX_HTTP_RESPONSE_BYTES) {
                    return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                            "Provider model response exceeded the 4 MiB discovery limit");
                }
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            if (body == null || body.isBlank()) return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                    "Provider returned an empty model response");
            if (remaining(deadlineNanos).isZero()) {
                return fail(ModelDiscovery.Status.TIMEOUT,
                        "Timed out before parsing the provider model catalog");
            }
            JsonNode json;
            try {
                json = MAPPER.reader()
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(body);
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                        "Provider returned malformed model JSON");
            }
            if (json == null || json.isNull() || json.isMissingNode()) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE, "Provider returned invalid model JSON");
            }
            if (remaining(deadlineNanos).isZero()) {
                return fail(ModelDiscovery.Status.TIMEOUT,
                        "Timed out while parsing the provider model catalog");
            }
            JsonNode catalog = catalogArray(json);
            if (catalog == null) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                        "Provider model response did not contain a recognized model array");
            }
            String responseProfile = descriptor == null
                    ? "STANDARD" : descriptor.responseProfile();
            String catalogError = catalogContractError(catalog, responseProfile);
            if (catalogError != null) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE, catalogError);
            }
            List<LiveModelDiscovery.Model> models =
                    LiveModelDiscovery.parseHttpModels(
                            body, vendor, responseProfile,
                            descriptor == null ? List.of() : descriptor.idFields());
            if (remaining(deadlineNanos).isZero()) {
                return fail(ModelDiscovery.Status.TIMEOUT,
                        "Timed out while validating the provider model catalog");
            }
            if (hasSelectableCatalogEntries(catalog, responseProfile) && models.isEmpty()) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                        "Provider model response contained entries but no usable model identifiers");
            }
            String paginationError = paginationError(descriptor, json);
            if (paginationError != null) {
                return fail(ModelDiscovery.Status.INVALID_RESPONSE,
                        paginationError);
            }
            String nextToken = pageToken(descriptor, json);
            return new HttpResult(models.isEmpty() ? ModelDiscovery.Status.SUCCESS_EMPTY
                    : ModelDiscovery.Status.SUCCESS, models, "", nextToken);
        } catch (HttpTimeoutException error) {
            return fail(ModelDiscovery.Status.TIMEOUT, "Timed out while discovering provider models");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return fail(ModelDiscovery.Status.TIMEOUT, "Interrupted while discovering provider models");
        } catch (Exception error) {
            return fail(ModelDiscovery.Status.UNAVAILABLE, "Unable to retrieve the provider model catalog");
        }
    }

    private static ModelDiscovery.Status credentialStatus(CredentialFailure failure) {
        return switch (failure.kind()) {
            case REAUTH_REQUIRED -> ModelDiscovery.Status.AUTH_REQUIRED;
            case PERMISSION_DENIED -> ModelDiscovery.Status.FORBIDDEN;
            case RATE_LIMITED -> ModelDiscovery.Status.RATE_LIMITED;
            case TEMPORARY, LOCAL_OR_PROTOCOL, INTERRUPTED -> ModelDiscovery.Status.UNAVAILABLE;
        };
    }

    private static OAuthProviderFlow.RequestAuth refreshWithDeadline(
            String provider, OAuthProviderFlow.RequestAuth rejected, RefreshAuth refresh,
            long deadlineNanos) throws IOException, InterruptedException {
        Duration budget = remaining(deadlineNanos);
        if (budget.isZero()) throw new HttpTimeoutException("Credential recovery timed out");
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "provider-model-catalog-auth");
            thread.setDaemon(true);
            return thread;
        });
        Future<OAuthProviderFlow.RequestAuth> pending = worker.submit(() -> {
            if (remaining(deadlineNanos).isZero() || Thread.currentThread().isInterrupted()) {
                throw new HttpTimeoutException("Credential recovery timed out");
            }
            return refresh.refresh(provider, rejected);
        });
        try {
            return pending.get(remaining(deadlineNanos).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new HttpTimeoutException("Credential recovery timed out");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) throw io;
            // Never expose unexpected credential-store exception messages.
            throw new IOException("Credential recovery failed");
        } finally {
            // Bound the discovery caller and prohibit a late catalog retry. Cancellation is
            // cooperative: the credential manager owns any in-flight store/HTTP cleanup.
            pending.cancel(true);
            worker.shutdownNow();
        }
    }

    private static HttpResult fail(ModelDiscovery.Status status, String message) {
        return new HttpResult(status, List.of(), message, null);
    }

    private static long deadlineNanos(Duration timeout) {
        Duration effective = timeout == null ? TIMEOUT : timeout;
        if (effective.isZero() || effective.isNegative()) {
            return System.nanoTime();
        }
        try {
            return Math.addExact(System.nanoTime(), effective.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration remaining(long deadlineNanos) {
        long nanos = deadlineNanos - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    private static byte[] readBodyWithDeadline(InputStream input, Duration timeout)
            throws Exception {
        return readBodyWithDeadline(input, timeout, MAX_HTTP_RESPONSE_BYTES);
    }

    private static byte[] readBodyWithDeadline(InputStream input, Duration timeout, int maxBytes)
            throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "provider-model-catalog-body");
            thread.setDaemon(true);
            return thread;
        });
        Future<byte[]> body = reader.submit(
                () -> input.readNBytes(maxBytes + 1));
        try {
            long nanos = Math.max(1L, timeout == null ? TIMEOUT.toNanos() : timeout.toNanos());
            return body.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            body.cancel(true);
            try { input.close(); } catch (IOException ignored) { }
            throw new HttpTimeoutException("Timed out while reading provider model catalog");
        } finally {
            if (!body.isDone()) body.cancel(true);
            reader.shutdownNow();
        }
    }

    private static String pageToken(
            ProviderModelCatalogs.Descriptor descriptor,
            JsonNode json) {
        if (descriptor == null || !descriptor.paginated()
                || json == null || !json.isObject()) {
            return null;
        }
        if (!descriptor.paginationHasMore().isBlank()
                && !json.path(descriptor.paginationHasMore()).asBoolean(false)) {
            return null;
        }
        String value = json.path(descriptor.paginationToken()).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String paginationError(
            ProviderModelCatalogs.Descriptor descriptor,
            JsonNode json) {
        if (descriptor == null || !descriptor.paginated() || json == null || !json.isObject()) {
            return null;
        }
        JsonNode token = json.get(descriptor.paginationToken());
        if (!descriptor.paginationHasMore().isBlank()) {
            JsonNode hasMore = json.get(descriptor.paginationHasMore());
            if (hasMore == null || !hasMore.isBoolean()) {
                return "Provider model response omitted a valid "
                        + descriptor.paginationHasMore() + " pagination flag";
            }
            if (hasMore.asBoolean()
                    && (token == null || !token.isTextual() || token.asText().isBlank())) {
                return "Provider model response indicated another page but omitted a valid pagination token";
            }
            return null;
        }
        if (token != null && (!token.isTextual() || token.asText().isBlank())) {
            return "Provider model response returned an invalid pagination token";
        }
        return null;
    }

    private static JsonNode catalogArray(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root;
        for (JsonNode candidate : List.of(
                root.path("data"),
                root.path("models"),
                root.path("result").path("models"))) {
            if (candidate.isArray()) return candidate;
        }
        return null;
    }

    private static boolean hasSelectableCatalogEntries(
            JsonNode catalog,
            String responseProfile) {
        if (catalog == null || catalog.isEmpty()) return false;
        if ("GITHUB_COPILOT".equals(responseProfile)) {
            for (JsonNode model : catalog) {
                if (model.path("model_picker_enabled").isBoolean()
                        && model.path("model_picker_enabled").asBoolean()
                        && model.path("capabilities").path("type").isTextual()
                        && "chat".equalsIgnoreCase(
                                model.path("capabilities").path("type").asText())) {
                    return true;
                }
            }
            return false;
        }
        if ("CODEX".equals(responseProfile)) {
            for (JsonNode model : catalog) {
                if (!model.hasNonNull("visibility")
                        || "list".equalsIgnoreCase(model.path("visibility").asText())) {
                    return true;
                }
            }
            return false;
        }
        if (!"GEMINI".equals(responseProfile)) return true;
        for (JsonNode model : catalog) {
            JsonNode methods = model.path("supportedGenerationMethods");
            if (!model.has("supportedGenerationMethods")) return true;
            if (methods.isArray()) {
                for (JsonNode method : methods) {
                    if ("generateContent".equals(method.asText())) return true;
                }
            }
        }
        return false;
    }

    private static String catalogContractError(JsonNode catalog, String responseProfile) {
        if (catalog == null || !"CODEX".equals(responseProfile)) {
            return null;
        }
        for (JsonNode model : catalog) {
            if (!model.has("visibility")) continue;
            JsonNode visibility = model.path("visibility");
            if (!visibility.isTextual() || visibility.asText().isBlank()) {
                return "Provider model response contained an invalid Codex visibility value";
            }
            String value = visibility.asText();
            if (!"list".equalsIgnoreCase(value) && !"hide".equalsIgnoreCase(value)) {
                return "Provider model response contained an unknown Codex visibility value: "
                        + value;
            }
        }
        return null;
    }

    private static boolean sameOrigin(String first, String second) {
        try {
            URI left = URI.create(first);
            URI right = URI.create(second);
            return left.getScheme() != null && right.getScheme() != null
                    && left.getScheme().equalsIgnoreCase(right.getScheme())
                    && Objects.equals(normalizeAuthority(left), normalizeAuthority(right));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalizeAuthority(URI uri) {
        String host = uri.getHost();
        if (host == null) return "";
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443
                    : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
        }
        return host.toLowerCase(Locale.ROOT) + ":" + port;
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
        Map<String, String> headers = auth == null || auth.headers() == null
                ? Map.of() : auth.headers();
        if ((token == null || token.isBlank()) && headers.isEmpty()) {
            return "";
        }
        StringBuilder material = new StringBuilder(
                auth != null && auth.oauth() ? "oauth:" : "key:");
        material.append(token == null ? "" : token);
        headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> material.append('\n')
                        .append(entry.getKey().toLowerCase(Locale.ROOT))
                        .append(':').append(entry.getValue()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            return Integer.toHexString(material.toString().hashCode());
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
    private static final int MAX_ENTRIES = 128;

    private final Clock clock;
    private final Duration freshAge;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    ModelDiscoveryCache() {
        this(Clock.systemUTC(), DEFAULT_FRESH_AGE);
    }

    ModelDiscoveryCache(Clock clock, Duration freshAge) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.freshAge = requirePositive(freshAge, "freshAge");
    }

    Optional<ModelDiscovery.Result> fresh(String provider, String baseUrl) {
        return fresh(provider, baseUrl, "");
    }

    Optional<ModelDiscovery.Result> fresh(String provider, String baseUrl, String authIdentity) {
        return find(provider, baseUrl, authIdentity, freshAge);
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

    void remove(String provider, String baseUrl, String authIdentity) {
        entries.remove(new Key(provider, baseUrl, authIdentity));
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
