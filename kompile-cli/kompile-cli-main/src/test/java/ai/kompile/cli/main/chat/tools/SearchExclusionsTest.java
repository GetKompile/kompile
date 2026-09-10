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

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the consolidated exclusion source of truth and, in particular, the {@code .gitignore}
 * directory classification that keeps the search tools from walking a repo's multi-gigabyte
 * git-ignored data directories (the root cause of grep/glob/explore timeouts) while still
 * letting them search ignored file <em>types</em> (e.g. {@code *.json}).
 */
class SearchExclusionsTest {

    @Test
    void staticDirsArePrunedRegardlessOfHiddenFlag() {
        assertTrue(SearchExclusions.isExcludedDir("target"));
        assertTrue(SearchExclusions.isExcludedDir("node_modules"));
        // .claude holds full repo copies under worktrees — must be pruned even with hidden on.
        assertTrue(SearchExclusions.isExcludedDir(".claude", true));
        assertTrue(SearchExclusions.isExcludedDir(".git", true));
    }

    @Test
    void hiddenFlagOnlyGovernsOtherDotDirs() {
        assertTrue(SearchExclusions.isExcludedDir(".github", false));
        assertFalse(SearchExclusions.isExcludedDir(".github", true));
        assertFalse(SearchExclusions.isExcludedDir("src", false));
    }

    @Test
    void gitignoreLiteralDirectoryIsPrunedAndExported() {
        // Mirrors the real repo: a plain directory entry for a huge data dir.
        var f = SearchExclusions.fromLines(List.of("kompile-rag-builds/", "anserini-models/"));
        assertFalse(f.isEmpty());
        assertTrue(f.isIgnoredDir("kompile-rag-builds", "kompile-rag-builds"));
        assertTrue(f.isIgnoredDir("some/nested/kompile-rag-builds", "kompile-rag-builds"));
        assertTrue(f.isIgnoredDir("anserini-models", "anserini-models"));
        // The literal name is passed straight to grep --exclude-dir / rg !glob.
        assertTrue(f.excludeDirArgs().contains("kompile-rag-builds"));
    }

    @Test
    void gitignoreFileGlobsAreNotTreatedAsDirectories() {
        // *.json / *.onnx are file-type ignores — they must NOT prune any directory, so config
        // and data files of those types stay searchable (grep keeps --no-ignore for this).
        var f = SearchExclusions.fromLines(List.of("*.json", "*.onnx", "*.log", "*.bin"));
        assertTrue(f.isEmpty());
        assertFalse(f.isIgnoredDir("config", "config"));
        assertTrue(f.excludeDirArgs().isEmpty());
    }

    @Test
    void invalidGitignorePatternsAreIgnored() {
        assertTrue(SearchExclusions.fromLines(List.of("[" + "invalid")).isEmpty());
        assertTrue(SearchExclusions.fromLines(List.of("foo/[bar")).isEmpty());
    }

    @Test
    void gitignoreSingleSegmentGlobMatchesByBasename() {
        var f = SearchExclusions.fromLines(List.of("gcc-*/"));
        assertTrue(f.isIgnoredDir("gcc-4.9.4", "gcc-4.9.4"));
        assertFalse(f.isIgnoredDir("gccfoo", "gccfoo"));
        assertTrue(f.excludeDirArgs().contains("gcc-*"));
    }

    @Test
    void gitignoreAnchoredMultiSegmentMatchesRelativePathOnly() {
        var f = SearchExclusions.fromLines(List.of("kompile-demo/logs/"));
        assertTrue(f.isIgnoredDir("kompile-demo/logs", "logs"));
        // A bare "logs" dir elsewhere must NOT be pruned by an anchored pattern.
        assertFalse(f.isIgnoredDir("src/logs", "logs"));
        assertFalse(f.isIgnoredDir("logs", "logs"));
        // Anchored/multi-segment patterns are not safe as a basename --exclude-dir arg.
        assertFalse(f.excludeDirArgs().contains("logs"));
    }

    @Test
    void gitignoreRootAnchoredSingleSegmentOnlyPrunesRepoRootPath() {
        var f = SearchExclusions.fromLines(List.of("/build/", "./cache/"));
        assertTrue(f.isIgnoredDir("build", "build"));
        assertTrue(f.isIgnoredDir("cache", "cache"));
        assertFalse(f.isIgnoredDir("sub/build", "build"));
        assertFalse(f.isIgnoredDir("module/cache", "cache"));
        assertTrue(f.excludeDirArgs().contains("/build"));
        assertTrue(f.excludeDirArgs().contains("/cache"));
    }

