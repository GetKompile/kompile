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

/**
 * JPA entity for persisting evaluation test cases.
 * Maps to the EvalCase model class from kompile-react-agent.
 */
@Entity
@Table(name = "eval_cases", indexes = {
    @Index(name = "idx_eval_case_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_eval_case_suite", columnList = "suite_id"),
    @Index(name = "idx_eval_case_enabled", columnList = "is_enabled"),
    @Index(name = "idx_eval_case_priority", columnList = "priority"),
    @Index(name = "idx_eval_case_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * Human-readable name for the test case.
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Description of what this test case validates.
     */
    @Column(length = 1000)
    private String description;

    /**
     * The fact sheet ID this test case is associated with.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * The fact sheet name (for display purposes, denormalized).
     */
    @Column(name = "fact_sheet_name", length = 255)
    private String factSheetName;

    /**
     * The suite this test case belongs to (optional).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suite_id")
    private EvalSuiteEntity suite;

    /**
     * The input query to test.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    /**
     * Expected answer or ground truth (optional).
     */
    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    /**
     * Expected key facts that should be present in the response (JSON array).
     */
    @Column(name = "expected_facts_json", columnDefinition = "TEXT")
    private String expectedFactsJson;

    /**
     * Facts that should NOT be in the response (JSON array).
     */
    @Column(name = "forbidden_facts_json", columnDefinition = "TEXT")
    private String forbiddenFactsJson;

    /**
     * Expected entities that should be mentioned (JSON array).
     */
    @Column(name = "expected_entities_json", columnDefinition = "TEXT")
    private String expectedEntitiesJson;

    /**
     * Expected tool calls the agent should make (JSON array).
     */
    @Column(name = "expected_tool_calls_json", columnDefinition = "TEXT")
    private String expectedToolCallsJson;

    /**
     * Evaluation types to run for this test case (JSON array of EvaluationType names).
     */
    @Column(name = "evaluation_types_json", columnDefinition = "TEXT")
    private String evaluationTypesJson;

    /**
     * Minimum score thresholds per evaluation type (JSON map).
     */
    @Column(name = "thresholds_json", columnDefinition = "TEXT")
    private String thresholdsJson;

    /**
     * Tags for categorizing test cases (JSON array).
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    /**
     * Priority level (1-5, higher is more important).
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 3;

    /**
     * Whether this test case is active.
     */
    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * Maximum time allowed for this test case in milliseconds.
     */
    @Column(name = "timeout_ms", nullable = false)
    @Builder.Default
    private Long timeoutMs = 30000L;

    /**
     * When this test case was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When this test case was last modified.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Additional metadata for the test case (JSON object).
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
}
