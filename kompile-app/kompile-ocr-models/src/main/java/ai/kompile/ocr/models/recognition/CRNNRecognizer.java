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

package ai.kompile.ocr.models.recognition;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.ocr.OcrModelType;
import ai.kompile.ocr.TextRecognitionModel;
import ai.kompile.ocr.models.AbstractSameDiffOcrModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
 * CRNN (Convolutional Recurrent Neural Network) text recognizer.
 * This is a classic architecture used in many OCR systems including PaddleOCR and Tesseract.
 *
 * <p>CRNN combines CNN for feature extraction with RNN (LSTM/GRU) for sequence modeling,
 * followed by CTC decoding.</p>
 */
public class CRNNRecognizer extends AbstractSameDiffOcrModel implements TextRecognitionModel {

    private static final String MODEL_ID = "crnn-v2";
    private static final String MODEL_NAME = "CRNN Text Recognizer";
    private static final String MODEL_DESC = "Convolutional Recurrent Neural Network for text recognition";

    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};
    private static final int INPUT_HEIGHT = 32;

    private final KompileModelManager modelManager;
    private List<String> vocabulary;
    private Map<Integer, String> indexToChar;
    private int blankIndex = 0;

    public CRNNRecognizer(KompileModelManager modelManager) {
        super(MODEL_ID, MODEL_NAME, MODEL_DESC, OcrModelType.OCR_RECOGNITION);
        this.modelManager = modelManager;

        setCapabilities(ModelCapabilities.builder()
                .type(OcrModelType.OCR_RECOGNITION)
                .supportsBatch(true)
                .supportsHandwriting(false)
                .supportedLanguages(List.of("en"))
                .maxBatchSize(32)
                .inputHeight(INPUT_HEIGHT)
                .inputWidth(-1)  // Variable width
                .averageAccuracy(0.95)
                .build());
    }

    @Override
    public void load() throws Exception {
        super.load();
        loadVocabulary();
    }

    @Override
    protected File getModelFile() throws Exception {
        return modelManager.getModelFile(MODEL_ID);
    }

    /**
     * Loads the character vocabulary from file.
     */
    private void loadVocabulary() throws Exception {
        File vocabFile = modelManager.getVocabularyFile(MODEL_ID);

        vocabulary = new ArrayList<>();
        indexToChar = new HashMap<>();

        if (vocabFile != null && vocabFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(vocabFile))) {
                String line;
                int index = 0;
                while ((line = reader.readLine()) != null) {
                    vocabulary.add(line);
                    indexToChar.put(index, line);
                    if (line.equals("[blank]") || line.equals("<blank>") || line.isEmpty()) {
                        blankIndex = index;
                    }
                    index++;
                }
            }
        } else {
            // Default ASCII vocabulary
            vocabulary.add("");  // blank token
            for (char c = ' '; c <= '~'; c++) {
                vocabulary.add(String.valueOf(c));
                indexToChar.put(vocabulary.size() - 1, String.valueOf(c));
            }
        }

        logger.info("Loaded vocabulary with {} characters", vocabulary.size());
    }

    @Override
    public RecognizedText recognize(INDArray regionImage) {
        if (!isLoaded()) {
            try {
                load();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load model: " + e.getMessage(), e);
            }
        }

        // Get original dimensions
        long[] shape = regionImage.shape();
        int originalHeight = (int) shape[2];
        int originalWidth = (int) shape[3];

        // Resize to fixed height, keeping aspect ratio
        float scale = (float) INPUT_HEIGHT / originalHeight;
        int targetWidth = Math.round(originalWidth * scale);
        // Make width divisible by 4 for CNN compatibility
        targetWidth = Math.max(4, (targetWidth / 4) * 4);

        INDArray resized = resizeImage(regionImage, INPUT_HEIGHT, targetWidth);

        // Preprocess
        INDArray preprocessed = preprocessImage(resized, MEAN, STD);

        // Run inference
        Map<String, INDArray> inputs = new HashMap<>();
        inputs.put(getInputNames().isEmpty() ? "input" : getInputNames().get(0), preprocessed);

        Map<String, INDArray> outputs = runInference(inputs);

        // Get logits (usually shape [1, T, num_classes])
        INDArray logits = outputs.values().iterator().next();

        // CTC decode
        RecognizedText result = ctcDecode(logits);

        // Cleanup
        resized.close();
        preprocessed.close();

        return result;
    }

    @Override
    public List<RecognizedText> recognizeBatch(INDArray regionImages) {
        List<RecognizedText> results = new ArrayList<>();
        long batchSize = regionImages.size(0);

        for (int i = 0; i < batchSize; i++) {
            INDArray single = regionImages.get(
                            org.nd4j.linalg.indexing.NDArrayIndex.point(i),
                            org.nd4j.linalg.indexing.NDArrayIndex.all(),
                            org.nd4j.linalg.indexing.NDArrayIndex.all(),
                            org.nd4j.linalg.indexing.NDArrayIndex.all())
                    .reshape(1, regionImages.size(1), regionImages.size(2), regionImages.size(3));
            results.add(recognize(single));
        }

        return results;
    }

    @Override
    public List<String> getVocabulary() {
        return Collections.unmodifiableList(vocabulary);
    }

    @Override
    public int getExpectedHeight() {
        return INPUT_HEIGHT;
    }

    /**
     * CTC greedy decoding.
     */
    private RecognizedText ctcDecode(INDArray logits) {
        // logits shape: [1, T, num_classes] or [T, num_classes]
        long[] shape = logits.shape();
        int T = shape.length == 3 ? (int) shape[1] : (int) shape[0];
        int numClasses = shape.length == 3 ? (int) shape[2] : (int) shape[1];

        StringBuilder text = new StringBuilder();
        List<CharPrediction> predictions = new ArrayList<>();
        int prevIndex = -1;
        double totalConfidence = 0;
        int charCount = 0;

        for (int t = 0; t < T; t++) {
            // Get probabilities for this timestep
            INDArray probs;
            if (shape.length == 3) {
                probs = logits.get(
                        org.nd4j.linalg.indexing.NDArrayIndex.point(0),
                        org.nd4j.linalg.indexing.NDArrayIndex.point(t),
                        org.nd4j.linalg.indexing.NDArrayIndex.all()
                );
            } else {
                probs = logits.get(
                        org.nd4j.linalg.indexing.NDArrayIndex.point(t),
                        org.nd4j.linalg.indexing.NDArrayIndex.all()
                );
            }

            // Apply softmax if not already
            INDArray softmax = Nd4j.nn().softmax(probs, 0);

            // Get best index
            int bestIndex = Nd4j.argMax(softmax, 0).getInt(0);
            double bestProb = softmax.getDouble(bestIndex);

            // CTC decoding: skip blanks and repeated characters
            if (bestIndex != blankIndex && bestIndex != prevIndex) {
                String c = indexToChar.getOrDefault(bestIndex, "?");
                text.append(c);

                // Get alternatives
                List<CharAlternative> alternatives = getAlternatives(softmax, bestIndex);

                predictions.add(new CharPrediction(
                        c.isEmpty() ? ' ' : c.charAt(0),
                        bestProb,
                        alternatives
                ));

                totalConfidence += bestProb;
                charCount++;
            }

            prevIndex = bestIndex;
        }

        double avgConfidence = charCount > 0 ? totalConfidence / charCount : 0.0;

        return new RecognizedText(text.toString(), avgConfidence, predictions);
    }

    /**
     * Gets top-k alternative predictions for a character.
     */
    private List<CharAlternative> getAlternatives(INDArray probs, int bestIndex) {
        List<CharAlternative> alternatives = new ArrayList<>();

        // Create list of (index, probability) pairs
        List<int[]> indexedProbs = new ArrayList<>();
        for (int i = 0; i < probs.length(); i++) {
            indexedProbs.add(new int[]{i, (int)(probs.getDouble(i) * 10000)});
        }

        // Sort by probability descending
        indexedProbs.sort((a, b) -> Integer.compare(b[1], a[1]));

        // Get top 3 alternatives (excluding best and blank)
        int count = 0;
        for (int[] pair : indexedProbs) {
            if (count >= 3) break;
            int idx = pair[0];
            if (idx != bestIndex && idx != blankIndex) {
                String c = indexToChar.getOrDefault(idx, "?");
                double prob = probs.getDouble(idx);
                if (prob > 0.01) {
                    alternatives.add(new CharAlternative(
                            c.isEmpty() ? ' ' : c.charAt(0),
                            prob
                    ));
                    count++;
                }
            }
        }

        return alternatives;
    }
}
