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

package ai.kompile.app.services;

import ai.kompile.app.config.ContextualRagConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContextualRagConfigService} — default config, presets,
 * update/reset, validation, persistence, and prompt templates.
 */
class ContextualRagConfigServiceTest {

    @TempDir
    Path tempDir;

    private ContextualRagConfigService service;

    @BeforeEach
    void setUp() {
        service = new ContextualRagConfigService(tempDir.toString());
        service.loadConfig();
    }

    // ─── Initial state ───────────────────────────────────────────────

    @Test
    void getConfiguration_returnsDefaults() {
        ContextualRagConfig config = service.getConfiguration();
        assertNotNull(config);
        assertFalse(config.getEnabled()); // disabled by default
        assertEquals("openai", config.getLlmProvider());
        assertEquals("gpt-4o-mini", config.getLlmModel());
    }

    @Test
    void isEnabled_defaultsFalse() {
        assertFalse(service.isEnabled());
    }

    @Test
    void isSourceAttributionEnabled_defaultsTrue() {
        assertTrue(service.isSourceAttributionEnabled());
    }

    // ─── Update configuration ────────────────────────────────────────

    @Test
    void updateConfiguration_mergesValues() {
        ContextualRagConfig update = ContextualRagConfig.builder()
                .enabled(true)
                .llmModel("claude-3-haiku")
                .build();

        ContextualRagConfig result = service.updateConfiguration(update);

        assertTrue(result.getEnabled());
        assertEquals("claude-3-haiku", result.getLlmModel());
        assertEquals("openai", result.getLlmProvider()); // unchanged from default
    }

    @Test
    void updateConfiguration_nullUpdate_returnsCurrentConfig() {
        ContextualRagConfig before = service.getConfiguration();
        ContextualRagConfig result = service.updateConfiguration(null);
        assertSame(before, result);
    }

    @Test
    void updateConfiguration_persists() {
        service.updateConfiguration(ContextualRagConfig.builder()
                .enabled(true).build());

        // Create new service pointing at same dir to verify persistence
        ContextualRagConfigService service2 = new ContextualRagConfigService(tempDir.toString());
        service2.loadConfig();

        assertTrue(service2.getConfiguration().getEnabled());
    }

    // ─── Reset configuration ─────────────────────────────────────────

    @Test
    void resetConfiguration_restoresDefaults() {
        service.updateConfiguration(ContextualRagConfig.builder()
                .enabled(true).llmModel("custom-model").build());

        ContextualRagConfig result = service.resetConfiguration();

        assertFalse(result.getEnabled());
        assertEquals("gpt-4o-mini", result.getLlmModel());
    }

    // ─── Apply presets ───────────────────────────────────────────────

    @Test
    void applyPreset_disabled() {
        ContextualRagConfig result = service.applyPreset("disabled");
        assertFalse(result.getEnabled());
    }

    @Test
    void applyPreset_fast() {
        ContextualRagConfig result = service.applyPreset("fast");
        assertTrue(result.getEnabled());
        assertEquals("gpt-4o-mini", result.getLlmModel());
        assertFalse(result.getIncludeDocumentSummary());
    }

    @Test
    void applyPreset_balanced() {
        ContextualRagConfig result = service.applyPreset("balanced");
        assertTrue(result.getEnabled());
        assertTrue(result.getIncludeDocumentSummary());
    }

    @Test
    void applyPreset_quality() {
        ContextualRagConfig result = service.applyPreset("quality");
        assertTrue(result.getEnabled());
        assertEquals("gpt-4o", result.getLlmModel());
        assertTrue(result.getIncludeSurroundingChunks());
    }

    @Test
    void applyPreset_minimal() {
        ContextualRagConfig result = service.applyPreset("minimal");
        assertFalse(result.getEnabled());
        assertTrue(result.getSourceAttributionEnabled());
    }

    @Test
    void applyPreset_unknown_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.applyPreset("nonexistent"));
    }

    // ─── Get presets ─────────────────────────────────────────────────

    @Test
    void getPreset_byName_returnsConfig() {
        assertNotNull(service.getPreset("fast"));
        assertNotNull(service.getPreset("balanced"));
        assertNotNull(service.getPreset("quality"));
        assertNotNull(service.getPreset("disabled"));
        assertNotNull(service.getPreset("minimal"));
    }

    @Test
    void getPreset_unknown_returnsNull() {
        assertNull(service.getPreset("nonexistent"));
    }

    @Test
    void getAvailablePresets_returnsFive() {
        List<ContextualRagConfigService.PresetInfo> presets = service.getAvailablePresets();
        assertEquals(5, presets.size());
    }

    @Test
    void getAvailablePresets_containsExpectedNames() {
        List<String> names = service.getAvailablePresets().stream()
                .map(ContextualRagConfigService.PresetInfo::name).toList();
        assertTrue(names.contains("disabled"));
        assertTrue(names.contains("minimal"));
        assertTrue(names.contains("fast"));
        assertTrue(names.contains("balanced"));
        assertTrue(names.contains("quality"));
    }

    // ─── Prompt template ─────────────────────────────────────────────

    @Test
    void getPromptTemplate_defaultTemplate_notBlank() {
        String template = service.getPromptTemplate();
        assertNotNull(template);
        assertFalse(template.isBlank());
        assertTrue(template.contains("{chunk_text}"));
        assertTrue(template.contains("{document_title}"));
    }

    @Test
    void getPromptTemplate_customTemplate_returned() {
        service.updateConfiguration(ContextualRagConfig.builder()
                .contextPromptTemplate("Custom: {chunk_text}").build());

        assertEquals("Custom: {chunk_text}", service.getPromptTemplate());
    }

    // ─── Validation ──────────────────────────────────────────────────

    @Test
    void updateConfiguration_invalidTemperature_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .temperature(5.0).build()));
    }

    @Test
    void updateConfiguration_negativeTemperature_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .temperature(-1.0).build()));
    }

    @Test
    void updateConfiguration_invalidMaxContextTokens_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .maxContextTokens(5).build())); // min 10
    }

    @Test
    void updateConfiguration_invalidBatchSize_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .batchSize(0).build())); // min 1
    }

    @Test
    void updateConfiguration_invalidWebSearchThreshold_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .webSearchFallbackThreshold(2.0).build())); // max 1.0
    }

    @Test
    void updateConfiguration_invalidProvider_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .llmProvider("invalid-provider").build()));
    }

    @Test
    void updateConfiguration_validProvider_accepted() {
        assertDoesNotThrow(() ->
                service.updateConfiguration(ContextualRagConfig.builder()
                        .llmProvider("anthropic").build()));
        assertEquals("anthropic", service.getConfiguration().getLlmProvider());
    }

    // ─── Config file path ────────────────────────────────────────────

    @Test
    void getConfigFilePath_notNull() {
        String path = service.getConfigFilePath();
        assertNotNull(path);
        assertTrue(path.contains("contextual-rag-config.json"));
    }

    // ─── PresetInfo record ───────────────────────────────────────────

    @Test
    void presetInfo_fieldsAccessible() {
        ContextualRagConfigService.PresetInfo info =
                new ContextualRagConfigService.PresetInfo("fast", "Fast", "Quick enrichment");
        assertEquals("fast", info.name());
        assertEquals("Fast", info.displayName());
        assertEquals("Quick enrichment", info.description());
    }
}
