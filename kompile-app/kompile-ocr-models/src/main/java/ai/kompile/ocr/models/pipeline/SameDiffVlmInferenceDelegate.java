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

package ai.kompile.ocr.models.pipeline;

import ai.kompile.modelmanager.vlm.VlmExtractionConfig;
import ai.kompile.modelmanager.vlm.VlmInferenceDelegate;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.SamplingConfig;
import org.eclipse.deeplearning4j.vlm.model.VisionLanguageModel;
import org.eclipse.deeplearning4j.vlm.preprocessing.VLMImagePreprocessor;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SameDiff-based implementation of {@link VlmInferenceDelegate}.
 *
 * <p>Uses DL4J's {@link VisionLanguageModel} for model loading and inference.
 * Delegates all generation to VLM's own pipeline which handles the full
 * vision encoder → embedding merge → decode flow.</p>
 */
@Component
public class SameDiffVlmInferenceDelegate implements VlmInferenceDelegate {

    private static final Logger log = LoggerFactory.getLogger(SameDiffVlmInferenceDelegate.class);

    private final Map<String, VisionLanguageModel> loadedModels = new ConcurrentHashMap<>();
    private final Map<String, VLMImagePreprocessor> preprocessors = new ConcurrentHashMap<>();

    @Override
    public String runDocumentUnderstanding(BufferedImage image, String modelId,
                                            VlmExtractionConfig config,
                                            Consumer<String> progressCallback) {
        VisionLanguageModel vlm = getOrLoadModel(modelId);
        if (vlm == null) {
            log.warn("VLM model not available for document understanding: {}", modelId);
            return null;
        }

        try {
            if (progressCallback != null) {
                progressCallback.accept("Preprocessing image...");
            }

            VLMImagePreprocessor preprocessor = preprocessors.get(modelId);
            INDArray imageArray = bufferedImageToINDArray(image);
            INDArray preprocessed = preprocessor != null ? preprocessor.preprocess(imageArray) : imageArray;

            SamplingConfig samplingConfig = buildSamplingConfig(config);

            if (progressCallback != null) {
                progressCallback.accept("Running VLM generation...");
            }

            GenerationResult genResult = vlm.generateWithMetrics(
                    preprocessed,
                    buildPrompt(config.getOutputFormat()),
                    samplingConfig.getMaxNewTokens(),
                    samplingConfig.getTemperature(),
                    samplingConfig.isDoSample()
            );

            if (preprocessed != imageArray) {
                preprocessed.close();
            }
            imageArray.close();

            return genResult.getText();

        } catch (Exception e) {
            log.error("Document understanding inference failed: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> runTableExtraction(BufferedImage image, String modelId,
                                                          VlmExtractionConfig config,
                                                          Consumer<String> progressCallback) {
        VisionLanguageModel vlm = getOrLoadModel(modelId);
        if (vlm == null) {
            return Collections.emptyList();
        }

        try {
            INDArray imageArray = bufferedImageToINDArray(image);
            VLMImagePreprocessor preprocessor = preprocessors.get(modelId);
            INDArray preprocessed = preprocessor != null ? preprocessor.preprocess(imageArray) : imageArray;

            String tablePrompt = "Extract all tables from this document in markdown format.";
            String tableText = vlm.generate(preprocessed, tablePrompt, config.getMaxNewTokens(), 0.0, false);

            if (preprocessed != imageArray) {
                preprocessed.close();
            }
            imageArray.close();

            if (tableText != null && !tableText.isEmpty()) {
                Map<String, Object> tableMap = new HashMap<>();
                tableMap.put("markdown", tableText);
                tableMap.put("rowCount", 0);
                tableMap.put("columnCount", 0);
                return List.of(tableMap);
            }

        } catch (Exception e) {
            log.error("Table extraction inference failed: {}", e.getMessage(), e);
        }

        return Collections.emptyList();
    }

    @Override
    public float[] runImageEmbedding(BufferedImage image, String modelId) {
        log.debug("Image embedding via VLM is model-specific; returning null for {}", modelId);
        return null;
    }

    @Override
    public Map<String, String> runFormExtraction(BufferedImage image, String modelId,
                                                   VlmExtractionConfig config,
                                                   Consumer<String> progressCallback) {
        VisionLanguageModel vlm = getOrLoadModel(modelId);
        if (vlm == null) {
            return Collections.emptyMap();
        }

        try {
            INDArray imageArray = bufferedImageToINDArray(image);
            VLMImagePreprocessor preprocessor = preprocessors.get(modelId);
            INDArray preprocessed = preprocessor != null ? preprocessor.preprocess(imageArray) : imageArray;

            String formPrompt = "Extract all form fields and their values as key-value pairs in JSON format.";
            String formJson = vlm.generate(preprocessed, formPrompt, config.getMaxNewTokens(), 0.0, false);

            if (preprocessed != imageArray) {
                preprocessed.close();
            }
            imageArray.close();

            if (formJson != null && !formJson.isEmpty()) {
                Map<String, String> result = new HashMap<>();
                result.put("raw_output", formJson);
                return result;
            }

        } catch (Exception e) {
            log.error("Form extraction inference failed: {}", e.getMessage(), e);
        }

        return Collections.emptyMap();
    }

    @Override
    public boolean isAvailable() {
        return !loadedModels.isEmpty();
    }

    @Override
    public List<String> getSupportedModelIds() {
        return new ArrayList<>(loadedModels.keySet());
    }

    /**
     * Register a pre-loaded VLM model with this delegate.
     */
    public void registerModel(String modelId, VisionLanguageModel vlm, VLMImagePreprocessor preprocessor) {
        loadedModels.put(modelId, vlm);
        if (preprocessor != null) {
            preprocessors.put(modelId, preprocessor);
        }
        log.info("Registered VLM model: {}", modelId);
    }

    /**
     * Load a VLM model from a directory.
     */
    public void loadModel(String modelId, File modelDirectory) throws Exception {
        VisionLanguageModel vlm = VisionLanguageModel.fromDirectory(modelDirectory);
        VLMImagePreprocessor preprocessor = vlm.getImagePreprocessor();
        if (preprocessor == null) {
            File configFile = new File(modelDirectory, "preprocessor_config.json");
            if (configFile.exists()) {
                preprocessor = VLMImagePreprocessor.fromConfig(configFile);
            }
        }
        registerModel(modelId, vlm, preprocessor);
    }

    private VisionLanguageModel getOrLoadModel(String modelId) {
        return loadedModels.get(modelId);
    }

    private SamplingConfig buildSamplingConfig(VlmExtractionConfig config) {
        String preset = config.getSamplingPreset();
        if (preset != null) {
            switch (preset.toLowerCase()) {
                case "creative":
                    return SamplingConfig.builder()
                            .temperature(0.9).topK(50).topP(0.95).doSample(true)
                            .maxNewTokens(config.getMaxNewTokens())
                            .build();
                case "precise":
                    return SamplingConfig.builder()
                            .temperature(0.3).topP(0.85).doSample(true)
                            .maxNewTokens(config.getMaxNewTokens())
                            .build();
            }
        }

        return SamplingConfig.builder()
                .temperature(config.getTemperature())
                .topP(config.getTopP())
                .topK(config.getTopK())
                .repetitionPenalty(config.getRepetitionPenalty())
                .doSample(config.isDoSample())
                .maxNewTokens(config.getMaxNewTokens())
                .build();
    }

    private String buildPrompt(String outputFormat) {
        if (outputFormat == null) outputFormat = "MARKDOWN";
        switch (outputFormat.toUpperCase()) {
            case "DOCTAGS":
                return "Convert this document to DocTags format with structure tags and bounding boxes.";
            case "JSON":
                return "Extract the document content as structured JSON with text, tables, and figures.";
            case "TEXT":
                return "Extract all text from this document.";
            case "MARKDOWN":
            default:
                return "Convert this document to Markdown format, preserving headers, lists, and tables.";
        }
    }

    private INDArray bufferedImageToINDArray(BufferedImage image) {
        int h = image.getHeight();
        int w = image.getWidth();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

        float[] data = new float[3 * h * w];
        int hwSize = h * w;
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            data[i] = ((rgb >> 16) & 0xFF) / 255.0f;
            data[hwSize + i] = ((rgb >> 8) & 0xFF) / 255.0f;
            data[2 * hwSize + i] = (rgb & 0xFF) / 255.0f;
        }

        return Nd4j.create(data, new int[]{1, 3, h, w}, 'c');
    }
}
