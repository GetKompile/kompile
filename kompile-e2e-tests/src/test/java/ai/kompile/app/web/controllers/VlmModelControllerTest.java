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

import ai.kompile.ocr.integration.OcrPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VlmModelController}.
 *
 * Note: VlmModelController uses VlmModelSet and VlmModels static methods,
 * so we focus on testing the controller dispatch logic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmModelControllerTest {

    @Mock
    private OcrPipelineService ocrPipelineService;

    private VlmModelController controller;
    private VlmModelController controllerNoOcr;

    @BeforeEach
    void setUp() {
        controller = new VlmModelController(ocrPipelineService);
        controllerNoOcr = new VlmModelController(null);
    }

    @Test
    void getModelSets_returnsOk() {
        ResponseEntity<?> response = controller.getModelSets();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getModelSetsStatus_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getModelSetsStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getModelSet_unknownModelSet_returnsNotFound() {
        ResponseEntity<?> response = controller.getModelSet("nonexistent-model-set");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getStatus_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
