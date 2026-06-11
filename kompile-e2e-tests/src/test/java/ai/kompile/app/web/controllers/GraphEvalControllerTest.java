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

import ai.kompile.core.evaluation.GraphEvaluator;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphEvalControllerTest {

    @Mock
    private GraphConstructor graphConstructor;

    @Mock
    private GraphEvaluator graphEvaluator;

    // ─── getStatus ────────────────────────────────────────────────────────────

    @Test
    void getStatus_noConstructorNoEvaluators_returnsUnavailable() {
        GraphEvalController ctrl = new GraphEvalController(null, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.getStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("extractionAvailable")).isEqualTo(false);
        assertThat(resp.getBody().get("evaluatorCount")).isEqualTo(0);
    }

    @Test
    void getStatus_withConstructorAndEvaluators_returnsAvailable() {
        when(graphEvaluator.getName()).thenReturn("entity-f1");
        when(graphEvaluator.getType()).thenReturn(ai.kompile.core.evaluation.EvaluationType.RELEVANCY);
        when(graphEvaluator.requiresLlm()).thenReturn(false);
        when(graphEvaluator.requiresGroundTruth()).thenReturn(true);

        GraphEvalController ctrl = new GraphEvalController(graphConstructor, List.of(graphEvaluator));

        ResponseEntity<Map<String, Object>> resp = ctrl.getStatus();

        assertThat(resp.getBody().get("extractionAvailable")).isEqualTo(true);
        assertThat(resp.getBody().get("evaluatorCount")).isEqualTo(1);
        List<?> evaluators = (List<?>) resp.getBody().get("evaluators");
        assertThat(evaluators).hasSize(1);
    }

    // ─── runEvaluation ────────────────────────────────────────────────────────

    @Test
    void runEvaluation_missingSourceText_returnsBadRequest() {
        GraphEvalController ctrl = new GraphEvalController(graphConstructor, List.of());

        ResponseEntity<Map<String, Object>> resp = ctrl.runEvaluation(Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    void runEvaluation_blankSourceText_returnsBadRequest() {
        GraphEvalController ctrl = new GraphEvalController(graphConstructor, List.of());

        ResponseEntity<Map<String, Object>> resp = ctrl.runEvaluation(Map.of("sourceText", "  "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void runEvaluation_noConstructor_returns503() {
        GraphEvalController ctrl = new GraphEvalController(null, List.of());

        Map<String, Object> request = new HashMap<>();
        request.put("sourceText", "Alice works at Acme Corp.");

        ResponseEntity<Map<String, Object>> resp = ctrl.runEvaluation(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    void runEvaluation_withConstructor_returnsExtractedGraph() throws Exception {
        Graph graph = new Graph();
        graph.setEntities(List.of());
        graph.setRelationships(List.of());
        when(graphConstructor.constructGraphFromDocs(anyList(), isNull(), eq(SchemaEnforcementMode.LENIENT)))
                .thenReturn(graph);

        GraphEvalController ctrl = new GraphEvalController(graphConstructor, List.of());
        Map<String, Object> request = new HashMap<>();
        request.put("sourceText", "Alice works at Acme Corp.");

        ResponseEntity<Map<String, Object>> resp = ctrl.runEvaluation(request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        assertThat(resp.getBody()).containsKey("extractedGraph");
        assertThat(resp.getBody()).containsKey("evaluationResults");
    }

    @Test
    void runEvaluation_evaluatorRequiresGroundTruthButNoneProvided_skipsEvaluator() throws Exception {
        Graph graph = new Graph();
        graph.setEntities(List.of());
        graph.setRelationships(List.of());
        when(graphConstructor.constructGraphFromDocs(anyList(), isNull(), any()))
                .thenReturn(graph);
        when(graphEvaluator.requiresGroundTruth()).thenReturn(true);

        GraphEvalController ctrl = new GraphEvalController(graphConstructor, List.of(graphEvaluator));
        Map<String, Object> request = new HashMap<>();
        request.put("sourceText", "some text");

        ctrl.runEvaluation(request);

        verify(graphEvaluator, never()).evaluate(any(), any());
    }

    // ─── evaluateOnly ─────────────────────────────────────────────────────────

    @Test
    void evaluateOnly_missingExtractedGraph_returnsBadRequest() {
        GraphEvalController ctrl = new GraphEvalController(null, List.of());

        ResponseEntity<Map<String, Object>> resp = ctrl.evaluateOnly(Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    void evaluateOnly_withGraph_returnsSuccess() {
        GraphEvalController ctrl = new GraphEvalController(null, List.of());
        Map<String, Object> request = new HashMap<>();
        request.put("extractedGraph", Map.of("entities", List.of(), "relationships", List.of()));

        ResponseEntity<Map<String, Object>> resp = ctrl.evaluateOnly(request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        assertThat(resp.getBody()).containsKey("evaluationResults");
        assertThat(resp.getBody()).containsKey("evaluationTimeMs");
    }

    @Test
    void evaluateOnly_evaluatorRequiresLlmButNoSourceText_skips() throws Exception {
        when(graphEvaluator.requiresGroundTruth()).thenReturn(false);
        when(graphEvaluator.requiresLlm()).thenReturn(true);

        GraphEvalController ctrl = new GraphEvalController(null, List.of(graphEvaluator));
        Map<String, Object> request = new HashMap<>();
        request.put("extractedGraph", Map.of("entities", List.of(), "relationships", List.of()));
        // no sourceText → evaluator should be skipped

        ctrl.evaluateOnly(request);

        verify(graphEvaluator, never()).evaluate(any(), any());
    }
}
