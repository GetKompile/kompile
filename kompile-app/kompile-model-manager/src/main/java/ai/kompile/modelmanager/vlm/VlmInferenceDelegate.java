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

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Delegate interface for VLM inference operations.
 *
 * <p>This interface decouples the lightweight {@code kompile-model-manager} module from
 * heavy DL4J/SameDiff dependencies. Implementations (e.g., in {@code kompile-ocr-models})
 * provide actual VLM inference using {@code VisionLanguageModel} and {@code SamplingConfig}.</p>
 *
 * <p>When no delegate is set, {@link VlmContentExtractor} falls back to placeholder behavior.</p>
 *
 * @author Kompile Inc.
 */
public interface VlmInferenceDelegate {

    /**
     * Run document understanding inference on an image.
     *
     * @param image the document page image
     * @param modelId the model identifier
     * @param config extraction configuration
     * @param progressCallback optional progress callback
     * @return generated text output (markdown, doctags, etc.)
     */
    String runDocumentUnderstanding(BufferedImage image, String modelId,
                                    VlmExtractionConfig config,
                                    Consumer<String> progressCallback);

    /**
     * Run table extraction inference on an image.
     *
     * @param image the document page image
     * @param modelId the model identifier
     * @param config extraction configuration
     * @param progressCallback optional progress callback
     * @return list of extracted tables as maps with "markdown", "rowCount", "columnCount" keys
     */
    List<Map<String, Object>> runTableExtraction(BufferedImage image, String modelId,
                                                  VlmExtractionConfig config,
                                                  Consumer<String> progressCallback);

    /**
     * Run image embedding inference.
     *
     * @param image the image to embed
     * @param modelId the model identifier
     * @return float array embedding vector, or null if not supported
     */
    float[] runImageEmbedding(BufferedImage image, String modelId);

    /**
     * Run form extraction inference on an image.
     *
     * @param image the form image
     * @param modelId the model identifier
     * @param config extraction configuration
     * @param progressCallback optional progress callback
     * @return map of field name to extracted value
     */
    Map<String, String> runFormExtraction(BufferedImage image, String modelId,
                                          VlmExtractionConfig config,
                                          Consumer<String> progressCallback);

    /**
     * Check if this delegate is available and ready for inference.
     *
     * @return true if VLM models are loaded and inference is possible
     */
    boolean isAvailable();

    /**
     * Get the list of supported model IDs.
     *
     * @return list of model IDs this delegate can handle
     */
    default List<String> getSupportedModelIds() {
        return List.of();
    }
}
