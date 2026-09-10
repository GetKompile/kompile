package ai.kompile.cli.main.chat.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDiscoveryHttpTest {

    @Test
    void routesAreProviderSpecific() {
        List<String> openAi = ModelDiscoveryHttp.candidateUrls(
                "openai", "https://api.openai.com/v1");
        List<String> ollama = ModelDiscoveryHttp.candidateUrls(
                "ollama", "http://localhost:11434/v1");
        List<String> gemini = ModelDiscoveryHttp.candidateUrls(
                "gemini", "https://generativelanguage.googleapis.com/v1beta/openai");
        List<String> zai = ModelDiscoveryHttp.candidateUrls(
                "zai", "https://api.z.ai/api/coding/paas/v4");

        assertEquals(List.of("https://api.openai.com/v1/models"), openAi);
        assertTrue(ollama.contains("http://localhost:11434/api/tags"));
        assertFalse(ollama.stream().anyMatch(url -> url.endsWith("/v1/models")));
        assertTrue(gemini.contains(
                "https://generativelanguage.googleapis.com/v1beta/models"));
        assertEquals(List.of("https://api.z.ai/api/coding/paas/v4/models"), zai);
    }

    @Test
    void typedResultKeepsEmptySuccessDistinctFromUnavailable() {
        ModelDiscovery.Result empty = ModelDiscovery.Result.success(
                List.of(), List.of("https://example.test/v1/models"));
        ModelDiscovery.Result unavailable = ModelDiscovery.Result.failure(
                ModelDiscovery.Status.UNAVAILABLE, "offline", List.of());

        assertEquals(ModelDiscovery.Status.SUCCESS_EMPTY, empty.status());
        assertFalse(empty.isUsable());
        assertEquals(ModelDiscovery.Status.UNAVAILABLE, unavailable.status());
    }

    @Test
    void missingEndpointIsUnsupportedInsteadOfAnEmptyCatalog() {
        ModelDiscovery.Result result = ModelDiscoveryHttp.discoverResult(
                "provider-without-a-configured-endpoint", null, null);

        assertEquals(ModelDiscovery.Status.UNSUPPORTED, result.status());
        assertTrue(result.models().isEmpty());
    }

    @Test
    void paginationParametersMatchProviderContracts() {
        assertEquals("https://example.test/models?pageToken=next+page",
                ModelDiscoveryHttp.nextPageEndpoint("gemini",
                        "https://example.test/models", "next page"));
        assertEquals("https://example.test/models?after_id=cursor",
                ModelDiscoveryHttp.nextPageEndpoint("anthropic",
                        "https://example.test/models", "cursor"));
        assertNull(ModelDiscoveryHttp.nextPageEndpoint("ollama",
                "https://example.test/models", "cursor"));
    }

    @Test
    void geminiFilteringKeepsOnlyGenerativeModelsWhenCapabilitiesArePresent() {
        String body = """
                {"models":[
                  {"name":"models/gemini-pro","baseModelId":"gemini-pro",
                   "supportedGenerationMethods":["generateContent"]},
                  {"name":"models/text-embedding","baseModelId":"text-embedding",
                   "supportedGenerationMethods":["embedContent"]},
                  {"name":"models/legacy","baseModelId":"legacy"}
                ]}
                """;
        assertEquals(List.of("gemini-pro", "legacy"),
                LiveModelDiscovery.parseHttpModels(body, "gemini").stream()
                        .map(LiveModelDiscovery.Model::id).toList());
    }

    @Test
    void copilotKeepsOnlyChatModelsEnabledForThePicker() {
        String body = """
                {"data":[
                  {"id":"chat-model","model_picker_enabled":true,
                   "capabilities":{"type":"chat"}},
                  {"id":"hidden-chat","model_picker_enabled":false,
                   "capabilities":{"type":"chat"}},
                  {"id":"embedding-model","model_picker_enabled":true,
                   "capabilities":{"type":"embeddings"}},
                  {"id":"missing-contract-fields"}]}
                """;
        assertEquals(List.of("chat-model"),
                LiveModelDiscovery.parseHttpModels(
                                body, "github-copilot", "GITHUB_COPILOT", List.of("id"))
                        .stream().map(LiveModelDiscovery.Model::id).toList());
    }

    @Test
    void catalogDescriptorsCoverEveryRemoteProviderWithoutModelIds() {
        assertEquals(List.of(
                        "anthropic", "deepseek", "gemini", "github-copilot", "groq",
                        "ollama", "openai", "openrouter", "radius", "xai", "zai"),
                ProviderModelCatalogs.all().keySet().stream().sorted().toList());
    }

    @Test
    void everyRegisteredDirectProviderUsesItsDescriptorRouteAndCredentialContract() throws Exception {
        Map<String, String> environmentVariables = Map.ofEntries(
                Map.entry("openai", "OPENAI_API_KEY"),
                Map.entry("anthropic", "ANTHROPIC_API_KEY"),
                Map.entry("gemini", "GOOGLE_API_KEY"),
                Map.entry("ollama", ""),
                Map.entry("openrouter", "OPENROUTER_API_KEY"),
                Map.entry("xai", "XAI_API_KEY"),
                Map.entry("zai", "ZAI_API_KEY"),
                Map.entry("github-copilot", "COPILOT_GITHUB_TOKEN"),
                Map.entry("radius", "RADIUS_API_KEY"),
                Map.entry("deepseek", "DEEPSEEK_API_KEY"),
                Map.entry("groq", "GROQ_API_KEY"));
        Map<String, String> routes = Map.ofEntries(
                Map.entry("openai", "/v1/models"),
                Map.entry("anthropic", "/v1/models"),
                Map.entry("gemini", "/models"),
                Map.entry("ollama", "/api/tags"),
                Map.entry("openrouter", "/v1/models"),
                Map.entry("xai", "/v1/models"),
                Map.entry("zai", "/models"),
                Map.entry("github-copilot", "/models"),
                Map.entry("radius", "/v1/config"),
                Map.entry("deepseek", "/v1/models"),
                Map.entry("groq", "/v1/models"));

        List<String> requests = new ArrayList<>();
        List<String> requestAuth = new ArrayList<>();
        List<String> requestQueries = new ArrayList<>();
        AtomicReference<String> activeProvider = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null) {
                authorization = exchange.getRequestHeaders().getFirst("x-api-key");
            }
            if (authorization == null) {
                authorization = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            }
            requestAuth.add(authorization);
            requestQueries.add(exchange.getRequestURI().getRawQuery());
            String responseBody = switch (activeProvider.get()) {
                case "anthropic" -> "{\"data\":[{\"id\":\"fixture-model\"}],\"has_more\":false}";
                case "gemini" -> "{\"models\":[{\"baseModelId\":\"fixture-model\"}]}";
                case "github-copilot" -> "{\"data\":[{\"id\":\"fixture-model\",\"model_picker_enabled\":true,\"capabilities\":{\"type\":\"chat\"}}]}";
                case "ollama" -> "{\"models\":[{\"model\":\"fixture-model\"}]}";
                default -> "{\"data\":[{\"id\":\"fixture-model\"}]}";
            };
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String providerId : routes.keySet()) {
                activeProvider.set(providerId);
                ChatProvider provider = ChatProviderRegistry.find(providerId);
                assertTrue(provider != null, "missing provider descriptor: " + providerId);
                String expectedEnvironment = providerId.equals("ollama")
                        ? null : environmentVariables.get(providerId);
                assertEquals(expectedEnvironment, provider.environmentVariable(), providerId);
                boolean expectedSupportsApiKey = !providerId.equals("ollama")
                        && !providerId.equals("github-copilot");
                assertEquals(expectedSupportsApiKey, provider.supportsApiKey(), providerId);

                ModelDiscovery.Result result = provider.modelDiscoveryStrategy().discover(
                        new ModelDiscovery.Context(providerId, base,
                                environmentVariables.get(providerId).isBlank() ? null : "fixture-key",
                                null, HttpClient.newHttpClient(), Duration.ofSeconds(2)));
                assertTrue(result.isUsable(), providerId + ": " + result.message());
                assertEquals(routes.get(providerId), requests.remove(0), providerId);
                String expectedAuth = environmentVariables.get(providerId).isBlank()
                        ? null
                        : switch (providerId) {
                            case "anthropic", "gemini" -> "fixture-key";
                            default -> "Bearer fixture-key";
                        };
                assertEquals(expectedAuth, requestAuth.remove(0), providerId);
                assertNull(requestQueries.remove(0), providerId);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oauthTenantHeadersPartitionTheModelCache() throws Exception {
        ModelDiscoveryHttp.clearCache();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            requests.incrementAndGet();
            String account = exchange.getRequestHeaders().getFirst("chatgpt-account-id");
            byte[] response = ("{\"data\":[{\"id\":\"model-" + account
                    + "\",\"model_picker_enabled\":true,"
                    + "\"capabilities\":{\"type\":\"chat\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var accountA = ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.oauth(
                    "shared-token", base,
                    Map.of("Authorization", "Bearer shared-token",
                            "chatgpt-account-id", "account-a"));
            var accountB = ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.oauth(
                    "shared-token", base,
                    Map.of("Authorization", "Bearer shared-token",
                            "chatgpt-account-id", "account-b"));

            ModelDiscovery.Result first = ModelDiscoveryHttp.discoverResultWithAuth(
                    "github-copilot", accountA, base);
            ModelDiscovery.Result second = ModelDiscoveryHttp.discoverResultWithAuth(
                    "github-copilot", accountB, base);
            ModelDiscovery.Result cachedFirst = ModelDiscoveryHttp.discoverResultWithAuth(
                    "github-copilot", accountA, base);

            assertEquals(List.of("model-account-a"), first.models().stream()
                    .map(LiveModelDiscovery.Model::id).toList());
            assertEquals(List.of("model-account-b"), second.models().stream()
                    .map(LiveModelDiscovery.Model::id).toList());
            assertEquals(List.of("model-account-a"), cachedFirst.models().stream()
                    .map(LiveModelDiscovery.Model::id).toList());
            assertEquals(2, requests.get(), "each account must have an isolated cache entry");
        } finally {
            server.stop(0);
            ModelDiscoveryHttp.clearCache();
        }
    }

    @Test
    void liveOutageFailsLoudlyInsteadOfReturningCachedModels() throws Exception {
        ModelDiscoveryHttp.clearCache();
        AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            requests.incrementAndGet();
            if (unavailable.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] response = "{\"models\":[{\"model\":\"cached-model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result live = ModelDiscoveryHttp.discoverResult(
                    "ollama", null, base);
            assertTrue(live.isUsable(), live.message());
            assertEquals(1, requests.get());

            unavailable.set(true);
            ModelDiscovery.Result cached = ModelDiscoveryHttp.discoverResult(
                    "ollama", null, base);
            assertTrue(cached.isUsable(), cached.message());
            assertTrue(cached.message().contains("cache"));
            assertEquals(1, requests.get(), "a normal read may use the fresh cache");

            ModelDiscovery.Result outage = ModelDiscoveryHttp.refreshResult(
                    "ollama", null, base);
            assertEquals(ModelDiscovery.Status.UNAVAILABLE, outage.status());
            assertTrue(outage.models().isEmpty());
            assertFalse(outage.message().isBlank());
            assertEquals(2, requests.get(), "forced refresh must contact the provider");
        } finally {
            server.stop(0);
            ModelDiscoveryHttp.clearCache();
        }
    }

    @Test
    void successfulEmptyRefreshEvictsThePreviousCatalog() throws Exception {
        ModelDiscoveryHttp.clearCache();
        AtomicInteger mode = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            requests.incrementAndGet();
            if (mode.get() == 2) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String body = mode.get() == 0
                    ? "{\"models\":[{\"model\":\"retired-model\"}]}"
                    : "{\"models\":[]}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(ModelDiscovery.Status.SUCCESS,
                    ModelDiscoveryHttp.discoverResult("ollama", null, base).status());

            mode.set(1);
            ModelDiscovery.Result empty = ModelDiscoveryHttp.refreshResult(
                    "ollama", null, base);
            assertEquals(ModelDiscovery.Status.SUCCESS_EMPTY, empty.status());

            mode.set(2);
            ModelDiscovery.Result afterEviction = ModelDiscoveryHttp.discoverResult(
                    "ollama", null, base);
            assertEquals(ModelDiscovery.Status.UNAVAILABLE, afterEviction.status());
            assertTrue(afterEviction.models().isEmpty());
            assertEquals(3, requests.get(),
                    "the old non-empty catalog must not survive an authoritative empty refresh");
        } finally {
            server.stop(0);
            ModelDiscoveryHttp.clearCache();
        }
    }

    @Test
    void builtInCredentialsCannotBeRedirectedToAnotherOrigin() {
        ModelDiscovery.Result result = ModelDiscoveryHttp.discoverResult(
                "openai", "secret-key", "http://127.0.0.1:1");

        assertEquals(ModelDiscovery.Status.FORBIDDEN, result.status());
        assertTrue(result.models().isEmpty());
        assertTrue(result.message().contains("untrusted"));
    }

    @Test
    void credentialFreeOllamaMayUseConfiguredRemoteRuntime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] response = "{\"models\":[{\"model\":\"remote-ollama-model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelDiscovery.Result result =
                    ModelDiscoveryHttp.discoverResult("ollama", null, base);
            assertTrue(result.isUsable(), result.message());
            assertEquals(List.of("remote-ollama-model"), result.models().stream()
                    .map(LiveModelDiscovery.Model::id).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void profileSpecificIdentifiersRejectDisplayNameOnlyResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] response = "{\"data\":[{\"id\":42,\"name\":\"display-only\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result result = ChatProviderRegistry.find("openai")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "openai", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofSeconds(2)));
            assertEquals(ModelDiscovery.Status.INVALID_RESPONSE, result.status());
            assertTrue(result.models().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedJsonIsReportedAsInvalidResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] response = "{\"data\":[]} trailing"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result result = ChatProviderRegistry.find("openai")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "openai", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofSeconds(2)));
            assertEquals(ModelDiscovery.Status.INVALID_RESPONSE, result.status());
            assertTrue(result.models().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledResponseBodyTimesOutAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result result = ChatProviderRegistry.find("openai")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "openai", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofMillis(75)));
            assertEquals(ModelDiscovery.Status.TIMEOUT, result.status());
            assertTrue(result.models().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonPositiveOverallDeadlineFailsBeforeAnyRequest() {
        ModelDiscovery.Context context = new ModelDiscovery.Context(
                "openai", "http://127.0.0.1:1", "key", null,
                HttpClient.newHttpClient(), Duration.ZERO);

        ModelDiscovery.Result result = ModelDiscoveryHttp.discover(
                context, List.of("http://127.0.0.1:1/models"));

        assertEquals(ModelDiscovery.Status.TIMEOUT, result.status());
        assertTrue(result.models().isEmpty());
        assertTrue(result.attemptedEndpoints().isEmpty());
    }

    @Test
    void anthropicMissingPaginationTokenFailsInsteadOfReturningPartialCatalog() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] response = "{\"data\":[{\"id\":\"claude-live\"}],\"has_more\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result result = ChatProviderRegistry.find("anthropic")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "anthropic", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofSeconds(2)));
            assertEquals(ModelDiscovery.Status.INVALID_RESPONSE, result.status());
            assertTrue(result.models().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedPaginationFieldTypesFailLoudly() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> provider = new AtomicReference<>("anthropic");
        server.createContext("/v1/models", exchange -> {
            String body = "anthropic".equals(provider.get())
                    ? "{\"data\":[{\"id\":\"claude-live\"}],\"has_more\":\"yes\",\"last_id\":\"cursor\"}"
                    : "{\"models\":[{\"baseModelId\":\"gemini-live\"}],\"nextPageToken\":{}}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/models", exchange -> {
            byte[] response = "{\"models\":[{\"baseModelId\":\"gemini-live\"}],\"nextPageToken\":{}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result anthropic = ChatProviderRegistry.find("anthropic")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "anthropic", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofSeconds(2)));
            assertEquals(ModelDiscovery.Status.INVALID_RESPONSE, anthropic.status());

            provider.set("gemini");
            ModelDiscovery.Result gemini = ChatProviderRegistry.find("gemini")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "gemini", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofSeconds(2)));
            assertEquals(ModelDiscovery.Status.INVALID_RESPONSE, gemini.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void paginationLimitFailsExplicitlyWithoutReturningPartialModels() throws Exception {
        AtomicInteger page = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            int number = page.incrementAndGet();
            byte[] response = ("{\"data\":[{\"id\":\"model-" + number
                    + "\"}],\"has_more\":true,\"last_id\":\"cursor-" + number + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelDiscovery.Result result = ChatProviderRegistry.find("anthropic")
                    .modelDiscoveryStrategy().discover(new ModelDiscovery.Context(
                            "anthropic", base, "key", null,
                            HttpClient.newHttpClient(), Duration.ofSeconds(2)));

            assertEquals(ModelDiscovery.Status.INVALID_RESPONSE, result.status());
            assertTrue(result.models().isEmpty());
            assertEquals(20, result.attemptedEndpoints().size());
            assertTrue(result.message().contains("20-page"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localAliasHasExplicitRuntimeOwnedStrategy() {
        ChatProvider local = ChatProviderRegistry.find("kompile-local");
        assertTrue(local != null);
        assertFalse(local.modelDiscoveryRequiresBaseUrl());
        ModelDiscovery.Result inventory = local.modelDiscoveryStrategy().discover(
                new ModelDiscovery.Context("kompile-local", null, null, null, null, Duration.ofSeconds(1)));
        assertEquals(ModelDiscovery.Status.UNSUPPORTED, inventory.status());
        assertTrue(inventory.models().isEmpty());
    }
}
