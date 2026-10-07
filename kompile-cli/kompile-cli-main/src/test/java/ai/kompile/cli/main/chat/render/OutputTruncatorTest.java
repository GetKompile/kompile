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

package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OutputTruncator}.
 * <p>
 * Tests truncation thresholds (2000 lines / 12000 UTF-8 bytes), preview generation,
 * file save behaviour, and edge cases (null/empty input).
 */
class OutputTruncatorTest {

    private OutputTruncator truncator;

    @BeforeEach
    void setUp() {
        truncator = new OutputTruncator();
    }

    // ===================================================================
    // Short output — no truncation
    // ===================================================================

    @Nested
    class ShortOutput {

        @Test
        void shortString_shouldNotBeTruncated() {
            String output = "Hello World";
            OutputTruncator.TruncationResult result = truncator.truncate(output, "bash");

            assertFalse(result.isTruncated());
            assertEquals(output, result.getOutput());
            assertNull(result.getSavedFile());
        }

        @Test
        void hundredLines_shouldNotBeTruncated() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("Line ").append(i).append("\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(sb.toString(), "grep");

            assertFalse(result.isTruncated());
            assertNull(result.getSavedFile());
        }

        @Test
        void exactlyAtLineLimit_shouldNotBeTruncated() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                sb.append("x\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(sb.toString(), "bash");

            // 2000 lines at 2 bytes each = 4000 bytes, well under 12000 bytes
            assertFalse(result.isTruncated());
        }
    }

    // ===================================================================
    // Null/empty
    // ===================================================================

    @Nested
    class NullAndEmpty {

        @Test
        void nullInput_shouldNotBeTruncated() {
            OutputTruncator.TruncationResult result = truncator.truncate(null, "bash");
            assertFalse(result.isTruncated());
            assertNull(result.getOutput());
        }

        @Test
        void emptyInput_shouldNotBeTruncated() {
            OutputTruncator.TruncationResult result = truncator.truncate("", "bash");
            assertFalse(result.isTruncated());
            assertEquals("", result.getOutput());
        }
    }

    // ===================================================================
    // Over line threshold — should truncate
    // ===================================================================

    @Nested
    class OverLineThreshold {

        @Test
        void overTwoThousandLines_shouldBeTruncated() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2500; i++) {
                sb.append("Line ").append(i).append("\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(sb.toString(), "bash");

            assertTrue(result.isTruncated(), "Over 2000 lines should be truncated");
            assertTrue(result.getOutput().contains("truncated"),
                    "Output should mention truncation");
            assertTrue(result.getOutput().contains("grep or read"),
                    "Should include guidance on reading full output");
        }

        @Test
        void truncatedPreview_shouldContainFirstLines() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3000; i++) {
                sb.append("LINE_").append(i).append("\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(sb.toString(), "grep");

            assertTrue(result.isTruncated());
            // Preview should contain the first few lines
            assertTrue(result.getOutput().contains("LINE_0"),
                    "Preview should start with the first line");
            assertTrue(result.getOutput().contains("LINE_10"),
                    "Preview should contain early lines");
        }

        @Test
        void savedFile_shouldBeReturned() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2500; i++) {
                sb.append("data ").append(i).append("\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(sb.toString(), "bash");

            assertTrue(result.isTruncated());
            assertNotNull(result.getSavedFile(), "Full output should be saved to a file");
            assertTrue(result.getOutput().contains(result.getSavedFile().toString()),
                    "Output should reference the saved file path");
        }
    }

    // ===================================================================
    // Over byte threshold — should truncate
    // ===================================================================

    @Nested
    class OverByteThreshold {

        @Test
        void over50KBOnFewLines_shouldBeTruncated() {
            // Create a single very long line (60KB)
            StringBuilder sb = new StringBuilder();
            sb.append("X".repeat(60 * 1024));
            sb.append("\n");

            OutputTruncator.TruncationResult result = truncator.truncate(sb.toString(), "bash");

            // 1 line but > 12000 bytes → should be truncated
            assertTrue(result.isTruncated());
        }
    }

    @Nested
    class Utf8Budgets {

        @Test
        void mediumOutput_shouldBeTruncated() {
            String output = "x".repeat(20000);
            OutputTruncator.TruncationResult result = truncator.truncate(output, "medium");

            assertTrue(result.isTruncated(), "Results between 12KB and 50KB must truncate");
            assertEquals("x".repeat(4000), previewOf(result));
            assertTrue(result.getOutput().contains("1 lines / "),
                    "The partially omitted line must be counted");
        }

        @Test
        void longSingleLine_shouldHaveBoundedNonemptyPreview() {
            OutputTruncator.TruncationResult result = truncator.truncate("x".repeat(100000), "long-line");

            assertTrue(result.isTruncated());
            assertEquals("x".repeat(4000), previewOf(result));
            assertTrue(result.getOutput().getBytes(StandardCharsets.UTF_8).length < 5000,
                    "The notice must not reintroduce a huge result");
        }

