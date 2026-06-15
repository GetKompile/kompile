/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphrag.format;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExtractionTextCompressor}.
 */
class ExtractionTextCompressorTest {

    @Nested
    class CompressBasic {

        @Test
        void nullReturnsEmpty() {
            assertEquals("", ExtractionTextCompressor.compress(null));
        }

        @Test
        void blankReturnsEmpty() {
            assertEquals("", ExtractionTextCompressor.compress("   \n\n  "));
        }

        @Test
        void preservesMeaningfulContent() {
            String input = "Alice works at Acme Corp in New York.";
            assertEquals(input, ExtractionTextCompressor.compress(input));
        }
    }

    @Nested
    class BlankLineCollapsing {

        @Test
        void collapseMultipleBlankLines() {
            String input = "First paragraph.\n\n\n\n\nSecond paragraph.";
            String result = ExtractionTextCompressor.compress(input);
            assertEquals("First paragraph.\n\nSecond paragraph.", result);
        }

        @Test
        void whitespaceOnlyLinesCollapsed() {
            String input = "Line one.\n   \n   \nLine two.";
            String result = ExtractionTextCompressor.compress(input);
            // Whitespace-only lines become empty, then blank runs collapse
            assertTrue(result.contains("Line one."));
            assertTrue(result.contains("Line two."));
            assertFalse(result.contains("\n\n\n"));
        }
    }

    @Nested
    class BoilerplateRemoval {

        @Test
        void stripPageNumbers() {
            String input = "Important content.\nPage 5\nMore content.\nPage 6 of 10\nFinal.";
            String result = ExtractionTextCompressor.compress(input);
            assertFalse(result.contains("Page 5"));
            assertFalse(result.contains("Page 6 of 10"));
            assertTrue(result.contains("Important content."));
            assertTrue(result.contains("More content."));
            assertTrue(result.contains("Final."));
        }

        @Test
        void stripCopyrightLines() {
            String input = "Content here.\nCopyright 2024 Acme Corp. All rights reserved.\nMore content.";
            String result = ExtractionTextCompressor.compress(input);
            assertFalse(result.contains("Copyright"));
            assertTrue(result.contains("Content here."));
            assertTrue(result.contains("More content."));
        }

        @Test
        void stripAllRightsReserved() {
            String input = "Data.\nAll Rights Reserved.\nMore data.";
            String result = ExtractionTextCompressor.compress(input);
            assertFalse(result.toLowerCase().contains("all rights reserved"));
        }

        @Test
        void stripNavigationBreadcrumbs() {
            String input = "Home > Products > Widget\nWidget details here.";
            String result = ExtractionTextCompressor.compress(input);
            assertFalse(result.contains("Home >"));
            assertTrue(result.contains("Widget details here."));
        }

        @Test
        void stripSkipToContent() {
            String input = "Skip to main content\nActual content here.";
            String result = ExtractionTextCompressor.compress(input);
            assertFalse(result.toLowerCase().contains("skip to"));
            assertTrue(result.contains("Actual content here."));
        }
    }

    @Nested
    class WhitespaceNormalization {

        @Test
        void trailingWhitespaceRemoved() {
            String input = "Line with trailing spaces.   \nNext line.";
            String result = ExtractionTextCompressor.compress(input);
            assertFalse(result.contains("spaces.   "));
            assertTrue(result.contains("Line with trailing spaces."));
        }

        @Test
        void innerMultiSpacesCollapsed() {
            String input = "Word    with    gaps.";
            String result = ExtractionTextCompressor.compress(input);
            assertEquals("Word with gaps.", result);
        }

        @Test
        void preserveTablesWhenFlagSet() {
            String input = "Name      Age    City\nAlice     30     NYC";
            String result = ExtractionTextCompressor.compress(input, true);
            // With preserveTables=true, inner multi-spaces should be kept
            assertTrue(result.contains("Name      Age"));
        }

        @Test
        void collapseTablesWhenFlagNotSet() {
            String input = "Name      Age    City\nAlice     30     NYC";
            String result = ExtractionTextCompressor.compress(input, false);
            // Without preserveTables, inner spaces should collapse
            assertFalse(result.contains("      "));
        }
    }

    @Nested
    class SeparatorDeduplication {

        @Test
        void consecutiveSeparatorsCollapsedToOne() {
            String input = "Section A\n====\n====\n====\nSection B";
            String result = ExtractionTextCompressor.compress(input);
            // Should only contain one separator line
            int sepCount = 0;
            for (String line : result.split("\\n")) {
                if (line.trim().matches("[=]{4,}")) sepCount++;
            }
            assertEquals(1, sepCount);
        }

