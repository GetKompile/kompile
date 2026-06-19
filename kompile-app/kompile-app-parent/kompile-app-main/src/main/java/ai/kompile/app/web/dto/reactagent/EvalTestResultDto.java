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
package ai.kompile.app.web.dto.reactagent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ReAct agent evaluation test result DTO.
 */
@Data
@Builder
public class EvalTestResultDto {
    private String id;
    private String testCaseId;
    private String testCaseName;
    private String suiteId;
    private Long factSheetId;
    private String executionId;
    private boolean passed;
    private double score;
    private String query;
    private String expectedAnswer;
    private String actualAnswer;
    private List<String> retrievedDocuments;
    private List<String> toolCalls;
    private int stepsExecuted;
    private Map<String, Double> scores;
    private Map<String, Boolean> passedByType;
    private List<String> failureReasons;
    private Instant startedAt;
    private Instant completedAt;
    private long executionTimeMs;
    private long totalTokens;
    private Map<String, Object> metadata;
}
