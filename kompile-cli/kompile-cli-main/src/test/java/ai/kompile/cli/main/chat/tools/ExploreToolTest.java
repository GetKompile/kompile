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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExploreToolTest {

    private ExploreTool tool;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        tool = new ExploreTool();
        om = new ObjectMapper();
    }

    private ToolContext ctxFor(Path workingDir) {
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        perms.setAutoApproveAll(true);
        ToolRegistry registry = new ToolRegistry(om);
        return new ToolContext("test-" + UUID.randomUUID(), agent, perms, workingDir, registry);
    }

    @Test
    void respectGitignoreFalseKeepsGitignoredDirectoriesVisible() throws Exception {
        Path tmp = Files.createTempDirectory("explore-gitignore-flag-test");
        try {
            Files.writeString(tmp.resolve(".gitignore"), "data/\n");
            Files.createDirectories(tmp.resolve("data"));
            Files.writeString(tmp.resolve("data/kept.txt"), "visible\n");

            ObjectNode params = om.createObjectNode();
            params.put("respect_gitignore", false);
            params.put("include_glimpses", false);
            params.put("depth", 2);

            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("data"), result.getOutput());
            assertTrue(result.getOutput().contains("kept.txt"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void hiddenAliasIncludesDotPrefixedEntries() throws Exception {
        Path tmp = Files.createTempDirectory("explore-hidden-alias-test");
        try {
            Files.createDirectories(tmp.resolve(".github"));
            Files.writeString(tmp.resolve(".github/workflows.yml"), "name: ci\n");

            ObjectNode params = om.createObjectNode();
            params.put("hidden", true);
            params.put("include_glimpses", false);
            params.put("depth", 2);

            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains(".github/"), result.getOutput());
            assertTrue(result.getOutput().contains("workflows.yml"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void symlinksAreRenderedAndIncludedInStructuredEntries() throws Exception {
        Path tmp = Files.createTempDirectory("explore-symlink-test");
        try {
            Files.writeString(tmp.resolve("target.txt"), "target\n");
            Path link = tmp.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, Path.of("target.txt"));
            } catch (UnsupportedOperationException | IOException e) {
                return;
            }

            ObjectNode params = om.createObjectNode();
            params.put("include_glimpses", false);
            params.put("depth", 1);

            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("link.txt -> target.txt"), result.getOutput());
            assertEquals(1, result.getMetadata().get("symlinkCount"));
            assertTrue(entries(result).stream().anyMatch(e ->
                    "link.txt".equals(e.get("path")) && "symlink".equals(e.get("type"))
                            && "target.txt".equals(e.get("symlinkTarget"))));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void maxEntriesTruncatesAfterSortedSelectionAndReportsOmissions() throws Exception {
        Path tmp = Files.createTempDirectory("explore-max-entries-test");
        try {
            Files.writeString(tmp.resolve("zeta.txt"), "z\n");
            Files.writeString(tmp.resolve("alpha.txt"), "a\n");
            Files.writeString(tmp.resolve("middle.txt"), "m\n");

            ObjectNode params = om.createObjectNode();
            params.put("include_glimpses", false);
            params.put("depth", 1);
            params.put("max_entries", 2);

            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue((Boolean) result.getMetadata().get("truncated"));
            assertEquals(1, result.getMetadata().get("omittedEntryCount"));
            assertTrue(result.getOutput().contains("alpha.txt"), result.getOutput());
            assertTrue(result.getOutput().contains("middle.txt"), result.getOutput());
            assertFalse(result.getOutput().contains("zeta.txt"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void nestedKeyFileGlimpsesAreRendered() throws Exception {
        Path tmp = Files.createTempDirectory("explore-key-files-test");
        try {
            Files.createDirectories(tmp.resolve("module"));
            Files.writeString(tmp.resolve("module/pom.xml"), "<project>\n  <modelVersion>4.0.0</modelVersion>\n</project>\n");

            ObjectNode params = om.createObjectNode();
            params.put("include_glimpses", true);
            params.put("glimpse_lines", 1);
            params.put("depth", 2);

            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("**module/pom.xml**"), result.getOutput());
            assertTrue(result.getOutput().contains("... (more lines)"), result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(ToolResult result) {
        return (List<Map<String, Object>>) result.getMetadata().get("entries");
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
                    // best-effort cleanup
                }
            });
        }
    }
}
