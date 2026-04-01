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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum representing the type of ML model in the RAG pipeline.
 */
public enum ModelType {
    DENSE_ENCODER("dense_encoder"),
    SPARSE_ENCODER("sparse_encoder"),
    CROSS_ENCODER("cross_encoder"),
    OCR_DETECTION("ocr_detection"),
    OCR_RECOGNITION("ocr_recognition"),
    OCR_TABLE("ocr_table"),
    LAYOUT_MODEL("layout_model"),
    OCR_PIPELINE("ocr_pipeline"),
    DOCUMENT_CLASSIFIER("document_classifier"),
    VLM_PIPELINE("vlm_pipeline"),
    LLM_GGML("llm_ggml"),
    @Deprecated
    ENCODER("encoder");

    private final String value;

    ModelType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ModelType fromValue(String value) {
        if (value == null) {
            return DENSE_ENCODER;
        }
        String lower = value.toLowerCase().trim();
        if ("encoder".equals(lower)) {
            return DENSE_ENCODER;
        }
        if ("reranker".equals(lower)) {
            return CROSS_ENCODER;
        }
        for (ModelType type : values()) {
            if (type.value.equalsIgnoreCase(lower)) {
                return type;
            }
        }
        return DENSE_ENCODER;
    }

    public String getDirectoryName() {
        switch (this) {
            case DENSE_ENCODER:
            case ENCODER:
                return "encoders";
            case SPARSE_ENCODER:
                return "sparse-encoders";
            case CROSS_ENCODER:
                return "cross-encoders";
            default:
                return value.replace("_", "-") + "s";
        }
    }

    public boolean isRetrieval() {
        return this == DENSE_ENCODER || this == SPARSE_ENCODER || this == ENCODER;
    }

    public boolean isReranking() {
        return this == CROSS_ENCODER;
    }

    public boolean isOcr() {
        return this == OCR_DETECTION || this == OCR_RECOGNITION || this == OCR_TABLE
                || this == LAYOUT_MODEL || this == OCR_PIPELINE || this == DOCUMENT_CLASSIFIER;
    }

    public boolean isVlm() {
        return this == VLM_PIPELINE;
    }

    public boolean isLlm() {
        return this == LLM_GGML;
    }

    public String getDisplayName() {
        switch (this) {
            case DENSE_ENCODER:
                return "Dense Encoder";
            case SPARSE_ENCODER:
                return "Sparse Encoder";
            case CROSS_ENCODER:
                return "Cross-Encoder (Reranker)";
            case OCR_DETECTION:
                return "OCR Detection";
            case OCR_RECOGNITION:
                return "OCR Recognition";
            case OCR_TABLE:
                return "OCR Table";
            case LAYOUT_MODEL:
                return "Layout Model";
            case OCR_PIPELINE:
                return "OCR Pipeline";
            case DOCUMENT_CLASSIFIER:
                return "Document Classifier";
            case VLM_PIPELINE:
                return "VLM Pipeline";
            case LLM_GGML:
                return "LLM (GGML)";
            case ENCODER:
                return "Encoder (Legacy)";
            default:
                return value;
        }
    }
}
