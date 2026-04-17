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

import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigResponse;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.services.subprocess.SubprocessHandle;
import ai.kompile.app.services.subprocess.SubprocessIngestLauncher;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for SubprocessConfigController.
 * Uses standalone MockMvc setup to avoid Spring context loading issues.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubprocessConfigController Tests")
class SubprocessConfigControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private SubprocessConfigService configService;
    private SubprocessIngestLauncher subprocessLauncher;
    private AnseriniEmbeddingModelImpl embeddingModel;
    private Nd4jEnvironmentConfigService nd4jConfigService;
    private SubprocessConfigController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        configService = mock(SubprocessConfigService.class);
        subprocessLauncher = mock(SubprocessIngestLauncher.class);
        embeddingModel = mock(AnseriniEmbeddingModelImpl.class);
        nd4jConfigService = mock(Nd4jEnvironmentConfigService.class);
        controller = new SubprocessConfigController(configService, subprocessLauncher, embeddingModel, nd4jConfigService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    private void rebuildMockMvc(SubprocessConfigController ctrl) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(ctrl)
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("GET /api/subprocess-config")
    class GetConfigurationTests {

        @Test
        @DisplayName("Should return configuration when configService is available")
        void shouldReturnConfiguration() throws Exception {
            SubprocessConfigResponse response = mock(SubprocessConfigResponse.class);
            when(configService.getConfiguration()).thenReturn(response);

            mockMvc.perform(get("/api/subprocess-config"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 when configService is null")
        void shouldReturn404WhenConfigServiceNull() throws Exception {
            controller = new SubprocessConfigController(null, subprocessLauncher, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(get("/api/subprocess-config"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/subprocess-config")
    class UpdateConfigurationTests {

        @Test
        @DisplayName("Should update configuration")
        void shouldUpdateConfiguration() throws Exception {
            SubprocessConfigResponse response = mock(SubprocessConfigResponse.class);
            when(configService.getConfiguration()).thenReturn(response);

            String updateJson = objectMapper.writeValueAsString(Map.of("enabled", true));

            mockMvc.perform(post("/api/subprocess-config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateJson))
                    .andExpect(status().isOk());

            verify(configService).updateConfiguration(any(SubprocessConfigUpdate.class));
        }

        @Test
        @DisplayName("Should return 404 when configService is null")
        void shouldReturn404WhenConfigServiceNull() throws Exception {
            controller = new SubprocessConfigController(null, subprocessLauncher, null, null);
            rebuildMockMvc(controller);

            String updateJson = objectMapper.writeValueAsString(Map.of("enabled", true));

            mockMvc.perform(post("/api/subprocess-config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateJson))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/subprocess-config/reset")
    class ResetConfigurationTests {

        @Test
        @DisplayName("Should reset configuration to defaults")
        void shouldResetConfiguration() throws Exception {
            SubprocessConfigResponse response = mock(SubprocessConfigResponse.class);
            when(configService.getConfiguration()).thenReturn(response);

            mockMvc.perform(post("/api/subprocess-config/reset"))
                    .andExpect(status().isOk());

            verify(configService).resetToDefaults();
        }

        @Test
        @DisplayName("Should return 404 when configService is null")
        void shouldReturn404WhenConfigServiceNull() throws Exception {
            controller = new SubprocessConfigController(null, subprocessLauncher, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(post("/api/subprocess-config/reset"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/subprocess-config/enable")
    class EnableTests {

        @Test
        @DisplayName("Should enable subprocess mode")
        void shouldEnableSubprocessMode() throws Exception {
            SubprocessConfigResponse response = mock(SubprocessConfigResponse.class);
            when(response.enabled()).thenReturn(true);
            when(configService.isEnabled()).thenReturn(false, true);
            when(configService.getConfiguration()).thenReturn(response);

            mockMvc.perform(post("/api/subprocess-config/enable"))
                    .andExpect(status().isOk());

            verify(configService).setEnabled(true);
        }
    }

    @Nested
    @DisplayName("POST /api/subprocess-config/disable")
    class DisableTests {

        @Test
        @DisplayName("Should disable subprocess mode")
        void shouldDisableSubprocessMode() throws Exception {
            SubprocessConfigResponse response = mock(SubprocessConfigResponse.class);
            when(configService.getConfiguration()).thenReturn(response);

            mockMvc.perform(post("/api/subprocess-config/disable"))
                    .andExpect(status().isOk());

            verify(configService).setEnabled(false);
        }
    }

    @Nested
    @DisplayName("GET /api/subprocess-config/heap-options")
    class GetHeapOptionsTests {

        @Test
        @DisplayName("Should return heap options from service")
        void shouldReturnHeapOptionsFromService() throws Exception {
            when(configService.getHeapSizeOptions())
                    .thenReturn(List.of("2g", "4g", "8g"));

            mockMvc.perform(get("/api/subprocess-config/heap-options"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0]", is("2g")));
        }

        @Test
        @DisplayName("Should return default heap options without service")
        void shouldReturnDefaultHeapOptionsWithoutService() throws Exception {
            controller = new SubprocessConfigController(null, subprocessLauncher, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(get("/api/subprocess-config/heap-options"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(7)))
                    .andExpect(jsonPath("$[0]", is("1g")));
        }
    }

    @Nested
    @DisplayName("GET /api/subprocess-config/active-processes")
    class GetActiveProcessesTests {

        @Test
        @DisplayName("Should return active processes from launcher")
        void shouldReturnActiveProcesses() throws Exception {
            SubprocessHandle.SubprocessStatus status = mock(SubprocessHandle.SubprocessStatus.class);
            when(subprocessLauncher.getAllStatuses()).thenReturn(List.of(status));

            mockMvc.perform(get("/api/subprocess-config/active-processes"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return empty list when launcher is null")
        void shouldReturnEmptyListWhenLauncherNull() throws Exception {
            controller = new SubprocessConfigController(configService, null, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(get("/api/subprocess-config/active-processes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/subprocess-config/active-processes/{taskId}")
    class GetProcessStatusTests {

        @Test
        @DisplayName("Should return process status when found")
        void shouldReturnProcessStatusWhenFound() throws Exception {
            SubprocessHandle.SubprocessStatus processStatus = mock(SubprocessHandle.SubprocessStatus.class);
            when(subprocessLauncher.getStatus("task-1")).thenReturn(processStatus);

            mockMvc.perform(get("/api/subprocess-config/active-processes/task-1"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 when process not found")
        void shouldReturn404WhenProcessNotFound() throws Exception {
            when(subprocessLauncher.getStatus("nonexistent")).thenReturn(null);

            mockMvc.perform(get("/api/subprocess-config/active-processes/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 404 when launcher is null")
        void shouldReturn404WhenLauncherNull() throws Exception {
            controller = new SubprocessConfigController(configService, null, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(get("/api/subprocess-config/active-processes/task-1"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/subprocess-config/active-processes/{taskId}/cancel")
    class CancelProcessTests {

        @Test
        @DisplayName("Should cancel process successfully")
        void shouldCancelProcessSuccessfully() throws Exception {
            when(subprocessLauncher.cancelIngest("task-cancel")).thenReturn(true);

            mockMvc.perform(post("/api/subprocess-config/active-processes/task-cancel/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is("task-cancel")))
                    .andExpect(jsonPath("$.cancelled", is(true)));
        }

        @Test
        @DisplayName("Should return 404 when launcher is null")
        void shouldReturn404WhenLauncherNull() throws Exception {
            controller = new SubprocessConfigController(configService, null, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(post("/api/subprocess-config/active-processes/task-cancel/cancel"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/subprocess-config/system-info")
    class GetSystemInfoTests {

        @Test
        @DisplayName("Should return system information")
        void shouldReturnSystemInfo() throws Exception {
            mockMvc.perform(get("/api/subprocess-config/system-info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.availableProcessors").isNumber())
                    .andExpect(jsonPath("$.maxMemoryMb").isNumber())
                    .andExpect(jsonPath("$.osName").isString())
                    .andExpect(jsonPath("$.javaVersion").isString())
                    .andExpect(jsonPath("$.javaHome").isString());
        }
    }

    @Nested
    @DisplayName("GET /api/subprocess-config/debug")
    class DebugConfigTests {

        @Test
        @DisplayName("Should return debug state with service")
        void shouldReturnDebugStateWithService() throws Exception {
            when(configService.isEnabled()).thenReturn(true);
            SubprocessConfigResponse response = mock(SubprocessConfigResponse.class);
            when(configService.getConfiguration()).thenReturn(response);

            mockMvc.perform(get("/api/subprocess-config/debug"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inMemoryEnabled", is(true)))
                    .andExpect(jsonPath("$.launcherAvailable", is(true)));
        }

        @Test
        @DisplayName("Should return error when configService is null")
        void shouldReturnErrorWhenConfigServiceNull() throws Exception {
            controller = new SubprocessConfigController(null, null, null, null);
            rebuildMockMvc(controller);

            mockMvc.perform(get("/api/subprocess-config/debug"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error", is("configService is NULL")));
        }
    }
}
