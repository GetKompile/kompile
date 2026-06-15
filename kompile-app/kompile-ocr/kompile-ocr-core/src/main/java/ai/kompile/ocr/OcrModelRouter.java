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

import ai.kompile.ocr.document.DocumentAnalyzer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routes documents to appropriate OCR models based on document analysis
 * and configuration.
 */
public interface OcrModelRouter {

    /**
     * Gets the best detection model for the given document characteristics.
     *
     * @param analysis Document analysis result
     * @param preferredModelId Optional preferred model ID
     * @return Selected detection model
     */
    Optional<TextDetectionModel> getDetectionModel(DocumentAnalyzer.DocumentAnalysis analysis,
                                                   String preferredModelId);

    /**
     * Gets the best recognition model for the given document characteristics.
     *
     * @param analysis Document analysis result
     * @param preferredModelId Optional preferred model ID
     * @return Selected recognition model
     */
    Optional<TextRecognitionModel> getRecognitionModel(DocumentAnalyzer.DocumentAnalysis analysis,
                                                       String preferredModelId);

    /**
     * Gets a table extraction model if needed.
     *
     * @param analysis Document analysis result
     * @param preferredModelId Optional preferred model ID
     * @return Selected table model or empty if not needed
     */
    Optional<TableExtractionModel> getTableModel(DocumentAnalyzer.DocumentAnalysis analysis,
                                                 String preferredModelId);

    /**
     * Gets a layout model if needed.
     *
     * @param analysis Document analysis result
     * @param preferredModelId Optional preferred model ID
     * @return Selected layout model or empty if not needed
     */
    Optional<LayoutModel> getLayoutModel(DocumentAnalyzer.DocumentAnalysis analysis,
                                         String preferredModelId);

    /**
     * Gets all available models of a specific type.
     *
     * @param type Model type
     * @return List of available models
     */
    List<? extends OcrModel> getAvailableModels(OcrModelType type);

    /**
     * Gets a model by its ID.
     *
     * @param modelId Model ID
     * @return The model or empty if not found
     */
    Optional<OcrModel> getModelById(String modelId);

    /**
     * Loads all models required for the given configuration.
     *
     * @param config Pipeline configuration
     * @throws Exception if loading fails
     */
    void loadModels(OcrPipelineConfig config) throws Exception;

    /**
     * Unloads all currently loaded models.
     */
    void unloadAllModels();

    /**
     * Gets information about all registered models.
     *
     * @return Map of model ID to model info
     */
    Map<String, ModelInfo> getModelRegistry();

    /**
     * Information about a registered model.
     */
    record ModelInfo(
        String modelId,
        String name,
        OcrModelType type,
        boolean isLoaded,
        boolean isAvailable,
        String description,
        OcrModel.ModelCapabilities capabilities
    ) {}
}
