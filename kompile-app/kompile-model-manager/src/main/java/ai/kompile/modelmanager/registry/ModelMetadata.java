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

package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata about a model including dimensions, architecture, and provenance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelMetadata {

    @JsonProperty("embedding_dim")
    private Integer embeddingDim;

    @JsonProperty("hidden_size")
    private Integer hiddenSize;

    @JsonProperty("num_layers")
    private Integer numLayers;

    @JsonProperty("max_sequence_length")
    private Integer maxSequenceLength;

    @JsonProperty("model_type")
    @Builder.Default
    private String modelType = "dense";

    @JsonProperty("encoder_type")
    private String encoderType;

    @JsonProperty("rag_role")
    private String ragRole;

    @JsonProperty("framework")
    @Builder.Default
    private String framework = "samediff";

    @JsonProperty("training_data")
    private String trainingData;

    @JsonProperty("source_origin")
    private String sourceOrigin;

    @JsonProperty("source_repository")
    private String sourceRepository;

    @JsonProperty("original_format")
    private String originalFormat;

    @JsonProperty("conversion_date")
    private String conversionDate;

    @JsonProperty("description")
    private String description;

    @JsonProperty("vocab_size")
    private Integer vocabSize;

    @JsonProperty("version")
    private String version;

    // Optimization tracking fields
    @JsonProperty("optimized")
    @Builder.Default
    private Boolean optimized = false;

    @JsonProperty("optimized_at")
    private String optimizedAt;

    @JsonProperty("optimization_time_ms")
    private Long optimizationTimeMs;

    @JsonProperty("unoptimized_backup_file")
    private String unoptimizedBackupFile;

    @JsonProperty("applied_optimizations")
    private java.util.List<String> appliedOptimizations;

    @JsonProperty("optimization_stats")
    private OptimizationStats optimizationStats;

    @JsonProperty("optimization_config")
    private OptimizationConfig optimizationConfig;

    // Benchmark tracking
    @JsonProperty("benchmark_result")
    private BenchmarkResult benchmarkResult;

    // OCR-specific fields
    @JsonProperty("input_height")
    private Integer inputHeight;

    @JsonProperty("input_width")
    private Integer inputWidth;

    @JsonProperty("supported_languages")
    private java.util.List<String> supportedLanguages;

    @JsonProperty("supports_batch")
    @Builder.Default
    private Boolean supportsBatch = true;

    @JsonProperty("max_batch_size")
    private Integer maxBatchSize;

    @JsonProperty("supports_handwriting")
    @Builder.Default
    private Boolean supportsHandwriting = false;

    @JsonProperty("average_accuracy")
    private Double averageAccuracy;

    @JsonProperty("ocr_vocab_size")
    private Integer ocrVocabSize;

    @JsonProperty("uses_ctc")
    private Boolean usesCtc;

    // VLM-specific fields
    @JsonProperty("vision_frames")
    private Integer visionFrames;

    @JsonProperty("image_size")
    private Integer imageSize;

    @JsonProperty("tile_size")
    private Integer tileSize;

    @JsonProperty("components")
    private java.util.List<String> components;

    // Vision encoder IO config (auto-probed from SameDiff graph, user-overridable)
    @JsonProperty("vision_encoder_pixel_values_name")
    private String visionEncoderPixelValuesName;

    @JsonProperty("vision_encoder_pixel_attention_mask_name")
    private String visionEncoderPixelAttentionMaskName;

    @JsonProperty("vision_encoder_primary_output_name")
    private String visionEncoderPrimaryOutputName;

    @JsonProperty("vision_encoder_output_names")
    private java.util.List<String> visionEncoderOutputNames;

    // Archive provenance
    @JsonProperty("source_archive_id")
    private String sourceArchiveId;

    @JsonProperty("source_archive_version")
    private String sourceArchiveVersion;

    @JsonProperty("installed_from")
    private String installedFrom;

    @JsonProperty("installed_at")
    private String installedAt;

    @JsonProperty("staging_registry_version")
    private String stagingRegistryVersion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OptimizationStats {
        @JsonProperty("ops_before")
        private int opsBefore;
        @JsonProperty("ops_after")
        private int opsAfter;
        @JsonProperty("vars_before")
        private int varsBefore;
        @JsonProperty("vars_after")
        private int varsAfter;
        @JsonProperty("size_before_bytes")
        private long sizeBeforeBytes;
        @JsonProperty("size_after_bytes")
        private long sizeAfterBytes;
        @JsonProperty("reduction_percent")
        private double reductionPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OptimizationConfig {
        @JsonProperty("enabled_passes")
        private java.util.List<String> enabledPasses;
        @JsonProperty("preset")
        private String preset;
        @JsonProperty("quantization_type")
        private String quantizationType;
        @JsonProperty("quantize_per_channel")
        @Builder.Default
        private boolean quantizePerChannel = false;
        @JsonProperty("max_iterations")
        @Builder.Default
        private int maxIterations = 3;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BenchmarkResult {
        @JsonProperty("throughput_tok_per_sec")
        private double throughputTokPerSec;
        @JsonProperty("latency_p99_ms")
        private double latencyP99Ms;
        @JsonProperty("token_diversity")
        private double tokenDiversity;
        @JsonProperty("structure_valid")
        private boolean structureValid;
        @JsonProperty("baseline_model")
        private String baselineModel;
        @JsonProperty("throughput_delta_percent")
        private double throughputDeltaPercent;
        @JsonProperty("regression")
        private boolean regression;
        @JsonProperty("benchmarked_at")
        private String benchmarkedAt;
    }
}
