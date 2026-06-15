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

package ai.kompile.staging.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents a model available in the catalog for download.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogModel {
    private String id;
    private String source;
    private String repo;
    private String format;
    private Map<String, String> files;
    private CatalogModelMetadata metadata;
    private String modelType;
    private boolean installed;
    /**
     * Whether this model has a SameDiff (.fb/.sdz) file on disk and can be
     * run through the GraphOptimizer. A model may be installed (e.g. ONNX)
     * but not optimizable until its SameDiff equivalent is converted.
     */
    private boolean optimizable;
    private String status;
    private String path;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CatalogModelMetadata {
        private Integer embeddingDim;
        private Integer hiddenSize;
        private Integer numLayers;
        private Integer maxSequenceLength;
        private String trainingData;
        private String description;
        private String framework;
        private String encoderType;
        private String ragRole;
        private String version;
        // OCR fields
        private Integer inputHeight;
        private Integer inputWidth;
        private List<String> supportedLanguages;
        private Boolean supportsBatch;
        private Integer maxBatchSize;
        private Boolean supportsHandwriting;
        private Double averageAccuracy;
        private Integer ocrVocabSize;
        private Boolean usesCtc;
        // VLM fields
        private Integer visionFrames;
        private Integer imageSize;
        private Integer tileSize;
        private List<String> components;
        // Vision encoder IO
        @JsonProperty("vision_encoder_output_names")
        private List<String> visionEncoderOutputNames;
        @JsonProperty("vision_encoder_primary_output_name")
        private String visionEncoderPrimaryOutputName;
        // Optimization tracking
        private Boolean optimized;
        @JsonProperty("optimized_at")
        private String optimizedAt;
        @JsonProperty("optimization_time_ms")
        private Long optimizationTimeMs;
        @JsonProperty("applied_optimizations")
        private List<String> appliedOptimizations;
        @JsonProperty("optimization_stats")
        private OptimizationStatsData optimizationStats;
        @JsonProperty("optimization_config")
        private OptimizationConfigData optimizationConfig;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptimizationStatsData {
        @JsonProperty("ops_before")
        private Integer opsBefore;
        @JsonProperty("ops_after")
        private Integer opsAfter;
        @JsonProperty("vars_before")
        private Integer varsBefore;
        @JsonProperty("vars_after")
        private Integer varsAfter;
        @JsonProperty("size_before_bytes")
        private Long sizeBeforeBytes;
        @JsonProperty("size_after_bytes")
        private Long sizeAfterBytes;
        @JsonProperty("reduction_percent")
        private Double reductionPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptimizationConfigData {
        @JsonProperty("enabled_passes")
        private List<String> enabledPasses;
        private String preset;
        @JsonProperty("quantization_type")
        private String quantizationType;
        @JsonProperty("quantize_per_channel")
        private Boolean quantizePerChannel;
        @JsonProperty("max_iterations")
        private Integer maxIterations;
    }
}
