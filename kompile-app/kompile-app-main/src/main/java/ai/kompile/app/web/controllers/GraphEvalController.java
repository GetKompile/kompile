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

import ai.kompile.core.evaluation.GraphEvaluationContext;
import ai.kompile.core.evaluation.GraphEvaluationResult;
import ai.kompile.core.evaluation.GraphEvaluator;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for inline graph extraction evaluation.
 * Accepts source text and optional ground truth, extracts a graph,
 * then runs graph evaluators and returns results.
 */
@RestController
@RequestMapping("/api/graph-eval")
@CrossOrigin(origins = "*")
public class GraphEvalController {

    private static final Logger logger = LoggerFactory.getLogger(GraphEvalController.class);

    private final GraphConstructor graphConstructor;
    private final List<GraphEvaluator> graphEvaluators;

    @Autowired
    public GraphEvalController(
            @Autowired(required = false) GraphConstructor graphConstructor,
            @Autowired(required = false) List<GraphEvaluator> graphEvaluators
    ) {
        this.graphConstructor = graphConstructor;
        this.graphEvaluators = graphEvaluators != null ? graphEvaluators : List.of();
    }

    /**
     * Get status of graph evaluation capabilities.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("extractionAvailable", graphConstructor != null);
        status.put("evaluatorCount", graphEvaluators.size());
        status.put("evaluators", graphEvaluators.stream()
                .map(e -> Map.of(
                        "name", e.getName(),
                        "type", e.getType().name(),
                        "requiresLlm", e.requiresLlm(),
                        "requiresGroundTruth", e.requiresGroundTruth()))
                .collect(Collectors.toList()));
        return ResponseEntity.ok(status);
    }

    /**
     * Extract entities/relationships from source text and optionally evaluate
     * against ground truth.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runEvaluation(@RequestBody Map<String, Object> request) {
        long startTime = System.currentTimeMillis();

        String sourceText = (String) request.get("sourceText");
        if (sourceText == null || sourceText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "sourceText is required"));
        }

        if (graphConstructor == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Graph extraction is not available. Configure an LLM provider."));
        }

        try {
            // Extract graph from source text
            String docId = "eval-inline-" + UUID.randomUUID();
            RetrievedDoc doc = new RetrievedDoc(docId, sourceText, Map.of(), 1.0);

            Graph extracted = graphConstructor.constructGraphFromDocs(
                    List.of(doc), null, SchemaEnforcementMode.LENIENT);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);

            // Serialize extracted graph
            result.put("extractedGraph", graphToMap(extracted));

            // Parse ground truth if provided
            @SuppressWarnings("unchecked")
            Map<String, Object> groundTruthMap = (Map<String, Object>) request.get("groundTruth");
            Graph groundTruth = groundTruthMap != null ? mapToGraph(groundTruthMap) : null;

            // Build evaluation context
            boolean fuzzyMatch = Boolean.TRUE.equals(request.get("fuzzyMatch"));
            Number simThreshold = (Number) request.get("similarityThreshold");

            GraphEvaluationContext ctx = GraphEvaluationContext.builder()
                    .groundTruth(groundTruth)
                    .sourceText(sourceText)
                    .fuzzyMatch(fuzzyMatch)
                    .similarityThreshold(simThreshold != null ? simThreshold.doubleValue() : 0.85)
                    .build();

            // Run evaluators
            List<Map<String, Object>> evaluationResults = new ArrayList<>();
            for (GraphEvaluator evaluator : graphEvaluators) {
                if (evaluator.requiresGroundTruth() && groundTruth == null) {
                    continue;
                }
                try {
                    GraphEvaluationResult evalResult = evaluator.evaluate(extracted, ctx);
                    evaluationResults.add(evalResultToMap(evalResult));
                } catch (Exception e) {
                    logger.warn("Evaluator {} failed: {}", evaluator.getName(), e.getMessage());
                    evaluationResults.add(Map.of(
                            "evaluatorName", evaluator.getName(),
                            "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                }
            }
            result.put("evaluationResults", evaluationResults);
            result.put("evaluationTimeMs", System.currentTimeMillis() - startTime);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Graph evaluation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Extraction failed: " + e.getMessage()));
        }
    }

    /**
     * Evaluate a pre-extracted graph against ground truth (no extraction step).
     */
    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateOnly(@RequestBody Map<String, Object> request) {
        long startTime = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedMap = (Map<String, Object>) request.get("extractedGraph");
        @SuppressWarnings("unchecked")
        Map<String, Object> groundTruthMap = (Map<String, Object>) request.get("groundTruth");

        if (extractedMap == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "extractedGraph is required"));
        }

        Graph extracted = mapToGraph(extractedMap);
        Graph groundTruth = groundTruthMap != null ? mapToGraph(groundTruthMap) : null;
        String sourceText = (String) request.get("sourceText");

        boolean fuzzyMatch = Boolean.TRUE.equals(request.get("fuzzyMatch"));
        Number simThreshold = (Number) request.get("similarityThreshold");

        GraphEvaluationContext ctx = GraphEvaluationContext.builder()
                .groundTruth(groundTruth)
                .sourceText(sourceText)
                .fuzzyMatch(fuzzyMatch)
                .similarityThreshold(simThreshold != null ? simThreshold.doubleValue() : 0.85)
                .build();

        List<Map<String, Object>> evaluationResults = new ArrayList<>();
        for (GraphEvaluator evaluator : graphEvaluators) {
            if (evaluator.requiresGroundTruth() && groundTruth == null) {
                continue;
            }
            if (evaluator.requiresLlm() && sourceText == null) {
                continue;
            }
            try {
                GraphEvaluationResult evalResult = evaluator.evaluate(extracted, ctx);
                evaluationResults.add(evalResultToMap(evalResult));
            } catch (Exception e) {
                logger.warn("Evaluator {} failed: {}", evaluator.getName(), e.getMessage());
                evaluationResults.add(Map.of(
                        "evaluatorName", evaluator.getName(),
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("evaluationResults", evaluationResults);
        result.put("evaluationTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(result);
    }

    // ─── Serialization helpers ──────────────────────────────────────────

    private Map<String, Object> graphToMap(Graph graph) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entities", graph.getEntities() != null
                ? graph.getEntities().stream().map(this::entityToMap).collect(Collectors.toList())
                : List.of());
        map.put("relationships", graph.getRelationships() != null
                ? graph.getRelationships().stream().map(this::relationshipToMap).collect(Collectors.toList())
                : List.of());
        return map;
    }

    private Map<String, Object> entityToMap(Entity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("type", e.getType());
        if (e.getDescription() != null) m.put("description", e.getDescription());
        if (e.getConfidence() != null) m.put("confidence", e.getConfidence());
        return m;
    }

    private Map<String, Object> relationshipToMap(Relationship r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", r.getSource());
        m.put("target", r.getTarget());
        m.put("type", r.getType());
        if (r.getDescription() != null) m.put("description", r.getDescription());
        if (r.getConfidence() != null) m.put("confidence", r.getConfidence());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Graph mapToGraph(Map<String, Object> map) {
        Graph graph = new Graph();
        List<Map<String, Object>> entitiesList = (List<Map<String, Object>>) map.get("entities");
        if (entitiesList != null) {
            graph.setEntities(entitiesList.stream().map(this::mapToEntity).collect(Collectors.toList()));
        } else {
            graph.setEntities(List.of());
        }

        List<Map<String, Object>> relsList = (List<Map<String, Object>>) map.get("relationships");
        if (relsList != null) {
            graph.setRelationships(relsList.stream().map(this::mapToRelationship).collect(Collectors.toList()));
        } else {
            graph.setRelationships(List.of());
        }
        return graph;
    }

    private Entity mapToEntity(Map<String, Object> m) {
        Entity e = new Entity();
        e.setId((String) m.get("id"));
        e.setTitle((String) m.get("title"));
        e.setType((String) m.get("type"));
        e.setDescription((String) m.get("description"));
        if (m.get("confidence") instanceof Number n) {
            e.setConfidence(n.doubleValue());
        }
        return e;
    }

    private Relationship mapToRelationship(Map<String, Object> m) {
        Relationship r = new Relationship();
        r.setSource((String) m.get("source"));
        r.setTarget((String) m.get("target"));
        r.setType((String) m.get("type"));
        r.setDescription((String) m.get("description"));
        if (m.get("confidence") instanceof Number n) {
            r.setConfidence(n.doubleValue());
        }
        return r;
    }

    private Map<String, Object> evalResultToMap(GraphEvaluationResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evaluatorName", r.getEvaluatorName());
        m.put("evaluationType", r.getEvaluationType().name());
        m.put("passed", r.isPassed());
        m.put("score", r.getScore());
        m.put("precision", r.getPrecision());
        m.put("recall", r.getRecall());
        m.put("f1", r.getF1());
        m.put("truePositives", r.getTruePositives());
        m.put("falsePositives", r.getFalsePositives());
        m.put("falseNegatives", r.getFalseNegatives());
        m.put("threshold", r.getThreshold());
        m.put("explanation", r.getExplanation());
        m.put("evaluationTimeMs", r.getEvaluationTimeMs());
        m.put("metrics", r.getMetrics());

        if (r.getEntityMatches() != null && !r.getEntityMatches().isEmpty()) {
            m.put("entityMatches", r.getEntityMatches().stream()
                    .map(em -> {
                        Map<String, Object> emm = new LinkedHashMap<>();
                        if (em.getExtractedTitle() != null) emm.put("extractedTitle", em.getExtractedTitle());
                        if (em.getExpectedTitle() != null) emm.put("expectedTitle", em.getExpectedTitle());
                        if (em.getExtractedType() != null) emm.put("extractedType", em.getExtractedType());
                        if (em.getExpectedType() != null) emm.put("expectedType", em.getExpectedType());
                        emm.put("matchType", em.getMatchType().name());
                        emm.put("similarity", em.getSimilarity());
                        return emm;
                    })
                    .collect(Collectors.toList()));
        }

        if (r.getRelationshipMatches() != null && !r.getRelationshipMatches().isEmpty()) {
            m.put("relationshipMatches", r.getRelationshipMatches().stream()
                    .map(rm -> {
                        Map<String, Object> rmm = new LinkedHashMap<>();
                        if (rm.getExtractedSource() != null) rmm.put("extractedSource", rm.getExtractedSource());
                        if (rm.getExtractedTarget() != null) rmm.put("extractedTarget", rm.getExtractedTarget());
                        if (rm.getExtractedType() != null) rmm.put("extractedType", rm.getExtractedType());
                        if (rm.getExpectedSource() != null) rmm.put("expectedSource", rm.getExpectedSource());
                        if (rm.getExpectedTarget() != null) rmm.put("expectedTarget", rm.getExpectedTarget());
                        if (rm.getExpectedType() != null) rmm.put("expectedType", rm.getExpectedType());
                        rmm.put("matchType", rm.getMatchType().name());
                        return rmm;
                    })
                    .collect(Collectors.toList()));
        }

        return m;
    }
}
