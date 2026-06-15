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

package ai.kompile.modelmanager.vlm;

/**
 * Types of content extraction that VLM models can perform during document processing.
 *
 * Each extraction type maps to specific VLM model sets optimized for that task.
 * During chunking, the appropriate models are selected based on content detection.
 *
 * <h2>Extraction Pipeline</h2>
 * <pre>
 * Document Page
 *     │
 *     ▼
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                   CONTENT DETECTION                             │
 * │                                                                 │
 * │  Analyze page to determine content types present:               │
 * │  - Text regions (paragraphs, headers, lists)                   │
 * │  - Tables (structured data grids)                              │
 * │  - Figures/Charts (images with captions)                       │
 * │  - Code blocks (syntax-highlighted regions)                    │
 * │  - Mathematical equations                                      │
 * │  - Forms (input fields, checkboxes)                            │
 * └───────────────────────────┬─────────────────────────────────────┘
 *                             │
 *     ┌───────────────────────┼───────────────────────┐
 *     │                       │                       │
 *     ▼                       ▼                       ▼
 * ┌─────────┐           ┌─────────┐           ┌─────────┐
 * │  TEXT   │           │  TABLE  │           │ FIGURE  │
 * │ EXTRACT │           │ EXTRACT │           │ EXTRACT │
 * │         │           │         │           │         │
 * │SmolDoc- │           │TableFor-│           │ CLIP/   │
 * │ling     │           │mer      │           │ SigLIP  │
 * └────┬────┘           └────┬────┘           └────┬────┘
 *      │                     │                     │
 *      ▼                     ▼                     ▼
 * ┌─────────┐           ┌─────────┐           ┌─────────┐
 * │ Markdown│           │ Markdown│           │ Caption │
 * │  Text   │           │  Table  │           │ + Embed │
 * └────┬────┘           └────┬────┘           └────┬────┘
 *      │                     │                     │
 *      └─────────────────────┼─────────────────────┘
 *                            │
 *                            ▼
 *                    ┌───────────────┐
 *                    │   CHUNKING    │
 *                    │   SERVICE     │
 *                    │               │
 *                    │ Multi-vector  │
 *                    │ documents     │
 *                    └───────────────┘
 * </pre>
 *
 * @author Kompile Inc.
 */
public enum VlmExtractionType {

    /**
     * Full document understanding - converts entire page to structured text.
     * Best for: Scanned documents, PDFs with mixed content.
     *
     * <ul>
     *   <li><b>Model:</b> SmolDocling-256M, Donut, Nougat</li>
     *   <li><b>Input:</b> Document page image</li>
     *   <li><b>Output:</b> DocTags, Markdown, or JSON</li>
     * </ul>
     */
    DOCUMENT_UNDERSTANDING("document-understanding",
        "Full document to structured text conversion",
        VlmModelSet.SMOLDOCLING_256M),

    /**
     * Table structure recognition - extracts tables as structured data.
     * Best for: Documents with data tables, forms, spreadsheet-like content.
     *
     * <ul>
     *   <li><b>Model:</b> TableFormer, Table Transformer</li>
     *   <li><b>Input:</b> Table region image</li>
     *   <li><b>Output:</b> Markdown table, HTML, or CSV</li>
     * </ul>
     */
    TABLE_EXTRACTION("table-extraction",
        "Extract tables as structured markdown or HTML",
        VlmModelSet.DOCLING_TABLEFORMER),

    /**
     * Figure/chart understanding - generates captions and extracts data from charts.
     * Best for: Scientific papers, reports with charts/graphs.
     *
     * <ul>
     *   <li><b>Model:</b> Chart-to-Text, Pix2Struct, Deplot</li>
     *   <li><b>Input:</b> Figure/chart image</li>
     *   <li><b>Output:</b> Caption text, data table</li>
     * </ul>
     */
    FIGURE_UNDERSTANDING("figure-understanding",
        "Generate captions and extract data from figures/charts",
        null),  // No default model set yet

    /**
     * Image embedding - generates dense vectors for image similarity search.
     * Best for: Image retrieval, visual similarity matching.
     *
     * <ul>
     *   <li><b>Model:</b> CLIP, SigLIP, OpenCLIP</li>
     *   <li><b>Input:</b> Image</li>
     *   <li><b>Output:</b> Dense embedding vector</li>
     * </ul>
     */
    IMAGE_EMBEDDING("image-embedding",
        "Generate embeddings for visual similarity search",
        VlmModelSet.SIGLIP_VISION),

