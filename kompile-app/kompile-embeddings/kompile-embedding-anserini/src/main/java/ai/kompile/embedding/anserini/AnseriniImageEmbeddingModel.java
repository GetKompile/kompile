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

package ai.kompile.embedding.anserini;

import ai.kompile.core.embeddings.EmbeddingModel;
import io.anserini.encoder.samediff.VlmImageEncoder;
import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Image embedding model that delegates to VLM vision encoders via the staging service.
 *
 * <p>This model discovers VLM models <b>dynamically</b> from the staging service registry
 * at runtime — no static configuration required. When a staging service is connected
 * and has a VLM model loaded (SmolDocling, SigLIP, CLIP, etc.), this model automatically
 * becomes available for image embedding.</p>
 *
 * <p>Configuration is done through the UI (staging service connection), NOT via
 * application.properties. The model supports live reloading — when the staging service
 * loads or switches VLM models, this model picks up the change.</p>
 *
 * <p>The embed(String) method treats the string as an image file path and encodes
 * the image at that path via the staging service's /api/vlm/embed endpoint.</p>
 *
 * <p>This bean is always created (when anserini embedding is enabled) but remains
 * inactive until a VLM model is discovered in the staging service. Check
 * {@link #isInitialized()} before use.</p>
 */
@Service("anseriniImageEmbeddingModel")
@ConditionalOnClass(name = "ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl")
@ConditionalOnProperty(value = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
@org.springframework.context.annotation.Lazy
public class AnseriniImageEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(AnseriniImageEmbeddingModel.class);

    private volatile VlmImageEncoder imageEncoder;
    private volatile String activeModelId;
    private volatile int cachedDimensions = -1;
    private volatile String initError;
    private volatile boolean initialized = false;

    public AnseriniImageEmbeddingModel() {
        log.info("AnseriniImageEmbeddingModel created (will activate when VLM model is available in staging)");
    }

    // ==================== Dynamic Discovery ====================

    /**
     * Attempt to discover and connect to a VLM image model from the staging service.
     * Called by the startup initializer or polling mechanism when staging is available.
     *
     * @return true if a VLM model was discovered and connected
     */
    public boolean discoverFromStaging() {
        String stagingUrl = AnseriniEncoderFactory.getStagingUrl();
        if (stagingUrl == null || stagingUrl.isBlank()) {
            log.debug("No staging service configured — VLM image embedding not available");
            return false;
        }

        // Query the staging service for loaded VLM model info
        VlmImageEncoder probe = new VlmImageEncoder("probe", stagingUrl);
        if (!probe.isAvailable()) {
            log.debug("Staging service at {} has no VLM model loaded", stagingUrl);
            probe.close();
            return false;
        }

        int dim = probe.getEmbeddingDimension();
        probe.close();

        // VLM model is available — create or update the encoder
        return connectToStaging(stagingUrl, dim);
    }

    /**
     * Connect to a specific VLM model at the staging service.
     *
     * @param modelId the VLM model identifier
     * @param stagingUrl the staging service URL
     * @return true if connected successfully
     */
    public boolean connectToModel(String modelId, String stagingUrl) {
        if (stagingUrl == null || stagingUrl.isBlank()) {
            initError = "No staging URL provided";
            return false;
        }

        try {
            VlmImageEncoder newEncoder = AnseriniEncoderFactory.createImageEncoder(modelId, stagingUrl);

            if (!newEncoder.isAvailable()) {
                initError = "VLM model not available at staging service: " + stagingUrl;
                newEncoder.close();
                return false;
            }

            int dim = newEncoder.getEmbeddingDimension();

            // Swap encoder atomically
            VlmImageEncoder old = this.imageEncoder;
            this.imageEncoder = newEncoder;
            this.activeModelId = modelId;
            this.cachedDimensions = dim;
            this.initialized = true;
            this.initError = null;

            if (old != null) {
                old.close();
            }

            log.info("VLM image embedding model recognized: {}, dimensions: {}", modelId, dim);
            return true;
        } catch (Exception e) {
            initError = "Failed to connect to VLM model: " + e.getMessage();
            log.warn("VLM image embedding connection failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Disconnect from the current VLM model.
     */
    public void disconnect() {
        VlmImageEncoder old = this.imageEncoder;
        this.imageEncoder = null;
        this.activeModelId = null;
        this.cachedDimensions = -1;
        this.initialized = false;
        this.initError = null;

        if (old != null) {
            old.close();
            log.info("VLM image embedding model disconnected");
        }
    }

    /**
     * Refresh the connection — re-probe the staging service.
     * Used for live reloading when staging models change.
     *
     * @return true if still connected or reconnected
     */
    public boolean refresh() {
        return discoverFromStaging();
    }

    // ==================== Private Discovery Helpers ====================

    private boolean connectToStaging(String stagingUrl, int dimension) {
        try {
            // Use the staging URL to determine the model ID from the info endpoint
            VlmImageEncoder newEncoder = new VlmImageEncoder("vlm-image", stagingUrl);

            VlmImageEncoder old = this.imageEncoder;
            this.imageEncoder = newEncoder;
            this.activeModelId = "vlm-image"; // Will be updated from info endpoint
            this.cachedDimensions = dimension;
            this.initialized = true;
            this.initError = null;

            if (old != null) {
                old.close();
            }

            log.info("VLM image embedding model recognized: staging service at {}, dimensions: {}",
                    stagingUrl, dimension);
            return true;
        } catch (Exception e) {
            initError = "Failed to connect: " + e.getMessage();
            log.warn("VLM image embedding connection failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== EmbeddingModel Interface ====================

    /**
     * Embed an image file at the given path.
     *
     * @param imagePath path to the image file
     * @return INDArray with the embedding (1 x dimensions)
     */
    @Override
    public INDArray embed(String imagePath) {
        if (imageEncoder == null) {
            log.error("VLM image encoder not available. Configure staging service and load a VLM model.");
            return Nd4j.empty();
        }

        try {
            File imageFile = new File(imagePath);
            if (!imageFile.isFile()) {
                log.error("Image file not found: {}", imagePath);
                return Nd4j.empty();
            }

            float[] embedding = imageEncoder.encodeImage(imageFile);
            cachedDimensions = embedding.length;
            return Nd4j.create(embedding).reshape(1, embedding.length);
        } catch (IOException e) {
            log.error("Failed to embed image: {}", imagePath, e);
            return Nd4j.empty();
        }
    }

    @Override
    public INDArray embed(List<String> imagePaths) {
        if (imageEncoder == null || imagePaths == null || imagePaths.isEmpty()) {
            return Nd4j.empty();
        }

        List<float[]> embeddings = new ArrayList<>();
        for (String path : imagePaths) {
            try {
                File imageFile = new File(path);
                if (imageFile.isFile()) {
                    embeddings.add(imageEncoder.encodeImage(imageFile));
                } else {
                    log.warn("Skipping non-existent image: {}", path);
                    embeddings.add(new float[0]);
                }
            } catch (IOException e) {
                log.error("Failed to embed image: {}", path, e);
                embeddings.add(new float[0]);
            }
        }

        if (embeddings.isEmpty() || embeddings.get(0).length == 0) {
            return Nd4j.empty();
        }

        int dim = embeddings.get(0).length;
        cachedDimensions = dim;
        float[][] matrix = new float[embeddings.size()][dim];
        for (int i = 0; i < embeddings.size(); i++) {
            float[] emb = embeddings.get(i);
            if (emb.length == dim) {
                matrix[i] = emb;
            }
        }
        return Nd4j.create(matrix);
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        List<String> paths = new ArrayList<>();
        for (Document doc : documents) {
            String path = doc.getMetadata() != null ?
                    (String) doc.getMetadata().get("source") : null;
            if (path == null) {
                path = doc.getText();
            }
            paths.add(path);
        }
        return embed(paths);
    }

    @Override
    public int dimensions() {
        return cachedDimensions > 0 ? cachedDimensions : -1;
    }

    @Override
    public String getModelName() {
        return "VLM Image Embedding";
    }

    @Override
    public String getModelIdentifier() {
        return activeModelId != null ? activeModelId : "vlm-image";
    }

    @Override
    public boolean isInitialized() {
        return initialized && imageEncoder != null;
    }

    @Override
    public String getInitializationError() {
        return initError;
    }

    /**
     * Get the active VLM model ID from the staging service.
     */
    public String getActiveModelId() {
        return activeModelId;
    }

    @PreDestroy
    @Override
    public void close() {
        disconnect();
    }
}
