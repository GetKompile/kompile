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

import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GrepBatchToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private final GrepBatchTool tool = new GrepBatchTool();
    private ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("grep-batch-test-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(om));
        Files.writeString(tempDir.resolve("Alpha.java"), "class Alpha { void run() {} }\n");
        Files.writeString(tempDir.resolve("Beta.java"), "class Beta { void work() {} }\n");
    }

    private ObjectNode params(ObjectNode... queries) {
        ObjectNode params = om.createObjectNode();
        ArrayNode arr = params.putArray("queries");
        for (ObjectNode q : queries) arr.add(q);
        return params;
    }

    private ObjectNode query(String pattern) {
        ObjectNode q = om.createObjectNode();
        q.put("pattern", pattern);
        return q;
    }

    @Test
    void runsMultipleQueriesWithSections() throws Exception {
        ToolResult result = tool.execute(params(
                query("class Alpha"),
                query("void work")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("2/2 queries succeeded"));
        assertTrue(result.getOutput().contains("[1] /class Alpha/"));
        assertTrue(result.getOutput().contains("[2] /void work/"));
        assertTrue(result.getOutput().contains("Alpha.java"));
        assertTrue(result.getOutput().contains("Beta.java"));
    }

    @Test
    void perQueryOptionsArePassedThrough() throws Exception {
        ObjectNode scoped = query("class");
        scoped.put("glob", "Alpha.java");
        scoped.put("output_mode", "files");

        ToolResult result = tool.execute(params(scoped), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("Alpha.java"));
        assertFalse(result.getOutput().contains("Beta.java"),
                () -> "glob should have scoped out Beta.java: " + result.getOutput());
    }

    @Test
    void failingQueryDoesNotBlockOthers() throws Exception {
        ToolResult result = tool.execute(params(
                query("class Beta"),
                query("")), context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("1/2 queries succeeded"));
        assertTrue(result.getOutput().contains("pattern is required"));
        assertTrue(result.getOutput().contains("Beta.java"));
    }

    @Test
    void emptyQueriesIsAnError() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.putArray("queries");
        assertTrue(tool.execute(params, context).isError());
    }
}
