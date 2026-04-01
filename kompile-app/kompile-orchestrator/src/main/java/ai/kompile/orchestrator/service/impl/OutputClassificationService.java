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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.model.output.*;
import ai.kompile.orchestrator.repository.ClassificationRuleRepository;
import ai.kompile.orchestrator.repository.OutputClassifierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Service for managing output classifiers and performing classification.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutputClassificationService {

    private final OutputClassifierRepository classifierRepository;
    private final ClassificationRuleRepository ruleRepository;

    // ==================== Classifier CRUD ====================

    /**
     * Create a new output classifier.
     */
    @Transactional
    public OutputClassifier createClassifier(OutputClassifier classifier) {
        log.info("Creating output classifier: {} for instance: {}",
                classifier.getName(), classifier.getOrchestratorInstanceId());

        if (classifierRepository.existsByOrchestratorInstanceIdAndName(
                classifier.getOrchestratorInstanceId(), classifier.getName())) {
            throw new IllegalArgumentException("Classifier with name already exists: " + classifier.getName());
        }

        // Link rules to classifier
        if (classifier.getRules() != null) {
            for (ClassificationRule rule : classifier.getRules()) {
                rule.setClassifier(classifier);
                validateRule(rule);
            }
        }

        return classifierRepository.save(classifier);
    }

    /**
     * Update an existing classifier.
     */
    @Transactional
    public OutputClassifier updateClassifier(Long classifierId, OutputClassifier updates) {
        OutputClassifier existing = classifierRepository.findById(classifierId)
                .orElseThrow(() -> new IllegalArgumentException("Classifier not found: " + classifierId));

        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setEnabled(updates.isEnabled());
        existing.setApplyAllMatches(updates.isApplyAllMatches());
        existing.setDefaultAction(updates.getDefaultAction());
        existing.setTags(updates.getTags());

        return classifierRepository.save(existing);
    }

    /**
     * Get a classifier by ID.
     */
    @Transactional(readOnly = true)
    public Optional<OutputClassifier> getClassifier(Long classifierId) {
        return classifierRepository.findById(classifierId);
    }

    /**
     * Get all classifiers for an orchestrator instance.
     */
    @Transactional(readOnly = true)
    public List<OutputClassifier> getClassifiersForInstance(String instanceId) {
        return classifierRepository.findByOrchestratorInstanceId(instanceId);
    }

    /**
     * Get enabled classifiers for an orchestrator instance.
     */
    @Transactional(readOnly = true)
    public List<OutputClassifier> getEnabledClassifiersForInstance(String instanceId) {
        return classifierRepository.findByOrchestratorInstanceIdAndEnabledTrue(instanceId);
    }

    /**
     * Delete a classifier.
     */
    @Transactional
    public void deleteClassifier(Long classifierId) {
        log.info("Deleting output classifier: {}", classifierId);
        classifierRepository.deleteById(classifierId);
    }

    /**
     * Create a default classifier for an instance.
     */
    @Transactional
    public OutputClassifier createDefaultClassifier(String instanceId) {
        OutputClassifier classifier = OutputClassifier.createDefault(instanceId);
        return createClassifier(classifier);
    }

    // ==================== Rule CRUD ====================

    /**
     * Add a rule to a classifier.
     */
    @Transactional
    public ClassificationRule addRule(Long classifierId, ClassificationRule rule) {
        OutputClassifier classifier = classifierRepository.findById(classifierId)
                .orElseThrow(() -> new IllegalArgumentException("Classifier not found: " + classifierId));

        validateRule(rule);

        // Set order if not specified
        if (rule.getRuleOrder() == 0) {
            Integer maxOrder = ruleRepository.findMaxRuleOrderByClassifierId(classifierId);
            rule.setRuleOrder(maxOrder == null ? 100 : maxOrder + 10);
        }

        rule.setClassifier(classifier);
        classifier.addRule(rule);

        classifierRepository.save(classifier);
        return rule;
    }

    /**
     * Update a rule.
     */
    @Transactional
    public ClassificationRule updateRule(Long ruleId, ClassificationRule updates) {
        ClassificationRule existing = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        validateRule(updates);

        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setPattern(updates.getPattern());
        existing.setCaseSensitive(updates.isCaseSensitive());
        existing.setMultiline(updates.isMultiline());
        existing.setClassificationType(updates.getClassificationType());
        existing.setSeverity(updates.getSeverity());
        existing.setAction(updates.getAction());
        existing.setActionConfig(updates.getActionConfig());
        existing.setTargetStateId(updates.getTargetStateId());
        existing.setHandlerTaskId(updates.getHandlerTaskId());
        existing.setLlmPromptTemplate(updates.getLlmPromptTemplate());
        existing.setMaxRetries(updates.getMaxRetries());
        existing.setRetryDelaySeconds(updates.getRetryDelaySeconds());
        existing.setRuleOrder(updates.getRuleOrder());
        existing.setEnabled(updates.isEnabled());
        existing.setStopOnMatch(updates.isStopOnMatch());
        existing.setTags(updates.getTags());

        // Clear compiled pattern cache
        existing.setCompiledPattern(null);

        return ruleRepository.save(existing);
    }

    /**
     * Get a rule by ID.
     */
    @Transactional(readOnly = true)
    public Optional<ClassificationRule> getRule(Long ruleId) {
        return ruleRepository.findById(ruleId);
    }

    /**
     * Get all rules for a classifier.
     */
    @Transactional(readOnly = true)
    public List<ClassificationRule> getRulesForClassifier(Long classifierId) {
        return ruleRepository.findByClassifierIdOrderByRuleOrderAsc(classifierId);
    }

    /**
     * Delete a rule.
     */
    @Transactional
    public void deleteRule(Long ruleId) {
        log.info("Deleting classification rule: {}", ruleId);
        ruleRepository.deleteById(ruleId);
    }

    /**
     * Reorder rules within a classifier.
     */
    @Transactional
    public void reorderRules(Long classifierId, List<Long> ruleIds) {
        for (int i = 0; i < ruleIds.size(); i++) {
            Long ruleId = ruleIds.get(i);
            final int newOrder = (i + 1) * 10;
            ruleRepository.findById(ruleId).ifPresent(rule -> {
                if (rule.getClassifier().getId().equals(classifierId)) {
                    rule.setRuleOrder(newOrder);
                    ruleRepository.save(rule);
                }
            });
        }
    }

    // ==================== Classification ====================

    /**
     * Classify output using all enabled classifiers for an instance.
     */
    @Transactional(readOnly = true)
    public ClassificationResult classifyOutput(String instanceId, String output) {
        if (output == null || output.isEmpty()) {
            return ClassificationResult.noMatch(output);
        }

        ClassificationResult result = ClassificationResult.builder()
                .output(output)
                .matches(new ArrayList<>())
                .build();

        List<OutputClassifier> classifiers = getEnabledClassifiersForInstance(instanceId);

        for (OutputClassifier classifier : classifiers) {
            classifyWithClassifier(classifier, output, result);
        }

        log.debug("Classification result for instance {}: {}", instanceId, result.getSummary());
        return result;
    }

    /**
     * Classify output using a specific classifier.
     */
    @Transactional(readOnly = true)
    public ClassificationResult classifyWithClassifier(Long classifierId, String output) {
        if (output == null || output.isEmpty()) {
            return ClassificationResult.noMatch(output);
        }

        OutputClassifier classifier = classifierRepository.findById(classifierId)
                .orElseThrow(() -> new IllegalArgumentException("Classifier not found: " + classifierId));

        ClassificationResult result = ClassificationResult.builder()
                .output(output)
                .matches(new ArrayList<>())
                .build();

        classifyWithClassifier(classifier, output, result);
        return result;
    }

    /**
     * Internal classification logic.
     */
    private void classifyWithClassifier(OutputClassifier classifier, String output, ClassificationResult result) {
        if (!classifier.isEnabled()) {
            return;
        }

        for (ClassificationRule rule : classifier.getEnabledRules()) {
            try {
                Pattern pattern = rule.getCompiledPattern();
                if (pattern == null) {
                    continue;
                }

                Matcher matcher = pattern.matcher(output);
                while (matcher.find()) {
                    result.addMatch(rule, matcher);

                    // If stop on match and not applying all matches, break
                    if (rule.isStopOnMatch() && !classifier.isApplyAllMatches()) {
                        return;
                    }

                    // Only match once per rule unless applying all matches
                    if (!classifier.isApplyAllMatches()) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Error applying rule {}: {}", rule.getName(), e.getMessage());
            }
        }
    }

    // ==================== Pattern Testing ====================

    /**
     * Test a regex pattern against sample input.
     */
    public PatternTestResult testPattern(String pattern, String input, boolean caseSensitive, boolean multiline) {
        PatternTestResult result = new PatternTestResult();
        result.setPattern(pattern);
        result.setInput(input);

        try {
            int flags = 0;
            if (!caseSensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            if (multiline) {
                flags |= Pattern.MULTILINE;
            }

            Pattern compiledPattern = Pattern.compile(pattern, flags);
            Matcher matcher = compiledPattern.matcher(input);

            List<PatternTestResult.Match> matches = new ArrayList<>();
            while (matcher.find()) {
                PatternTestResult.Match match = new PatternTestResult.Match();
                match.setText(matcher.group());
                match.setStart(matcher.start());
                match.setEnd(matcher.end());

                List<String> groups = new ArrayList<>();
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    groups.add(matcher.group(i));
                }
                match.setGroups(groups);
                matches.add(match);
            }

            result.setMatches(matches);
            result.setValid(true);
            result.setMatchCount(matches.size());

        } catch (PatternSyntaxException e) {
            result.setValid(false);
            result.setError(e.getMessage());
            result.setErrorIndex(e.getIndex());
        }

        return result;
    }

    // ==================== Validation ====================

    /**
     * Validate a classification rule.
     */
    private void validateRule(ClassificationRule rule) {
        if (rule.getName() == null || rule.getName().isEmpty()) {
            throw new IllegalArgumentException("Rule name is required");
        }

        if (rule.getPattern() == null || rule.getPattern().isEmpty()) {
            throw new IllegalArgumentException("Rule pattern is required");
        }

        if (!rule.isValidPattern()) {
            throw new IllegalArgumentException("Invalid regex pattern: " + rule.getPattern());
        }

        if (rule.getClassificationType() == null) {
            throw new IllegalArgumentException("Classification type is required");
        }

        if (rule.getSeverity() == null) {
            throw new IllegalArgumentException("Severity is required");
        }

        if (rule.getAction() == null) {
            throw new IllegalArgumentException("Action is required");
        }
    }

    // ==================== DTO for Pattern Testing ====================

    @lombok.Data
    public static class PatternTestResult {
        private String pattern;
        private String input;
        private boolean valid;
        private String error;
        private int errorIndex;
        private int matchCount;
        private List<Match> matches = new ArrayList<>();

        @lombok.Data
        public static class Match {
            private String text;
            private int start;
            private int end;
            private List<String> groups = new ArrayList<>();
        }
    }
}
