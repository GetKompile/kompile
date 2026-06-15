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

package ai.kompile.loader.pdf;

import ai.kompile.core.loaders.PdfClassificationResult;
import ai.kompile.core.loaders.PdfClassificationResult.PdfContentType;
import ai.kompile.core.loaders.PdfContentClassifier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDFBox-based PDF content classifier that detects embedded images
 * without rendering pages.
 *
 * <p>Inspects each page's XObject resources for {@link PDImageXObject}
 * instances. Also does a fast text strip to measure extractable text
 * volume. Classification is typically under 100ms for a 50-page PDF.</p>
 *
 * <p>Classification rules:</p>
 * <ul>
 *   <li><b>TEXT_ONLY</b>: No image XObjects on any page</li>
 *   <li><b>IMAGE_BASED</b>: Images on every page, or images present and
 *       text extraction yields fewer than {@link #SCANNED_TEXT_THRESHOLD}
 *       chars per page</li>
 *   <li><b>MIXED</b>: Some pages have images, others are text-only,
 *       and there is enough text to warrant partial text extraction</li>
 * </ul>
 */
@Component
public class PdfContentClassifierImpl implements PdfContentClassifier {

    private static final Logger log = LoggerFactory.getLogger(PdfContentClassifierImpl.class);

    /**
     * Minimum average characters per page to consider a PDF as having
     * meaningful extractable text. Below this threshold, pages with images
     * are considered scanned.
     */
    private static final int SCANNED_TEXT_THRESHOLD = 50;

    @Override
    public PdfClassificationResult classify(File pdfFile) {
        long startTime = System.currentTimeMillis();
        String path = pdfFile.getAbsolutePath();

        if (!pdfFile.exists() || !pdfFile.canRead()) {
            return PdfClassificationResult.builder()
                    .contentType(PdfContentType.UNKNOWN)
                    .sourcePath(path)
                    .classificationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                return PdfClassificationResult.builder()
                        .contentType(PdfContentType.UNKNOWN)
                        .pageCount(0)
                        .sourcePath(path)
                        .classificationTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            List<Integer> imagePageIndices = new ArrayList<>();
            boolean hasScannedPages = false;

            // Pass 1: Scan page resources for image XObjects
            for (int i = 0; i < pageCount; i++) {
                PDPage page = document.getPage(i);
                if (pageContainsImages(page)) {
                    imagePageIndices.add(i);
                }
            }

            boolean hasImages = !imagePageIndices.isEmpty();

            // Pass 2: Fast text extraction to measure extractable content
            long textCharCount = 0;
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                textCharCount = text != null ? text.length() : 0;
            } catch (Exception e) {
                log.debug("Text extraction failed during classification of {}: {}", path, e.getMessage());
            }

            // Determine if image pages are scanned (images but no text)
            if (hasImages && pageCount > 0) {
                long avgCharsPerPage = textCharCount / pageCount;
                if (avgCharsPerPage < SCANNED_TEXT_THRESHOLD) {
                    hasScannedPages = true;
                }
            }

            // Classify
            PdfContentType contentType;
            if (!hasImages) {
                contentType = PdfContentType.TEXT_ONLY;
            } else if (imagePageIndices.size() == pageCount || hasScannedPages) {
                contentType = PdfContentType.IMAGE_BASED;
            } else {
                contentType = PdfContentType.MIXED;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("PDF classified: {} -> {} ({} pages, {} image pages, {} chars) in {}ms",
                    pdfFile.getName(), contentType, pageCount, imagePageIndices.size(), textCharCount, elapsed);

            return PdfClassificationResult.builder()
                    .contentType(contentType)
                    .pageCount(pageCount)
                    .imagePagesCount(imagePageIndices.size())
                    .imagePageIndices(List.copyOf(imagePageIndices))
                    .textCharCount(textCharCount)
                    .hasImages(hasImages)
                    .hasScannedPages(hasScannedPages)
                    .classificationTimeMs(elapsed)
                    .sourcePath(path)
                    .build();

        } catch (IOException e) {
            log.warn("Failed to classify PDF {}: {}", path, e.getMessage());
            return PdfClassificationResult.builder()
                    .contentType(PdfContentType.UNKNOWN)
                    .sourcePath(path)
                    .classificationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * Check if a PDF page contains any image XObjects in its resources.
     * This does NOT render the page — it only inspects the resource dictionary.
     */
    private boolean pageContainsImages(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) {
            return false;
        }

        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDImageXObject) {
                    return true;
                }
            } catch (IOException e) {
                // Corrupt XObject — skip but don't fail classification
                log.trace("Could not inspect XObject {} on page: {}", name, e.getMessage());
            }
        }

        return false;
    }
}