        @Test
        void nonConsecutiveSeparatorsPreserved() {
            String input = "Part A\n====\nContent between.\n----\nPart B";
            String result = ExtractionTextCompressor.compress(input);
            assertTrue(result.contains("===="));
            assertTrue(result.contains("----"));
        }
    }

    @Nested
    class StripRepeatedHeaders {

        @Test
        void removesRepeatedHeadersAcrossChunks() {
            List<String> chunks = new ArrayList<>();
            chunks.add("Company Report 2024\nFirst section content.\nPage footer text");
            chunks.add("Company Report 2024\nSecond section content.\nPage footer text");
            chunks.add("Company Report 2024\nThird section content.\nPage footer text");
            chunks.add("Company Report 2024\nFourth section content.\nPage footer text");

            List<String> result = ExtractionTextCompressor.stripRepeatedHeaders(chunks, 3);

            assertEquals(4, result.size());
            // Repeated header should be removed
            for (String chunk : result) {
                assertFalse(chunk.contains("Company Report 2024"),
                        "Repeated header should be stripped");
                assertFalse(chunk.contains("Page footer text"),
                        "Repeated footer should be stripped");
            }
            // Content should be preserved
            assertTrue(result.get(0).contains("First section content."));
            assertTrue(result.get(1).contains("Second section content."));
        }

        @Test
        void preservesUniqueHeaders() {
            List<String> chunks = new ArrayList<>();
            chunks.add("Unique header A\nContent A");
            chunks.add("Unique header B\nContent B");
            chunks.add("Unique header C\nContent C");

            List<String> result = ExtractionTextCompressor.stripRepeatedHeaders(chunks, 3);
            assertEquals(chunks, result);
        }

        @Test
        void nullAndEmptyChunksHandled() {
            List<String> chunks = new ArrayList<>();
            chunks.add(null);
            chunks.add("");
            chunks.add("Content");

            List<String> result = ExtractionTextCompressor.stripRepeatedHeaders(chunks, 3);
            assertEquals(3, result.size());
        }

        @Test
        void tooFewChunksReturnedAsIs() {
            List<String> chunks = List.of("A", "B");
            List<String> result = ExtractionTextCompressor.stripRepeatedHeaders(chunks, 3);
            assertSame(chunks, result);
        }
    }

    @Nested
    class DeduplicateChunkOverlap {

        @Test
        void removesOverlappingTextBetweenChunks() {
            String overlap = "This is the overlapping portion that appears in both chunks.";
            String chunk1 = "Beginning of first chunk. " + overlap;
            String chunk2 = overlap + " End of second chunk.";

            List<String> input = List.of(chunk1, chunk2);
            List<String> result = ExtractionTextCompressor.deduplicateChunkOverlap(input, 200);

            assertEquals(2, result.size());
            assertEquals(chunk1, result.get(0)); // first chunk unchanged
            // Second chunk should have overlap removed
            assertFalse(result.get(1).startsWith(overlap),
                    "Overlap should be removed from second chunk");
            assertTrue(result.get(1).contains("End of second chunk."));
        }

        @Test
        void noOverlapPreservesChunks() {
            List<String> input = List.of(
                    "Completely different first chunk.",
                    "Completely different second chunk.");
            List<String> result = ExtractionTextCompressor.deduplicateChunkOverlap(input, 200);
            assertEquals(input, result);
        }

        @Test
        void singleChunkReturnedAsIs() {
            List<String> input = List.of("Only chunk");
            List<String> result = ExtractionTextCompressor.deduplicateChunkOverlap(input, 200);
            assertSame(input, result);
        }

        @Test
        void nullListReturnedAsIs() {
            assertNull(ExtractionTextCompressor.deduplicateChunkOverlap(null, 200));
        }
    }

    @Nested
    class CompressionEffectiveness {

        @Test
        void pdfLikeTextCompressesMeaningfully() {
            // Simulate typical PDF-extracted text with lots of waste
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append("Company Report 2024\n");
                sb.append("Confidential and Proprietary.\n");
                sb.append("\n\n\n");
                sb.append("Section ").append(i + 1).append(" content with   extra    spaces.\n");
                sb.append("Page ").append(i + 1).append(" of 10\n");
                sb.append("Copyright 2024 Acme Corp.\n");
                sb.append("All Rights Reserved.\n");
                sb.append("================\n");
                sb.append("================\n");
                sb.append("\n\n\n\n");
            }
            String input = sb.toString();
            String result = ExtractionTextCompressor.compress(input);

            // Should achieve meaningful compression
            double ratio = (double) result.length() / input.length();
            assertTrue(ratio < 0.6,
                    "Expected >40% compression on PDF-like text, got " +
                            String.format("%.1f%%", (1 - ratio) * 100));

            // Content should survive
            assertTrue(result.contains("Section 1 content"));
            assertTrue(result.contains("Section 10 content"));
        }
    }
}
