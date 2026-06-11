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
package ai.kompile.enrichment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EnrichmentConfigService.
 * Uses a real ObjectMapper (no Spring context required).
 */
@ExtendWith(MockitoExtension.class)
class EnrichmentConfigServiceTest {

    private EnrichmentConfigService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EnrichmentConfigService(objectMapper);
    }

    // ─── loadConfig ──────────────────────────────────────────────────────────

    /**
     * When the config file does not exist (e.g. in a CI environment where ~/.kompile/config
     * has not been initialised), loadConfig() must return a fully-defaulted EnrichmentConfig
     * rather than throwing an exception.
     */
    @Test
    void loadConfigReturnsDefaultsWhenFileNotFound() {
        EnrichmentConfig config = service.loadConfig();

        assertNotNull(config, "loadConfig must never return null");
        // Verify at least one well-known default value is present
        assertEquals(0.85, config.getDeduplicationJaccardThreshold(), 1e-9,
                "Default jaccardThreshold should be 0.85");
        assertNotNull(config.getEnabledPhases(),
                "Default enabledPhases list must not be null");
        assertFalse(config.getEnabledPhases().isEmpty(),
                "Default enabledPhases list must not be empty");
    }

    // ─── mergeWithDefaults ───────────────────────────────────────────────────

    @Test
    void mergeWithDefaultsReturnsRequestIfProvided() {
        EnrichmentConfig provided = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.70)
                .autoTriggerAfterCrawl(true)
                .build();

        EnrichmentConfig result = service.mergeWithDefaults(provided);

        // The exact same object must be returned (no merging occurs when non-null)
        assertSame(provided, result,
                "mergeWithDefaults must return the provided config unchanged when non-null");
        assertEquals(0.70, result.getDeduplicationJaccardThreshold(), 1e-9);
        assertTrue(result.isAutoTriggerAfterCrawl());
    }

    @Test
    void mergeWithDefaultsLoadsFileIfNull() {
        // When null is passed, the service should fall back to loadConfig()
        // which returns defaults (file absent in test environment).
        EnrichmentConfig result = service.mergeWithDefaults(null);

        assertNotNull(result, "mergeWithDefaults(null) must not return null");
        // Should have the same defaults as loadConfig()
        assertEquals(0.85, result.getDeduplicationJaccardThreshold(), 1e-9,
                "mergeWithDefaults(null) must return defaults from loadConfig");
    }
}
