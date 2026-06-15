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

package ai.kompile.staging.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for VLM image embedding operations.
 * Wraps VlmExecutionService to expose image embedding capabilities
 * via the loaded VisionLanguageModel's vision encoder.
 *
 * The vision encoder preprocesses images and produces dense vector representations
 * suitable for similarity search, clustering, and retrieval tasks.
 */
@Service
public class VlmImageEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(VlmImageEmbeddingService.class);

    @Autowired
    private VlmExecutionService vlmExecutionService;

    /**
     * Embed a single image, returning the vision encoder output as a float array.
     *
     * @param imageData raw image bytes (PNG, JPEG, etc.)
     * @return float array embedding from the vision encoder
     * @throws IOException if image processing fails or no model is loaded
     */
    public float[] embedImage(byte[] imageData) throws IOException {
        if (!vlmExecutionService.isModelReady()) {
            throw new IOException("No VLM model loaded. Load a VLM model first for image embedding.");
        }
        return vlmExecutionService.embedImage(imageData);
    }

    /**
     * Embed multiple images in batch.
     *
     * @param imageDataList list of raw image bytes
     * @return list of float array embeddings
     * @throws IOException if any image processing fails
     */
    public List<float[]> embedImageBatch(List<byte[]> imageDataList) throws IOException {
        if (!vlmExecutionService.isModelReady()) {
            throw new IOException("No VLM model loaded. Load a VLM model first for image embedding.");
        }
        return vlmExecutionService.embedImageBatch(imageDataList);
    }

    /**
     * Get the embedding dimension for the currently loaded model.
     *
     * @return embedding dimension, or -1 if no model is loaded
     */
    public int getEmbeddingDimension() {
        return vlmExecutionService.getEmbeddingDimension();
    }

    /**
     * Check if a VLM model is loaded and available for image embedding.
     */
    public boolean isModelLoaded() {
        return vlmExecutionService.isModelReady();
    }

    /**
     * Get embedding service info including model status and dimensions.
     */
    public Map<String, Object> getEmbeddingInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("modelId", vlmExecutionService.getActiveModelId());
        info.put("dimensions", getEmbeddingDimension());
        info.put("loaded", isModelLoaded());
        info.put("type", "vlm_image");
        return info;
    }
}
