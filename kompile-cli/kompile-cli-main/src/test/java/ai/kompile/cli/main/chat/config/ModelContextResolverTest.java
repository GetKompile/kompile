/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.core.llm.CliModelCatalog;
import ai.kompile.core.llm.ModelContextWindows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context-window resolution per chat lane: catalog-known models come from
 * {@link ModelContextWindows}; unknown models on a loopback endpoint (a kompile-staged
 * GGUF behind the staging OpenAI facade) are probed via {@code /api/llm/status};
 * everything else falls back to the catalog default.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ModelContextResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CATALOG_PATHS_PROPERTY = "kompile.cli.modelCatalogPaths";

    @TempDir Path tempDir;
    private String previousCatalogPaths;

    @BeforeEach
    void isolateCatalog() throws Exception {
        previousCatalogPaths = System.getProperty(CATALOG_PATHS_PROPERTY);
        System.setProperty(CATALOG_PATHS_PROPERTY, tempDir.resolve("absent.json").toString());
        invalidateCatalogCache();
    }

    @AfterEach
    void restoreCatalog() throws Exception {
        if (previousCatalogPaths == null) System.clearProperty(CATALOG_PATHS_PROPERTY);
        else System.setProperty(CATALOG_PATHS_PROPERTY, previousCatalogPaths);
        invalidateCatalogCache();
    }

    private static void invalidateCatalogCache() throws Exception {
        Method method = CliModelCatalog.class.getDeclaredMethod("invalidateCacheForTest");
        method.setAccessible(true);
        method.invoke(null);
    }

    private ObjectNode adversarialCatalog(boolean includeOpenAi) throws Exception {
        ObjectNode catalog = (ObjectNode) MAPPER.readTree("""
                {
                  "llmgateway": {"models": {
                    "gpt-6-astra": {"limit": {"context": 1050000, "output": 1050000}},
                    "catalog-only-model": {"limit": {"context": 900000, "output": 900000}}
                  }},
                  "nano-gpt": {"models": {
                    "openai/gpt-6-astra": {"limit": {"context": 900000, "output": 900000}}
                  }}
                }
                """);
        if (includeOpenAi) {
            ObjectNode models = catalog.putObject("openai").putObject("models");
            models.putObject("gpt-6-astra").putObject("limit")
                    .put("context", 1_050_000).put("output", 128_000);
            models.putObject("catalog-only-model").putObject("limit")
                    .put("context", 64_000).put("output", 4_000);
        }
        return catalog;
    }

    private void useCatalog(ObjectNode catalog) throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, catalog.toString());
        System.setProperty(CATALOG_PATHS_PROPERTY, file.toString());
        invalidateCatalogCache();
    }

    private static ChatConfig codexConfig() {
        ChatConfig config = new ChatConfig("openai-codex", null, "gpt-6-astra", "https://unused.invalid");
        // resolveBaseUrl() also resolves credentials; never read/refresh the user's saved OAuth.
        config.setAuthenticationMethod("none");
        return config;
    }

    private static ModelContextResolver noProbeResolver() {
        return new ModelContextResolver(uri -> {
            throw new AssertionError("Unexpected probe: " + uri);
        });
    }

    @Test
    void codexConfigUsesStaticLimitsWhenCorrectProviderMetadataIsAbsent() throws Exception {
        useCatalog(adversarialCatalog(false));
        ChatConfig config = codexConfig();

        assertEquals(new ModelContextResolver.ModelLimits(1_050_000, 128_000),
                noProbeResolver().resolveLimits(config, null));
        assertEquals(0, config.getContextWindowTokens());
        assertEquals(0, config.getMaxOutputTokens());
    }

    @Test
    void codexConfigUsesActualOpenAiScopeAndRespectsModelOverride() throws Exception {
        useCatalog(adversarialCatalog(true));
        ChatConfig config = codexConfig();
        ModelContextResolver resolver = noProbeResolver();

        assertEquals(new ModelContextResolver.ModelLimits(1_050_000, 128_000), resolver.resolveLimits(config, null));
        assertEquals(new ModelContextResolver.ModelLimits(64_000, 4_000),
                resolver.resolveLimits(config, "catalog-only-model"),
                "the override must use the chosen provider, not the first bare-id match");
    }

    @Test
    void dedicatedNativeMetadataTakesPriorityOverUpstreamFallback() throws Exception {
        ObjectNode catalog = adversarialCatalog(true);
        catalog.putObject("codex").putObject("models").putObject("gpt-6-astra").putObject("limit")
                .put("context", 300_000).put("output", 96_000);
        useCatalog(catalog);
        ModelContextResolver resolver = noProbeResolver();
        assertEquals(new ModelContextResolver.ModelLimits(300_000, 96_000),
                resolver.resolveLimits("openai-codex", "gpt-6-astra", null, 0, 0));

        catalog.putObject("openai-codex").putObject("models").putObject("gpt-6-astra").putObject("limit")
                .put("context", 512_000).put("output", 64_000);
        useCatalog(catalog);
        assertEquals(new ModelContextResolver.ModelLimits(512_000, 64_000),
                resolver.resolveLimits("openai-codex", "gpt-6-astra", null, 0, 0));
        assertEquals(new ModelContextResolver.ModelLimits(300_000, 96_000),
                resolver.resolveLimits("codex", "gpt-6-astra", null, 0, 0));
        assertEquals(new ModelContextResolver.ModelLimits(1_050_000, 128_000),
                resolver.resolveLimits("openai", "gpt-6-astra", null, 0, 0));
    }

    @Test
    void explicitProviderWinsEvenWhenTheModelIdContainsSlashes() throws Exception {
        useCatalog(adversarialCatalog(true));
        ModelContextResolver resolver = noProbeResolver();

        assertEquals(new ModelContextResolver.ModelLimits(900_000, 900_000),
                resolver.resolveLimits("nano-gpt", "openai/gpt-6-astra", null, 0, 0));
        assertEquals(new ModelContextResolver.ModelLimits(1_050_000, 1_050_000),
                resolver.resolveLimits("llmgateway", "gpt-6-astra", null, 0, 0));
        assertEquals(new ModelContextResolver.ModelLimits(1_050_000, 128_000),
                resolver.resolveLimits("openai", "gpt-6-astra", null, 0, 0));
    }

    @Test
    void unknownRemoteProviderDoesNotInheritAnotherProvidersLimits() throws Exception {
        useCatalog(adversarialCatalog(true));
        ModelContextResolver resolver = noProbeResolver();

        for (String provider : new String[]{"custom", "unknown", "custom-openai"}) {
            assertEquals(new ModelContextResolver.ModelLimits(
                            ModelContextWindows.DEFAULT_CONTEXT_WINDOW, ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS),
                    resolver.resolveLimits(provider, "catalog-only-model", "https://unused.invalid", 0, 0));
        }
    }

    @Test
    void providerScopedMissStillUsesLocalServingProbe() throws Exception {
        useCatalog(adversarialCatalog(false));
        ModelContextResolver resolver = new ModelContextResolver(fixedStatus(true, 4_096, 1_024));

        assertEquals(new ModelContextResolver.ModelLimits(4_096, 1_024),
                resolver.resolveLimits("kompile-local", "catalog-only-model", "http://localhost:8090/v1", 0, 0),
                "a foreign bare-id match must not suppress a local model's status probe");
    }

    @Test
    void explicitAndPartialOverridesWinOverConflictingCatalogLimits() throws Exception {
        useCatalog(adversarialCatalog(false));
        ModelContextResolver resolver = noProbeResolver();
        ChatConfig config = codexConfig();
        config.setContextWindowTokens(32_000);
        assertEquals(new ModelContextResolver.ModelLimits(32_000, 128_000), resolver.resolveLimits(config, null));
        config.setMaxOutputTokens(4_000);
        assertEquals(new ModelContextResolver.ModelLimits(32_000, 4_000), resolver.resolveLimits(config, null));
        config.setContextWindowTokens(0);
        assertEquals(new ModelContextResolver.ModelLimits(1_050_000, 4_000), resolver.resolveLimits(config, null));
        assertEquals(new ModelContextResolver.ModelLimits(32_000, 4_000),
                resolver.resolveLimits("llmgateway", "gpt-6-astra", null, 32_000, 4_000));
    }

    @Test
    void partialOverridesRetainLocalProbeForTheOtherLimit() throws Exception {
        useCatalog(adversarialCatalog(false));
        ModelContextResolver resolver = new ModelContextResolver(fixedStatus(true, 16_384, 2_048));

        assertEquals(new ModelContextResolver.ModelLimits(8_192, 2_048),
                resolver.resolveLimits("kompile-local", "catalog-only-model", "http://localhost:8090/v1", 8_192, 0));
        assertEquals(new ModelContextResolver.ModelLimits(16_384, 512),
                resolver.resolveLimits("kompile-local", "catalog-only-model", "http://localhost:8090/v1", 0, 512));
    }

    private static Function<URI, Optional<JsonNode>> fixedStatus(boolean loaded, int maxContextLength) {
        return fixedStatus(loaded, maxContextLength, 0);
    }

    private static Function<URI, Optional<JsonNode>> fixedStatus(
            boolean loaded, int maxContextLength, int maxOutputTokens) {
        return uri -> {
            try {
                return Optional.of(MAPPER.readTree(
                        "{\"loaded\":" + loaded + ",\"modelId\":\"lfm2\",\"maxContextLength\":"
                                + maxContextLength + ",\"maxOutputTokens\":" + maxOutputTokens + "}"));
            } catch (Exception e) {
                return Optional.empty();
            }
        };
    }

    @Test
    void catalogKnownModelUsesCatalog() {
        AtomicInteger probes = new AtomicInteger();
        ModelContextResolver resolver = new ModelContextResolver(uri -> {
            probes.incrementAndGet();
            return Optional.empty();
        });
        int window = resolver.resolveContextWindow("claude-sonnet-4", "http://localhost:8090/v1");
        assertEquals(ModelContextWindows.getContextWindow("claude-sonnet-4"), window);
        assertEquals(0, probes.get(), "catalog-known model must not hit the staging probe");
    }

    @Test
    void currentOpenAiSlugsUseDocumentedMillionTokenLimits() {
        ModelContextResolver resolver = new ModelContextResolver(uri -> Optional.empty());

        for (String model : new String[]{
                "gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"}) {
            ModelContextResolver.ModelLimits limits = resolver.resolveLimits(
                    "openai", model, "https://api.openai.com/v1", 0, 0);
            assertEquals(1_050_000, limits.contextWindow(), model);
            assertEquals(128_000, limits.maxOutputTokens(), model);
        }
    }

    @Test
    void unknownLocalModelProbesStagingStatus() {
        ModelContextResolver resolver = new ModelContextResolver(fixedStatus(true, 4_096));
        int window = resolver.resolveContextWindow(
                "totally-unknown-staged-model", "http://localhost:8090/v1");
        assertEquals(4_096, window, "the staging server's maxContextLength is authoritative");
    }

    @Test
    void localServingProbeReturnsBothContextAndOutputLimits() {
        ModelContextResolver resolver = new ModelContextResolver(fixedStatus(true, 16_384, 2_048));
        ModelContextResolver.ModelLimits limits = resolver.resolveLimits(
                "kompile-local", "unknown-local-model", "http://localhost:8090/v1", 0, 0);
        assertEquals(16_384, limits.contextWindow());
        assertEquals(2_048, limits.maxOutputTokens());
    }

    @Test
    void explicitOverridesWinForEveryProvider() {
        AtomicInteger probes = new AtomicInteger();
        ModelContextResolver resolver = new ModelContextResolver(uri -> {
            probes.incrementAndGet();
            return Optional.empty();
        });
        ModelContextResolver.ModelLimits limits = resolver.resolveLimits(
                "custom", "unknown-local", "http://127.0.0.1:8090/v1", 32_000, 4_000);
        assertEquals(32_000, limits.contextWindow());
        assertEquals(4_000, limits.maxOutputTokens());
        assertEquals(0, probes.get(),
                "complete explicit limits must not trigger a best-effort local probe");
    }

    @Test
    void stalledStatusBodyIsCancelledAtTheProbeDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/llm/status", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ModelContextResolver resolver = new ModelContextResolver();
            long started = System.nanoTime();
            int window = resolver.resolveContextWindow(
                    "unknown-stalled-model",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals(ModelContextWindows.getContextWindow("unknown-stalled-model"), window);
            assertTrue(elapsedMs < TimeUnit.SECONDS.toMillis(5),
                    "status probing must not pin the chat thread: " + elapsedMs + "ms");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void unknownLocalModelWithNoLoadedModelFallsBackToDefault() {
        ModelContextResolver resolver = new ModelContextResolver(fixedStatus(false, 0));
        int window = resolver.resolveContextWindow(
                "totally-unknown-staged-model", "http://localhost:8090/v1");
        assertEquals(ModelContextWindows.getContextWindow("totally-unknown-staged-model"), window);
    }

    @Test
    void unknownRemoteModelNeverProbes() {
        AtomicInteger probes = new AtomicInteger();
        ModelContextResolver resolver = new ModelContextResolver(uri -> {
            probes.incrementAndGet();
            return Optional.empty();
        });
        resolver.resolveContextWindow("mystery-model", "https://api.example.com/v1");
        assertEquals(0, probes.get(), "remote endpoints are never probed");
    }

    @Test
    void probeResultIsCached() {
        AtomicInteger probes = new AtomicInteger();
        ModelContextResolver resolver = new ModelContextResolver(uri -> {
            probes.incrementAndGet();
            return fixedStatus(true, 8_192).apply(uri);
        });
        resolver.resolveContextWindow("staged-model", "http://127.0.0.1:8090/v1");
        resolver.resolveContextWindow("staged-model", "http://127.0.0.1:8090/v1");
        assertEquals(1, probes.get(), "repeat lookups within the TTL must be served from cache");
    }

    @Test
    void originStripsV1Suffix() {
        assertEquals("http://localhost:8090", ModelContextResolver.originOf("http://localhost:8090/v1"));
        assertEquals("http://localhost:8090", ModelContextResolver.originOf("http://localhost:8090/v1/"));
        assertEquals("http://localhost:8090", ModelContextResolver.originOf("http://localhost:8090"));
    }

    @Test
    void localEndpointDetection() {
        assertTrue(ModelContextResolver.isLocalEndpoint("http://localhost:8090/v1"));
        assertTrue(ModelContextResolver.isLocalEndpoint("http://127.0.0.1:11434/v1"));
        assertFalse(ModelContextResolver.isLocalEndpoint("https://api.openai.com/v1"));
        assertFalse(ModelContextResolver.isLocalEndpoint(null));
        assertFalse(ModelContextResolver.isLocalEndpoint(""));
    }
}
