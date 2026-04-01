/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for Nd4jEnvironmentController.
 * Uses standalone MockMvc setup to avoid Spring context loading (and ND4J initialization).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Nd4jEnvironmentController Tests")
class Nd4jEnvironmentControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @Mock
    private Nd4jEnvironmentConfigService configService;

    @InjectMocks
    private Nd4jEnvironmentController nd4jEnvironmentController;

    private Nd4jEnvironmentConfig defaultConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(nd4jEnvironmentController)
                .setMessageConverters(converter)
                .build();

        defaultConfig = Nd4jEnvironmentConfig.builder()
                .maxThreads(4)
                .maxMasterThreads(4)
                .debug(false)
                .verbose(false)
                .lifecycleTracking(true)
                .ndArrayTracking(true)
                .dataBufferTracking(false)
                .tadCacheTracking(false)
                .shapeCacheTracking(false)
                .opContextTracking(false)
                .stackDepth(10)
                .reportInterval(60)
                .maxDeletionHistory(1000)
                .trackViews(false)
                .trackDeletions(false)
                .blasSerializationEnabled(true)
                .openBlasThreads(1)
                .build();
    }

    @Nested
    @DisplayName("GET /api/nd4j/environment")
    class GetConfiguration {

        @Test
        @DisplayName("should return persisted, actual, and summary configuration")
        void getConfigurationReturnsAll() throws Exception {
            Nd4jEnvironmentConfig actualConfig = Nd4jEnvironmentConfig.builder()
                    .maxThreads(4)
                    .maxMasterThreads(4)
                    .build();

            Map<String, Object> summary = new HashMap<>();
            summary.put("threads", Map.of("maxThreads", 4, "maxMasterThreads", 4));

            when(configService.getConfiguration()).thenReturn(defaultConfig);
            when(configService.getActualConfiguration()).thenReturn(actualConfig);
            when(configService.getConfigurationSummary()).thenReturn(summary);

            mockMvc.perform(get("/api/nd4j/environment"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.persisted", notNullValue()))
                    .andExpect(jsonPath("$.actual", notNullValue()))
                    .andExpect(jsonPath("$.summary", notNullValue()));

            verify(configService).getConfiguration();
            verify(configService).getActualConfiguration();
            verify(configService).getConfigurationSummary();
        }
    }

    @Nested
    @DisplayName("GET /api/nd4j/environment/summary")
    class GetSummary {

        @Test
        @DisplayName("should return configuration summary")
        void getSummary() throws Exception {
            Map<String, Object> summary = new HashMap<>();
            summary.put("threads", Map.of("maxThreads", 4));
            summary.put("tracking", Map.of("lifecycleTracking", true));

            when(configService.getConfigurationSummary()).thenReturn(summary);

            mockMvc.perform(get("/api/nd4j/environment/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.threads", notNullValue()))
                    .andExpect(jsonPath("$.tracking", notNullValue()));

            verify(configService).getConfigurationSummary();
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment")
    class UpdateConfiguration {

        @Test
        @DisplayName("should update configuration and return success")
        void updateConfigSuccess() throws Exception {
            Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .debug(true)
                    .build();

            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .maxMasterThreads(4)
                    .debug(true)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.message", is("Configuration updated and persisted")))
                    .andExpect(jsonPath("$.config", notNullValue()));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/threads")
    class UpdateThreads {

        @Test
        @DisplayName("should update thread settings and return new values")
        void updateThreadSettings() throws Exception {
            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .maxMasterThreads(2)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment/threads")
                            .param("maxThreads", "8")
                            .param("maxMasterThreads", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.maxThreads", is(8)))
                    .andExpect(jsonPath("$.maxMasterThreads", is(2)));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/lifecycle-tracking")
    class LifecycleTracking {

        @Test
        @DisplayName("should enable lifecycle tracking")
        void enableLifecycleTracking() throws Exception {
            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .lifecycleTracking(true)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment/lifecycle-tracking")
                            .param("enabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.lifecycleTracking", is(true)))
                    .andExpect(jsonPath("$.message", is("Lifecycle tracking enabled")));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }

        @Test
        @DisplayName("should disable lifecycle tracking")
        void disableLifecycleTracking() throws Exception {
            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .lifecycleTracking(false)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment/lifecycle-tracking")
                            .param("enabled", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.lifecycleTracking", is(false)))
                    .andExpect(jsonPath("$.message", is("Lifecycle tracking disabled")));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/trackers")
    class UpdateTrackers {

        @Test
        @DisplayName("should update individual tracker toggles")
        void updateTrackerToggles() throws Exception {
            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .ndArrayTracking(true)
                    .dataBufferTracking(true)
                    .tadCacheTracking(false)
                    .shapeCacheTracking(false)
                    .opContextTracking(true)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment/trackers")
                            .param("ndArrayTracking", "true")
                            .param("dataBufferTracking", "true")
                            .param("opContextTracking", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.ndArrayTracking", is(true)))
                    .andExpect(jsonPath("$.dataBufferTracking", is(true)))
                    .andExpect(jsonPath("$.opContextTracking", is(true)));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/preset/{presetName}")
    class ApplyPreset {

        @Test
        @DisplayName("should apply a valid preset and return success")
        void applyPresetSuccess() throws Exception {
            Nd4jEnvironmentConfig presetConfig = Nd4jEnvironmentConfig.builder()
                    .maxThreads(2)
                    .lifecycleTracking(true)
                    .build();

            when(configService.applyPreset(eq("minimal"))).thenReturn(presetConfig);

            mockMvc.perform(post("/api/nd4j/environment/preset/{presetName}", "minimal"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.preset", is("minimal")))
                    .andExpect(jsonPath("$.config", notNullValue()));

            verify(configService).applyPreset(eq("minimal"));
        }

        @Test
        @DisplayName("should return 400 for invalid preset name")
        void applyPresetInvalid() throws Exception {
            when(configService.applyPreset(eq("invalid")))
                    .thenThrow(new IllegalArgumentException("Unknown preset: invalid"));

            mockMvc.perform(post("/api/nd4j/environment/preset/{presetName}", "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is("error")))
                    .andExpect(jsonPath("$.message", is("Unknown preset: invalid")));

            verify(configService).applyPreset(eq("invalid"));
        }
    }

    @Nested
    @DisplayName("GET /api/nd4j/environment/presets")
    class GetPresets {

        @Test
        @DisplayName("should return available presets with descriptions")
        void getAvailablePresets() throws Exception {
            mockMvc.perform(get("/api/nd4j/environment/presets"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.presets", notNullValue()))
                    .andExpect(jsonPath("$.presets.minimal.name", is("minimal")))
                    .andExpect(jsonPath("$.presets.balanced.name", is("balanced")))
                    .andExpect(jsonPath("$.presets.detailed.name", is("detailed")))
                    .andExpect(jsonPath("$.presets.performance.name", is("performance")))
                    .andExpect(jsonPath("$.currentPreset", is("custom")));
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/reset")
    class ResetConfiguration {

        @Test
        @DisplayName("should reset configuration to defaults")
        void resetConfig() throws Exception {
            when(configService.resetConfiguration()).thenReturn(defaultConfig);

            mockMvc.perform(post("/api/nd4j/environment/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.message", is("Configuration reset to defaults and persisted")))
                    .andExpect(jsonPath("$.config", notNullValue()));

            verify(configService).resetConfiguration();
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/lifecycle-params")
    class LifecycleParams {

        @Test
        @DisplayName("should update lifecycle parameters successfully")
        void updateLifecycleParamsSuccess() throws Exception {
            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .stackDepth(20)
                    .reportInterval(120)
                    .maxDeletionHistory(5000)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment/lifecycle-params")
                            .param("stackDepth", "20")
                            .param("reportInterval", "120")
                            .param("maxDeletionHistory", "5000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.stackDepth", is(20)))
                    .andExpect(jsonPath("$.reportInterval", is(120)))
                    .andExpect(jsonPath("$.maxDeletionHistory", is(5000)));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }

        @Test
        @DisplayName("should return 400 for invalid stackDepth")
        void invalidStackDepth() throws Exception {
            mockMvc.perform(post("/api/nd4j/environment/lifecycle-params")
                            .param("stackDepth", "300"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is("error")))
                    .andExpect(jsonPath("$.message", is("stackDepth must be between 0 and 256")));
        }
    }

    @Nested
    @DisplayName("POST /api/nd4j/environment/high-overhead-tracking")
    class HighOverheadTracking {

        @Test
        @DisplayName("should update high overhead tracking settings")
        void updateHighOverheadTracking() throws Exception {
            Nd4jEnvironmentConfig updatedConfig = Nd4jEnvironmentConfig.builder()
                    .trackViews(true)
                    .trackDeletions(false)
                    .build();

            when(configService.updateConfiguration(any(Nd4jEnvironmentConfig.class))).thenReturn(updatedConfig);

            mockMvc.perform(post("/api/nd4j/environment/high-overhead-tracking")
                            .param("trackViews", "true")
                            .param("trackDeletions", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("success")))
                    .andExpect(jsonPath("$.trackViews", is(true)))
                    .andExpect(jsonPath("$.trackDeletions", is(false)));

            verify(configService).updateConfiguration(any(Nd4jEnvironmentConfig.class));
        }
    }

    @Nested
    @DisplayName("GET /api/nd4j/environment/blas")
    class GetBlasSettings {

        @Test
        @DisplayName("should return BLAS configuration settings")
        void getBlasSettings() throws Exception {
            Nd4jEnvironmentConfig actualConfig = Nd4jEnvironmentConfig.builder()
                    .blasSerializationEnabled(true)
                    .openBlasThreads(1)
                    .build();

            when(configService.getActualConfiguration()).thenReturn(actualConfig);

            mockMvc.perform(get("/api/nd4j/environment/blas"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.blasSerializationEnabled", is(true)))
                    .andExpect(jsonPath("$.openBlasThreads", is(1)))
                    .andExpect(jsonPath("$.availableProcessors", notNullValue()))
                    .andExpect(jsonPath("$.description", notNullValue()));

            verify(configService).getActualConfiguration();
        }
    }
}
