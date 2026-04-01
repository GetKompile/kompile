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
package ai.kompile.react.eval.model;

import ai.kompile.core.evaluation.EvaluationType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents an individual evaluation test case tied to a fact sheet.
 * Each test case defines an input query, expected output, and evaluation criteria.
 *
 * <p>Test cases can be used to:
 * <ul>
 *   <li>Validate agent reasoning quality</li>
 *   <li>Track performance over time</li>
 *   <li>Guide reasoning through expected patterns</li>
 *   <li>Benchmark against ground truth</li>
 * </ul>
 */
@Data
@Builder
public class EvalCase {

    /**
     * Unique identifier for this test case.
     */
    private String id;

    /**
     * Human-readable name for the test case.
     */
    private String name;

    /**
     * Description of what this test case validates.
     */
    private String description;

    /**
     * The fact sheet ID this test case is associated with.
     */
    private Long factSheetId;

    /**
     * The fact sheet name (for display purposes).
     */
    private String factSheetName;

    /**
     * The input query to test.
     */
    private String query;

    /**
     * Expected answer or ground truth (optional).
     */
    private String expectedAnswer;

    /**
     * Expected key facts that should be present in the response.
     */
    @Builder.Default
    private List<String> expectedFacts = List.of();

    /**
     * Facts that should NOT be in the response.
     */
    @Builder.Default
    private List<String> forbiddenFacts = List.of();

    /**
     * Expected entities that should be mentioned.
     */
    @Builder.Default
    private List<String> expectedEntities = List.of();

    /**
     * Expected tool calls the agent should make.
     */
    @Builder.Default
    private List<String> expectedToolCalls = List.of();

    /**
     * Evaluation types to run for this test case.
     */
    @Builder.Default
    private List<EvaluationType> evaluationTypes = List.of(
            EvaluationType.RELEVANCY,
            EvaluationType.FAITHFULNESS,
            EvaluationType.ANSWER_CORRECTNESS
    );

    /**
     * Minimum score thresholds per evaluation type.
     */
    @Builder.Default
    private Map<EvaluationType, Double> thresholds = Map.of();

    /**
     * Tags for categorizing test cases.
     */
    @Builder.Default
    private List<String> tags = List.of();

    /**
     * Priority level (1-5, higher is more important).
     */
    @Builder.Default
    private int priority = 3;

    /**
     * Whether this test case is active.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * When this test case was created.
     */
    private Instant createdAt;

    /**
     * When this test case was last modified.
     */
    private Instant updatedAt;

    /**
     * Maximum time allowed for this test case in milliseconds.
     */
    @Builder.Default
    private long timeoutMs = 30000;

    /**
     * Additional metadata for the test case.
     */
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    /**
     * Create a simple test case with query and expected answer.
     */
    public static EvalCase of(String id, String query, String expectedAnswer) {
        return EvalCase.builder()
                .id(id)
                .name(id)
                .query(query)
                .expectedAnswer(expectedAnswer)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * Create a test case linked to a fact sheet.
     */
    public static EvalCase forFactSheet(String id, Long factSheetId, String query, String expectedAnswer) {
        return EvalCase.builder()
                .id(id)
                .name(id)
                .factSheetId(factSheetId)
                .query(query)
                .expectedAnswer(expectedAnswer)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
