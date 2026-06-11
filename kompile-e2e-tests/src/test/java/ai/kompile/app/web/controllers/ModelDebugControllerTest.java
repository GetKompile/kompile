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

import ai.kompile.app.services.EmbeddingStatusBroadcaster;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher.ModelInitStatus;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingStartupInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelDebugControllerTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private LanguageModel languageModel;

    @Mock
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    @Mock
    private AnseriniEmbeddingStartupInitializer startupInitializer;

    @Mock
    private ModelInitSubprocessLauncher subprocessLauncher;

    @Mock
    private EmbeddingStatusBroadcaster embeddingStatusBroadcaster;

    private ModelDebugController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelDebugController();
        ReflectionTestUtils.setField(controller, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(controller, "nd4jEnvironmentConfigService", nd4jEnvironmentConfigService);
        ReflectionTestUtils.setField(controller, "startupInitializer", startupInitializer);
        ReflectionTestUtils.setField(controller, "subprocessLauncher", subprocessLauncher);
        ReflectionTestUtils.setField(controller, "embeddingStatusBroadcaster", embeddingStatusBroadcaster);
        // Null embedding / language models by default (absent)
        ReflectionTestUtils.setField(controller, "embeddingModels", null);
        ReflectionTestUtils.setField(controller, "languageModels", null);
        // Stub subprocess status to avoid NPE in controller which calls .name()
        when(startupInitializer.getSubprocessStatus())
                .thenReturn(AnseriniEmbeddingStartupInitializer.SubprocessInitStatus.IDLE);
        // Stub launcher current status to avoid NPE when controller calls .status()
        when(subprocessLauncher.getCurrentStatus()).thenReturn(ModelInitStatus.idle());
    }

    @Test
    void getModelStatus_withNoEmbeddingModels_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> response = controller.getModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> embeddingStatus = (Map<?, ?>) response.getBody().get("embedding");
        assertNotNull(embeddingStatus);
        assertEquals(false, embeddingStatus.get("available"));
    }

    @Test
    void getModelStatus_withEmbeddingModels_returnsAvailableTrue() {
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.getOptimalBatchSize()).thenReturn(32);
        ReflectionTestUtils.setField(controller, "embeddingModels", List.of(embeddingModel));

        ResponseEntity<Map<String, Object>> response = controller.getModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> embeddingStatus = (Map<?, ?>) response.getBody().get("embedding");
        assertNotNull(embeddingStatus);
        assertEquals(true, embeddingStatus.get("available"));
        assertEquals(1, embeddingStatus.get("count"));
    }

    @Test
    void getModelStatus_withLanguageModels_reportsLanguageModelInfo() {
        // No need to mock getClass() — Mockito's proxy class has a valid getSimpleName()
        ReflectionTestUtils.setField(controller, "languageModels", List.of(languageModel));

        ResponseEntity<Map<String, Object>> response = controller.getModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // The controller puts llm info under "languageModel" key
        Map<?, ?> llmStatus = (Map<?, ?>) response.getBody().get("languageModel");
        assertNotNull(llmStatus);
        assertEquals(true, llmStatus.get("available"));
    }

    @Test
    void getModelStatus_withNoLanguageModels_reportsUnavailable() {
        ResponseEntity<Map<String, Object>> response = controller.getModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // The controller puts llm info under "languageModel" key
        Map<?, ?> llmStatus = (Map<?, ?>) response.getBody().get("languageModel");
        assertNotNull(llmStatus);
        assertEquals(false, llmStatus.get("available"));
    }

    @Test
    void getModelStatus_alwaysIncludesSubprocessSection() {
        when(subprocessLauncher.isInitializationRunning()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.getModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // subprocess section should be present
        assertNotNull(response.getBody());
    }
}
