package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProviderContextMetadataTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "anthropic,max_input_tokens,max_tokens",
            "gemini,inputTokenLimit,outputTokenLimit",
            "groq,context_window,max_completion_tokens",
            "openai-codex,context_window,max_output_tokens",
            "radius,contextWindow,maxTokens",
            "claude,contextWindow,maxOutputTokens"
    })
    void preservesEachVendorsLimitFields(String provider, String contextField, String outputField) throws Exception {
        var row = MAPPER.createObjectNode().put("id", "future-model").put("baseModelId", "future-model")
                .put(contextField, 234567).put(outputField, 34567);
        var model = LiveModelDiscovery.parseHttpModels("{\"data\":[" + row + "]}", provider).get(0);
        assertEquals(234567, model.contextWindow());
        assertEquals(34567, model.maxOutputTokens());
    }

    @Test
    void nativeImageResourcesIncludeVendorContextConfiguration() throws Exception {
        String resource = "ai/kompile/cli/main/chat/context-metadata.json";
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/ai.kompile/kompile-cli/resource-config.json")) {
            assertNotNull(input);
            boolean included = false;
            for (var include : MAPPER.readTree(input).path("resources").path("includes")) {
                included |= resource.matches(include.path("pattern").asText());
            }
            assertTrue(included, "the native CLI must ship the same vendor mappings as the JVM CLI");
        }
    }

    @Test
    void nestedProviderLimitsAreNotFlattenedOrMixed() {
        var router = LiveModelDiscovery.parseHttpModels("""
                {"data":[{"id":"vendor/model","context_length":900000,
                "top_provider":{"context_length":123456,"max_completion_tokens":12345}}]}
                """, "openrouter").get(0);
        assertEquals(123456, router.contextWindow());
        assertEquals(12345, router.maxOutputTokens());
        var copilot = LiveModelDiscovery.parseHttpModels("""
                {"data":[{"id":"same-model","model_picker_enabled":true,
                "capabilities":{"type":"chat","limits":{"max_context_window_tokens":64000,
                "max_prompt_tokens":60000,"max_output_tokens":4000}}}]}
                """, "github-copilot").get(0);
        assertEquals(64000, copilot.contextWindow());
        assertEquals(4000, copilot.maxOutputTokens());
        var nativeModel = LiveModelDiscovery.parseNativeOutput("""
                {"id":"model","providerID":"vendor","limit":{"context":32000,"output":2000}}
                """).get(0);
        assertEquals("vendor/model", nativeModel.id());
        assertEquals(32000, nativeModel.contextWindow());
        assertEquals(2000, nativeModel.maxOutputTokens());
    }

    @Test
    void absentInvalidAndForeignFieldsNeverInventLimits() throws Exception {
        assertEquals(0, ProviderContextMetadata.limits("openai", MAPPER.readTree(
                "{\"context_window\":900000,\"max_output_tokens\":900000}")).contextWindow());
        assertEquals(0, ProviderContextMetadata.limits("gemini", MAPPER.readTree(
                "{\"context_window\":900000}")).contextWindow());
        for (String value : List.of("-1", "0", "null", "true", "1.5", "999999999999", "\"64000\"")) {
            assertEquals(0, ProviderContextMetadata.limits("gemini", MAPPER.readTree(
                    "{\"inputTokenLimit\":" + value + "}")).contextWindow());
        }
        var merged = LiveModelDiscovery.merge(
                new LiveModelDiscovery.Model("m", List.of(), java.util.Map.of(), "", false, "", 8000, 1000),
                new LiveModelDiscovery.Model("m", List.of()));
        assertEquals(8000, merged.contextWindow());
        assertEquals(1000, merged.maxOutputTokens());
    }

    @Test
    void vendorMappingsCanBeConfiguredWithoutAddingModelTables(@TempDir Path dir) throws Exception {
        String property = "kompile.chat.contextMetadataConfig";
        String previous = System.getProperty(property);
        Path file = dir.resolve("context.json");
        Files.writeString(file, """
                {"schemaVersion":1,"providers":{"private-vendor":{
                "context":["/limits/input"],"output":["/limits/output"]}}}
                """);
        try {
            System.setProperty(property, file.toString());
            assertEquals(new ModelContextResolver.ModelLimits(8000, 1000), ProviderContextMetadata.limits(
                    "private-vendor", MAPPER.readTree("{\"limits\":{\"input\":8000,\"output\":1000}}")));
            assertEquals(123, ProviderContextMetadata.limits("gemini", MAPPER.readTree(
                    "{\"inputTokenLimit\":123}")).contextWindow());
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    @Test
    void codexUsesOnlySelectedCatalogRowsAndDoesNotApplyNativeHarnessHeadroomToDirectHttp() throws Exception {
        var listed = List.of(new LiveModelDiscovery.Model("native-model", List.of()),
                new LiveModelDiscovery.Model("other-model", List.of()));
        var merged = CodexAppServerModelDiscovery.mergeCachedLimits(listed, MAPPER.readTree("""
                {"models":[{"slug":"native-model","context_window":272000,
                "effective_context_window_percent":95,"max_output_tokens":64000},
                {"slug":"not-selectable","context_window":999999}]}
                """));
        assertEquals(2, merged.size());
        assertEquals(272000, merged.get(0).contextWindow());
        assertEquals(64000, merged.get(0).maxOutputTokens());
        assertEquals(0, merged.get(1).contextWindow());
    }

    @Test
    void liveLimitsReachResolverAndSessionSnapshotWithoutRepeatedHttpOrCredentialLeakage() throws Exception {
        ModelDiscoveryHttp.clearCache();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/models", exchange -> {
            requests.incrementAndGet();
            byte[] body = """
                    {"data":[{"id":"metadata-model","context_window":64000,"max_completion_tokens":4000},
                    {"id":"other-model","context_window":32000,"max_completion_tokens":2000}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
            ChatConfig config = new ChatConfig("groq", "fixture-key", "metadata-model", base);
            config.setAuthenticationMethod("api-key");
            var result = ModelDiscoveryHttp.refreshResultWithAuth("groq",
                    new ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth(
                            "fixture-key", base, java.util.Map.of(), false), base);
            assertTrue(result.isUsable(), result.message());
            ModelContextResolver resolver = new ModelContextResolver(uri -> Optional.empty());
            assertEquals(new ModelContextResolver.ModelLimits(64000, 4000), resolver.resolveLimits(config, null));
            assertFalse(resolver.isContextWindowProvisional(config, null));
            assertTrue(resolver.isContextWindowProvisional(config, "unknown-model"));
            assertEquals(32000, resolver.resolveContextWindow(config, "other-model"));
            assertEquals(1, requests.get());
            ChatConfig otherAccount = config.copy();
            otherAccount.setApiKey("another-key");
            assertTrue(ModelDiscoveryHttp.knownModel(otherAccount, "metadata-model").isEmpty());
            ChatConfig otherOrigin = config.copy();
            otherOrigin.setBaseUrl("http://127.0.0.1:1/openai/v1");
            assertTrue(ModelDiscoveryHttp.knownModel(otherOrigin, "metadata-model").isEmpty());
            config.setContextWindowTokens(16000);
            assertEquals(16000, resolver.resolveContextWindow(config, null));
            assertFalse(resolver.isContextWindowProvisional(config, null));

            ChatSessionMetrics metrics = new ChatSessionMetrics("fixture-session");
            metrics.recordContextWindow("groq/metadata-model", 64000);
            metrics.recordTokenUsage(100, 20, 1200, 0);
            metrics.recordContextInputTokens(1300);
            var snapshot = metrics.toJson(MAPPER);
            assertEquals(64000, snapshot.path("context").path("window").asInt());
            assertEquals(1300, snapshot.path("context").path("lastInputTokens").asInt());
            assertEquals(1320, snapshot.path("tokens").path("total").asInt());
            metrics.recordProviderCompaction();
            assertTrue(metrics.toJson(MAPPER).path("context").path("lastInputTokens").isNull());
            assertEquals(1320, metrics.getTotalTokens());
        } finally {
            server.stop(0);
            ModelDiscoveryHttp.clearCache();
        }
    }
}
