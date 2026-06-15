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

import ai.kompile.core.loaders.PdfClassificationResult.PdfContentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfClassificationResultTest {

    @Test
    void testTextOnlyIsTextOnly() {
        PdfClassificationResult result = PdfClassificationResult.builder()
                .contentType(PdfContentType.TEXT_ONLY)
                .pageCount(5)
                .imagePagesCount(0)
                .textCharCount(5000)
                .hasImages(false)
                .hasScannedPages(false)
                .sourcePath("/tmp/test.pdf")
                .build();

        assertTrue(result.isTextOnly());
        assertFalse(result.requiresVlm());
    }

    @Test
    void testImageBasedRequiresVlm() {
        PdfClassificationResult result = PdfClassificationResult.builder()
                .contentType(PdfContentType.IMAGE_BASED)
                .pageCount(3)
                .imagePagesCount(3)
                .imagePageIndices(List.of(0, 1, 2))
                .textCharCount(10)
                .hasImages(true)
                .hasScannedPages(true)
                .sourcePath("/tmp/scanned.pdf")
                .build();

        assertFalse(result.isTextOnly());
        assertTrue(result.requiresVlm());
    }

    @Test
    void testMixedRequiresVlm() {
        PdfClassificationResult result = PdfClassificationResult.builder()
                .contentType(PdfContentType.MIXED)
                .pageCount(4)
                .imagePagesCount(2)
                .imagePageIndices(List.of(1, 3))
                .textCharCount(3000)
                .hasImages(true)
                .hasScannedPages(false)
                .sourcePath("/tmp/mixed.pdf")
                .build();

        assertFalse(result.isTextOnly());
        assertTrue(result.requiresVlm());
    }

    @Test
    void testUnknownIsNeitherTextOnlyNorVlm() {
        PdfClassificationResult result = PdfClassificationResult.builder()
                .contentType(PdfContentType.UNKNOWN)
                .sourcePath("/tmp/corrupt.pdf")
                .build();

        assertFalse(result.isTextOnly());
        assertFalse(result.requiresVlm());
    }

    @Test
    void testBuilderDefaults() {
        PdfClassificationResult result = PdfClassificationResult.builder()
                .build();

        assertEquals(PdfContentType.UNKNOWN, result.contentType());
        assertEquals(0, result.pageCount());
        assertEquals(0, result.imagePagesCount());
        assertEquals(List.of(), result.imagePageIndices());
        assertEquals(0, result.textCharCount());
        assertFalse(result.hasImages());
        assertFalse(result.hasScannedPages());
        assertEquals(0, result.classificationTimeMs());
        assertNull(result.sourcePath());
    }

    @Test
    void testRecordAccessors() {
        PdfClassificationResult result = PdfClassificationResult.builder()
                .contentType(PdfContentType.TEXT_ONLY)
                .pageCount(10)
                .imagePagesCount(0)
                .imagePageIndices(List.of())
                .textCharCount(50000)
                .hasImages(false)
                .hasScannedPages(false)
                .classificationTimeMs(42)
                .sourcePath("/docs/report.pdf")
                .build();

        assertEquals(PdfContentType.TEXT_ONLY, result.contentType());
        assertEquals(10, result.pageCount());
        assertEquals(0, result.imagePagesCount());
        assertEquals(List.of(), result.imagePageIndices());
        assertEquals(50000, result.textCharCount());
        assertFalse(result.hasImages());
        assertFalse(result.hasScannedPages());
        assertEquals(42, result.classificationTimeMs());
        assertEquals("/docs/report.pdf", result.sourcePath());
    }

    @Test
    void testImagePageIndicesPreserved() {
        List<Integer> indices = List.of(0, 2, 5, 7);
        PdfClassificationResult result = PdfClassificationResult.builder()
                .contentType(PdfContentType.MIXED)
                .imagePageIndices(indices)
                .imagePagesCount(4)
                .build();

        assertEquals(indices, result.imagePageIndices());
        assertEquals(4, result.imagePagesCount());
    }
}
