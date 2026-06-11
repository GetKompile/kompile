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

import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.staging.StagingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphExtractionModelControllerTest {

    @Mock
    private StagingService stagingService;

    @Mock
    private GraphConstructor graphConstructor;

    private GraphExtractionModelController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphExtractionModelController();
        ReflectionTestUtils.setField(controller, "stagingService", stagingService);
        ReflectionTestUtils.setField(controller, "graphConstructors", List.of(graphConstructor));
    }

    // ─── listExtractionModels ─────────────────────────────────────────────────

    @Test
    void listExtractionModels_noStagingService_returnsBuiltInOnly() {
        GraphExtractionModelController ctrl = new GraphExtractionModelController();
        ReflectionTestUtils.setField(ctrl, "stagingService", null);
        ReflectionTestUtils.setField(ctrl, "graphConstructors", null);

        ResponseEntity<List<Map<String, Object>>> resp = ctrl.listExtractionModels();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // built-in providers: default, openai, anthropic, ollama
        assertThat(resp.getBody()).hasSize(4);
        assertThat(resp.getBody().stream().map(m -> m.get("provider")).toList())
                .containsExactlyInAnyOrder("default", "openai", "anthropic", "ollama");
    }

    @Test
    void listExtractionModels_withStagingService_includesStagedModels() {
        StagingModelInfo readyModel = mock(StagingModelInfo.class);
        when(readyModel.getStatus()).thenReturn(StagingStatus.READY);
        when(readyModel.getModelId()).thenReturn("my-staged-model");

        StagingModelInfo notReadyModel = mock(StagingModelInfo.class);
        when(notReadyModel.getStatus()).thenReturn(StagingStatus.PENDING);

        when(stagingService.getStagingModels()).thenReturn(List.of(readyModel, notReadyModel));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listExtractionModels();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 4 built-in + 1 ready staged model
        assertThat(resp.getBody()).hasSize(5);
        List<String> providerIds = resp.getBody().stream()
                .map(m -> (String) m.get("provider")).toList();
        assertThat(providerIds).contains("staged");
    }

    @Test
    void listExtractionModels_stagingServiceThrows_returnsBuiltInOnly() {
        when(stagingService.getStagingModels()).thenThrow(new RuntimeException("connection refused"));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listExtractionModels();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(4);
    }

    // ─── configureExtractionModel ─────────────────────────────────────────────

    @Test
    void configureExtractionModel_callsConstructorConfigure() {
        Map<String, Object> config = Map.of(
                "provider", "openai",
                "modelName", "gpt-4",
                "temperature", 0.2,
                "maxTokens", 2048);

        ResponseEntity<Map<String, String>> resp = controller.configureExtractionModel(config);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("configured");
        assertThat(resp.getBody().get("provider")).isEqualTo("openai");
        assertThat(resp.getBody().get("modelName")).isEqualTo("gpt-4");
        verify(graphConstructor).configure(any());
    }

    @Test
    void configureExtractionModel_noConstructors_doesNotThrow() {
        GraphExtractionModelController ctrl = new GraphExtractionModelController();
        ReflectionTestUtils.setField(ctrl, "stagingService", null);
        ReflectionTestUtils.setField(ctrl, "graphConstructors", null);

        ResponseEntity<Map<String, String>> resp = ctrl.configureExtractionModel(Map.of(
                "provider", "default",
                "modelName", "default"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("configured");
    }

    @Test
    void configureExtractionModel_defaultsApplied() {
        // provider defaults to "default", modelName may be null
        Map<String, Object> config = Map.of();

        ResponseEntity<Map<String, String>> resp = controller.configureExtractionModel(config);

        assertThat(resp.getBody().get("provider")).isEqualTo("default");
        assertThat(resp.getBody().get("modelName")).isEqualTo("default"); // null → "default"
    }
}
