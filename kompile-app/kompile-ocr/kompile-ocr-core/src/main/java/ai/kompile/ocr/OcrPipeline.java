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

import ai.kompile.ocr.document.ParsedDocument;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for complete OCR pipelines that combine detection, recognition,
 * table extraction, and layout analysis.
 *
 * <p>Pipelines provide end-to-end document processing with configurable stages.</p>
 */
public interface OcrPipeline {

    /**
     * Gets the pipeline name/identifier.
     */
    String getName();

    /**
     * Gets a description of what this pipeline does.
     */
    String getDescription();

    /**
     * Processes a single image.
     *
     * @param image Input image as INDArray [1, C, H, W]
     * @return Parsed document with all extracted information
     */
    ParsedDocument processImage(INDArray image);

    /**
     * Processes a single image with configuration.
     */
    ParsedDocument processImage(INDArray image, OcrPipelineConfig config);

    /**
     * Processes all pages of a PDF file.
     *
     * @param pdfFile PDF file to process
     * @return List of parsed documents, one per page
     */
    List<ParsedDocument> processPdf(File pdfFile);

    /**
     * Processes PDF with progress callback.
     */
    List<ParsedDocument> processPdf(File pdfFile, OcrPipelineConfig config,
                                    Consumer<PipelineProgress> progressCallback);

    /**
     * Processes PDF from input stream.
     */
    List<ParsedDocument> processPdf(InputStream pdfStream, String sourceId);

    /**
     * Processes a single page from a PDF.
     */
    ParsedDocument processPage(File pdfFile, int pageNumber);

    /**
     * Gets the models used by this pipeline.
     */
    PipelineModels getModels();

    /**
     * Checks if the pipeline is ready (all models loaded).
     */
    boolean isReady();

    /**
     * Loads all required models.
     */
    void loadModels() throws Exception;

    /**
     * Unloads all models to free resources.
     */
    void unloadModels();

    /**
     * Models used by the pipeline.
     */
    record PipelineModels(
        TextDetectionModel detectionModel,
        TextRecognitionModel recognitionModel,
        TableExtractionModel tableModel,
        LayoutModel layoutModel
    ) {
        /**
         * Checks if all required models are set.
         */
        public boolean hasRequiredModels() {
            return detectionModel != null && recognitionModel != null;
        }

        /**
         * Checks if table extraction is available.
         */
        public boolean hasTableExtraction() {
            return tableModel != null;
        }

        /**
         * Checks if layout understanding is available.
         */
        public boolean hasLayoutUnderstanding() {
            return layoutModel != null;
        }
    }

    /**
     * Progress information for pipeline processing.
     */
    record PipelineProgress(
        int currentPage,
        int totalPages,
        String currentStage,
        double overallProgress,
        String statusMessage,
        // VLM token metrics (null for non-VLM stages)
        Integer generatedTokens,
        Integer promptTokens,
        Double tokensPerSecond,
        Long generateTimeMs,
        String vlmModelId
    ) {
        public static PipelineProgress starting(int totalPages) {
            return new PipelineProgress(0, totalPages, "Starting", 0.0, "Initializing...",
                    null, null, null, null, null);
        }

        public static PipelineProgress processing(int page, int total, String stage) {
            double progress = (page - 1.0) / total * 100;
            return new PipelineProgress(page, total, stage, progress,
                    String.format("Processing page %d/%d: %s", page, total, stage),
                    null, null, null, null, null);
        }

        public static PipelineProgress completed(int total) {
            return new PipelineProgress(total, total, "Completed", 100.0, "Processing complete",
                    null, null, null, null, null);
        }

        /** Reports a sub-step within VLM page processing (Rendering, Preprocessing, Generating, Parsing). */
        public static PipelineProgress vlmStep(int page, int total, String step) {
            double progress = (page - 1.0) / total * 100;
            return new PipelineProgress(page, total, step, progress,
                    String.format("Page %d/%d: %s", page, total, step),
                    null, null, null, null, null);
        }

        /** Reports VLM page completion with token generation metrics. */
        public static PipelineProgress vlmPageCompleted(int page, int total,
                int generatedTokens, int promptTokens, double tokensPerSecond,
                long generateTimeMs, String modelId) {
            double progress = (double) page / total * 100;
            return new PipelineProgress(page, total, "Page completed", progress,
                    String.format("Page %d/%d completed: %d tokens at %.1f tok/s in %dms",
                            page, total, generatedTokens, tokensPerSecond, generateTimeMs),
                    generatedTokens, promptTokens, tokensPerSecond, generateTimeMs, modelId);
        }
    }
}
