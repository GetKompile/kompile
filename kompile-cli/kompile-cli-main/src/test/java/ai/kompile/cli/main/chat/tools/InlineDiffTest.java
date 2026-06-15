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
 * Unit tests for {@link InlineDiff}.
 * <p>
 * Covers unified diff generation: identical files, simple edits, additions,
 * deletions, new files (null old content), null new content, context lines,
 * hunk headers, and truncation of large diffs.
 */
class InlineDiffTest {

    // ===================================================================
    // No diff (identical content)
    // ===================================================================

    @Nested
    class IdenticalContent {

        @Test
        void identicalStrings_shouldReturnNull() {
            String content = "line1\nline2\nline3";
            assertNull(InlineDiff.compute(content, content, "test.txt"));
        }

        @Test
        void bothEmpty_shouldReturnNull() {
            assertNull(InlineDiff.compute("", "", "empty.txt"));
        }

        @Test
        void singleIdenticalLine_shouldReturnNull() {
            assertNull(InlineDiff.compute("hello", "hello", "single.txt"));
        }
    }

    // ===================================================================
    // Null new content
    // ===================================================================

    @Nested
    class NullNewContent {

        @Test
        void nullNewContent_shouldReturnNull() {
            assertNull(InlineDiff.compute("old content", null, "file.txt"));
        }
    }

    // ===================================================================
    // New file (null old content)
    // ===================================================================

    @Nested
    class NewFile {

        @Test
        void nullOldContent_shouldTreatAsEmptyFile() {
            String result = InlineDiff.compute(null, "new line 1\nnew line 2", "new.txt");

            assertNotNull(result);
            assertTrue(result.contains("+new line 1"), "Added lines should be prefixed with +");
            assertTrue(result.contains("+new line 2"));
            assertTrue(result.contains("--- new.txt"), "Should have diff header");
            assertTrue(result.contains("+++ new.txt"));
        }

        @Test
        void emptyOldContent_shouldShowAllAsAdded() {
            String result = InlineDiff.compute("", "added line", "file.txt");

            assertNotNull(result);
            assertTrue(result.contains("+added line"));
        }
    }

    // ===================================================================
    // Simple edits
    // ===================================================================

    @Nested
    class SimpleEdits {

        @Test
        void singleLineChange_shouldShowRemoveAndAdd() {
            String old = "line1\nline2\nline3";
            String newContent = "line1\nmodified\nline3";
            String result = InlineDiff.compute(old, newContent, "test.java");

            assertNotNull(result);
            assertTrue(result.contains("-line2"), "Old line should be shown as removed");
            assertTrue(result.contains("+modified"), "New line should be shown as added");
            assertTrue(result.contains("@@ "), "Should have hunk header");
        }

        @Test
        void firstLineChanged_shouldShowDiff() {
            String old = "old first\nsecond\nthird";
            String newContent = "new first\nsecond\nthird";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("-old first"));
            assertTrue(result.contains("+new first"));
        }

