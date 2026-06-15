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

import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Result of classifying output against rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationResult {

    /**
     * The output that was classified.
     */
    private String output;

    /**
     * All matched rules.
     */
    @Builder.Default
    private List<RuleMatch> matches = new ArrayList<>();

    /**
     * The most severe classification found.
     */
    private ClassificationType primaryType;

    /**
     * The most severe severity found.
     */
    private ClassificationSeverity primarySeverity;

    /**
     * The primary action to take.
     */
    private ClassificationAction primaryAction;

    /**
     * Whether any error pattern was matched.
     */
    @Builder.Default
    private boolean hasErrors = false;

    /**
     * Whether any success pattern was matched.
     */
    @Builder.Default
    private boolean hasSuccess = false;

    /**
     * Timestamp of classification.
     */
    @Builder.Default
    private LocalDateTime classifiedAt = LocalDateTime.now();

    /**
     * A single rule match with captured groups.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleMatch {
        private Long ruleId;
        private String ruleName;
        private ClassificationType type;
        private ClassificationSeverity severity;
        private ClassificationAction action;
        private String matchedText;
        private int matchStart;
        private int matchEnd;
        private List<String> capturedGroups;
    }

    /**
     * Add a match from a rule.
     */
    public void addMatch(ClassificationRule rule, Matcher matcher) {
        List<String> groups = new ArrayList<>();
        for (int i = 0; i <= matcher.groupCount(); i++) {
            groups.add(matcher.group(i));
        }

        RuleMatch match = RuleMatch.builder()
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                .type(rule.getClassificationType())
                .severity(rule.getSeverity())
                .action(rule.getAction())
                .matchedText(matcher.group())
                .matchStart(matcher.start())
                .matchEnd(matcher.end())
                .capturedGroups(groups)
                .build();

        matches.add(match);

        // Update primary values based on severity
        if (primarySeverity == null || rule.getSeverity().isMoreSevereThan(primarySeverity)) {
            primarySeverity = rule.getSeverity();
            primaryType = rule.getClassificationType();
            primaryAction = rule.getAction();
        }

        // Track error/success
        if (rule.getClassificationType() == ClassificationType.SUCCESS) {
            hasSuccess = true;
        }
        if (rule.getSeverity().isMoreSevereThan(ClassificationSeverity.WARNING) ||
            rule.getSeverity() == ClassificationSeverity.ERROR) {
            hasErrors = true;
        }
    }

    /**
     * Check if any rules matched.
     */
    public boolean hasMatches() {
        return !matches.isEmpty();
    }

    /**
     * Get the first match.
     */
    public Optional<RuleMatch> getFirstMatch() {
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /**
     * Get all matches of a specific type.
     */
    public List<RuleMatch> getMatchesByType(ClassificationType type) {
        return matches.stream()
                .filter(m -> m.getType() == type)
                .toList();
    }

    /**
     * Get all matches at or above a severity level.
     */
    public List<RuleMatch> getMatchesAboveSeverity(ClassificationSeverity minSeverity) {
        return matches.stream()
                .filter(m -> m.getSeverity().isMoreSevereThan(minSeverity) ||
                             m.getSeverity() == minSeverity)
                .toList();
    }

    /**
     * Get a summary of the classification.
     */
    public String getSummary() {
        if (!hasMatches()) {
            return "No patterns matched";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Matched %d rule(s). ", matches.size()));
        sb.append(String.format("Primary: %s (%s) - %s",
                primaryType, primarySeverity, primaryAction));

        if (hasErrors) {
            sb.append(" [HAS ERRORS]");
        }
        if (hasSuccess) {
            sb.append(" [HAS SUCCESS]");
        }

        return sb.toString();
    }

    /**
     * Create an empty result for output with no matches.
     */
    public static ClassificationResult noMatch(String output) {
        return ClassificationResult.builder()
                .output(output)
                .matches(new ArrayList<>())
                .build();
    }
}
