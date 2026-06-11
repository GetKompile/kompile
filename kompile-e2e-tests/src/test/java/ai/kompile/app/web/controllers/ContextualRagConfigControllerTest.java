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

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.ContextualRagConfig;
import ai.kompile.app.services.ContextualChunkEnricher;
import ai.kompile.app.services.ContextualRagConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextualRagConfigControllerTest {

    @Mock
    private ContextualRagConfigService configService;

    @Mock
    private ContextualChunkEnricher enricher;

    @InjectMocks
    private ContextualRagConfigController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ContextualRagConfig sampleConfig;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        sampleConfig = ContextualRagConfig.builder()
                .enabled(false)
                .llmProvider("openai")
                .llmModel("gpt-4o-mini")
                .cachingEnabled(true)
                .sourceAttributionEnabled(true)
                .build();

        when(configService.getConfiguration()).thenReturn(sampleConfig);
        when(configService.updateConfiguration(any())).thenReturn(sampleConfig);
        when(configService.resetConfiguration()).thenReturn(sampleConfig);
        when(configService.getConfigFilePath()).thenReturn("/home/user/.kompile/config/contextual-rag-config.json");
        when(configService.getAvailablePresets()).thenReturn(List.of(
                new ContextualRagConfigService.PresetInfo("fast", "Fast", "Quick enrichment")
        ));
        when(configService.applyPreset("fast")).thenReturn(sampleConfig);
        when(configService.getPreset("fast")).thenReturn(sampleConfig);
        when(configService.getPreset("nonexistent")).thenReturn(null);
        when(configService.getPromptTemplate()).thenReturn("Template: {chunk_text}");
    }

    @Test
    void getConfiguration_returnsConfig() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/config"))
                .andExpect(status().isOk());

        verify(configService).getConfiguration();
    }

    @Test
    void updateConfiguration_validConfig_returnsOk() throws Exception {
        ContextualRagConfig update = ContextualRagConfig.builder().enabled(true).build();

        mockMvc.perform(post("/api/contextual-rag/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());

        verify(configService).updateConfiguration(any());
    }

    @Test
    void updateConfiguration_invalidConfig_returnsBadRequest() throws Exception {
        when(configService.updateConfiguration(any())).thenThrow(new IllegalArgumentException("Invalid config"));

        mockMvc.perform(post("/api/contextual-rag/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetConfiguration_returnsDefaultConfig() throws Exception {
        mockMvc.perform(post("/api/contextual-rag/config/reset"))
                .andExpect(status().isOk());

        verify(configService).resetConfiguration();
    }

    @Test
    void getPresets_returnsPresetList() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("fast"));
    }

    @Test
    void applyPreset_validPreset_returnsConfig() throws Exception {
        mockMvc.perform(post("/api/contextual-rag/presets/fast"))
                .andExpect(status().isOk());

        verify(configService).applyPreset("fast");
    }

    @Test
    void applyPreset_invalidPreset_returnsBadRequest() throws Exception {
        when(configService.applyPreset("invalid")).thenThrow(new IllegalArgumentException("Unknown preset"));

        mockMvc.perform(post("/api/contextual-rag/presets/invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPreset_found_returnsConfig() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/presets/fast"))
                .andExpect(status().isOk());
    }

    @Test
    void getPreset_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/presets/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatus_returnsStatusMap() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.enricherAvailable").exists());
    }

    @Test
    void getDefaultPromptTemplate_returnsTemplate() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/prompt-template/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").exists());
    }

    @Test
    void getCurrentPromptTemplate_returnsTemplate() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/prompt-template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("Template: {chunk_text}"));
    }

    @Test
    void clearCaches_withEnricher_returnsSuccess() throws Exception {
        when(enricher.getCacheStats()).thenReturn(Map.of("documentSummaryCount", 5, "chunkContextCount", 10));
        doNothing().when(enricher).clearCaches();

        mockMvc.perform(post("/api/contextual-rag/cache/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void getCacheStats_withEnricher_returnsStats() throws Exception {
        when(enricher.getCacheStats()).thenReturn(Map.of("documentSummaryCount", 5));

        mockMvc.perform(get("/api/contextual-rag/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void toggleEnabled_withEnabledTrue_updatesConfig() throws Exception {
        Map<String, Boolean> body = Map.of("enabled", true);

        mockMvc.perform(post("/api/contextual-rag/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void toggleEnabled_missingField_returnsBadRequest() throws Exception {
        Map<String, Boolean> body = Map.of();

        mockMvc.perform(post("/api/contextual-rag/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing 'enabled' field"));
    }

    @Test
    void toggleSourceAttribution_returnsOk() throws Exception {
        Map<String, Boolean> body = Map.of("enabled", true);

        mockMvc.perform(post("/api/contextual-rag/source-attribution/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void getProviders_returnsProviderList() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void previewPrompt_returnsRenderedPrompt() throws Exception {
        Map<String, Object> body = Map.of("chunkText", "Sample chunk text");

        mockMvc.perform(post("/api/contextual-rag/debug/preview-prompt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedPrompt").exists());
    }

    @Test
    void sampleIndexedChunks_returnsNotAvailable() throws Exception {
        mockMvc.perform(get("/api/contextual-rag/debug/sample-chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }
}
