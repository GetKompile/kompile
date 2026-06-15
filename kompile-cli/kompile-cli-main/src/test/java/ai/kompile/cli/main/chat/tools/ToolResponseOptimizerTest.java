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

package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolResponseOptimizer}.
 * <p>
 * Tests plain-text compression (head+tail for read/bash, head-only for others),
 * JSON-aware compression, code-aware compression, search result compression,
 * and CompressedResult accessors. All methods are static/stateless.
 */
class ToolResponseOptimizerTest {

    private static final int THRESHOLD = ToolResponseOptimizer.DEFAULT_THRESHOLD_CHARS;

    // ===================================================================
    // Short output — no compression
    // ===================================================================

    @Nested
    class ShortOutput {

        @Test
        void shortOutput_notCompressed() {
            String output = "Hello World";
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("bash", output, THRESHOLD);

            assertFalse(result.wasCompressed());
            assertEquals(output, result.output());
            assertEquals(output.length(), result.originalChars());
            assertEquals(output.length(), result.compressedChars());
        }

        @Test
        void nullOutput_notCompressed() {
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("bash", null, THRESHOLD);

            assertFalse(result.wasCompressed());
            assertEquals("", result.output());
        }

        @Test
        void exactlyAtThreshold_notCompressed() {
            String output = "X".repeat(THRESHOLD - 1);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("read", output, THRESHOLD);

            assertFalse(result.wasCompressed());
        }
    }

    // ===================================================================
    // Head+tail compression (read, bash tools)
    // ===================================================================

    @Nested
    class HeadTailCompression {

