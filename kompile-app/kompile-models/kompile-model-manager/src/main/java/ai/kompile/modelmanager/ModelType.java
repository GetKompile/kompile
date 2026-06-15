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

package ai.kompile.modelmanager;

/**
 * Enumerates the types of models managed by the KompileModelManager.
 */
public enum ModelType {
    /**
     * OpenNLP sentence detection model.
     */
    OPENNLP_SENTENCE,

    /**
     * Anserini prebuilt Lucene index. These are typically .tar.gz archives
     * containing Lucene index data.
     */
    ANSERINI_INDEX,

    /**
     * Anserini encoder model. These are typically neural network model files
     * (e.g., ONNX, TensorFlow SavedModel, DL4J zip) used by Anserini's
     * dense or sparse encoders (like SameDiff encoders).
     */
    ANSERINI_ENCODER_MODEL,

    /**
     * Other generic NLP models not fitting specific categories above.
     */
    NLP_MODEL,

    /**
     * Generic embedding models not specifically tied to Anserini encoders.
     */
    EMBEDDING_MODEL,

    /**
     * Cross-encoder reranking models. These models take a query-document pair
     * and output a relevance score for reranking search results.
     */
    CROSS_ENCODER_MODEL,

    /**
     * Reranking models (general category for any reranking approach).
     */
    RERANKER_MODEL,

    // ==================== OCR Model Types ====================

    /**
     * OCR text detection model.
     * Detects text regions in images.
     * Examples: DBNet, EAST, CRAFT
     */
    OCR_DETECTION,

    /**
     * OCR text recognition model.
     * Converts text region images to strings.
     * Examples: CRNN, SVTR, TrOCR
     */
    OCR_RECOGNITION,

    /**
     * OCR table extraction model.
     * Identifies and structures tables.
     * Examples: TableFormer, PubLayNet
     */
    OCR_TABLE,

    /**
     * Document layout understanding model.
     * Maps text to semantic fields.
     * Examples: LayoutLM, LayoutLMv2, LayoutLMv3, DiT
     */
    LAYOUT_MODEL,

    /**
     * End-to-end OCR pipeline model.
     * Combined detection + recognition.
     * Examples: PaddleOCR pipeline, DocTR
     */
    OCR_PIPELINE,

    /**
     * Document classification model.
     * Classifies document types.
     * Examples: Invoice detector, receipt classifier
     */
    DOCUMENT_CLASSIFIER,

    // ==================== VLM Model Types ====================

    /**
     * Vision-Language Model for end-to-end document understanding.
     * Multi-part models with vision encoder + text decoder.
     * Examples: SmolDocling, LLaVA, Donut, Nougat
     */
    VLM_MODEL,

    /**
     * Vision encoder component of a VLM.
     * Converts images to embeddings.
     * Examples: ViT, CLIP vision encoder
     */
    VLM_VISION_ENCODER,

    /**
     * Text decoder component of a VLM.
     * Generates text from embeddings.
     * Examples: GPT-2, LLaMA decoder
     */
    VLM_DECODER,

    /**
     * Token embeddings component of a VLM.
     * Embeds text tokens for decoder.
     */
    VLM_EMBED_TOKENS,

    // ==================== SDX Runtime Types ====================

    /**
     * SDX Runtime SDK — platform-specific native runtime libraries.
     * Packages: .xcframework (iOS), .aar (Android), .zip (desktop)
     */
    SDX_RUNTIME_SDK,

    /**
     * SDX Model Bundle — inference bundles (.sdz files with manifest.json).
     * Platform-independent model packages for on-device inference.
     */
    SDX_MODEL_BUNDLE
}
