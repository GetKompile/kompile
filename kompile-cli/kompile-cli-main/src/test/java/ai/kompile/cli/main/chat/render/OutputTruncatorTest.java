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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OutputTruncator}.
 * <p>
 * Tests truncation thresholds (2000 lines / 50KB), preview generation,
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

            // 2000 lines at 2 bytes each = 4000 bytes, well under 50KB
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

            // 1 line but > 50KB → should be truncated
            assertTrue(result.isTruncated());
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