        @Test
        void readTool_usesHeadTailStrategy() {
            String output = generateLargeOutput(THRESHOLD + 2000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("read", output, THRESHOLD);

            assertTrue(result.wasCompressed());
            assertTrue(result.compressedChars() < result.originalChars());
            // Head+tail: should contain beginning and end
            assertTrue(result.output().contains("Line_0"),
                    "Should contain head content");
            String lastLine = "Line_" + (countLines(output) - 1);
            // The tail should contain something from the end
            assertTrue(result.output().contains("truncated") || result.output().contains("..."),
                    "Should contain truncation notice");
        }

        @Test
        void bashTool_usesHeadTailStrategy() {
            String output = generateLargeOutput(THRESHOLD + 1000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("bash", output, THRESHOLD);

            assertTrue(result.wasCompressed());
            assertTrue(result.compressedChars() < result.originalChars());
        }

        @Test
        void mcpBash_usesHeadTailStrategy() {
            // MCP-prefixed bash should also use head+tail
            String output = generateLargeOutput(THRESHOLD + 1000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("mcp__kompile__bash", output, THRESHOLD);

            assertTrue(result.wasCompressed());
        }
    }

    // ===================================================================
    // Head-only compression (other tools)
    // ===================================================================

    @Nested
    class HeadOnlyCompression {

        @Test
        void grepTool_usesHeadTruncation() {
            String output = generateLargeOutput(THRESHOLD + 2000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("grep", output, THRESHOLD);

            assertTrue(result.wasCompressed());
            assertTrue(result.compressedChars() < result.originalChars());
            // Should start with the beginning of the output
            assertTrue(result.output().contains("Line_0"));
        }

        @Test
        void unknownTool_usesHeadTruncation() {
            String output = generateLargeOutput(THRESHOLD + 1000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("custom_tool", output, THRESHOLD);

            assertTrue(result.wasCompressed());
        }
    }

    // ===================================================================
    // Custom threshold
    // ===================================================================

    @Nested
    class CustomThreshold {

        @Test
        void smallThreshold_compressesSoooner() {
            String output = "X".repeat(200);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("bash", output, 100);

            assertTrue(result.wasCompressed());
            assertTrue(result.compressedChars() <= 200);
        }

        @Test
        void largeThreshold_doesNotCompress() {
            String output = "X".repeat(5000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compress("bash", output, 10000);

            assertFalse(result.wasCompressed());
        }
    }

    // ===================================================================
    // JSON-aware compression
    // ===================================================================

    @Nested
    class JsonCompression {

        @Test
        void shortJson_notCompressed() {
            String json = "{\"key\": \"value\", \"count\": 42}";
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressJson("tool", json, THRESHOLD);

            assertFalse(result.wasCompressed());
            // Output may be reformatted (compact JSON), so just verify content
            assertTrue(result.output().contains("key"));
            assertTrue(result.output().contains("value"));
            assertTrue(result.output().contains("42"));
        }

        @Test
        void largeJsonArray_compressed() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"id\":").append(i)
                        .append(",\"name\":\"item_").append(i)
                        .append("\",\"description\":\"This is a description for item ")
                        .append(i).append(" with extra padding text to make it larger\"}");
            }
            sb.append("]");

            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressJson("tool", sb.toString(), 2000);

            assertTrue(result.wasCompressed());
            assertTrue(result.compressedChars() < result.originalChars());
        }

        @Test
        void invalidJson_fallsBackToPlainCompression() {
            String notJson = "This is not { valid JSON at all " + "X".repeat(5000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressJson("tool", notJson, THRESHOLD);

            // Should still compress without throwing
            assertTrue(result.wasCompressed());
        }
    }

    // ===================================================================
    // Code-aware compression
    // ===================================================================

    @Nested
    class CodeCompression {

        @Test
        void shortCode_notCompressed() {
            String code = "public class A { void run() {} }";
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressCode(code, "A.java", THRESHOLD);

            assertFalse(result.wasCompressed());
        }

        @Test
        void largeCode_compressed() {
            StringBuilder sb = new StringBuilder();
            sb.append("package com.example;\n\n");
            for (int i = 0; i < 200; i++) {
                sb.append("    public void method").append(i).append("() {\n");
                sb.append("        System.out.println(\"method ").append(i).append("\");\n");
                sb.append("    }\n\n");
            }

            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressCode(sb.toString(), "Large.java", THRESHOLD);

            assertTrue(result.wasCompressed());
            assertTrue(result.compressedChars() < result.originalChars());
        }
    }

    // ===================================================================
    // Search result compression
    // ===================================================================

    @Nested
    class SearchResultCompression {

        @Test
        void shortSearchResults_notCompressed() {
            String results = "Found 3 matches:\nfile1.java:10: match1\nfile2.java:20: match2";
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressSearchResults(results, THRESHOLD);

            assertFalse(result.wasCompressed());
        }

        @Test
        void largeSearchResults_compressed() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("src/com/example/Class").append(i).append(".java:")
                        .append(i * 10).append(": public void method")
                        .append(i).append("() { /* implementation */ }\n");
            }

            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressSearchResults(sb.toString(), THRESHOLD);

            assertTrue(result.wasCompressed());
        }
    }

    // ===================================================================
    // Observation compression
    // ===================================================================

    @Nested
    class ObservationCompression {

        @Test
        void shortObservation_notCompressed() {
            String obs = "The file contains 10 lines of Java code.";
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressObservation(obs, THRESHOLD);

            assertFalse(result.wasCompressed());
        }

        @Test
        void largeObservation_compressed() {
            String obs = "Observation: " + "X".repeat(THRESHOLD + 1000);
            ToolResponseOptimizer.CompressedResult result =
                    ToolResponseOptimizer.compressObservation(obs, THRESHOLD);

            assertTrue(result.wasCompressed());
        }
    }

    // ===================================================================
    // CompressedResult record
    // ===================================================================

    @Nested
    class CompressedResultAccessors {

        @Test
        void allFieldsAccessible() {
            ToolResponseOptimizer.CompressedResult result =
                    new ToolResponseOptimizer.CompressedResult(
                            "compressed output", 10000, 500, true);

            assertEquals("compressed output", result.output());
            assertEquals(10000, result.originalChars());
            assertEquals(500, result.compressedChars());
            assertTrue(result.wasCompressed());
        }

        @Test
        void notCompressedResult() {
            ToolResponseOptimizer.CompressedResult result =
                    new ToolResponseOptimizer.CompressedResult(
                            "original", 8, 8, false);

            assertEquals("original", result.output());
            assertFalse(result.wasCompressed());
            assertEquals(result.originalChars(), result.compressedChars());
        }
    }

    // ===================================================================
    // Default constant
    // ===================================================================

    @Nested
    class Constants {

        @Test
        void defaultThreshold_is4000() {
            assertEquals(4000, ToolResponseOptimizer.DEFAULT_THRESHOLD_CHARS);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String generateLargeOutput(int targetChars) {
        StringBuilder sb = new StringBuilder();
        int lineNum = 0;
        while (sb.length() < targetChars) {
            sb.append("Line_").append(lineNum++).append(": ")
                    .append("This is output content that fills up the buffer.\n");
        }
        return sb.toString();
    }

    private static int countLines(String text) {
        return text.split("\n", -1).length;
    }
}
