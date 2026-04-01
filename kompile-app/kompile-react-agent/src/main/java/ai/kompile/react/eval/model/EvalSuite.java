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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a collection of evaluation test cases.
 * Suites group related tests and track overall performance metrics.
 *
 * <p>Suites can be organized by:
 * <ul>
 *   <li>Fact sheet (all tests for a specific knowledge base)</li>
 *   <li>Feature (all tests for a specific capability)</li>
 *   <li>Priority (smoke tests, regression tests, etc.)</li>
 *   <li>Category (RAG, reasoning, tool use, etc.)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSuite {

    /**
     * Unique identifier for this suite.
     */
    private String id;

    /**
     * Human-readable name for the suite.
     */
    private String name;

    /**
     * Description of what this suite validates.
     */
    private String description;

    /**
     * Optional fact sheet ID to scope all tests to.
     */
    private Long factSheetId;

    /**
     * The test cases in this suite.
     */
    @Builder.Default
    private List<EvalCase> testCases = new ArrayList<>();

    /**
     * Tags for categorizing the suite.
     */
    @Builder.Default
    private List<String> tags = List.of();

    /**
     * Whether this suite is active.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Minimum overall pass rate required (0.0 to 1.0).
     */
    @Builder.Default
    private double requiredPassRate = 0.8;

    /**
     * When this suite was created.
     */
    private Instant createdAt;

    /**
     * When this suite was last modified.
     */
    private Instant updatedAt;

    /**
     * Additional metadata for the suite.
     */
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    /**
     * Add a test case to the suite.
     */
    public void addTestCase(EvalCase testCase) {
        if (testCases == null) {
            testCases = new ArrayList<>();
        }
        testCases.add(testCase);
        updatedAt = Instant.now();
    }

    /**
     * Remove a test case by ID.
     */
    public boolean removeTestCase(String testCaseId) {
        if (testCases == null) {
            return false;
        }
        boolean removed = testCases.removeIf(tc -> tc.getId().equals(testCaseId));
        if (removed) {
            updatedAt = Instant.now();
        }
        return removed;
    }

    /**
     * Get the count of test cases.
     */
    public int getTestCaseCount() {
        return testCases != null ? testCases.size() : 0;
    }

    /**
     * Get the count of enabled test cases.
     */
    public int getEnabledTestCaseCount() {
        if (testCases == null) {
            return 0;
        }
        return (int) testCases.stream().filter(EvalCase::isEnabled).count();
    }

    /**
     * Create a suite for a specific fact sheet.
     */
    public static EvalSuite forFactSheet(String id, String name, Long factSheetId) {
        return EvalSuite.builder()
                .id(id)
                .name(name)
                .factSheetId(factSheetId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * Create a simple suite with a name.
     */
    public static EvalSuite of(String id, String name) {
        return EvalSuite.builder()
                .id(id)
                .name(name)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
