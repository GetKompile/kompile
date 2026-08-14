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

import ai.kompile.core.llm.ModelContextWindows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
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
class ModelContextResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        ModelContextResolver resolver = new ModelContextResolver(uri -> Optional.empty());
        ModelContextResolver.ModelLimits limits = resolver.resolveLimits(
                "custom", "gpt-4o", "https://example.test/v1", 32_000, 4_000);
        assertEquals(32_000, limits.contextWindow());
        assertEquals(4_000, limits.maxOutputTokens());
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
