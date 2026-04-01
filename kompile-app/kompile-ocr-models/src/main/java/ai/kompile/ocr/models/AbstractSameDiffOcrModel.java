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

package ai.kompile.ocr.models;

import ai.kompile.ocr.OcrModel;
import ai.kompile.ocr.OcrModelType;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for SameDiff-based OCR models.
 * Provides common functionality for model loading, inference, and resource management.
 */
public abstract class AbstractSameDiffOcrModel implements OcrModel {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final String modelId;
    protected final String name;
    protected final String description;
    protected final OcrModelType modelType;

    protected SameDiff model;
    protected boolean loaded = false;
    protected ModelCapabilities capabilities;

    protected AbstractSameDiffOcrModel(String modelId, String name, String description,
                                       OcrModelType modelType) {
        this.modelId = modelId;
        this.name = name;
        this.description = description;
        this.modelType = modelType;
        this.capabilities = ModelCapabilities.defaultFor(modelType);
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public OcrModelType getModelType() {
        return modelType;
    }

    @Override
    public boolean isLoaded() {
        return loaded && model != null;
    }

    @Override
    public ModelCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public void load() throws Exception {
        if (loaded) {
            logger.debug("Model {} is already loaded", modelId);
            return;
        }

        logger.info("Loading OCR model: {} ({})", name, modelId);
        long startTime = System.currentTimeMillis();

        try {
            File modelFile = getModelFile();
            if (modelFile == null || !modelFile.exists()) {
                throw new IllegalStateException("Model file not found for: " + modelId);
            }

            model = loadSameDiffModel(modelFile);
            loaded = true;

            long loadTime = System.currentTimeMillis() - startTime;
            logger.info("Loaded OCR model {} in {}ms", modelId, loadTime);

        } catch (Exception e) {
            logger.error("Failed to load OCR model {}: {}", modelId, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void unload() {
        if (!loaded) {
            return;
        }

        logger.info("Unloading OCR model: {}", modelId);
        try {
            if (model != null) {
                // Clean up model resources
                model = null;
            }
            loaded = false;
        } catch (Exception e) {
            logger.warn("Error unloading model {}: {}", modelId, e.getMessage());
        }
    }

    /**
     * Gets the model file from the model manager.
     * Subclasses should implement this to provide the model file location.
     */
    protected abstract File getModelFile() throws Exception;

    /**
     * Loads a SameDiff model from a file.
     * Default implementation loads from FlatBuffers format.
     */
    protected SameDiff loadSameDiffModel(File modelFile) throws Exception {
        String fileName = modelFile.getName().toLowerCase();
        if (fileName.endsWith(".onnx")) {
            return loadOnnxModel(modelFile);
        } else if (fileName.endsWith(".fb") || fileName.endsWith(".flatbuffers")) {
            return SameDiff.fromFlatFile(modelFile);
        } else {
            // Try loading as FlatBuffers by default
            return SameDiff.fromFlatFile(modelFile);
        }
    }

    /**
     * Loads an ONNX model.
     */
    protected SameDiff loadOnnxModel(File onnxFile) throws Exception {
        // Import ONNX model using the importer instance
        org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter importer =
                new org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter();
        return importer.runImport(onnxFile.getAbsolutePath(), java.util.Collections.emptyMap(), false, false);
    }

    /**
     * Runs inference on the model.
     */
    protected Map<String, INDArray> runInference(Map<String, INDArray> inputs) {
        if (!isLoaded()) {
            throw new IllegalStateException("Model not loaded: " + modelId);
        }
        return model.output(inputs, model.outputs());
    }

    /**
     * Preprocesses an image for model input.
     * Default implementation normalizes to [0,1] and applies ImageNet normalization.
     */
    protected INDArray preprocessImage(INDArray image, float[] mean, float[] std) {
        // Ensure float type
        INDArray processed = image.castTo(org.nd4j.linalg.api.buffer.DataType.FLOAT);

        // Normalize to [0,1] if in [0,255]
        if (processed.maxNumber().floatValue() > 1.0f) {
            processed = processed.div(255.0f);
        }

        // Apply channel-wise normalization
        if (mean != null && std != null) {
            // Assuming NCHW format
            for (int c = 0; c < Math.min(mean.length, processed.size(1)); c++) {
                INDArray channel = processed.get(
                        org.nd4j.linalg.indexing.NDArrayIndex.all(),
                        org.nd4j.linalg.indexing.NDArrayIndex.point(c),
                        org.nd4j.linalg.indexing.NDArrayIndex.all(),
                        org.nd4j.linalg.indexing.NDArrayIndex.all()
                );
                channel.subi(mean[c]).divi(std[c]);
            }
        }

        return processed;
    }

    /**
     * Resizes an image to target dimensions.
     */
    protected INDArray resizeImage(INDArray image, int targetHeight, int targetWidth) {
        // Get current dimensions
        long[] shape = image.shape();
        if (shape.length != 4) {
            throw new IllegalArgumentException("Expected 4D tensor [N,C,H,W], got " + shape.length + "D");
        }

        int currentHeight = (int) shape[2];
        int currentWidth = (int) shape[3];

        if (currentHeight == targetHeight && currentWidth == targetWidth) {
            return image;
        }

        // Use ND4J image resize - size as [height, width] array
        INDArray size = Nd4j.createFromArray(new int[]{targetHeight, targetWidth});
        return Nd4j.image().imageResize(image, size,
                org.nd4j.enums.ImageResizeMethod.ResizeBilinear);
    }

    /**
     * Sets the model capabilities.
     */
    protected void setCapabilities(ModelCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Gets the input names for the model.
     */
    protected List<String> getInputNames() {
        if (model == null) {
            return List.of();
        }
        return model.inputs();
    }

    /**
     * Gets the output names for the model.
     */
    protected List<String> getOutputNames() {
        if (model == null) {
            return List.of();
        }
        return model.outputs();
    }
}
