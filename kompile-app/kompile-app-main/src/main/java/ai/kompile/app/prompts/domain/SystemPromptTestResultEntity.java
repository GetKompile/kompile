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
package ai.kompile.app.prompts.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity for storing test results when running a prompt against an eval suite.
 */
@Entity
@Table(name = "system_prompt_test_results", indexes = {
    @Index(name = "idx_prompt_test_prompt", columnList = "prompt_id"),
    @Index(name = "idx_prompt_test_suite", columnList = "eval_suite_id"),
    @Index(name = "idx_prompt_test_completed", columnList = "completed_at"),
    @Index(name = "idx_prompt_test_passed", columnList = "passed")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemPromptTestResultEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * The prompt that was tested.
     */
    @Column(name = "prompt_id", nullable = false, length = 36)
    private String promptId;

    /**
     * The prompt name at time of test (denormalized for history).
     */
    @Column(name = "prompt_name", length = 255)
    private String promptName;

    /**
     * The prompt version at time of test.
     */
    @Column(name = "prompt_version")
    private Integer promptVersion;

    /**
     * The eval suite used for testing.
     */
    @Column(name = "eval_suite_id", nullable = false, length = 36)
    private String evalSuiteId;

    /**
     * The eval suite name at time of test (denormalized for history).
     */
    @Column(name = "eval_suite_name", length = 255)
    private String evalSuiteName;

    /**
     * Whether the overall test passed.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean passed = false;

    /**
     * Overall score (0.0 - 1.0).
     */
    @Column
    private Double score;

    /**
     * Number of test cases that passed.
     */
    @Column(name = "passed_count")
    @Builder.Default
    private Integer passedCount = 0;

    /**
     * Number of test cases that failed.
     */
    @Column(name = "failed_count")
    @Builder.Default
    private Integer failedCount = 0;

    /**
     * Total number of test cases.
     */
    @Column(name = "total_count")
    @Builder.Default
    private Integer totalCount = 0;

    /**
     * Detailed results in JSON format.
     */
    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    /**
     * Error message if the test failed to complete.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * When the test was started.
     */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * When the test completed.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Total execution time in milliseconds.
     */
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
