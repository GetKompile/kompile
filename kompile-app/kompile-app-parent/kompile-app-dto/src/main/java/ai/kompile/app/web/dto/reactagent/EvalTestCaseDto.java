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
 * ReAct agent evaluation test case DTO.
 */
@Data
@Builder
public class EvalTestCaseDto {
    private String id;
    private String name;
    private String description;
    private Long factSheetId;
    private String factSheetName;
    private String query;
    private String expectedAnswer;
    private List<String> expectedFacts;
    private List<String> forbiddenFacts;
    private List<String> expectedEntities;
    private List<String> expectedToolCalls;
    private List<String> evaluationTypes;
    private Map<String, Double> thresholds;
    private List<String> tags;
    private int priority;
    private boolean enabled;
    private long timeoutMs;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> metadata;
}
