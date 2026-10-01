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

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The code-index tools keep their whole hint in the MCP COMPACT listing. */
@TemporaryUserHome
class CodeToolCompactHintTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compactListingKeepsEachCodeToolsWholeHint() {
        for (CliTool tool : List.<CliTool>of(new CodeSearchTool((String) null, mapper),
                new CodeGraphTool((String) null, mapper), new LocalCodeIndexTool(), new FileContextTool())) {
            String hint = tool.compactHint();
            assertNotNull(hint, tool.id());
            assertFalse(hint.isBlank(), tool.id());

            ArrayNode definitions = mapper.createArrayNode();
            definitions.addObject()
                    .put("name", tool.id())
                    .put("description", tool.description())
                    .set("inputSchema", tool.parameterSchema());
            JsonNode listed = ToolSchemaOptimizer.optimize(definitions,
                    ToolSchemaOptimizer.OptimizationLevel.COMPACT, Map.of(tool.id(), hint)).get(0);

            assertEquals(hint, listed.path("description").asText(),
                    tool.id() + ": the MCP listing cuts hints longer than 200 characters");
        }
    }
}
