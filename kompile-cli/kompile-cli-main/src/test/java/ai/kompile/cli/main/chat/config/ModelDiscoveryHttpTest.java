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

        assertEquals(List.of("https://api.openai.com/v1/models"), openAi);
        assertTrue(ollama.contains("http://localhost:11434/api/tags"));
        assertFalse(ollama.stream().anyMatch(url -> url.endsWith("/v1/models")));
        assertTrue(gemini.contains(
                "https://generativelanguage.googleapis.com/v1beta/models"));
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
    void everyRegisteredDirectProviderUsesItsDescriptorRouteAndCredentialContract() throws Exception {
        Map<String, String> environmentVariables = Map.of(
                "openai", "OPENAI_API_KEY",
                "anthropic", "ANTHROPIC_API_KEY",
                "gemini", "GOOGLE_API_KEY",
                "ollama", "",
                "openrouter", "OPENROUTER_API_KEY",
                "xai", "XAI_API_KEY",
                "github-copilot", "COPILOT_GITHUB_TOKEN",
                "radius", "RADIUS_API_KEY",
                "deepseek", "DEEPSEEK_API_KEY",
                "groq", "GROQ_API_KEY");
        Map<String, String> routes = Map.of(
                "openai", "/v1/models",
                "anthropic", "/v1/models",
                "gemini", "/models",
                "ollama", "/api/tags",
                "openrouter", "/v1/models",
                "xai", "/v1/models",
                "github-copilot", "/models",
                "radius", "/v1/config",
                "deepseek", "/v1/models",
                "groq", "/v1/models");

        List<String> requests = new ArrayList<>();
        List<String> requestAuth = new ArrayList<>();
        List<String> requestQueries = new ArrayList<>();
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
            byte[] response = "{\"data\":[{\"id\":\"fixture-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String providerId : routes.keySet()) {
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
                assertEquals("gemini".equals(providerId) ? "key=fixture-key" : null,
                        requestQueries.remove(0), providerId);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oauthAndLocalAliasesHaveExplicitNonHttpStrategies() {
        ChatProvider codex = ChatProviderRegistry.find("openai-codex");
        assertTrue(codex != null);
        assertFalse(codex.supportsApiKey());
        assertFalse(codex.modelDiscoveryRequiresBaseUrl());
        String previousCodex = System.getProperty("kompile.codex.executable");
        try {
            System.setProperty("kompile.codex.executable", "definitely-not-a-codex-binary");
            ModelDiscovery.Result codexUnavailable =
                    ModelDiscoveryHttp.discoverResult(
                            "openai-codex", "must-not-be-forwarded", "http://127.0.0.1:1");
            assertEquals(ModelDiscovery.Status.UNSUPPORTED, codexUnavailable.status());
            assertEquals(List.of(CodexAppServerModelDiscovery.ATTEMPTED_RESOURCE),
                    codexUnavailable.attemptedEndpoints());
        } finally {
            if (previousCodex == null) {
                System.clearProperty("kompile.codex.executable");
            } else {
                System.setProperty("kompile.codex.executable", previousCodex);
            }
        }

        ChatProvider local = ChatProviderRegistry.find("kompile-local");
        assertTrue(local != null);
        assertFalse(local.modelDiscoveryRequiresBaseUrl());
        ModelDiscovery.Result inventory = local.modelDiscoveryStrategy().discover(
                new ModelDiscovery.Context("kompile-local", null, null, null, null, Duration.ofSeconds(1)));
        assertEquals(ModelDiscovery.Status.UNSUPPORTED, inventory.status());
        assertTrue(inventory.models().isEmpty());
    }
}
