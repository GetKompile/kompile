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

package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-project policy for validating LLM graph extraction output.
 *
 * <p>The same policy is used to construct extraction prompts and to validate the
 * returned graph. This keeps small-model instructions aligned with the checks
 * that decide whether an extraction is accepted, retried, or accepted with
 * warnings.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphExtractionValidationPolicy {

    public static final String TYPE_NAME_FORMAT = "type-name-format";
    public static final String REQUIRED_DESCRIPTIONS = "required-descriptions";
    public static final String ENTITY_NAME_TYPE_CONSISTENCY = "entity-name-type-consistency";
    public static final String RELATION_SELF_LOOP = "relation-self-loop";
    public static final String RELATION_SCHEMA_PATTERN = "relation-schema-pattern";
    public static final String OCCURRED_AT_FORMAT = "occurred-at-format";
    public static final String REQUIRED_RELATION_OCCURRED_AT = "required-relation-occurred-at";

    public static final int DEFAULT_MAX_ERRORS_IN_RETRY_PROMPT = 8;
    public static final int MAX_ERRORS_IN_RETRY_PROMPT_LIMIT = 50;

    private static final List<String> DEFAULT_VALIDATORS = List.of(
            TYPE_NAME_FORMAT,
            REQUIRED_DESCRIPTIONS,
            ENTITY_NAME_TYPE_CONSISTENCY,
            RELATION_SELF_LOOP,
            RELATION_SCHEMA_PATTERN,
            OCCURRED_AT_FORMAT,
            REQUIRED_RELATION_OCCURRED_AT
    );

    public enum FailureMode {
        /** Reject invalid output so the extraction caller can retry with feedback. */
        RETRY,
        /** Accept semantic violations but surface them to logs and metrics. */
        WARN,
        /** Run only structural JSON/graph validation. */
        DISABLED
    }

    /** What to do when a configurable semantic validator reports a violation. */
    @Builder.Default
    private FailureMode failureMode = FailureMode.RETRY;

    /** Stable validator IDs to enable for this project. */
    @Builder.Default
    private List<String> enabledValidators = new ArrayList<>(DEFAULT_VALIDATORS);

    /**
     * Allowed relationship signatures, for example
     * {@code (PERSON)-[:APPROVED_BY]->(CLOSE_STEP)}.
     */
    @Builder.Default
    private List<String> relationPatterns = new ArrayList<>();

    /** Relationship types that must include an ISO-8601 {@code occurredAt}. */
    @Builder.Default
    private List<String> requiredOccurredAtRelationTypes = new ArrayList<>();

    /**
     * If true, every emitted relationship type must have at least one configured
     * relationship signature. If false, only relationship types that have
     * signatures are endpoint-validated.
     */
    @Builder.Default
    private boolean requirePatternForEveryRelationType = false;

    /** Maximum validation diagnostics copied into a retry prompt. */
    @Builder.Default
    private int maxErrorsInRetryPrompt = DEFAULT_MAX_ERRORS_IN_RETRY_PROMPT;

    public static GraphExtractionValidationPolicy defaults() {
        return GraphExtractionValidationPolicy.builder().build();
    }

    public static GraphExtractionValidationPolicy disabled() {
        return GraphExtractionValidationPolicy.builder()
                .failureMode(FailureMode.DISABLED)
                .build();
    }

    public GraphExtractionValidationPolicy copy() {
        return GraphExtractionValidationPolicy.builder()
                .failureMode(effectiveFailureMode())
                .enabledValidators(new ArrayList<>(effectiveEnabledValidators()))
                .relationPatterns(new ArrayList<>(effectiveRelationPatterns()))
                .requiredOccurredAtRelationTypes(new ArrayList<>(effectiveRequiredOccurredAtRelationTypes()))
                .requirePatternForEveryRelationType(requirePatternForEveryRelationType)
                .maxErrorsInRetryPrompt(effectiveMaxErrorsInRetryPrompt())
                .build();
    }

    public FailureMode effectiveFailureMode() {
        return failureMode == null ? FailureMode.RETRY : failureMode;
    }

    public List<String> effectiveEnabledValidators() {
        List<String> configured = enabledValidators == null ? DEFAULT_VALIDATORS : enabledValidators;
        return configured.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public List<String> effectiveRelationPatterns() {
        return normalizedDistinct(relationPatterns);
    }

    public List<String> effectiveRequiredOccurredAtRelationTypes() {
        return normalizedDistinct(requiredOccurredAtRelationTypes).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
    }

    public int effectiveMaxErrorsInRetryPrompt() {
        if (maxErrorsInRetryPrompt <= 0) {
            return DEFAULT_MAX_ERRORS_IN_RETRY_PROMPT;
        }
        return Math.min(maxErrorsInRetryPrompt, MAX_ERRORS_IN_RETRY_PROMPT_LIMIT);
    }

    public boolean isValidatorEnabled(String validatorId) {
        return effectiveFailureMode() != FailureMode.DISABLED
                && effectiveEnabledValidators().contains(validatorId);
    }

    public List<String> unknownValidatorIds() {
        Set<String> known = Set.copyOf(DEFAULT_VALIDATORS);
        return effectiveEnabledValidators().stream()
                .filter(id -> !known.contains(id))
                .toList();
    }

    public static List<String> defaultValidatorIds() {
        return DEFAULT_VALIDATORS;
    }

    private static List<String> normalizedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }
}