    @Test
    void gitignoreRecursiveGlobsDropTerminalWildcardSuffix() {
        var f = SearchExclusions.fromLines(List.of("ignored-cache/**"));
        assertTrue(f.excludeDirArgs().contains("ignored-cache"));
        assertTrue(f.isIgnoredDir("ignored-cache", "ignored-cache"));
        assertTrue(f.isIgnoredDir("ignored-cache/models", "models"));
        assertFalse(f.isIgnoredDir("kept/ignored-cache", "ignored-cache"));
    }

    @Test
    void gitignoreRootAnchoredPathsNormalizeSeparators() {
        var f = SearchExclusions.fromLines(List.of("/foo/bar/"));
        assertTrue(f.isIgnoredDir("foo/bar", "bar"));
        assertTrue(f.isIgnoredDir("foo\\bar", "bar"));
        assertFalse(f.isIgnoredDir("x/foo/bar", "bar"));
    }

    @Test
    void gitignoreMultiSegmentDataDirsStillPrunedWithoutTrailingSlash() {
        var f = SearchExclusions.fromLines(List.of("kompile-c-library/CMakeFiles", "kompile-python/kompile.egg-info"));
        assertTrue(f.isIgnoredDir("kompile-c-library/CMakeFiles", "CMakeFiles"));
        assertTrue(f.isIgnoredDir("kompile-python/kompile.egg-info", "kompile.egg-info"));
        assertTrue(f.excludeDirArgs().contains("kompile-c-library/CMakeFiles"));
        assertTrue(f.excludeDirArgs().contains("kompile-python/kompile.egg-info"));
    }

    @Test
    void negationsAndCommentsAreIgnored() {
        var f = SearchExclusions.fromLines(List.of("# a comment", "", "!keep/", "build/"));
        assertTrue(f.isIgnoredDir("build", "build"));
        assertFalse(f.isIgnoredDir("keep", "keep"));
    }

    @Test
    void gitignoreMultiSegmentFileEntriesAreNotDirectoryPrunes() {
        var f = SearchExclusions.fromLines(List.of("src/generated/schema.json", "reports/output.txt"));
        assertTrue(f.isEmpty());
        assertFalse(f.isIgnoredDir("src/generated/schema.json", "schema.json"));
        assertTrue(f.excludeDirArgs().isEmpty());
    }

    @Test
    void missingGitignoreYieldsEmptyNoOpFilter() {
        // null lines / no directory entries => empty filter, callers need no null checks.
        assertTrue(SearchExclusions.fromLines(null).isEmpty());
        assertTrue(SearchExclusions.fromLines(List.of("# only comments")).isEmpty());
    }

    @Test
    void mixedTextHeaderThenBinaryBodyIsDetected() throws Exception {
        Path tmp = Files.createTempDirectory("grep-mixed-binary-test");
        Path binaryLike = tmp.resolve("mixed-binary.txt");
        try {
            try (FileChannel ch = FileChannel.open(binaryLike,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer header = ByteBuffer.wrap("A".repeat(10_000).getBytes(StandardCharsets.UTF_8));
                ch.write(header);

                // Force a binary segment beyond the primary probe window, so a head-only probe
                // would miss it and force a full-content text scan.
                ch.position(80_000);
                ch.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 1, 2, 3, 4, 5}));
            }

