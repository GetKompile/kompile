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

import ai.kompile.core.evaluation.EvaluationService;
import ai.kompile.core.evaluation.RagEvaluator;
import ai.kompile.evaluation.EvaluationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for managing RAG evaluation configuration.
 */
@RestController
@RequestMapping("/api/evaluation")
@CrossOrigin(origins = "*")
public class EvaluationConfigController {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationConfigController.class);

    private final EvaluationProperties properties;
    private final EvaluationService evaluationService;

    @Autowired
    public EvaluationConfigController(
            @Autowired(required = false) EvaluationProperties properties,
            @Autowired(required = false) EvaluationService evaluationService
    ) {
        this.properties = properties;
        this.evaluationService = evaluationService;
    }

    /**
     * Get current evaluation configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        if (properties == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Evaluation module not loaded"
            ));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("available", true);
        config.put("enabled", properties.isEnabled());
        config.put("async", properties.isAsync());
        config.put("defaultThreshold", properties.getDefaultThreshold());

        // Evaluator configurations
        Map<String, Object> evaluators = new LinkedHashMap<>();

        // Relevancy
        Map<String, Object> relevancy = new LinkedHashMap<>();
        relevancy.put("enabled", properties.getRelevancy().isEnabled());
        relevancy.put("threshold", properties.getRelevancy().getThreshold());
        evaluators.put("relevancy", relevancy);

        // Faithfulness
        Map<String, Object> faithfulness = new LinkedHashMap<>();
        faithfulness.put("enabled", properties.getFaithfulness().isEnabled());
        faithfulness.put("threshold", properties.getFaithfulness().getThreshold());
        evaluators.put("faithfulness", faithfulness);

        // Answer correctness
        Map<String, Object> answerCorrectness = new LinkedHashMap<>();
        answerCorrectness.put("enabled", properties.getAnswerCorrectness().isEnabled());
        answerCorrectness.put("threshold", properties.getAnswerCorrectness().getThreshold());
        answerCorrectness.put("semanticWeight", properties.getAnswerCorrectness().getSemanticWeight());
        answerCorrectness.put("factualWeight", properties.getAnswerCorrectness().getFactualWeight());
        evaluators.put("answerCorrectness", answerCorrectness);

        // Context relevancy
        Map<String, Object> contextRelevancy = new LinkedHashMap<>();
        contextRelevancy.put("enabled", properties.getContextRelevancy().isEnabled());
        contextRelevancy.put("threshold", properties.getContextRelevancy().getThreshold());
        evaluators.put("contextRelevancy", contextRelevancy);

        // Hallucination
        Map<String, Object> hallucination = new LinkedHashMap<>();
        hallucination.put("enabled", properties.getHallucination().isEnabled());
        hallucination.put("threshold", properties.getHallucination().getThreshold());
        evaluators.put("hallucination", hallucination);

        config.put("evaluators", evaluators);

        return ResponseEntity.ok(config);
    }

    /**
     * Update evaluation configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> request) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Evaluation module not loaded"
            ));
        }

        logger.info("Updating evaluation configuration: {}", request);

        // Update top-level settings
        if (request.containsKey("enabled")) {
            properties.setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("async")) {
            properties.setAsync((Boolean) request.get("async"));
        }
        if (request.containsKey("defaultThreshold")) {
            properties.setDefaultThreshold(((Number) request.get("defaultThreshold")).doubleValue());
        }

        // Update evaluator configurations
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluators = (Map<String, Object>) request.get("evaluators");
        if (evaluators != null) {
            updateEvaluatorConfigs(evaluators);
        }

        return getConfig();
    }

    @SuppressWarnings("unchecked")
    private void updateEvaluatorConfigs(Map<String, Object> evaluators) {
        // Relevancy
        Map<String, Object> relevancy = (Map<String, Object>) evaluators.get("relevancy");
        if (relevancy != null) {
            if (relevancy.containsKey("enabled")) {
                properties.getRelevancy().setEnabled((Boolean) relevancy.get("enabled"));
            }
            if (relevancy.containsKey("threshold")) {
                properties.getRelevancy().setThreshold(((Number) relevancy.get("threshold")).doubleValue());
            }
        }

        // Faithfulness
        Map<String, Object> faithfulness = (Map<String, Object>) evaluators.get("faithfulness");
        if (faithfulness != null) {
            if (faithfulness.containsKey("enabled")) {
                properties.getFaithfulness().setEnabled((Boolean) faithfulness.get("enabled"));
            }
            if (faithfulness.containsKey("threshold")) {
                properties.getFaithfulness().setThreshold(((Number) faithfulness.get("threshold")).doubleValue());
            }
        }

        // Answer correctness
        Map<String, Object> answerCorrectness = (Map<String, Object>) evaluators.get("answerCorrectness");
        if (answerCorrectness != null) {
            if (answerCorrectness.containsKey("enabled")) {
                properties.getAnswerCorrectness().setEnabled((Boolean) answerCorrectness.get("enabled"));
            }
            if (answerCorrectness.containsKey("threshold")) {
                properties.getAnswerCorrectness().setThreshold(((Number) answerCorrectness.get("threshold")).doubleValue());
            }
            if (answerCorrectness.containsKey("semanticWeight")) {
                properties.getAnswerCorrectness().setSemanticWeight(((Number) answerCorrectness.get("semanticWeight")).doubleValue());
            }
            if (answerCorrectness.containsKey("factualWeight")) {
                properties.getAnswerCorrectness().setFactualWeight(((Number) answerCorrectness.get("factualWeight")).doubleValue());
            }
        }

        // Context relevancy
        Map<String, Object> contextRelevancy = (Map<String, Object>) evaluators.get("contextRelevancy");
        if (contextRelevancy != null) {
            if (contextRelevancy.containsKey("enabled")) {
                properties.getContextRelevancy().setEnabled((Boolean) contextRelevancy.get("enabled"));
            }
            if (contextRelevancy.containsKey("threshold")) {
                properties.getContextRelevancy().setThreshold(((Number) contextRelevancy.get("threshold")).doubleValue());
            }
        }

        // Hallucination
        Map<String, Object> hallucination = (Map<String, Object>) evaluators.get("hallucination");
        if (hallucination != null) {
            if (hallucination.containsKey("enabled")) {
                properties.getHallucination().setEnabled((Boolean) hallucination.get("enabled"));
            }
            if (hallucination.containsKey("threshold")) {
                properties.getHallucination().setThreshold(((Number) hallucination.get("threshold")).doubleValue());
            }
        }
    }

    /**
     * Get list of available evaluators.
     */
    @GetMapping("/evaluators")
    public ResponseEntity<Map<String, Object>> getAvailableEvaluators() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (evaluationService == null) {
            result.put("available", false);
            result.put("message", "Evaluation service not loaded");
            return ResponseEntity.ok(result);
        }

        result.put("available", true);
        result.put("serviceEnabled", evaluationService.isEnabled());

        List<Map<String, Object>> evaluators = evaluationService.getEvaluators().stream()
                .map(this::evaluatorToMap)
                .collect(Collectors.toList());
        result.put("evaluators", evaluators);

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> evaluatorToMap(RagEvaluator evaluator) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", evaluator.getName());
        map.put("type", evaluator.getType().name());
        return map;
    }

    /**
     * Toggle evaluation on/off.
     */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleEvaluation(@RequestBody Map<String, Boolean> request) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Evaluation module not loaded"
            ));
        }

        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            properties.setEnabled(enabled);
            logger.info("Evaluation {}", enabled ? "enabled" : "disabled");
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", properties.isEnabled()
        ));
    }

    /**
     * Get evaluation types available.
     */
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> getEvaluationTypes() {
        return ResponseEntity.ok(List.of(
                Map.of("type", "RELEVANCY", "description", "Measures how relevant the response is to the query"),
                Map.of("type", "FAITHFULNESS", "description", "Measures how faithful the response is to the retrieved context"),
                Map.of("type", "ANSWER_CORRECTNESS", "description", "Measures overall correctness of the answer"),
                Map.of("type", "CONTEXT_RELEVANCY", "description", "Measures how relevant the retrieved context is to the query"),
                Map.of("type", "HALLUCINATION_DETECTION", "description", "Detects fabricated information not in the context")
        ));
    }
}
