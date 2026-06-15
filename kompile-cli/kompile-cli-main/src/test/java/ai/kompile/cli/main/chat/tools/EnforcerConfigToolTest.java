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

import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnforcerConfigToolTest {

    private EnforcerConfigTool tool;
    private ObjectMapper mapper;
    private ToolContext ctx;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new EnforcerConfigTool();
        mapper = new ObjectMapper();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("enforcer_config", PermissionService.PermissionLevel.ALLOW);
        ctx = new ToolContext("test", null, perms, tempDir, null);
    }

    @Test
    void init_createsDefaultConfig() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "init");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Created enforcer config"));
        assertTrue(EnforcerConfig.exists(tempDir));
    }

    @Test
    void status_noConfig() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "status");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("No enforcer config found"));
    }

    @Test
    void status_withConfig() throws Exception {
        // Create config first
        EnforcerConfig config = new EnforcerConfig();
        config.setKeywordMode(true);
        config.setSemanticMode("wordnet");
        config.save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "status");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("keyword"));
        assertTrue(result.getOutput().contains("wordnet"));
    }

    @Test
    void addKeyword() throws Exception {
        // Init first
        new EnforcerConfig().save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "add_keyword");
        params.put("keyword", "pre-existing");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Added banned keyword"));

        // Verify persisted
        EnforcerConfig loaded = EnforcerConfig.load(tempDir);
        assertNotNull(loaded);
        assertTrue(loaded.getBannedKeywords().contains("pre-existing"));
    }

    @Test
    void removeKeyword() throws Exception {
        EnforcerConfig config = new EnforcerConfig();
        config.getBannedKeywords().add("pre-existing");
        config.save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "remove_keyword");
        params.put("keyword", "pre-existing");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Removed"));

        EnforcerConfig loaded = EnforcerConfig.load(tempDir);
        assertFalse(loaded.getBannedKeywords().contains("pre-existing"));
    }

    @Test
    void setSemantic_wordnet() throws Exception {
        new EnforcerConfig().save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "set_semantic");
        params.put("mode", "wordnet");
        params.put("threshold", 0.85);

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("wordnet"));

        EnforcerConfig loaded = EnforcerConfig.load(tempDir);
        assertEquals("wordnet", loaded.getSemanticMode());
        assertEquals(0.85, loaded.getSemanticThreshold(), 0.001);
    }

    @Test
    void setSemantic_invalidMode() throws Exception {
        new EnforcerConfig().save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "set_semantic");
        params.put("mode", "invalid_mode");

        ToolResult result = tool.execute(params, ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Invalid mode"));
    }

    @Test
    void setField() throws Exception {
        new EnforcerConfig().save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "set");
        params.put("field", "max_corrections");
        params.put("value", "5");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());

        EnforcerConfig loaded = EnforcerConfig.load(tempDir);
        assertEquals(5, loaded.getMaxCorrections());
    }

    @Test
    void test_semanticExpansion() throws Exception {
        EnforcerConfig config = new EnforcerConfig();
        config.setSemanticMode("wordnet");
        config.getBannedKeywords().add("pre-existing");
        config.save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "test");
        params.put("phrase", "pre-existing");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Semantic expansion"));
        // Should have expanded variants (combinations of pre→synonyms × existing→synonyms)
        assertTrue(result.getOutput().contains("Expanded variants"),
                "Output should list expanded variants: " + result.getOutput());
        // At minimum, the original phrase should be in the list
        assertTrue(result.getOutput().contains("pre-existing") || result.getOutput().contains("pre existing"),
                "Output should contain original phrase: " + result.getOutput());
    }

    @Test
    void delete() throws Exception {
        new EnforcerConfig().save(tempDir);
        assertTrue(EnforcerConfig.exists(tempDir));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "delete");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertFalse(EnforcerConfig.exists(tempDir));
    }

    @Test
    void getFullConfig() throws Exception {
        EnforcerConfig config = new EnforcerConfig();
        config.setSemanticMode("both");
        config.setEmbeddingUrl("http://localhost:8080/api/embed");
        config.save(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "get");

        ToolResult result = tool.execute(params, ctx);
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("\"semanticMode\" : \"both\""));
        assertTrue(result.getOutput().contains("8080"));
    }
}
