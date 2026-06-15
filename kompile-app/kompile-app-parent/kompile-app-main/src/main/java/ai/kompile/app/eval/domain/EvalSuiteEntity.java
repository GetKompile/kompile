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
package ai.kompile.app.eval.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity for persisting evaluation suites.
 * Maps to the EvalSuite model class from kompile-react-agent.
 */
@Entity
@Table(name = "eval_suites", indexes = {
    @Index(name = "idx_eval_suite_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_eval_suite_enabled", columnList = "is_enabled"),
    @Index(name = "idx_eval_suite_name", columnList = "name"),
    @Index(name = "idx_eval_suite_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSuiteEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * Human-readable name for the suite.
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Description of what this suite validates.
     */
    @Column(length = 1000)
    private String description;

    /**
     * Optional fact sheet ID to scope all tests to.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * The test cases in this suite.
     */
    @OneToMany(mappedBy = "suite", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<EvalCaseEntity> testCases = new ArrayList<>();

    /**
     * Tags for categorizing the suite (JSON array).
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    /**
     * Whether this suite is active.
     */
    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * Minimum overall pass rate required (0.0 to 1.0).
     */
    @Column(name = "required_pass_rate", nullable = false)
    @Builder.Default
    private Double requiredPassRate = 0.8;

    /**
     * When this suite was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When this suite was last modified.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Additional metadata for the suite (JSON object).
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Add a test case to this suite.
     */
    public void addTestCase(EvalCaseEntity testCase) {
        if (testCases == null) {
            testCases = new ArrayList<>();
        }
        testCases.add(testCase);
        testCase.setSuite(this);
    }

    /**
     * Remove a test case from this suite.
     */
    public void removeTestCase(EvalCaseEntity testCase) {
        if (testCases != null) {
            testCases.remove(testCase);
            testCase.setSuite(null);
        }
    }

    /**
     * Get count of test cases.
     */
    public int getTestCaseCount() {
        return testCases != null ? testCases.size() : 0;
    }

    /**
     * Get count of enabled test cases.
     */
    public int getEnabledTestCaseCount() {
        if (testCases == null) {
            return 0;
        }
        return (int) testCases.stream()
                .filter(tc -> Boolean.TRUE.equals(tc.getEnabled()))
                .count();
    }
}