            assertTrue(SearchExclusions.isLikelyBinaryFile(binaryLike),
                    "mixed-text headers with binary bytes later must be treated as binary");
        } finally {
            Files.deleteIfExists(binaryLike);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void mediumSizedMixedTextThenBinaryIsDetected() throws Exception {
        Path tmp = Files.createTempDirectory("grep-mixed-binary-mid-size-test");
        Path binaryLike = tmp.resolve("medium-mixed-binary.txt");
        try {
            try (FileChannel ch = FileChannel.open(binaryLike,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                // Head is clearly text, with a binary tail that starts before the first 16 KB window.
                ch.write(ByteBuffer.wrap("A".repeat(10_000).getBytes(StandardCharsets.UTF_8)));
                ch.position(11_000);
                ch.write(ByteBuffer.wrap(new byte[] {0x00, 0x00, (byte) 0xFE, (byte) 0xFF, 'X', 'Y', 'Z'}));
            }

            assertTrue(SearchExclusions.isLikelyBinaryFile(binaryLike),
                    "medium-sized text files with late binary sections must be treated as binary");
        } finally {
            Files.deleteIfExists(binaryLike);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void gitignoreFilterUsesRepoRelativePathsWhenSearchStartsInSubdirectory() {
        Path repo = Path.of("/repo");
        Path project = repo.resolve("generated-project");
        var f = SearchExclusions.fromLines(List.of("generated-project/log-archives/"), repo);

        assertTrue(f.isIgnoredDir(f.relativePath(project.resolve("log-archives"), project), "log-archives"));
        assertFalse(f.isIgnoredDir("log-archives", "log-archives"));
        assertTrue(f.excludeDirArgs(project).contains("log-archives"));
        assertFalse(f.excludeDirArgs(project).contains("generated-project/log-archives"));
    }

    // ─── Binary detection: UTF-8 multibyte content must not be misclassified ───

    /**
     * A Java source file dense with box-drawing comment dividers (U+2500 ─ = E2 94 80),
     * em-dashes (—), section signs (§), and arrows (→) is valid UTF-8 text and must be
     * classified as text, not binary.  This was the root-cause scenario for the bug: the
     * probe window was cut at an arbitrary byte offset, leaving a truncated multibyte
     * sequence at the tail that caused the UTF-8 decoder to throw and report binary.
     */
    @Test
    void javaFileWithBoxDrawingCommentDividersIsText() throws Exception {
        // Construct content similar to IncrementalReasoningOrchestrator.java:
        // Java code interspersed with section-divider comments using multibyte chars.
        String divider = "// ─────────────────────────────────────────────────────────────────────\n";
        String emDash   = "// Section — a subsection → detail with §1 coverage ≥ 90%\n";
        StringBuilder sb = new StringBuilder();
        sb.append("package ai.kompile.example;\n\n");
        sb.append("/**\n * Orchestrator — the entry point\n */\n");
        sb.append("public class Example {\n");
        for (int i = 0; i < 200; i++) {
            sb.append(divider);
            sb.append(emDash);
            sb.append("    public void method").append(i).append("() { /* § impl */ }\n");
        }
        sb.append("}\n");
        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);

        Path tmp = Files.createTempDirectory("binary-detect-test");
        Path javaFile = tmp.resolve("Example.java");
        try {
            Files.write(javaFile, content);
            assertFalse(SearchExclusions.isLikelyBinaryFile(javaFile),
                    "Java source dense with UTF-8 multibyte chars must be TEXT, not binary");
        } finally {
            Files.deleteIfExists(javaFile);
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A file that genuinely contains a NUL byte must be binary regardless of surrounding text.
     */
    @Test
    void fileWithNulByteIsBinary() throws Exception {
        Path tmp = Files.createTempDirectory("binary-detect-nul-test");
        Path nulFile = tmp.resolve("hasnul.java");
        try {
            byte[] content = "public class Foo { // normal\n\0 // nul here\n}".getBytes(StandardCharsets.ISO_8859_1);
            Files.write(nulFile, content);
            assertTrue(SearchExclusions.isLikelyBinaryFile(nulFile),
                    "File containing a NUL byte must be BINARY");
        } finally {
            Files.deleteIfExists(nulFile);
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A probe window truncated exactly at the middle of a 3-byte UTF-8 sequence must still
     * be classified as TEXT — the truncated tail must be stripped before decoding.
     */
    @Test
    void truncatedMultibyteAtSampleBoundaryIsText() {
        // Build a byte array that is valid UTF-8 except for a truncated 3-byte sequence at the end.
        // U+2500 ─ = E2 94 80.  We include the lead byte + one continuation but drop the last byte.
        byte[] content = ("Hello, valid UTF-8 text. " +
                "Box divider: ─── and em—dash").getBytes(StandardCharsets.UTF_8);
        // The full content is valid; now simulate a truncated probe window that ends after E2 94
        // (the first two bytes of another ─ sequence that would follow).
        byte[] truncated = new byte[content.length + 2];
        System.arraycopy(content, 0, truncated, 0, content.length);
        truncated[content.length]     = (byte) 0xE2; // lead byte of U+2500
        truncated[content.length + 1] = (byte) 0x94; // first continuation — sequence incomplete
        int trimmed = SearchExclusions.trimIncompleteUtf8Tail(truncated, truncated.length);
        // The trim should drop the last 2 bytes, leaving only the complete sequences.
        assertEquals(content.length, trimmed,
                "trimIncompleteUtf8Tail must strip the incomplete 3-byte lead+1-continuation tail");
    }

    /**
     * A probe window starting at a non-zero file offset may begin with continuation bytes
     * from a multibyte sequence whose lead byte is before the window.
     * {@code skipLeadingContinuationBytes} must skip at most 3 such bytes.
     */
    @Test
    void skipLeadingContinuationBytesSkipsOrphanedContinuations() {
        // Two continuation bytes (0x94, 0x80) from a U+2500 lead at a prior offset, then valid ASCII.
        byte[] buf = {(byte) 0x94, (byte) 0x80, 0x2F, 0x2F, 0x20}; // continuation bytes then "// "
        assertEquals(2, SearchExclusions.skipLeadingContinuationBytes(buf, buf.length),
                "Must skip exactly the 2 leading continuation bytes");

        // If the first byte is a lead byte it must NOT be skipped.
        byte[] lead = {(byte) 0xE2, (byte) 0x94, (byte) 0x80}; // complete U+2500
        assertEquals(0, SearchExclusions.skipLeadingContinuationBytes(lead, lead.length),
                "Lead byte at position 0 must not be skipped");

        // All continuation bytes: we still stop at 3 and return 3 (caller passes 0 instead).
        byte[] allCont = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        assertEquals(3, SearchExclusions.skipLeadingContinuationBytes(allCont, allCont.length),
                "At most 3 leading continuation bytes are skipped");
    }

    /**
     * Genuinely malformed UTF-8 (a lone lead byte followed by non-continuation) must be binary.
     */
    @Test
    void genuinelyInvalidUtf8IsBinary() throws Exception {
        // 0xC3 0x28: C3 is a valid 2-byte lead but 0x28 '(' is not a continuation byte.
        byte[] invalid = {0x41, 0x42, (byte) 0xC3, 0x28, 0x43, 0x44};

        Path tmp = Files.createTempDirectory("binary-detect-invalid-utf8-test");
        Path badFile = tmp.resolve("invalid.txt");
        try {
            Files.write(badFile, invalid);
            assertTrue(SearchExclusions.isLikelyBinaryFile(badFile),
                    "File with genuinely malformed UTF-8 must be BINARY");
        } finally {
            Files.deleteIfExists(badFile);
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A file with random high-byte noise (0xFF, 0xFE sequences etc.) that is not valid UTF-8
     * must be binary.
     */
    @Test
    void randomBinaryNoiseIsBinary() throws Exception {
        byte[] noise = new byte[512];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) (0x80 + (i % 128)); // all continuation/high bytes, no valid structure
        }

        Path tmp = Files.createTempDirectory("binary-detect-noise-test");
        Path noiseFile = tmp.resolve("noise.bin");
        try {
            Files.write(noiseFile, noise);
            assertTrue(SearchExclusions.isLikelyBinaryFile(noiseFile),
                    "Random high-byte noise must be BINARY");
        } finally {
            Files.deleteIfExists(noiseFile);
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Plain ASCII content must always be classified as text.
     */
    @Test
    void plainAsciiIsText() throws Exception {
        String ascii = "public class Foo {\n    public static void main(String[] args) {}\n}\n";
        Path tmp = Files.createTempDirectory("binary-detect-ascii-test");
        Path asciiFile = tmp.resolve("Foo.java");
        try {
            Files.write(asciiFile, ascii.getBytes(StandardCharsets.US_ASCII));
            assertFalse(SearchExclusions.isLikelyBinaryFile(asciiFile),
                    "Plain ASCII content must be TEXT");
        } finally {
            Files.deleteIfExists(asciiFile);
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A large file (>4×BINARY_PROBE_BYTES ~= 33KB) whose content is purely valid UTF-8 with
     * heavy multibyte chars in every probe window must be text.  This targets the multi-probe
     * code path (start + quarter + mid + three-quarter + tail offsets) and ensures every probe
     * window boundary is handled correctly by the tail-trimming logic.
     */
    @Test
    void largeDenseMultibyteFileIsText() throws Exception {
        // Each line has a box-drawing divider (3 bytes each) repeated 20 times = 60+ multibyte bytes/line.
        String line = "// " + "─".repeat(20) + " § section → next — done\n";
        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        // Write ~120KB so all 5 probe offsets fire.
        int targetSize = 130_000;
        int repeatCount = (targetSize / lineBytes.length) + 1;
        byte[] content = new byte[repeatCount * lineBytes.length];
        for (int i = 0; i < repeatCount; i++) {
            System.arraycopy(lineBytes, 0, content, i * lineBytes.length, lineBytes.length);
        }

        Path tmp = Files.createTempDirectory("binary-detect-large-multibyte-test");
        Path largeFile = tmp.resolve("LargeOrchestrator.java");
        try {
            Files.write(largeFile, content);
            assertFalse(SearchExclusions.isLikelyBinaryFile(largeFile),
                    "Large file dense with UTF-8 multibyte chars must be TEXT across all probe windows");
        } finally {
            Files.deleteIfExists(largeFile);
            Files.deleteIfExists(tmp);
        }
    }
}
