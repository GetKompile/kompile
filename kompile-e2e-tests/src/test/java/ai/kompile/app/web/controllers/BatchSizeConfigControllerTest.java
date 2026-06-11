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

import ai.kompile.app.services.BatchSizeConfigService;
import ai.kompile.app.web.dto.*;
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
class BatchSizeConfigControllerTest {

    @Mock
    private BatchSizeConfigService configService;

    @InjectMocks
    private BatchSizeConfigController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BatchSizeConfigResponse sampleResponse;
    private EmbeddingModelInfo sampleModelInfo;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        sampleResponse = BatchSizeConfigResponse.builder()
                .modelId("bge-base-en-v1.5")
                .currentOptimalBatchSize(32)
                .currentMaxBatchSize(64)
                .absoluteMaxBatchSize(128)
                .isAutoScaled(true)
                .build();

        sampleModelInfo = EmbeddingModelInfo.builder()
                .modelId("bge-base-en-v1.5")
                .displayName("BGE Base EN v1.5")
                .dimensions(768)
                .isLoaded(true)
                .build();

        when(configService.getAvailableModels()).thenReturn(List.of(sampleModelInfo));
        when(configService.getConfiguration(anyString())).thenReturn(sampleResponse);
        when(configService.getConfiguration(null)).thenReturn(sampleResponse);
        when(configService.updateConfiguration(anyString(), any())).thenReturn(sampleResponse);
        when(configService.updateConfiguration(isNull(), any())).thenReturn(sampleResponse);
        when(configService.resetConfiguration(anyString())).thenReturn(sampleResponse);
        when(configService.resetConfiguration(null)).thenReturn(sampleResponse);
        when(configService.getTimeoutConfiguration()).thenReturn(Map.of("modelLoadTimeoutSeconds", 0L));
        when(configService.updateTimeoutConfiguration(any(), any(), any(), any(), any())).thenReturn(Map.of("modelLoadTimeoutSeconds", 60L));
        when(configService.resetTimeoutConfiguration()).thenReturn(Map.of("modelLoadTimeoutSeconds", 0L));
    }

    @Test
    void getAvailableModels_returnsModelList() throws Exception {
        mockMvc.perform(get("/api/embeddings/batch-config/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelId").value("bge-base-en-v1.5"));
    }

    @Test
    void getAvailableModels_serviceThrows_returns500() throws Exception {
        when(configService.getAvailableModels()).thenThrow(new RuntimeException("Service error"));

        mockMvc.perform(get("/api/embeddings/batch-config/models"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getModelConfig_returnsConfig() throws Exception {
        mockMvc.perform(get("/api/embeddings/batch-config/models/bge-base-en-v1.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentOptimalBatchSize").value(32));
    }

    @Test
    void getGlobalConfig_returnsConfig() throws Exception {
        mockMvc.perform(get("/api/embeddings/batch-config/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentOptimalBatchSize").value(32));
    }

    @Test
    void updateModelConfig_validRequest_returnsUpdatedConfig() throws Exception {
        BatchSizeConfigRequest request = new BatchSizeConfigRequest(null, 32, 64, null, false);

        mockMvc.perform(put("/api/embeddings/batch-config/models/bge-base-en-v1.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void updateModelConfig_illegalArg_returns400() throws Exception {
        when(configService.updateConfiguration(anyString(), any()))
                .thenThrow(new IllegalArgumentException("Invalid batch size"));

        BatchSizeConfigRequest request = new BatchSizeConfigRequest(null, -1, 0, null, false);

        mockMvc.perform(put("/api/embeddings/batch-config/models/bge-base-en-v1.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid batch size"));
    }

    @Test
    void updateGlobalConfig_validRequest_returnsOk() throws Exception {
        BatchSizeConfigRequest request = new BatchSizeConfigRequest(null, 16, 32, null, true);

        mockMvc.perform(put("/api/embeddings/batch-config/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void resetModelConfig_returnsResetConfig() throws Exception {
        mockMvc.perform(post("/api/embeddings/batch-config/models/bge-base-en-v1.5/reset"))
                .andExpect(status().isOk());

        verify(configService).resetConfiguration("bge-base-en-v1.5");
    }

    @Test
    void resetGlobalConfig_returnsResetConfig() throws Exception {
        mockMvc.perform(post("/api/embeddings/batch-config/global/reset"))
                .andExpect(status().isOk());

        verify(configService).resetConfiguration(null);
    }

    @Test
    void getTimeoutConfig_returnsOk() throws Exception {
        mockMvc.perform(get("/api/embeddings/batch-config/timeouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelLoadTimeoutSeconds").value(0));
    }

    @Test
    void updateTimeoutConfig_returnsUpdated() throws Exception {
        Map<String, Object> body = Map.of("modelLoadTimeoutSeconds", 60);

        mockMvc.perform(put("/api/embeddings/batch-config/timeouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelLoadTimeoutSeconds").value(60));
    }

    @Test
    void resetTimeoutConfig_returnsDefaults() throws Exception {
        mockMvc.perform(post("/api/embeddings/batch-config/timeouts/reset"))
                .andExpect(status().isOk());

        verify(configService).resetTimeoutConfiguration();
    }

    @Test
    void getStatus_returnsStatusInfo() throws Exception {
        mockMvc.perform(get("/api/embeddings/batch-config/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
