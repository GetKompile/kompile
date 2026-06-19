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

package ai.kompile.ocr;

/**
 * Output format for VLM (Vision-Language Model) document processing.
 * Shared across OCR pipeline and PDF processing configuration.
 */
public enum VlmOutputFormat {
    /**
     * DocTags format (Docling native) - structured XML-like format.
     * Used by SmolDocling, Granite-Docling.
     */
    DOCTAGS,

    /**
     * Markdown format - human-readable.
     * Used by Nougat, GOT-OCR, Kosmos-2.5, Qwen-VL.
     */
    MARKDOWN,

    /**
     * Florence-2 task-specific format with quantized location tokens.
     */
    FLORENCE2,

    /**
     * Donut JSON-mapped tag format.
     * Used by Donut, OCRonos.
     */
    DONUT,

    /**
     * Plain text - no structure.
     * Used by GOT-OCR (plain), Tesseract-style output.
     */
    PLAIN_TEXT,

    /**
     * JSON format - machine-parseable.
     * @deprecated Use model-specific formats (DONUT, FLORENCE2) instead.
     */
    @Deprecated
    JSON,

    /**
     * Plain text alias.
     * @deprecated Use PLAIN_TEXT instead for consistency with DL4J OutputFormat.
     */
    @Deprecated
    TEXT
}
