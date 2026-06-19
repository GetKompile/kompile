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

/**
 * ReAct agent configuration DTO.
 */
@Data
@Builder
public class ReActConfigDto {
    private boolean enabled;
    private int maxSteps;
    private String executionMode;
    private boolean graphRagEnabled;
    private String graphRagSearchType;
    private int graphRagMaxResults;
    private boolean filterChainEnabled;
    private boolean evalBasedEnabled;
    private boolean evalTrackingEnabled;
    private boolean evalHookEnabled;
    private boolean selfEvaluate;
    private boolean evaluateReasoning;
    private double qualityThreshold;
    private int evalRetentionDays;
    private boolean summarizeResults;
    private int maxResultLength;
}
