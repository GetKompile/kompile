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
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.orchestrator.model.output.ClassificationRule;
import ai.kompile.orchestrator.model.output.ClassificationResult;
import ai.kompile.orchestrator.model.output.OutputClassifier;
import ai.kompile.orchestrator.service.impl.OutputClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for output classification management.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/{instanceId}/classifiers")
@RequiredArgsConstructor
public class OutputClassifierController {

    private final OutputClassificationService classificationService;

    // ==================== Classifier Endpoints ====================

    /**
     * Get all classifiers for an orchestrator instance.
     */
    @GetMapping
    public ResponseEntity<List<OutputClassifier>> getClassifiers(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(classificationService.getClassifiersForInstance(instanceId));
    }

    /**
     * Get a classifier by ID.
     */
    @GetMapping("/{classifierId}")
    public ResponseEntity<OutputClassifier> getClassifier(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId) {
        return classificationService.getClassifier(classifierId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new classifier.
     */
    @PostMapping
    public ResponseEntity<OutputClassifier> createClassifier(
            @PathVariable("instanceId") String instanceId,
            @RequestBody OutputClassifier classifier) {
        log.info("Creating classifier {} for orchestrator: {}", classifier.getName(), instanceId);

        classifier.setOrchestratorInstanceId(instanceId);
        OutputClassifier created = classificationService.createClassifier(classifier);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Create a default classifier with common patterns.
     */
    @PostMapping("/default")
    public ResponseEntity<OutputClassifier> createDefaultClassifier(
            @PathVariable("instanceId") String instanceId) {
        log.info("Creating default classifier for orchestrator: {}", instanceId);

        OutputClassifier classifier = classificationService.createDefaultClassifier(instanceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(classifier);
    }

    /**
     * Update a classifier.
     */
    @PutMapping("/{classifierId}")
    public ResponseEntity<OutputClassifier> updateClassifier(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @RequestBody OutputClassifier classifier) {
        log.info("Updating classifier {} for orchestrator: {}", classifierId, instanceId);

        OutputClassifier updated = classificationService.updateClassifier(classifierId, classifier);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a classifier.
     */
    @DeleteMapping("/{classifierId}")
    public ResponseEntity<Map<String, Object>> deleteClassifier(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId) {
        log.info("Deleting classifier {} for orchestrator: {}", classifierId, instanceId);

        classificationService.deleteClassifier(classifierId);
        return ResponseEntity.ok(Map.of(
                "classifierId", classifierId,
                "message", "Classifier deleted successfully"));
    }

    // ==================== Rule Endpoints ====================

    /**
     * Get all rules for a classifier.
     */
    @GetMapping("/{classifierId}/rules")
    public ResponseEntity<List<ClassificationRule>> getRules(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId) {
        return ResponseEntity.ok(classificationService.getRulesForClassifier(classifierId));
    }

    /**
     * Get a rule by ID.
     */
    @GetMapping("/{classifierId}/rules/{ruleId}")
    public ResponseEntity<ClassificationRule> getRule(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @PathVariable("ruleId") Long ruleId) {
        return classificationService.getRule(ruleId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add a rule to a classifier.
     */
    @PostMapping("/{classifierId}/rules")
    public ResponseEntity<ClassificationRule> addRule(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @RequestBody ClassificationRule rule) {
        log.info("Adding rule {} to classifier {} for orchestrator: {}",
                rule.getName(), classifierId, instanceId);

        ClassificationRule created = classificationService.addRule(classifierId, rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update a rule.
     */
    @PutMapping("/{classifierId}/rules/{ruleId}")
    public ResponseEntity<ClassificationRule> updateRule(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @PathVariable("ruleId") Long ruleId,
            @RequestBody ClassificationRule rule) {
        log.info("Updating rule {} in classifier {} for orchestrator: {}",
                ruleId, classifierId, instanceId);

        ClassificationRule updated = classificationService.updateRule(ruleId, rule);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a rule.
     */
    @DeleteMapping("/{classifierId}/rules/{ruleId}")
    public ResponseEntity<Map<String, Object>> deleteRule(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @PathVariable("ruleId") Long ruleId) {
        log.info("Deleting rule {} from classifier {} for orchestrator: {}",
                ruleId, classifierId, instanceId);

        classificationService.deleteRule(ruleId);
        return ResponseEntity.ok(Map.of(
                "ruleId", ruleId,
                "message", "Rule deleted successfully"));
    }

    /**
     * Reorder rules within a classifier.
     */
    @PostMapping("/{classifierId}/rules/reorder")
    public ResponseEntity<Map<String, Object>> reorderRules(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @RequestBody List<Long> ruleIds) {
        log.info("Reordering {} rules in classifier {} for orchestrator: {}",
                ruleIds.size(), classifierId, instanceId);

        classificationService.reorderRules(classifierId, ruleIds);
        return ResponseEntity.ok(Map.of(
                "message", "Rules reordered successfully",
                "ruleCount", ruleIds.size()));
    }

    // ==================== Classification Endpoints ====================

    /**
     * Classify output using all enabled classifiers.
     */
    @PostMapping("/classify")
    public ResponseEntity<ClassificationResult> classifyOutput(
            @PathVariable("instanceId") String instanceId,
            @RequestBody ClassifyRequest request) {
        log.debug("Classifying output for orchestrator: {}", instanceId);

        ClassificationResult result = classificationService.classifyOutput(instanceId, request.getOutput());
        return ResponseEntity.ok(result);
    }

    /**
     * Classify output using a specific classifier.
     */
    @PostMapping("/{classifierId}/classify")
    public ResponseEntity<ClassificationResult> classifyWithClassifier(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("classifierId") Long classifierId,
            @RequestBody ClassifyRequest request) {
        log.debug("Classifying output with classifier {} for orchestrator: {}", classifierId, instanceId);

        ClassificationResult result = classificationService.classifyWithClassifier(classifierId, request.getOutput());
        return ResponseEntity.ok(result);
    }

    /**
     * Test a regex pattern.
     */
    @PostMapping("/test-pattern")
    public ResponseEntity<OutputClassificationService.PatternTestResult> testPattern(
            @PathVariable("instanceId") String instanceId,
            @RequestBody PatternTestRequest request) {
        OutputClassificationService.PatternTestResult result = classificationService.testPattern(
                request.getPattern(),
                request.getInput(),
                request.isCaseSensitive(),
                request.isMultiline());
        return ResponseEntity.ok(result);
    }

    // ==================== Request DTOs ====================

    @lombok.Data
    public static class ClassifyRequest {
        private String output;
    }

    @lombok.Data
    public static class PatternTestRequest {
        private String pattern;
        private String input;
        private boolean caseSensitive = false;
        private boolean multiline = true;
    }
}
