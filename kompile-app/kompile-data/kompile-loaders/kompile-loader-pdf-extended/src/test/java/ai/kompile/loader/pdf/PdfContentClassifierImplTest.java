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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfContentClassifierImplTest {

    @TempDir
    Path tempDir;

    private final PdfContentClassifierImpl classifier = new PdfContentClassifierImpl();

    @Test
    void testClassifyTextOnlyPdf() throws Exception {
        File pdf = createTextOnlyPdf("text-only.pdf", 3);

        PdfClassificationResult result = classifier.classify(pdf);

        assertEquals(PdfContentType.TEXT_ONLY, result.contentType());
        assertTrue(result.isTextOnly());
        assertFalse(result.requiresVlm());
        assertEquals(3, result.pageCount());
        assertEquals(0, result.imagePagesCount());
        assertTrue(result.imagePageIndices().isEmpty());
        assertFalse(result.hasImages());
        assertFalse(result.hasScannedPages());
        assertTrue(result.textCharCount() > 0);
        assertTrue(result.classificationTimeMs() >= 0);
        assertEquals(pdf.getAbsolutePath(), result.sourcePath());
    }

    @Test
    void testClassifyImageBasedPdf() throws Exception {
        File pdf = createImageOnlyPdf("image-based.pdf", 2);

        PdfClassificationResult result = classifier.classify(pdf);

        assertEquals(PdfContentType.IMAGE_BASED, result.contentType());
        assertFalse(result.isTextOnly());
        assertTrue(result.requiresVlm());
        assertEquals(2, result.pageCount());
        assertEquals(2, result.imagePagesCount());
        assertEquals(List.of(0, 1), result.imagePageIndices());
        assertTrue(result.hasImages());
        assertTrue(result.hasScannedPages());
    }

    @Test
    void testClassifyMixedPdf() throws Exception {
        File pdf = createMixedPdf("mixed.pdf");

        PdfClassificationResult result = classifier.classify(pdf);

        assertEquals(PdfContentType.MIXED, result.contentType());
        assertFalse(result.isTextOnly());
        assertTrue(result.requiresVlm());
        assertEquals(3, result.pageCount());
        assertEquals(1, result.imagePagesCount());
        assertTrue(result.hasImages());
        assertFalse(result.hasScannedPages());
        assertTrue(result.textCharCount() > 0);
    }

    @Test
    void testClassifyEmptyPdf() throws Exception {
        File pdf = tempDir.resolve("empty.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            doc.save(pdf);
        }

        PdfClassificationResult result = classifier.classify(pdf);

        assertEquals(PdfContentType.UNKNOWN, result.contentType());
        assertEquals(0, result.pageCount());
    }

    @Test
    void testClassifyNonExistentFile() {
        File nonExistent = new File("/nonexistent/path/fake.pdf");

        PdfClassificationResult result = classifier.classify(nonExistent);

        assertEquals(PdfContentType.UNKNOWN, result.contentType());
        assertEquals(nonExistent.getAbsolutePath(), result.sourcePath());
    }

    @Test
    void testClassifyCorruptFile() throws Exception {
        File corrupt = tempDir.resolve("corrupt.pdf").toFile();
        try (FileOutputStream fos = new FileOutputStream(corrupt)) {
            fos.write("This is definitely not a PDF".getBytes());
        }

        PdfClassificationResult result = classifier.classify(corrupt);

        assertEquals(PdfContentType.UNKNOWN, result.contentType());
        assertEquals(corrupt.getAbsolutePath(), result.sourcePath());
    }

    @Test
    void testClassifySinglePageTextPdf() throws Exception {
        File pdf = createTextOnlyPdf("single.pdf", 1);

        PdfClassificationResult result = classifier.classify(pdf);

        assertEquals(PdfContentType.TEXT_ONLY, result.contentType());
        assertEquals(1, result.pageCount());
        assertEquals(0, result.imagePagesCount());
    }

    @Test
    void testClassifyBatch() throws Exception {
        File textPdf = createTextOnlyPdf("batch-text.pdf", 1);
        File imagePdf = createImageOnlyPdf("batch-image.pdf", 1);
        File mixedPdf = createMixedPdf("batch-mixed.pdf");

        List<PdfClassificationResult> results = classifier.classifyBatch(
                List.of(textPdf, imagePdf, mixedPdf));

        assertEquals(3, results.size());
        assertEquals(PdfContentType.TEXT_ONLY, results.get(0).contentType());
        assertEquals(PdfContentType.IMAGE_BASED, results.get(1).contentType());
        assertEquals(PdfContentType.MIXED, results.get(2).contentType());
    }

    @Test
    void testClassificationTimeMsIsPositive() throws Exception {
        File pdf = createTextOnlyPdf("timed.pdf", 5);

        PdfClassificationResult result = classifier.classify(pdf);

        assertTrue(result.classificationTimeMs() >= 0);
    }

    // --- Helper methods ---

    private File createTextOnlyPdf(String name, int pageCount) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("This is page " + (i + 1) + " with enough text content to exceed the threshold. ");
                    cs.newLineAtOffset(0, -15);
                    cs.showText("Additional text to ensure the classifier detects meaningful extractable content.");
                    cs.endText();
                }
            }
            document.save(file);
        }
        return file;
    }

    private File createImageOnlyPdf(String name, int pageCount) throws Exception {
        File file = tempDir.resolve(name).toFile();

        // Create a small test image
        File imageFile = tempDir.resolve("test-image.png").toFile();
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                img.setRGB(x, y, 0xFF0000); // red pixels
            }
        }
        ImageIO.write(img, "png", imageFile);

        try (PDDocument document = new PDDocument()) {
            PDImageXObject pdImage = PDImageXObject.createFromFile(imageFile.getAbsolutePath(), document);

            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.drawImage(pdImage, 50, 400, 200, 200);
                }
            }
            document.save(file);
        }
        return file;
    }

    /**
     * Creates a PDF with 3 pages: page 0 and 2 are text-only, page 1 has an image.
     * Pages also have substantial text so it won't classify as scanned.
     */
    private File createMixedPdf(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();

        // Create a small test image
        File imageFile = tempDir.resolve("mixed-image.png").toFile();
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", imageFile);

        try (PDDocument document = new PDDocument()) {
            PDImageXObject pdImage = PDImageXObject.createFromFile(imageFile.getAbsolutePath(), document);

            // Page 0: text only
            PDPage page0 = new PDPage(PDRectangle.A4);
            document.addPage(page0);
            try (PDPageContentStream cs = new PDPageContentStream(document, page0)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Page one is a text-only page with substantial content for the classifier.");
                cs.newLineAtOffset(0, -15);
                cs.showText("More text here to ensure there is enough extractable content on this page.");
                cs.endText();
            }

            // Page 1: image
            PDPage page1 = new PDPage(PDRectangle.A4);
            document.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(document, page1)) {
                cs.drawImage(pdImage, 50, 400, 100, 100);
                // Also add text on the image page to push char count over threshold
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("This image page also has text so the PDF is not classified as scanned.");
                cs.endText();
            }

            // Page 2: text only
            PDPage page2 = new PDPage(PDRectangle.A4);
            document.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(document, page2)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Page three is another text-only page with plenty of content for analysis.");
                cs.newLineAtOffset(0, -15);
                cs.showText("Even more text content here to make sure the classifier counts chars properly.");
                cs.endText();
            }

            document.save(file);
        }
        return file;
    }
}
