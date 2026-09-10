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

package ai.kompile.staging.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for LLM text generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmGenerateResponse {
    private String generatedText;
    private double tokensPerSecond;
    private long firstTokenLatencyMs;
    private int totalTokens;
    private String finishReason;
    private long totalTimeMs;

    /*
     * Run-bound fields are deliberately nullable and excluded when absent so the legacy
     * request/response JSON shape remains unchanged.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UUID runId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UUID subjectId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long derivedFromRevision;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String referenceLanguage;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String learningLanguage;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String modelId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String modelVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String configurationVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String promptVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String policyVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String responseSchemaVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String promptHash;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant completedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> configurationEvidence;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double confidence;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String confidenceSource;
}