        @Test
        void nonAsciiOutput_shouldRespectBytesAndSaveCompleteOutput() throws Exception {
            // Only 6250 UTF-16 code units, but 14000 UTF-8 bytes.
            String output = "\u00e9\u6f22\ud83d\ude00".repeat(1500) + "\u00e9".repeat(250);
            OutputTruncator.TruncationResult result = truncator.truncate(output, "unicode");

            assertTrue(result.isTruncated());
            String preview = previewOf(result);
            assertFalse(preview.isEmpty());
            assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 4000);
            assertTrue(output.startsWith(preview));
            assertEquals(preview, new String(preview.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    "Preview must not contain a split surrogate pair");
            assertNotNull(result.getSavedFile());
            assertEquals(output, Files.readString(result.getSavedFile(), StandardCharsets.UTF_8));
            assertArrayEquals(output.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.getSavedFile()));
        }

        @Test
        void supplementaryCharacterAtBudgetBoundary_shouldNotBeSplit() {
            String output = "x".repeat(3999) + "\ud83d\ude00" + "y".repeat(10000);
            OutputTruncator.TruncationResult result = truncator.truncate(output, "surrogate-boundary");

            assertTrue(result.isTruncated());
            assertEquals("x".repeat(3999), previewOf(result));
        }

        @Test
        void exactlyAtUtf8ByteLimit_shouldRemainUnchanged() {
            String output = "\u6f22".repeat(4000);
            OutputTruncator.TruncationResult result = truncator.truncate(output, "unicode-limit");

            assertFalse(result.isTruncated());
            assertEquals(output, result.getOutput());
            assertNull(result.getSavedFile());
        }

        @Test
        void byteLimitedPreview_shouldCountOnlyCompletedLines() {
            String output = ("x".repeat(99) + "\n").repeat(200);
            OutputTruncator.TruncationResult result = truncator.truncate(output, "line-count");

            assertEquals(4000, previewOf(result).getBytes(StandardCharsets.UTF_8).length);
            assertTrue(result.getOutput().contains("160 lines / "));
        }

        @Test
        void crlfPreview_shouldKeepLineLimitAndCorrectNotice() {
            String output = "x\r\n".repeat(2500);
            OutputTruncator.TruncationResult result = truncator.truncate(output, "crlf");

            assertEquals("x\r\n".repeat(50), previewOf(result));
            assertTrue(result.getOutput().contains("2450 lines / "));
        }

        private String previewOf(OutputTruncator.TruncationResult result) {
            int noticeStart = result.getOutput().indexOf("\n... ");
            assertTrue(noticeStart > 0, "Truncation should retain a nonempty preview");
            return result.getOutput().substring(0, noticeStart);
        }
    }

    // ===================================================================
    // TruncationResult accessors
    // ===================================================================

    @Nested
    class TruncationResultAccessors {

        @Test
        void notTruncated_allFieldsAccessible() {
            OutputTruncator.TruncationResult result =
                    new OutputTruncator.TruncationResult("content", false, null);

            assertEquals("content", result.getOutput());
            assertFalse(result.isTruncated());
            assertNull(result.getSavedFile());
        }

        @Test
        void truncated_allFieldsAccessible() {
            java.nio.file.Path fakePath = java.nio.file.Path.of("/tmp/test-output.txt");
            OutputTruncator.TruncationResult result =
                    new OutputTruncator.TruncationResult("preview...", true, fakePath);

            assertEquals("preview...", result.getOutput());
            assertTrue(result.isTruncated());
            assertEquals(fakePath, result.getSavedFile());
        }
    }

    // ===================================================================
    // Cleanup
    // ===================================================================

    @Nested
    class Cleanup {

        @Test
        void cleanupOldFiles_shouldNotThrowWhenDirDoesNotExist() {
            // The truncation dir may not exist yet — cleanup should handle gracefully
            assertDoesNotThrow(() -> truncator.cleanupOldFiles());
        }
    }

    // ===================================================================
    // Tool name sanitisation in file names
    // ===================================================================

    @Nested
    class ToolNameSanitisation {

        @Test
        void specialCharsInToolName_shouldBeSanitised() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2500; i++) {
                sb.append("line\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(
                    sb.toString(), "mcp__kompile__bash");

            assertTrue(result.isTruncated());
            assertNotNull(result.getSavedFile());
            String fileName = result.getSavedFile().getFileName().toString();
            // replaceAll("[^a-zA-Z0-9]", "_") keeps underscores unchanged
            assertTrue(fileName.startsWith("mcp__kompile__bash"),
                    "Underscores should be preserved: " + fileName);
            assertTrue(fileName.endsWith(".txt"), "File should end with .txt");
        }

        @Test
        void dotsAndDashesInToolName_shouldBeReplaced() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2500; i++) {
                sb.append("line\n");
            }
            OutputTruncator.TruncationResult result = truncator.truncate(
                    sb.toString(), "my-tool.v2");

            assertTrue(result.isTruncated());
            assertNotNull(result.getSavedFile());
            String fileName = result.getSavedFile().getFileName().toString();
            // Dashes and dots should be replaced with underscores
            assertTrue(fileName.startsWith("my_tool_v2"),
                    "Dots and dashes should be replaced: " + fileName);
        }
    }
}
