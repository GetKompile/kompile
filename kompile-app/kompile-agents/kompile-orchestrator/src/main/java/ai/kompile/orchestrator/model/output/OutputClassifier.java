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
package ai.kompile.orchestrator.model.output;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Collection of classification rules for output pattern matching.
 */
@Entity
@Table(name = "output_classifiers", indexes = {
        @Index(name = "idx_classifier_instance", columnList = "orchestrator_instance_id"),
        @Index(name = "idx_classifier_name", columnList = "name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputClassifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Whether this classifier is enabled.
     */
    @Column(name = "enabled")
    @Builder.Default
    private boolean enabled = true;

    /**
     * Whether to apply all matching rules or just the first.
     */
    @Column(name = "apply_all_matches")
    @Builder.Default
    private boolean applyAllMatches = false;

    /**
     * Default action when no rules match.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_action")
    @Builder.Default
    private ClassificationAction defaultAction = ClassificationAction.NONE;

    /**
     * Tags for categorization.
     */
    @Column(name = "tags")
    private String tags;

    /**
     * Classification rules in this classifier.
     */
    @OneToMany(mappedBy = "classifier", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ruleOrder ASC")
    @Builder.Default
    private List<ClassificationRule> rules = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Add a rule to this classifier.
     */
    public void addRule(ClassificationRule rule) {
        rules.add(rule);
        rule.setClassifier(this);
    }

    /**
     * Remove a rule from this classifier.
     */
    public void removeRule(ClassificationRule rule) {
        rules.remove(rule);
        rule.setClassifier(null);
    }

    /**
     * Get enabled rules sorted by order.
     */
    public List<ClassificationRule> getEnabledRules() {
        return rules.stream()
                .filter(ClassificationRule::isEnabled)
                .sorted(Comparator.comparingInt(ClassificationRule::getRuleOrder))
                .collect(Collectors.toList());
    }

    /**
     * Classify output and return all matching rules.
     */
    public List<ClassificationRule> classify(String output) {
        if (!enabled || output == null) {
            return List.of();
        }

        List<ClassificationRule> matches = new ArrayList<>();
        for (ClassificationRule rule : getEnabledRules()) {
            if (rule.matches(output)) {
                matches.add(rule);
                if (rule.isStopOnMatch() && !applyAllMatches) {
                    break;
                }
            }
        }
        return matches;
    }

    /**
     * Get the first matching rule for the output.
     */
    public Optional<ClassificationRule> getFirstMatch(String output) {
        if (!enabled || output == null) {
            return Optional.empty();
        }

        return getEnabledRules().stream()
                .filter(rule -> rule.matches(output))
                .findFirst();
    }

    /**
     * Get the most severe matching rule.
     */
    public Optional<ClassificationRule> getMostSevereMatch(String output) {
        return classify(output).stream()
                .min(Comparator.comparingInt(r -> r.getSeverity().getPriority()));
    }

    /**
     * Check if output matches any success pattern.
     */
    public boolean matchesSuccess(String output) {
        return classify(output).stream()
                .anyMatch(r -> r.getClassificationType() == ClassificationType.SUCCESS);
    }

    /**
     * Check if output matches any error pattern.
     */
    public boolean matchesError(String output) {
        return classify(output).stream()
                .anyMatch(r -> r.getSeverity().isMoreSevereThan(ClassificationSeverity.WARNING) ||
                               r.getSeverity() == ClassificationSeverity.ERROR);
    }

    /**
     * Create a default classifier with common patterns.
     */
    public static OutputClassifier createDefault(String instanceId) {
        OutputClassifier classifier = OutputClassifier.builder()
                .orchestratorInstanceId(instanceId)
                .name("Default Classifier")
                .description("Default output classification rules")
                .build();

        // Compilation errors
        classifier.addRule(ClassificationRule.builder()
                .name("Java Compilation Error")
                .pattern("\\[ERROR\\].*\\.java:\\[\\d+")
                .classificationType(ClassificationType.COMPILATION_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.INVOKE_LLM)
                .ruleOrder(10)
                .build());

        classifier.addRule(ClassificationRule.builder()
                .name("C++ Compilation Error")
                .pattern("error:\\s+")
                .classificationType(ClassificationType.COMPILATION_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.INVOKE_LLM)
                .ruleOrder(11)
                .build());

        // Linker errors
        classifier.addRule(ClassificationRule.builder()
                .name("Undefined Reference")
                .pattern("undefined reference to")
                .classificationType(ClassificationType.LINKER_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.INVOKE_LLM)
                .ruleOrder(20)
                .build());

        // Runtime errors
        classifier.addRule(ClassificationRule.builder()
                .name("Java Exception")
                .pattern("Exception in thread|at\\s+[a-zA-Z0-9.]+\\([^)]+\\.java:\\d+\\)")
                .classificationType(ClassificationType.RUNTIME_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.INVOKE_LLM)
                .ruleOrder(30)
                .build());

        classifier.addRule(ClassificationRule.builder()
                .name("Segmentation Fault")
                .pattern("SIGSEGV|Segmentation fault")
                .classificationType(ClassificationType.RUNTIME_ERROR)
                .severity(ClassificationSeverity.CRITICAL)
                .action(ClassificationAction.INVOKE_LLM)
                .ruleOrder(31)
                .build());

        // Memory errors
        classifier.addRule(ClassificationRule.builder()
                .name("Out of Memory")
                .pattern("OutOfMemoryError|out of memory|OOM")
                .classificationType(ClassificationType.MEMORY_ERROR)
                .severity(ClassificationSeverity.CRITICAL)
                .action(ClassificationAction.ABORT)
                .ruleOrder(40)
                .build());

        // Timeout
        classifier.addRule(ClassificationRule.builder()
                .name("Timeout")
                .pattern("timeout|timed out|TIMEOUT")
                .classificationType(ClassificationType.TIMEOUT)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.RETRY)
                .maxRetries(2)
                .ruleOrder(50)
                .build());

        // Permission errors
        classifier.addRule(ClassificationRule.builder()
                .name("Permission Denied")
                .pattern("Permission denied|Access denied|EACCES")
                .classificationType(ClassificationType.PERMISSION_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.ABORT)
                .ruleOrder(60)
                .build());

        // Success patterns
        classifier.addRule(ClassificationRule.builder()
                .name("Build Success")
                .pattern("BUILD SUCCESS|Build successful")
                .classificationType(ClassificationType.SUCCESS)
                .severity(ClassificationSeverity.INFO)
                .action(ClassificationAction.NONE)
                .ruleOrder(100)
                .build());

        // Warnings
        classifier.addRule(ClassificationRule.builder()
                .name("Warning")
                .pattern("\\[WARNING\\]|warning:")
                .classificationType(ClassificationType.WARNING)
                .severity(ClassificationSeverity.WARNING)
                .action(ClassificationAction.LOG_CONTINUE)
                .ruleOrder(200)
                .build());

        return classifier;
    }
}
