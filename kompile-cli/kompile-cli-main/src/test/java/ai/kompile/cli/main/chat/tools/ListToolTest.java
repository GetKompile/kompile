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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused regression tests for list tool behavior on large directories.
 */
class ListToolTest {

    private ListTool tool;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        tool = new ListTool();
        om = new ObjectMapper();
    }

    private ToolContext ctxFor(Path workingDir) {
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        ToolRegistry registry = new ToolRegistry(om);
        return new ToolContext("test-" + UUID.randomUUID(), agent, perms, workingDir, registry);
    }

    @Test
    void listCutsOffAtMaxEntriesAndReturnsTruncated() throws Exception {
        Path tmp = Files.createTempDirectory("list-truncation-test");
        try {
            for (int i = 0; i < 700; i++) {
                Files.writeString(tmp.resolve("f" + String.format("%03d", i) + ".txt"),
                        "x\n");
            }

            ObjectNode params = om.createObjectNode();
            ToolResult result = tool.execute(params, ctxFor(tmp));

            assertFalse(result.isError(), result.getOutput());
            assertTrue((Boolean) result.getMetadata().get("truncated"));
            assertEquals(500, result.getMetadata().get("files"));
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
                    // best-effort cleanup
                }
            });
        }
    }
}

