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

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.time.Duration;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the grep tool hanging on FIFOs.
 *
 * <p>A running app's {@code *.stderr.fifo} inside the search tree caused the old shell grep
 * fallback to open and block on the pipe indefinitely. The Java fallback skips non-regular
 * files and has its own wall-clock cap.</p>
 */
class GrepToolFifoTest {

    private GrepTool tool;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        forceJavaFallback();
        tool = new GrepTool();
        om = new ObjectMapper();
    }

    private static void forceJavaFallback() throws Exception {
        Field rg = GrepTool.class.getDeclaredField("ripgrepAvailable");
        rg.setAccessible(true);
        rg.set(null, Boolean.FALSE);
    }

    private ToolContext ctxFor(Path workingDir) {
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        ToolRegistry registry = new ToolRegistry(om);
        return new ToolContext("test-" + UUID.randomUUID(), agent, perms, workingDir, registry);
    }

    @Test
    void grepSkipsFifoAndStillFindsMatchesQuickly() throws Exception {
        Path tmp = Files.createTempDirectory("grep-fifo-test");
        try {
            Files.writeString(tmp.resolve("hit.txt"), "alpha NEEDLE_TOKEN beta\n");
            Files.writeString(tmp.resolve("miss.txt"), "nothing to see here\n");

            // A FIFO with NO writer reproduces the old shell fallback hang on app *.stderr.fifo.
            // The Java fallback must skip it rather than opening it.
            Path fifo = tmp.resolve("app.stderr.fifo");
            Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
            boolean made = mk.waitFor(5, TimeUnit.SECONDS) && mk.exitValue() == 0;
            Assumptions.assumeTrue(made && Files.exists(fifo),
                    "mkfifo unavailable on this platform; skipping FIFO regression test");

            ToolContext ctx = ctxFor(tmp);
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "NEEDLE_TOKEN");

            // A healthy run returns in well under a second. The 10s preemptive bound sits
            // below the tool's 20s deadline, so a regression fails fast.
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx),
                    "grep hung on the FIFO; non-regular file skipping regressed");

            assertFalse(result.isError(), "grep should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("NEEDLE_TOKEN"),
                    "should find the match in the regular file while skipping the FIFO: "
                            + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepSkipsLikelyBinaryFilesBeforeTextScan() throws Exception {
        Path tmp = Files.createTempDirectory("grep-binary-file-skip-test");
        try {
            Files.write(tmp.resolve("binary-cache"), new byte[] {0, 0, 1, 1, 2, 3, 0, 4});
            Files.writeString(tmp.resolve("match.txt"), "BINARY_SAFE_TOKEN\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "BINARY_SAFE_TOKEN");

            ToolContext ctx = ctxFor(tmp);
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("match.txt"));
            assertFalse(result.getOutput().contains("binary-cache"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepSkipsHugeLikelyBinaryFileWithoutHanging() throws Exception {
        Path tmp = Files.createTempDirectory("grep-large-binary-file-test");
        try {
            Path hugeBinary = tmp.resolve("huge.bin");
            byte[] block = new byte[4096];
            for (int i = 0; i < block.length; i++) {
                block[i] = (byte) 0x00;
            }
            for (int i = 0; i < 1024; i++) {
                Files.write(hugeBinary, block, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            Files.writeString(tmp.resolve("match.txt"), "LARGE_BINARY_SAFE_TOKEN\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "LARGE_BINARY_SAFE_TOKEN");

            ToolContext ctx = ctxFor(tmp);
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx),
                    "search should skip huge binary file quickly in Java fallback");

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("match.txt"), result.getOutput());
            assertFalse(result.getOutput().contains("huge.bin"), result.getOutput());
            assertFalse(Boolean.TRUE.equals(result.getMetadata().get("timedOut")), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepSkipsTextHeaderBinaryBodyFileWithoutHung() throws Exception {
        Path tmp = Files.createTempDirectory("grep-mixed-binary-file-test");
        try {
            Path tricky = tmp.resolve("mixed.txt");
            try (FileChannel ch = FileChannel.open(tricky,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer header = ByteBuffer.wrap("A".repeat(10_000).getBytes(StandardCharsets.UTF_8));
                ch.write(header);
                ch.position(80_000);
                ch.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 1, 2, 3, 4, 5, 'H', 'I', 'T'}));
            }

            Files.writeString(tmp.resolve("match.txt"), "mixed-binary-token\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "mixed-binary-token");

            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctxFor(tmp)),
                    "search should still complete when a mixed binary/text file appears");

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("match.txt"));
            assertFalse(result.getOutput().contains("mixed.txt"));
            assertFalse(Boolean.TRUE.equals(result.getMetadata().get("timedOut")), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepAvoidsDirectorySymlinkCycles() throws Exception {
        Path tmp = Files.createTempDirectory("grep-symlink-cycle-test");
        try {
            Path realDir = tmp.resolve("real");
            Files.createDirectories(realDir);
            Files.writeString(realDir.resolve("target.txt"), "CYCLE_TOKEN\n");

            Path loop = tmp.resolve("loop");
            try {
                Files.createSymbolicLink(loop, tmp);
            } catch (Exception ex) {
                Assumptions.assumeTrue(false,
                        "symlink creation unavailable on this platform: " + ex.getMessage());
            }

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "CYCLE_TOKEN");

            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctxFor(tmp)),
                    "search should not loop indefinitely when a directory symlink cycle exists");

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("real/target.txt"), result.getOutput());
            assertFalse(result.getOutput().contains("loop"), result.getOutput());
            assertFalse(Boolean.TRUE.equals(result.getMetadata().get("timedOut")), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepSkipsGitignorePrunedLargeDirectoryWithoutTraversal() throws Exception {
        Path tmp = Files.createTempDirectory("grep-ignored-large-dir-test");
        try {
            Files.writeString(tmp.resolve(".gitignore"), "ignored-data/\n");
            Files.createDirectories(tmp.resolve("ignored-data"));
            for (int i = 0; i < 40; i++) {
                StringBuilder content = new StringBuilder("IGNOREME_TOKEN in ignored directory\n");
                for (int j = 0; j < 4_000; j++) {
                    content.append("filler\n");
                }
                Files.writeString(tmp.resolve("ignored-data/file-" + i + ".txt"), content);
            }

            Files.writeString(tmp.resolve("visible.txt"), "VISIBLE_TOKEN should be found\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "IGNOREME_TOKEN");

            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctxFor(tmp)),
                    "search should not hang while checking ignored directory entries");

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().toLowerCase().contains("no matches"),
                    "ignored directory should suppress all matches from ignored-data: " + result.getOutput());
            assertFalse(result.getOutput().contains("ignored-data"), result.getOutput());
            assertFalse(Boolean.TRUE.equals(result.getMetadata().get("timedOut")), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepSkipsMassiveGitignoreDirectoryWithoutEmittingMatches() throws Exception {
        Path tmp = Files.createTempDirectory("grep-massive-ignored-dir-test");
        try {
            Files.writeString(tmp.resolve(".gitignore"), "ignored-large/\n");
            Path ignored = tmp.resolve("ignored-large");
            Files.createDirectories(ignored);

            String token = "GREP_IGNORED_DIR_TOKEN";
            String markerLine = token + "\\n";
            for (int i = 0; i < 2000; i++) {
                Files.writeString(ignored.resolve("drop-" + i + ".txt"), markerLine);
            }

            ObjectNode params = om.createObjectNode();
            params.put("pattern", token);

            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(12),
                    () -> tool.execute(params, ctxFor(tmp)),
                    "search should skip a large ignored directory without walking all of it");

            assertFalse(result.isError(), result.getOutput());
            assertFalse(Boolean.TRUE.equals(result.getMetadata().get("timedOut")), result.getOutput());
            assertTrue(result.getOutput().toLowerCase().contains("no matches"));
            assertFalse(result.getOutput().contains("ignored-large"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepFindsPlainMatch() throws Exception {
        Path tmp = Files.createTempDirectory("grep-plain-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "first line\nUNIQUE_MARKER here\nlast line\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "UNIQUE_MARKER");

            ToolResult result = tool.execute(params, ctxFor(tmp));
            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("UNIQUE_MARKER"));
            assertEquals("content", result.getMetadata().get("outputMode"));
            assertEquals(1, result.getMetadata().get("matchCount"));
            assertEquals(1, result.getMetadata().get("outputLineCount"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepHandlesPatternStartingWithDash() throws Exception {
        Path tmp = Files.createTempDirectory("grep-leading-dash-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "flag --needle-option value\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "--needle-option");

            ToolResult result = tool.execute(params, ctxFor(tmp));
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("--needle-option"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepRejectsInvalidOutputModeAndUnknownArguments() throws Exception {
        Path tmp = Files.createTempDirectory("grep-invalid-args-test");
        try {
            ObjectNode badMode = om.createObjectNode();
            badMode.put("pattern", "anything");
            badMode.put("output_mode", "paths");
            ToolResult badModeResult = tool.execute(badMode, ctxFor(tmp));
            assertTrue(badModeResult.isError());
            assertTrue(badModeResult.getOutput().contains("output_mode"));

            ObjectNode unknown = om.createObjectNode();
            unknown.put("pattern", "anything");
            unknown.put("query", "wrong-name");
            ToolResult unknownResult = tool.execute(unknown, ctxFor(tmp));
            assertTrue(unknownResult.isError());
            assertTrue(unknownResult.getOutput().contains("Unknown grep parameter"));
            assertTrue(unknownResult.getOutput().contains("pattern"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepInvalidRegexReturnsError() throws Exception {
        Path tmp = Files.createTempDirectory("grep-invalid-regex-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "some text\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "(");

            ToolResult result = tool.execute(params, ctxFor(tmp));
            assertTrue(result.isError(), "invalid regex must be an error, not successful output: " + result.getOutput());
            assertTrue(result.getOutput().contains("grep failed") || result.getOutput().contains("Error running grep"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepSupportsCommaSeparatedGlobList() throws Exception {
        Path tmp = Files.createTempDirectory("grep-comma-glob-test");
        try {
            Files.writeString(tmp.resolve("a.cpp"), "COMMA_GLOB_TOKEN cpp\n");
            Files.writeString(tmp.resolve("b.cu"), "COMMA_GLOB_TOKEN cu\n");
            Files.writeString(tmp.resolve("c.h"), "COMMA_GLOB_TOKEN h\n");
            Files.writeString(tmp.resolve("d.java"), "COMMA_GLOB_TOKEN java\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "COMMA_GLOB_TOKEN");
            params.put("glob", "*.cpp,*.cu,*.h");

            ToolResult result = tool.execute(params, ctxFor(tmp));
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("a.cpp"));
            assertTrue(result.getOutput().contains("b.cu"));
            assertTrue(result.getOutput().contains("c.h"));
            assertFalse(result.getOutput().contains("d.java"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepContextMetadataDoesNotPretendOutputLinesAreMatches() throws Exception {
        Path tmp = Files.createTempDirectory("grep-context-metadata-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "before\nMETA_TOKEN here\nafter\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "META_TOKEN");
            params.put("context_lines", 1);

            ToolResult result = tool.execute(params, ctxFor(tmp));
            assertFalse(result.isError(), result.getOutput());
            Map<String, Object> metadata = result.getMetadata();
            assertEquals("content", metadata.get("outputMode"));
            assertTrue((int) metadata.get("outputLineCount") >= 3);
            assertFalse(metadata.containsKey("matchCount"), "context output lines are not match count");
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepTruncationMatchCountCountsReturnedContentMatches() throws Exception {
        Path tmp = Files.createTempDirectory("grep-truncated-count-test");
        try {
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 101; i++) {
                content.append("TRUNCATE_TOKEN ").append(i).append('\n');
            }
            Files.writeString(tmp.resolve("a.txt"), content.toString());

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "TRUNCATE_TOKEN");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            Map<String, Object> metadata = result.getMetadata();
            assertEquals(100, metadata.get("outputLineCount"));
            assertEquals(100, metadata.get("matchCount"));
            assertEquals(true, metadata.get("truncated"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepFallbackHonorsDirectoryComponentsInGlob() throws Exception {
        Path tmp = Files.createTempDirectory("grep-dir-glob-test");
        try {
            Files.createDirectories(tmp.resolve("src/main"));
            Files.createDirectories(tmp.resolve("other"));
            Files.writeString(tmp.resolve("src/main/Hit.java"), "DIR_GLOB_TOKEN src\n");
            Files.writeString(tmp.resolve("other/Hit.java"), "DIR_GLOB_TOKEN other\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "DIR_GLOB_TOKEN");
            params.put("glob", "src/**/*.java");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("src/main/Hit.java"), result.getOutput());
            assertFalse(result.getOutput().contains("other/Hit.java"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepFallbackHonorsAnchoredGitignoreDirectoryRules() throws Exception {
        Path tmp = Files.createTempDirectory("grep-anchored-gitignore-test");
        try {
            Files.writeString(tmp.resolve(".gitignore"), "ignored/root/\n");
            Files.createDirectories(tmp.resolve("ignored/root"));
            Files.createDirectories(tmp.resolve("ignored/keep"));
            Files.writeString(tmp.resolve("ignored/root/a.txt"), "ANCHOR_TOKEN ignored\n");
            Files.writeString(tmp.resolve("ignored/keep/b.txt"), "ANCHOR_TOKEN kept\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "ANCHOR_TOKEN");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("ignored/keep/b.txt"), result.getOutput());
            assertFalse(result.getOutput().contains("ignored/root/a.txt"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** Regression: a regex with {@code |} alternation must match in the Java fallback. */
    @Test
    void grepHandlesRegexAlternation() throws Exception {
        Path tmp = Files.createTempDirectory("grep-alt-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "first ALPHA_TOKEN line\nsecond line\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "ALPHA_TOKEN|NOPE_TOKEN");

            ToolContext ctx = ctxFor(tmp);
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx));
            assertFalse(result.isError(), "grep should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("ALPHA_TOKEN"),
                    "alternation a|b must match in the fallback: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * Regression: the Java fallback must skip hidden directories like GlobTool
     * ({@code SearchExclusions.isExcludedDir}) and ripgrep do.
     */
    @Test
    void grepSkipsHiddenDirectories() throws Exception {
        Path tmp = Files.createTempDirectory("grep-hidden-test");
        try {
            Files.createDirectories(tmp.resolve(".hidden"));
            Files.createDirectories(tmp.resolve("visible"));
            Files.writeString(tmp.resolve(".hidden/h.txt"), "HIDDEN_TOKEN should not be searched\n");
            Files.writeString(tmp.resolve("visible/v.txt"), "VISIBLE_TOKEN here\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "_TOKEN");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("VISIBLE_TOKEN"),
                    "visible match expected: " + result.getOutput());
            assertFalse(result.getOutput().contains("HIDDEN_TOKEN"),
                    "hidden directory must be skipped (matches GlobTool/rg): " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * The {@code hidden} opt-in must let grep descend hidden directories again, while the
     * default ({@link #grepSkipsHiddenDirectories}) keeps skipping them.
     */
    @Test
    void grepHiddenOptInSearchesHiddenDirectories() throws Exception {
        Path tmp = Files.createTempDirectory("grep-hidden-optin-test");
        try {
            Files.createDirectories(tmp.resolve(".hidden"));
            Files.writeString(tmp.resolve(".hidden/h.txt"), "HIDDEN_TOKEN here\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "HIDDEN_TOKEN");
            params.put("hidden", true);
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("HIDDEN_TOKEN"),
                    "hidden=true must search hidden dirs: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** Even with {@code hidden=true}, the always-junk dirs (e.g. {@code .git}) stay pruned. */
    @Test
    void grepHiddenOptInStillSkipsGitDir() throws Exception {
        Path tmp = Files.createTempDirectory("grep-git-skip-test");
        try {
            Files.createDirectories(tmp.resolve(".git"));
            Files.writeString(tmp.resolve(".git/config"), "GIT_INTERNAL_TOKEN here\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "GIT_INTERNAL_TOKEN");
            params.put("hidden", true);
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            // The token lives only in .git, which stays pruned, so the search must report no
            // matches. Assert on the no-match message rather than token absence — the no-match
            // message echoes the pattern, which itself contains the token.
            assertTrue(result.getOutput().toLowerCase().contains("no matches"),
                    ".git must stay pruned even with hidden=true: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepNoMatchReturnsCleanly() throws Exception {
        Path tmp = Files.createTempDirectory("grep-nomatch-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "just some text\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "PATTERN_THAT_DOES_NOT_EXIST_ANYWHERE");

            ToolContext ctx = ctxFor(tmp);
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx));
            assertFalse(result.isError());
            assertTrue(result.getOutput().toLowerCase().contains("no matches"),
                    "expected a clean no-match message: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * Regression: pom.xml / XML files must be found WITHOUT an explicit glob.
     *
     * <p>This test also places a FIFO alongside the pom.xml to confirm a non-regular file
     * neither blocks the search nor prevents the XML match from being returned.</p>
     */
    @Test
    void grepFindsXmlAndPomXmlWithoutExplicitGlob() throws Exception {
        Path tmp = Files.createTempDirectory("grep-xml-test");
        try {
            // Minimal pom.xml containing a distinctive string to search for
            String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<project>\n"
                    + "  <artifactId>XML_GREP_REGRESSION_MARKER</artifactId>\n"
                    + "</project>\n";
            Files.writeString(tmp.resolve("pom.xml"), pomContent);

            // An unrelated Java file — must not interfere
            Files.writeString(tmp.resolve("Foo.java"), "// no marker here\npublic class Foo {}\n");

            // A FIFO with no writer: ensures fallback search completes promptly rather than
            // blocking on the pipe.
            boolean hasMkfifo = false;
            try {
                Path fifo = tmp.resolve("app.stderr.fifo");
                Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
                hasMkfifo = mk.waitFor(5, TimeUnit.SECONDS) && mk.exitValue() == 0;
            } catch (Exception ignored) {
                // mkfifo unavailable — FIFO part of the test is skipped but XML part still runs
            }

            ToolContext ctx = ctxFor(tmp);
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "XML_GREP_REGRESSION_MARKER");
            // NO "glob" parameter — the tool must find pom.xml by default

            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx),
                    "grep hung on FIFO or excessive scan");

            assertFalse(result.isError(), "grep should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("XML_GREP_REGRESSION_MARKER"),
                    "pom.xml content must be found without an explicit glob; got: " + result.getOutput());
            assertTrue(result.getOutput().contains("pom.xml"),
                    "result should identify pom.xml as the matching file; got: " + result.getOutput());
            if (hasMkfifo) {
                assertFalse(result.getOutput().toLowerCase().contains("timed out"),
                        "search must complete well within timeout even with a FIFO present: "
                                + result.getOutput());
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
