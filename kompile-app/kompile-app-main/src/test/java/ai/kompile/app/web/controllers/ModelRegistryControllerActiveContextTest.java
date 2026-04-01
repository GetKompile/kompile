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

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for the /api/models/active-context endpoint.
 * Uses standalone MockMvc to avoid Spring context loading.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelRegistryController /active-context Tests")
class ModelRegistryControllerActiveContextTest {

    @Mock
    private AnseriniEmbeddingModelImpl embeddingModel;

    @Mock
    private AnseriniVectorStoreImpl vectorStore;

    @Mock
    private StagingServiceConfigService stagingConfigService;

    @Mock
    private StagingClientService stagingClientService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ModelRegistryController controller = new ModelRegistryController(
                embeddingModel, vectorStore, stagingConfigService, stagingClientService,
                null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Returns embedding info when model is loaded")
    void returnsEmbeddingInfo() throws Exception {
        when(embeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(embeddingModel.getEncoderType()).thenReturn("dense");
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.isInitialized()).thenReturn(true);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.empty());
        when(stagingClientService.getConnectionStatus())
                .thenReturn(StagingClientService.ConnectionStatus.notConnected());

        mockMvc.perform(get("/api/models/active-context")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.modelId", is("bge-base-en-v1.5")))
                .andExpect(jsonPath("$.embedding.encoderType", is("dense")))
                .andExpect(jsonPath("$.embedding.dimensions", is(768)))
                .andExpect(jsonPath("$.embedding.initialized", is(true)))
                .andExpect(jsonPath("$.embedding.status", is("ready")));
    }

    @Test
    @DisplayName("Returns null embedding when no model configured")
    void returnsNullEmbeddingWhenNotConfigured() throws Exception {
        when(embeddingModel.getActiveModelId()).thenReturn(null);
        when(embeddingModel.getEncoderType()).thenReturn(null);
        when(embeddingModel.dimensions()).thenReturn(0);
        when(embeddingModel.isInitialized()).thenReturn(false);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.empty());
        when(stagingClientService.getConnectionStatus())
                .thenReturn(StagingClientService.ConnectionStatus.notConnected());

        mockMvc.perform(get("/api/models/active-context")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.modelId").value(nullValue()))
                .andExpect(jsonPath("$.embedding.status", is("not_configured")))
                .andExpect(jsonPath("$.embedding.initialized", is(false)));
    }

    @Test
    @DisplayName("Returns reranker info when available")
    void returnsRerankerInfo() throws Exception {
        when(embeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(embeddingModel.getEncoderType()).thenReturn("dense");
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.isInitialized()).thenReturn(true);
        when(vectorStore.getRerankerModelId()).thenReturn("bge-reranker-v2-m3");
        when(vectorStore.isRerankingAvailable()).thenReturn(true);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.empty());
        when(stagingClientService.getConnectionStatus())
                .thenReturn(StagingClientService.ConnectionStatus.notConnected());

        mockMvc.perform(get("/api/models/active-context")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reranker.modelId", is("bge-reranker-v2-m3")))
                .andExpect(jsonPath("$.reranker.available", is(true)));
    }

    @Test
    @DisplayName("Returns staging context when connected")
    void returnsStagingContextWhenConnected() throws Exception {
        when(embeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(embeddingModel.getEncoderType()).thenReturn("dense");
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.isInitialized()).thenReturn(true);

        StagingServiceConfig config = new StagingServiceConfig();
        config.setEndpointUrl("http://localhost:8081");
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.of(config));
        when(stagingClientService.getConnectionStatus())
                .thenReturn(StagingClientService.ConnectionStatus.success("http://localhost:8081"));
        when(stagingClientService.getRegistry()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/models/active-context")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staging.connected", is(true)))
                .andExpect(jsonPath("$.staging.endpointUrl", is("http://localhost:8081")))
                .andExpect(jsonPath("$.staging.uiUrl", is("http://localhost:8081")));
    }

    @Test
    @DisplayName("Returns empty alternatives when staging disconnected")
    void returnsEmptyAlternativesWhenDisconnected() throws Exception {
        when(embeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(embeddingModel.getEncoderType()).thenReturn("dense");
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.isInitialized()).thenReturn(true);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.empty());
        when(stagingClientService.getConnectionStatus())
                .thenReturn(StagingClientService.ConnectionStatus.notConnected());

        mockMvc.perform(get("/api/models/active-context")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableEmbeddingModels", hasSize(0)))
                .andExpect(jsonPath("$.availableRerankerModels", hasSize(0)));
    }

    @Test
    @DisplayName("Handles all-null dependencies gracefully")
    void handlesAllNullDependencies() throws Exception {
        // Create controller with all null optional dependencies
        ModelRegistryController controller = new ModelRegistryController(
                null, null, null, null, null, null);
        MockMvc nullMockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        nullMockMvc.perform(get("/api/models/active-context")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding").value(nullValue()))
                .andExpect(jsonPath("$.reranker").value(nullValue()))
                .andExpect(jsonPath("$.staging").value(nullValue()))
                .andExpect(jsonPath("$.availableEmbeddingModels", hasSize(0)))
                .andExpect(jsonPath("$.availableRerankerModels", hasSize(0)));
    }
}
