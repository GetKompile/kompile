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

package ai.kompile.ocr.audit;

import ai.kompile.ocr.BoundingBox;
import ai.kompile.ocr.OcrModelType;
import ai.kompile.ocr.OcrPipelineConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete audit trail for OCR processing.
 * Provides full traceability from source coordinates to final output.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditTrail {

    /**
     * Unique identifier for this audit trail.
     */
    private String id;

    /**
     * Source document identifier.
     */
    private String sourceId;

    /**
     * Page number (1-indexed).
     */
    private int pageNumber;

    /**
     * Pipeline configuration used.
     */
    private OcrPipelineConfig pipelineConfig;

    /**
     * Results from each model in the pipeline.
     */
    @Builder.Default
    private List<ModelResult> modelResults = new ArrayList<>();

    /**
     * Validation results.
     */
    @Builder.Default
    private List<ValidationResult> validationResults = new ArrayList<>();

    /**
     * Confidence scores for different aspects.
     */
    @Builder.Default
    private Map<String, ConfidenceScore> confidenceScores = new HashMap<>();

    /**
     * Region-to-output mapping for traceability.
     */
    @Builder.Default
    private List<RegionMapping> regionMappings = new ArrayList<>();

    /**
     * Total processing time in milliseconds.
     */
    private long totalProcessingTimeMs;

    /**
     * Timestamp when processing started.
     */
    private Instant startTime;

    /**
     * Timestamp when processing completed.
     */
    private Instant endTime;

    /**
     * Kompile version used for processing.
     */
    private String kompileVersion;

    /**
     * Processing node/host identifier.
     */
    private String processingNode;

    /**
     * Maps a source region to output elements.
     */
    @Data
    @Builder
    public static class RegionMapping {
        private BoundingBox sourceRegion;
        private String outputType;          // "text", "table_cell", "field"
        private String outputId;
        private double confidence;
        private String modelUsed;
    }

    /**
     * Adds a model result to the audit trail.
     */
    public void addModelResult(ModelResult result) {
        if (modelResults == null) {
            modelResults = new ArrayList<>();
        }
        modelResults.add(result);
    }

    /**
     * Adds a validation result to the audit trail.
     */
    public void addValidationResult(ValidationResult result) {
        if (validationResults == null) {
            validationResults = new ArrayList<>();
        }
        validationResults.add(result);
    }

    /**
     * Adds a confidence score.
     */
    public void addConfidenceScore(String key, ConfidenceScore score) {
        if (confidenceScores == null) {
            confidenceScores = new HashMap<>();
        }
        confidenceScores.put(key, score);
    }

    /**
     * Adds a region mapping.
     */
    public void addRegionMapping(RegionMapping mapping) {
        if (regionMappings == null) {
            regionMappings = new ArrayList<>();
        }
        regionMappings.add(mapping);
    }

    /**
     * Gets the result for a specific model type.
     */
    public ModelResult getResultForType(OcrModelType type) {
        if (modelResults == null) {
            return null;
        }
        return modelResults.stream()
                .filter(r -> r.getModelType() == type)
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets all validation issues.
     */
    public List<ValidationResult.ValidationIssue> getAllIssues() {
        List<ValidationResult.ValidationIssue> allIssues = new ArrayList<>();
        if (validationResults != null) {
            for (ValidationResult vr : validationResults) {
                if (vr.getIssues() != null) {
                    allIssues.addAll(vr.getIssues());
                }
            }
        }
        return allIssues;
    }

    /**
     * Checks if any model failed.
     */
    public boolean hasFailures() {
        if (modelResults == null) {
            return false;
        }
        return modelResults.stream().anyMatch(r -> !r.isSuccess());
    }

    /**
     * Checks if any validation failed.
     */
    public boolean hasValidationFailures() {
        if (validationResults == null) {
            return false;
        }
        return validationResults.stream()
                .anyMatch(r -> r.getOutcome() == ValidationResult.ValidationOutcome.INVALID);
    }

    /**
     * Gets the overall confidence score.
     */
    public double getOverallConfidence() {
        if (confidenceScores == null || confidenceScores.isEmpty()) {
            return 0.0;
        }
        ConfidenceScore overall = confidenceScores.get("overall");
        if (overall != null) {
            return overall.getValue();
        }
        // Compute average
        return confidenceScores.values().stream()
                .mapToDouble(ConfidenceScore::getValue)
                .average()
                .orElse(0.0);
    }

    /**
     * Creates a new audit trail for a page.
     */
    public static AuditTrail forPage(String sourceId, int pageNumber) {
        return AuditTrail.builder()
                .id(java.util.UUID.randomUUID().toString())
                .sourceId(sourceId)
                .pageNumber(pageNumber)
                .startTime(Instant.now())
                .modelResults(new ArrayList<>())
                .validationResults(new ArrayList<>())
                .confidenceScores(new HashMap<>())
                .regionMappings(new ArrayList<>())
                .build();
    }

    /**
     * Completes the audit trail.
     */
    public void complete() {
        this.endTime = Instant.now();
        if (startTime != null) {
            this.totalProcessingTimeMs = java.time.Duration.between(startTime, endTime).toMillis();
        }
    }
}