    /**
     * Image-text similarity - matches images to text descriptions.
     * Best for: Cross-modal search, image captioning verification.
     *
     * <ul>
     *   <li><b>Model:</b> CLIP ViT</li>
     *   <li><b>Input:</b> Image + text query</li>
     *   <li><b>Output:</b> Similarity score</li>
     * </ul>
     */
    IMAGE_TEXT_SIMILARITY("image-text-similarity",
        "Compute similarity between images and text descriptions",
        VlmModelSet.CLIP_VIT_BASE),

    /**
     * OCR with layout - text extraction preserving document structure.
     * Best for: Scanned documents needing text extraction with formatting.
     *
     * <ul>
     *   <li><b>Model:</b> TrOCR, PaddleOCR, EasyOCR</li>
     *   <li><b>Input:</b> Document image</li>
     *   <li><b>Output:</b> Text with bounding boxes and reading order</li>
     * </ul>
     */
    OCR_WITH_LAYOUT("ocr-layout",
        "Text extraction with document layout preservation",
        null),  // Traditional OCR, not VLM

    /**
     * Mathematical equation recognition - converts equations to LaTeX.
     * Best for: Scientific papers, textbooks with formulas.
     *
     * <ul>
     *   <li><b>Model:</b> LaTeX-OCR, Pix2Tex</li>
     *   <li><b>Input:</b> Equation image</li>
     *   <li><b>Output:</b> LaTeX string</li>
     * </ul>
     */
    EQUATION_RECOGNITION("equation-recognition",
        "Convert mathematical equations to LaTeX",
        null),  // Specialized model needed

    /**
     * Code extraction - extracts code blocks with syntax highlighting info.
     * Best for: Technical documentation, programming tutorials.
     *
     * <ul>
     *   <li><b>Input:</b> Code region image or formatted text</li>
     *   <li><b>Output:</b> Code text with language detection</li>
     * </ul>
     */
    CODE_EXTRACTION("code-extraction",
        "Extract code blocks with language detection",
        null),  // Often text-based, VLM optional

    /**
     * Handwriting recognition - converts handwritten text to digital.
     * Best for: Scanned handwritten notes, forms with handwriting.
     *
     * <ul>
     *   <li><b>Model:</b> TrOCR-handwritten</li>
     *   <li><b>Input:</b> Handwritten text image</li>
     *   <li><b>Output:</b> Digitized text</li>
     * </ul>
     */
    HANDWRITING_RECOGNITION("handwriting-recognition",
        "Convert handwritten text to digital text",
        null),

    /**
     * Form field extraction - extracts key-value pairs from forms.
     * Best for: Structured forms, invoices, receipts.
     *
     * <ul>
     *   <li><b>Model:</b> LayoutLM, Donut fine-tuned</li>
     *   <li><b>Input:</b> Form image</li>
     *   <li><b>Output:</b> JSON with field names and values</li>
     * </ul>
     */
    FORM_EXTRACTION("form-extraction",
        "Extract key-value pairs from structured forms",
        VlmModelSet.DONUT_BASE);

    private final String id;
    private final String description;
    private final VlmModelSet defaultModelSet;

    VlmExtractionType(String id, String description, VlmModelSet defaultModelSet) {
        this.id = id;
        this.description = description;
        this.defaultModelSet = defaultModelSet;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the default model set for this extraction type.
     * @return model set, or null if no default VLM model is configured
     */
    public VlmModelSet getDefaultModelSet() {
        return defaultModelSet;
    }

    /**
     * Check if this extraction type has a configured VLM model.
     */
    public boolean hasDefaultModel() {
        return defaultModelSet != null;
    }

    /**
     * Find extraction type by ID.
     */
    public static VlmExtractionType fromId(String id) {
        for (VlmExtractionType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Get all extraction types that have VLM models available.
     */
    public static java.util.List<VlmExtractionType> getTypesWithModels() {
        java.util.List<VlmExtractionType> types = new java.util.ArrayList<>();
        for (VlmExtractionType type : values()) {
            if (type.hasDefaultModel()) {
                types.add(type);
            }
        }
        return types;
    }
}
