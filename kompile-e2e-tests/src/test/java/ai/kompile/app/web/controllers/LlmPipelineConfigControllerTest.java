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

import ai.kompile.modelmanager.llm.dynamic.LlmCustomModelSet;
import ai.kompile.modelmanager.llm.dynamic.LlmPipelineDefinition;
import ai.kompile.modelmanager.llm.dynamic.LlmStageDefinition;
import ai.kompile.modelmanager.llm.registry.LlmPipelineRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LlmPipelineConfigController.
 * Uses the singleton LlmPipelineRegistry directly (no Spring context needed).
 * Each test registers / removes its own pipelines/stages to avoid state bleed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmPipelineConfigControllerTest {

    private LlmPipelineConfigController controller;
    private LlmPipelineRegistry registry;

    @BeforeEach
    void setUp() {
        controller = new LlmPipelineConfigController();
        registry = LlmPipelineRegistry.getInstance();
    }

    // ─── helper ───────────────────────────────────────────────────────────────

    private LlmPipelineDefinition buildPipeline(String id) {
        LlmPipelineDefinition p = LlmPipelineDefinition.builder()
                .pipelineId(id)
                .displayName("Pipeline " + id)
                .description("Test pipeline")
                .pipelineType(null)  // null type bypasses SEQUENCE/GRAPH stage-count validation
                .isBuiltin(false)
                .enabled(true)
                .build();
        return p;
    }

    private LlmStageDefinition buildStage(String id) {
        LlmStageDefinition s = new LlmStageDefinition();
        s.setStageId(id);
        s.setDisplayName("Stage " + id);
        s.setBuiltin(false);
        return s;
    }

    private LlmCustomModelSet buildCustomModelSet(String id) {
        LlmCustomModelSet ms = new LlmCustomModelSet();
        ms.setSetId(id);
        ms.setDisplayName("Set " + id);
        ms.setComponents(Collections.emptyList());
        return ms;
    }

    // ─── pipeline list ───────────────────────────────────────────────────────

    @Test
    void listPipelines_withNoTypeFilter_returnsAll() {
        ResponseEntity<Map<String, Object>> response = controller.listPipelines(null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("pipelines"));
        assertTrue(response.getBody().containsKey("total"));
    }

    @Test
    void listPipelines_withBuiltinFilter_returnsOnlyBuiltins() {
        ResponseEntity<Map<String, Object>> response = controller.listPipelines("builtin");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // All returned pipelines are builtin
        List<?> items = (List<?>) response.getBody().get("pipelines");
        for (Object item : items) {
            Map<?, ?> map = (Map<?, ?>) item;
            assertEquals(true, map.get("isBuiltin"));
        }
    }

    @Test
    void listPipelines_withCustomFilter_returnsOnlyCustom() {
        String id = "test-custom-pipe-" + System.nanoTime();
        LlmPipelineDefinition p = buildPipeline(id);
        registry.registerPipeline(p);
        try {
            ResponseEntity<Map<String, Object>> response = controller.listPipelines("custom");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            List<?> items = (List<?>) response.getBody().get("pipelines");
            // All returned pipelines are NOT builtin
            for (Object item : items) {
                Map<?, ?> map = (Map<?, ?>) item;
                assertEquals(false, map.get("isBuiltin"));
            }
        } finally {
            registry.removePipeline(id);
        }
    }

    // ─── pipeline get ─────────────────────────────────────────────────────────

    @Test
    void getPipeline_whenExists_returnsOk() {
        String id = "test-pipe-get-" + System.nanoTime();
        registry.registerPipeline(buildPipeline(id));
        try {
            ResponseEntity<Object> response = controller.getPipeline(id);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
        } finally {
            registry.removePipeline(id);
        }
    }

    @Test
    void getPipeline_whenNotExists_returnsNotFound() {
        ResponseEntity<Object> response = controller.getPipeline("does-not-exist-xyz");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── pipeline create ──────────────────────────────────────────────────────

    @Test
    void createPipeline_successfullyCreates() {
        String id = "test-pipe-create-" + System.nanoTime();
        LlmPipelineDefinition p = buildPipeline(id);
        try {
            ResponseEntity<Map<String, Object>> response = controller.createPipeline(p);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertEquals(id, response.getBody().get("pipelineId"));
        } finally {
            registry.removePipeline(id);
        }
    }

    @Test
    void createPipeline_whenAlreadyExists_returnsConflict() {
        String id = "test-pipe-conflict-" + System.nanoTime();
        registry.registerPipeline(buildPipeline(id));
        try {
            ResponseEntity<Map<String, Object>> response = controller.createPipeline(buildPipeline(id));
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        } finally {
            registry.removePipeline(id);
        }
    }

    // ─── pipeline delete ──────────────────────────────────────────────────────

    @Test
    void deletePipeline_whenExists_returnsOk() {
        String id = "test-pipe-del-" + System.nanoTime();
        registry.registerPipeline(buildPipeline(id));
        ResponseEntity<Map<String, Object>> response = controller.deletePipeline(id);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(id, response.getBody().get("pipelineId"));
    }

    @Test
    void deletePipeline_whenNotExists_returnsNotFound() {
        ResponseEntity<Map<String, Object>> response = controller.deletePipeline("nonexistent-xyz-789");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── pipeline validate ────────────────────────────────────────────────────

    @Test
    void validatePipeline_returnsValidationResult() {
        LlmPipelineDefinition p = buildPipeline("validate-test-" + System.nanoTime());
        ResponseEntity<Map<String, Object>> response = controller.validatePipeline(p);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().containsKey("valid"));
        assertTrue(response.getBody().containsKey("errors"));
    }

    // ─── stage list ───────────────────────────────────────────────────────────

    @Test
    void listStages_returnsAllStages() {
        ResponseEntity<Map<String, Object>> response = controller.listStages();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("stages"));
    }

    // ─── stage get ────────────────────────────────────────────────────────────

    @Test
    void getStage_whenExists_returnsOk() {
        String id = "test-stage-" + System.nanoTime();
        registry.registerStage(buildStage(id));
        try {
            ResponseEntity<Object> response = controller.getStage(id);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        } finally {
            registry.removeStage(id);
        }
    }

    @Test
    void getStage_whenNotExists_returnsNotFound() {
        ResponseEntity<Object> response = controller.getStage("nonexistent-stage");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── stage create ─────────────────────────────────────────────────────────

    @Test
    void createStage_successfullyCreates() {
        String id = "test-stage-create-" + System.nanoTime();
        LlmStageDefinition stage = buildStage(id);
        try {
            ResponseEntity<Map<String, Object>> response = controller.createStage(stage);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertEquals(id, response.getBody().get("stageId"));
        } finally {
            registry.removeStage(id);
        }
    }

    @Test
    void createStage_whenAlreadyExists_returnsConflict() {
        String id = "test-stage-conflict-" + System.nanoTime();
        registry.registerStage(buildStage(id));
        try {
            ResponseEntity<Map<String, Object>> response = controller.createStage(buildStage(id));
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        } finally {
            registry.removeStage(id);
        }
    }

    // ─── stage delete ─────────────────────────────────────────────────────────

    @Test
    void deleteStage_whenExists_returnsOk() {
        String id = "test-stage-del-" + System.nanoTime();
        registry.registerStage(buildStage(id));
        ResponseEntity<Map<String, Object>> response = controller.deleteStage(id);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void deleteStage_whenNotExists_returnsNotFound() {
        ResponseEntity<Map<String, Object>> response = controller.deleteStage("nonexistent-stage-xyz");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── model set list ───────────────────────────────────────────────────────

    @Test
    void listModelSets_returnsAllModelSets() {
        ResponseEntity<Map<String, Object>> response = controller.listModelSets();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("modelSets"));
    }

    // ─── custom model set create ──────────────────────────────────────────────

    @Test
    void createCustomModelSet_successfullyCreates() {
        String id = "test-ms-" + System.nanoTime();
        LlmCustomModelSet ms = buildCustomModelSet(id);
        try {
            ResponseEntity<Map<String, Object>> response = controller.createCustomModelSet(ms);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertEquals(id, response.getBody().get("setId"));
        } finally {
            registry.removeCustomModelSet(id);
        }
    }

    @Test
    void deleteCustomModelSet_whenExists_returnsOk() {
        String id = "test-ms-del-" + System.nanoTime();
        registry.registerCustomModelSet(buildCustomModelSet(id));
        ResponseEntity<Map<String, Object>> response = controller.deleteCustomModelSet(id);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void deleteCustomModelSet_whenNotExists_returnsNotFound() {
        ResponseEntity<Map<String, Object>> response =
                controller.deleteCustomModelSet("nonexistent-ms-xyz");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── stats ────────────────────────────────────────────────────────────────

    @Test
    void getStats_returnsStats() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