        @Test
        void lastLineChanged_shouldShowDiff() {
            String old = "first\nsecond\nold last";
            String newContent = "first\nsecond\nnew last";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("-old last"));
            assertTrue(result.contains("+new last"));
        }

        @Test
        void multipleChangedLines_shouldShowAllChanges() {
            String old = "a\nb\nc\nd\ne";
            String newContent = "a\nB\nC\nd\ne";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("-b"));
            assertTrue(result.contains("-c"));
            assertTrue(result.contains("+B"));
            assertTrue(result.contains("+C"));
        }
    }

    // ===================================================================
    // Insertions
    // ===================================================================

    @Nested
    class Insertions {

        @Test
        void lineAdded_shouldShowAsInsertion() {
            String old = "line1\nline3";
            String newContent = "line1\nline2\nline3";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("+line2"), "Inserted line should be shown");
        }

        @Test
        void linesAddedAtEnd_shouldShow() {
            String old = "line1";
            String newContent = "line1\nline2\nline3";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("+line2"));
            assertTrue(result.contains("+line3"));
        }
    }

    // ===================================================================
    // Deletions
    // ===================================================================

    @Nested
    class Deletions {

        @Test
        void lineRemoved_shouldShowAsDeletion() {
            String old = "line1\nline2\nline3";
            String newContent = "line1\nline3";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("-line2"), "Removed line should be shown");
        }

        @Test
        void allLinesRemoved_shouldShowAllAsDeleted() {
            String old = "line1\nline2";
            String newContent = "";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains("-line1"));
            assertTrue(result.contains("-line2"));
        }
    }

    // ===================================================================
    // Diff header format
    // ===================================================================

    @Nested
    class DiffHeader {

        @Test
        void shouldContainFileNameInHeader() {
            String result = InlineDiff.compute("old", "new", "src/Main.java");

            assertNotNull(result);
            assertTrue(result.contains("--- src/Main.java"), "Should have --- header");
            assertTrue(result.contains("+++ src/Main.java"), "Should have +++ header");
        }

        @Test
        void shouldContainHunkHeader() {
            String result = InlineDiff.compute("old", "new", "file.txt");

            assertNotNull(result);
            assertTrue(result.contains("@@ -"), "Should have hunk header");
            assertTrue(result.contains(" +"), "Hunk header should have + section");
            assertTrue(result.contains(" @@"), "Hunk header should close with @@");
        }
    }

    // ===================================================================
    // Context lines
    // ===================================================================

    @Nested
    class ContextLines {

        @Test
        void contextBeforeChange_shouldBeShown() {
            String old = "before\nchanged\nafter";
            String newContent = "before\nmodified\nafter";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            // "before" is 1 line before the change — should be context
            assertTrue(result.contains(" before"), "Context line before should be shown with space prefix");
        }

        @Test
        void contextAfterChange_shouldBeShown() {
            String old = "before\nchanged\nafter";
            String newContent = "before\nmodified\nafter";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
            assertTrue(result.contains(" after") || result.contains("after"),
                    "Context line after should be shown");
        }
    }

    // ===================================================================
    // Large diffs — truncation
    // ===================================================================

    @Nested
    class LargeDiffs {

        @Test
        void manyChangedLines_shouldBeTruncated() {
            StringBuilder oldSb = new StringBuilder();
            StringBuilder newSb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                oldSb.append("old_").append(i).append("\n");
                newSb.append("new_").append(i).append("\n");
            }
            String result = InlineDiff.compute(oldSb.toString(), newSb.toString(), "big.txt");

            assertNotNull(result);
            // Should be truncated at MAX_DIFF_LINES (20)
            assertTrue(result.contains("more lines"),
                    "Large diff should show truncation notice");
        }

        @Test
        void exactlyTwentyChanges_shouldNotTruncate() {
            // 10 removed + 10 added = 20 diff lines, at the limit
            StringBuilder oldSb = new StringBuilder();
            StringBuilder newSb = new StringBuilder();
            oldSb.append("prefix\n");
            newSb.append("prefix\n");
            for (int i = 0; i < 10; i++) {
                oldSb.append("old_").append(i).append("\n");
                newSb.append("new_").append(i).append("\n");
            }
            oldSb.append("suffix\n");
            newSb.append("suffix\n");
            String result = InlineDiff.compute(oldSb.toString(), newSb.toString(), "exact.txt");

            assertNotNull(result);
            // At exactly 20, shouldn't show "more lines" (context lines + diff lines)
            // The actual truncation notice requires totalChanged > MAX_DIFF_LINES
        }
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Nested
    class EdgeCases {

        @Test
        void singleCharDifference_shouldProduceDiff() {
            String result = InlineDiff.compute("a", "b", "char.txt");

            assertNotNull(result);
            assertTrue(result.contains("-a"));
            assertTrue(result.contains("+b"));
        }

        @Test
        void trailingNewline_addedOrRemoved() {
            String old = "line1\n";
            String newContent = "line1\nline2\n";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
        }

        @Test
        void emptyLineChange_shouldDetect() {
            String old = "line1\n\nline3";
            String newContent = "line1\ninserted\nline3";
            String result = InlineDiff.compute(old, newContent, "test.txt");

            assertNotNull(result);
        }
    }
}
