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
 *  limitations under the License.
 */

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for staging a complete VLM pipeline.
 * Specifies all component files (vision encoder, decoder, embed tokens)
 * and associated tokenizer files for download and conversion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmPipelineRequest {

    /**
     * Model identifier for the VLM pipeline.
     */
    private String modelId;

    /**
     * Download source type (e.g., "huggingface", "url", "local").
     */
    private String source;

    /**
     * Repository path (e.g., "microsoft/Florence-2-base").
     */
    private String repository;

    /**
     * Filename of the vision encoder ONNX model.
     */
    private String visionEncoderFile;

    /**
     * Filename of the decoder ONNX model.
     */
    private String decoderFile;

    /**
     * Filename of the embed tokens ONNX model.
     */
    private String embedTokensFile;

    /**
     * Filename of the tokenizer model file.
     */
    private String tokenizerFile;

    /**
     * Filename of the tokenizer config JSON.
     */
    private String tokenizerConfigFile;

    /**
     * Whether to auto-promote the pipeline after successful staging.
     */
    private boolean autoPromote;
}
