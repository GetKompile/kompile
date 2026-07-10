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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code hidden} opt-in on {@link GlobTool}: hidden files and directories are
 * skipped by default (consistent with GrepTool and ripgrep), and included when requested —
 * except heavy trees like {@code .git}, which stay pruned regardless.
 */
class GlobToolHiddenTest {

    private GlobTool tool;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        tool = new GlobTool();
        om = new ObjectMapper();
    }

    private ToolContext ctxFor(Path workingDir) {
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        ToolRegistry registry = new ToolRegistry(om);
        return new ToolContext("test-" + UUID.randomUUID(), agent, perms, workingDir, registry);
    }

    // Hidden files are nested under a visible directory so this layout isolates
    // hidden-vs-visible handling from root-level glob matching.
    private Path layout() throws IOException {
        Path tmp = Files.createTempDirectory("glob-hidden-test");
        Files.createDirectories(tmp.resolve("visible"));
        Files.createDirectories(tmp.resolve(".hiddendir"));
        Files.createDirectories(tmp.resolve(".git"));
        Files.writeString(tmp.resolve("visible/v.conf"), "x\n");          // visible file, visible dir
        Files.writeString(tmp.resolve("visible/.secret.conf"), "x\n");    // hidden file, visible dir
        Files.writeString(tmp.resolve(".hiddendir/h.conf"), "x\n");       // file inside a hidden dir
        Files.writeString(tmp.resolve(".git/g.conf"), "x\n");             // file inside always-pruned dir
        return tmp;
    }

    @Test
    void defaultSkipsHiddenFilesAndDirs() throws Exception {
        Path tmp = layout();
        try {
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "**/*.conf");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("v.conf"), "visible file expected: " + result.getOutput());
            assertFalse(result.getOutput().contains("h.conf"), "hidden dir must be skipped: " + result.getOutput());
            assertFalse(result.getOutput().contains(".secret.conf"), "hidden file must be skipped: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void hiddenOptInIncludesHiddenButNotGit() throws Exception {
        Path tmp = layout();
        try {
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "**/*.conf");
            params.put("hidden", true);
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("v.conf"), "visible file expected: " + result.getOutput());
            assertTrue(result.getOutput().contains("h.conf"), "hidden dir should be searched: " + result.getOutput());
            assertTrue(result.getOutput().contains(".secret.conf"), "hidden file should be searched: " + result.getOutput());
            assertFalse(result.getOutput().contains("g.conf"), ".git must stay pruned even with hidden=true: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void bareFilenamePatternSearchesRecursivelyByBasename() throws Exception {
        Path tmp = Files.createTempDirectory("glob-bare-recursive-test");
        try {
            Files.createDirectories(tmp.resolve("a/b"));
            Files.writeString(tmp.resolve("a/b/cli-agents.json"), "[]\n");
            Files.writeString(tmp.resolve("a/b/MyTaskTest.java"), "class MyTaskTest {}\n");

            ObjectNode exactParams = om.createObjectNode();
            exactParams.put("pattern", "cli-agents.json");
            ToolResult exact = tool.execute(exactParams, ctxFor(tmp));
            assertFalse(exact.isError(), exact.getOutput());
            assertTrue(exact.getOutput().contains("a/b/cli-agents.json"), exact.getOutput());

            ObjectNode wildcardParams = om.createObjectNode();
            wildcardParams.put("pattern", "*Task*Test.java");
            ToolResult wildcard = tool.execute(wildcardParams, ctxFor(tmp));
            assertFalse(wildcard.isError(), wildcard.getOutput());
            assertTrue(wildcard.getOutput().contains("a/b/MyTaskTest.java"), wildcard.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void leadingGlobstarIncludesSearchRoot() throws Exception {
        Path tmp = Files.createTempDirectory("glob-root-globstar-test");
        try {
            Files.createDirectories(tmp.resolve("module"));
            Files.writeString(tmp.resolve("pom.xml"), "<project/>\n");
            Files.writeString(tmp.resolve("module/pom.xml"), "<project/>\n");

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "**/pom.xml");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("pom.xml"), result.getOutput());
            assertTrue(result.getOutput().contains("module/pom.xml"), result.getOutput());
            assertEquals(2, result.getMetadata().get("totalMatches"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void traversalDepthMatchesGlobShape() {
        assertEquals(Integer.MAX_VALUE, GlobTool.traversalDepthForGlob("*.txt"));
        assertEquals(2, GlobTool.traversalDepthForGlob("src/*.java"));
        assertEquals(3, GlobTool.traversalDepthForGlob("src/main/*.java"));
        assertEquals(Integer.MAX_VALUE, GlobTool.traversalDepthForGlob("**/*.java"));
    }

    @Test
    void newestMatchesAreSelectedAfterScanningPastInitialResultCap() throws Exception {
        Path tmp = Files.createTempDirectory("glob-newest-selection-test");
        try {
            long baseTime = System.currentTimeMillis() - 1_000_000L;
            for (int i = 0; i < 220; i++) {
                Path file = tmp.resolve("f" + String.format("%03d", i) + ".txt");
                Files.writeString(file, "x\n");
                Files.setLastModifiedTime(file, FileTime.fromMillis(baseTime + (i * 1000L)));
            }

            ObjectNode params = om.createObjectNode();
            params.put("pattern", "*.txt");
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertEquals(220, result.getMetadata().get("totalMatches"));
            assertEquals(100, result.getMetadata().get("count"));
            assertTrue((Boolean) result.getMetadata().get("truncated"));
            assertTrue(result.getOutput().contains("f219.txt"), result.getOutput());
            assertFalse(result.getOutput().contains("f000.txt"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
