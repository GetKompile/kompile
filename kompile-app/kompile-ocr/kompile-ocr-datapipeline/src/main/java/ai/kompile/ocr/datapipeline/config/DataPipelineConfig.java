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

package ai.kompile.ocr.datapipeline.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Root configuration for OCR data pipelines.
 * Serializable to JSON for UI configuration and persistence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataPipelineConfig {

    /**
     * Unique identifier for this configuration.
     */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /**
     * Human-readable name for this configuration.
     */
    @Builder.Default
    private String name = "Default Pipeline";

    /**
     * Pipeline type this configuration is optimized for.
     */
    @Builder.Default
    private PipelineType pipelineType = PipelineType.GENERIC;

    /**
     * Preprocessing configuration.
     */
    @Builder.Default
    private PreprocessConfig preprocess = PreprocessConfig.defaults();

    /**
     * Output parsing configuration.
     */
    @Builder.Default
    private OutputParseConfig outputParse = OutputParseConfig.defaults();

    /**
     * Entity indexing configuration.
     */
    @Builder.Default
    private EntityIndexConfig entityIndex = EntityIndexConfig.defaults();

    /**
     * Custom key-value configuration for extensions.
     */
    @Builder.Default
    private Map<String, Object> custom = new HashMap<>();

    /**
     * Creates default configuration.
     */
    public static DataPipelineConfig defaults() {
        return DataPipelineConfig.builder().build();
    }

    /**
     * Creates configuration preset for DeepSeek-OCR pipeline.
     */
    public static DataPipelineConfig forDeepSeek() {
        return DataPipelineConfig.builder()
                .name("DeepSeek-OCR Pipeline")
                .pipelineType(PipelineType.DEEPSEEK_OCR)
                .preprocess(PreprocessConfig.forDeepSeek())
                .outputParse(OutputParseConfig.forMarkdown())
                .entityIndex(EntityIndexConfig.defaults())
                .build();
    }

    /**
     * Creates configuration preset for PaddleOCR PP-Structure pipeline.
     */
    public static DataPipelineConfig forPaddleOcr() {
        return DataPipelineConfig.builder()
                .name("PaddleOCR PP-Structure Pipeline")
                .pipelineType(PipelineType.PADDLEOCR_PP)
                .preprocess(PreprocessConfig.forPaddleOcr())
                .outputParse(OutputParseConfig.forHtml())
                .entityIndex(EntityIndexConfig.defaults())
                .build();
    }

    /**
     * Creates configuration preset for LayoutLM pipeline.
     */
    public static DataPipelineConfig forLayoutLM() {
        return DataPipelineConfig.builder()
                .name("LayoutLM v3 Pipeline")
                .pipelineType(PipelineType.LAYOUTLM_V3)
                .preprocess(PreprocessConfig.forLayoutLM())
                .outputParse(OutputParseConfig.forJson())
                .entityIndex(EntityIndexConfig.defaults())
                .build();
    }

    /**
     * Creates configuration preset for Docling pipeline.
     */
    public static DataPipelineConfig forDocling() {
        return DataPipelineConfig.builder()
                .name("Docling/TableFormer Pipeline")
                .pipelineType(PipelineType.DOCLING)
                .preprocess(PreprocessConfig.forDocling())
                .outputParse(OutputParseConfig.forDocTags())
                .entityIndex(EntityIndexConfig.defaults())
                .build();
    }

    /**
     * Supported pipeline types.
     */
    public enum PipelineType {
        GENERIC,
        DEEPSEEK_OCR,
        PADDLEOCR_PP,
        LAYOUTLM_V3,
        DOCLING,
        KOMPILE_NATIVE
    }
}
