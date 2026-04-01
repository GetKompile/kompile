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

package ai.kompile.ocr.document;

import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.File;

/**
 * Interface for analyzing documents to determine type, complexity, and optimal processing strategy.
 * This analysis happens before the main OCR pipeline to route documents appropriately.
 */
public interface DocumentAnalyzer {

    /**
     * Analyzes a document image to determine its characteristics.
     *
     * @param image Document image as INDArray [1, C, H, W]
     * @return Analysis result with type, complexity, and recommendations
     */
    DocumentAnalysis analyze(INDArray image);

    /**
     * Analyzes a PDF file to determine characteristics of all pages.
     *
     * @param pdfFile PDF file to analyze
     * @return Analysis result for the entire document
     */
    DocumentAnalysis analyzePdf(File pdfFile);

    /**
     * Analyzes a specific page from a PDF.
     *
     * @param pdfFile PDF file
     * @param pageNumber Page number (1-indexed)
     * @return Analysis result for the page
     */
    DocumentAnalysis analyzePage(File pdfFile, int pageNumber);

    /**
     * Document analysis result.
     */
    record DocumentAnalysis(
        DocumentType documentType,
        DocumentComplexity complexity,
        boolean requiresOcr,
        boolean hasEmbeddedText,
        int pageCount,
        double textDensity,
        double imageDensity,
        PipelineRecommendation recommendation,
        PerPageAnalysis[] pageAnalyses
    ) {
        /**
         * Creates a simple analysis for a single page.
         */
        public static DocumentAnalysis singlePage(DocumentType type, DocumentComplexity complexity,
                                                  boolean requiresOcr) {
            return new DocumentAnalysis(type, complexity, requiresOcr, !requiresOcr,
                    1, 0.5, 0.5,
                    PipelineRecommendation.forComplexity(complexity), null);
        }

        /**
         * Checks if any page requires OCR.
         */
        public boolean anyPageRequiresOcr() {
            if (requiresOcr) return true;
            if (pageAnalyses == null) return requiresOcr;
            for (PerPageAnalysis page : pageAnalyses) {
                if (page.requiresOcr) return true;
            }
            return false;
        }
    }

    /**
     * Per-page analysis for multi-page documents.
     */
    record PerPageAnalysis(
        int pageNumber,
        DocumentType type,
        DocumentComplexity complexity,
        boolean requiresOcr,
        double textDensity,
        double imageDensity
    ) {}

    /**
     * Recommended pipeline configuration based on analysis.
     */
    record PipelineRecommendation(
        String detectionModelId,
        String recognitionModelId,
        String tableModelId,
        String layoutModelId,
        boolean enableTableExtraction,
        boolean enableLayoutAnalysis,
        boolean enableLlmPostProcessing
    ) {
        /**
         * Creates a recommendation based on complexity.
         */
        public static PipelineRecommendation forComplexity(DocumentComplexity complexity) {
            return new PipelineRecommendation(
                "dbnet-v2",                                   // default detection
                "crnn-v2",                                    // default recognition
                complexity.recommendsTableExtraction() ? "tableformer-v1" : null,
                complexity.recommendsLayoutAnalysis() ? "layoutlm-base" : null,
                complexity.recommendsTableExtraction(),
                complexity.recommendsLayoutAnalysis(),
                complexity.recommendsLlmPostProcessing()
            );
        }

        /**
         * Creates a minimal recommendation.
         */
        public static PipelineRecommendation minimal() {
            return new PipelineRecommendation(
                "dbnet-v2", "crnn-v2",
                null, null, false, false, false
            );
        }

        /**
         * Creates a full-featured recommendation.
         */
        public static PipelineRecommendation full() {
            return new PipelineRecommendation(
                "dbnet-v2", "crnn-v2",
                "tableformer-v1", "layoutlm-v3",
                true, true, true
            );
        }
    }
}
