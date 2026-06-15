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

package ai.kompile.core.loaders;

import java.io.File;
import java.util.List;

/**
 * Classifies PDFs by content type to enable dynamic routing between
 * text extraction and VLM pipelines.
 *
 * <p>Text-only PDFs can be handled by the fast, cheap text extraction
 * pipeline with Tabula for table detection. PDFs containing images
 * (embedded photos, scanned pages, diagrams) must be routed to the
 * VLM pipeline for accurate content extraction.</p>
 *
 * <p>Classification is fast and non-destructive — it inspects PDF page
 * resources without rendering pages or running OCR.</p>
 */
public interface PdfContentClassifier {

    /**
     * Classify a single PDF file.
     *
     * @param pdfFile the PDF file to classify
     * @return classification result with content type and page details
     */
    PdfClassificationResult classify(File pdfFile);

    /**
     * Classify multiple PDF files in batch.
     * Default implementation delegates to single-file classify.
     *
     * @param pdfFiles list of PDF files to classify
     * @return classification results in the same order as input
     */
    default List<PdfClassificationResult> classifyBatch(List<File> pdfFiles) {
        return pdfFiles.stream().map(this::classify).toList();
    }
}
