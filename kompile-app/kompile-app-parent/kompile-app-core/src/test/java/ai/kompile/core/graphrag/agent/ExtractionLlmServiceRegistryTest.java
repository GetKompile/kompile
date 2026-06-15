/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphrag.agent;

import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry.ProviderInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionLlmServiceRegistryTest {

    private ExtractionLlmServiceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExtractionLlmServiceRegistry();
    }

    // --- register / get ---

    @Test
    void registerAndGetProvider() {
        ExtractionLlmService service = stubService("openai", "OpenAI provider", true);
        registry.register(service);

        ExtractionLlmService result = registry.get("openai");
        assertNotNull(result);
        assertEquals("openai", result.getId());
    }

    @Test
    void getReturnsNullForUnknownProvider() {
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void registerOverwritesPreviousProvider() {
        registry.register(stubService("openai", "Old", true));
        registry.register(stubService("openai", "New", true));

        assertEquals("New", registry.get("openai").getDescription());
    }

    // --- setProviders (Spring autowired) ---

    @Test
    void setProvidersBulkRegisters() {
        ExtractionLlmService svc1 = stubService("openai", "OpenAI", true);
        ExtractionLlmService svc2 = stubService("anthropic", "Anthropic", true);
        registry.setProviders(List.of(svc1, svc2));

        assertNotNull(registry.get("openai"));
        assertNotNull(registry.get("anthropic"));
    }

    @Test
    void setProvidersHandlesNull() {
        registry.setProviders(null);
        // No exception
        assertNull(registry.get("anything"));
    }

    // --- getOrFallback ---

    @Test
    void getOrFallbackReturnsPreferred() {
        registry.register(stubService("openai", "OpenAI", true));
        registry.register(stubService("anthropic", "Anthropic", true));

        ExtractionLlmService result = registry.getOrFallback("openai");
        assertNotNull(result);
        assertEquals("openai", result.getId());
    }

    @Test
    void getOrFallbackFallsBackWhenPreferredUnavailable() {
        registry.register(stubService("openai", "OpenAI", false)); // unavailable
        registry.register(stubService("anthropic", "Anthropic", true));

        ExtractionLlmService result = registry.getOrFallback("openai");
        assertNotNull(result);
        assertEquals("anthropic", result.getId());
    }

    @Test
    void getOrFallbackFallsBackWhenPreferredNotFound() {
        registry.register(stubService("anthropic", "Anthropic", true));

        ExtractionLlmService result = registry.getOrFallback("nonexistent");
        assertNotNull(result);
        assertEquals("anthropic", result.getId());
    }

    @Test
    void getOrFallbackReturnsNullWhenNoneAvailable() {
        registry.register(stubService("openai", "OpenAI", false));

        assertNull(registry.getOrFallback("openai"));
    }

    @Test
    void getOrFallbackHandlesNullPreferredId() {
        registry.register(stubService("anthropic", "Anthropic", true));

        ExtractionLlmService result = registry.getOrFallback(null);
        assertNotNull(result);
        assertEquals("anthropic", result.getId());
    }

    @Test
    void getOrFallbackReturnsNullWhenRegistryEmpty() {
        assertNull(registry.getOrFallback("any"));
    }

    // --- listProviders ---

    @Test
    void listProvidersReturnsAllRegistered() {
        registry.register(stubService("openai", "OpenAI", true));
        registry.register(stubService("anthropic", "Anthropic", false));

        List<ProviderInfo> providers = registry.listProviders();
        assertEquals(2, providers.size());
    }

    @Test
    void listProvidersSortedById() {
        registry.register(stubService("z-provider", "Z", true));
        registry.register(stubService("a-provider", "A", true));
        registry.register(stubService("m-provider", "M", true));

        List<ProviderInfo> providers = registry.listProviders();
        assertEquals("a-provider", providers.get(0).id());
        assertEquals("m-provider", providers.get(1).id());
        assertEquals("z-provider", providers.get(2).id());
    }

    @Test
    void listProvidersIncludesAvailabilityStatus() {
        registry.register(stubService("available", "Available", true));
        registry.register(stubService("unavailable", "Unavailable", false));

        List<ProviderInfo> providers = registry.listProviders();
        ProviderInfo avail = providers.stream().filter(p -> "available".equals(p.id())).findFirst().orElseThrow();
        ProviderInfo unavail = providers.stream().filter(p -> "unavailable".equals(p.id())).findFirst().orElseThrow();
        assertTrue(avail.available());
        assertFalse(unavail.available());
    }

    @Test
    void listProvidersReturnsEmptyWhenRegistryEmpty() {
        assertTrue(registry.listProviders().isEmpty());
    }

    // --- helpers ---

    private static ExtractionLlmService stubService(String id, String description, boolean available) {
        return new ExtractionLlmService() {
            @Override
            public String getId() { return id; }

            @Override
            public String getDescription() { return description; }

            @Override
            public String complete(String prompt) { return "response"; }

            @Override
            public boolean isAvailable() { return available; }
        };
    }
}
