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

import ai.kompile.ocr.OcrModelType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Represents the result from a single model in the OCR pipeline.
 * Used for audit trail to track what each model produced.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelResult {

    /**
     * Model identifier.
     */
    private String modelId;

    /**
     * Model name for display.
     */
    private String modelName;

    /**
     * Model type.
     */
    private OcrModelType modelType;

    /**
     * Model version.
     */
    private String modelVersion;

    /**
     * Whether the model execution was successful.
     */
    @Builder.Default
    private boolean success = true;

    /**
     * Error message if execution failed.
     */
    private String errorMessage;

    /**
     * Processing time in milliseconds.
     */
    private long processingTimeMs;

    /**
     * Timestamp of execution.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Number of items produced (regions, tables, fields).
     */
    private int outputCount;

    /**
     * Average confidence of outputs.
     */
    private double averageConfidence;

    /**
     * Minimum confidence of outputs.
     */
    private double minConfidence;

    /**
     * Maximum confidence of outputs.
     */
    private double maxConfidence;

    /**
     * Input dimensions [height, width].
     */
    private int[] inputDimensions;

    /**
     * Additional metrics.
     */
    private Map<String, Object> metrics;

    /**
     * Creates a successful model result.
     */
    public static ModelResult success(String modelId, OcrModelType type,
                                      long processingTimeMs, int outputCount,
                                      double avgConfidence) {
        return ModelResult.builder()
                .modelId(modelId)
                .modelType(type)
                .success(true)
                .processingTimeMs(processingTimeMs)
                .outputCount(outputCount)
                .averageConfidence(avgConfidence)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Creates a failed model result.
     */
    public static ModelResult failure(String modelId, OcrModelType type,
                                      String errorMessage, long processingTimeMs) {
        return ModelResult.builder()
                .modelId(modelId)
                .modelType(type)
                .success(false)
                .errorMessage(errorMessage)
                .processingTimeMs(processingTimeMs)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Gets the model type as a display string.
     */
    public String getTypeDisplay() {
        return modelType != null ? modelType.getDisplayName() : "Unknown";
    }

    /**
     * Checks if confidence is available.
     */
    public boolean hasConfidence() {
        return averageConfidence > 0;
    }
}
