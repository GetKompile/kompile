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

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.config.AppIndexConfig.VectorStoreType;
import ai.kompile.app.services.AppIndexConfigService;
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

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppConfigControllerTest {

    @Mock
    private AppIndexConfigService configService;

    @InjectMocks
    private AppConfigController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AppIndexConfig sampleConfig;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        sampleConfig = AppIndexConfig.builder()
                .vectorStoreType(VectorStoreType.ANSERINI)
                .vectorStorePath("/data/vector")
                .keywordIndexPath("/data/keyword")
                .subprocessEnabled(true)
                .subprocessHeapSize("4g")
                .indexBatchSize(32)
                .adaptiveBatchSize(false)
                .embeddingTargetBatchSize(16)
                .pgvectorPassword(null)
                .build();

        when(configService.getActualConfiguration()).thenReturn(sampleConfig);
        when(configService.getConfiguration()).thenReturn(sampleConfig);
        when(configService.getConfigFilePath()).thenReturn("/home/user/.kompile/config/app-index-config.json");
        when(configService.updateConfiguration(any())).thenReturn(sampleConfig);
        when(configService.resetConfiguration()).thenReturn(sampleConfig);
    }

    @Test
    void getConfig_returnsOkWithConfigFields() throws Exception {
        mockMvc.perform(get("/api/config/k-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectorStoreType").value("ANSERINI"))
                .andExpect(jsonPath("$.vectorStorePath").value("/data/vector"))
                .andExpect(jsonPath("$.configFilePath").exists());
    }

    @Test
    void getConfig_pgvectorPasswordNotExposed() throws Exception {
        AppIndexConfig configWithPw = AppIndexConfig.builder()
                .vectorStoreType(VectorStoreType.ANSERINI)
                .pgvectorPassword("secret")
                .build();
        when(configService.getActualConfiguration()).thenReturn(configWithPw);

        mockMvc.perform(get("/api/config/k-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pgvectorPasswordSet").value(true))
                .andExpect(jsonPath("$.pgvectorPassword").doesNotExist());
    }

    @Test
    void getConfig_pgvectorPasswordNotSet_setFlagFalse() throws Exception {
        mockMvc.perform(get("/api/config/k-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pgvectorPasswordSet").value(false));
    }

    @Test
    void updateConfig_noChanges_returnsOk() throws Exception {
        Map<String, Object> body = new HashMap<>();

        mockMvc.perform(put("/api/config/k-app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(false));
    }

    @Test
    void updateConfig_validVectorStoreType_updatesConfig() throws Exception {
        AppIndexConfig changed = AppIndexConfig.builder()
                .vectorStoreType(VectorStoreType.PGVECTOR)
                .build();
        when(configService.updateConfiguration(any())).thenReturn(changed);

        Map<String, Object> body = new HashMap<>();
        body.put("vectorStoreType", "PGVECTOR");

        mockMvc.perform(put("/api/config/k-app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restartRequired").value(true));
    }

    @Test
    void updateConfig_invalidVectorStoreType_ignoredGracefully() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("vectorStoreType", "INVALID_TYPE");

        mockMvc.perform(put("/api/config/k-app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void updateConfig_batchSizeChange_detectsChange() throws Exception {
        AppIndexConfig changed = AppIndexConfig.builder()
                .vectorStoreType(VectorStoreType.ANSERINI)
                .indexBatchSize(64)
                .adaptiveBatchSize(false)
                .embeddingTargetBatchSize(16)
                .subprocessEnabled(true)
                .build();
        when(configService.updateConfiguration(any())).thenReturn(changed);

        Map<String, Object> body = new HashMap<>();
        body.put("indexBatchSize", 64);

        mockMvc.perform(put("/api/config/k-app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(true));
    }

    @Test
    void resetConfig_returnsOkWithDefaults() throws Exception {
        mockMvc.perform(post("/api/config/k-app/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Configuration reset to defaults"));

        verify(configService).resetConfiguration();
    }

    @Test
    void updateConfig_vespaEndpointChange_setsRestartRequired() throws Exception {
        AppIndexConfig changed = AppIndexConfig.builder()
                .vectorStoreType(VectorStoreType.VESPA)
                .vespaEndpoint("http://vespa:8080")
                .build();
        when(configService.updateConfiguration(any())).thenReturn(changed);
        // The current config returns null for vespaEndpoint (unset), so new value triggers change
        when(configService.getConfiguration()).thenReturn(AppIndexConfig.builder()
                .vectorStoreType(VectorStoreType.ANSERINI).build());

        Map<String, Object> body = new HashMap<>();
        body.put("vespaEndpoint", "http://vespa:8080");

        mockMvc.perform(put("/api/config/k-app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restartRequired").value(true));
    }
}
