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

package ai.kompile.cli.main.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.codeindex.CodeIndexSessionBrief;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The stdio host's initialize response carries the code-index instructions. */
@TemporaryUserHome
class McpStdioInitializeInstructionsTest {

    @TempDir Path workDir;

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private JsonNode initialize(McpStdioCommand command) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "initialize");
        request.putObject("params").put("protocolVersion", "2024-11-05");
        return command.handleMessage(request, Map.of(), mapper);
    }

    private static String awaitStatus(CodeIndexSessionBrief brief) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        String instructions = brief.instructions();
        while (instructions.equals(CodeIndexSessionBrief.GUIDANCE)) {
            if (System.currentTimeMillis() > deadline) fail("the session brief never produced a status line");
            Thread.sleep(50);
            instructions = brief.instructions();
        }
        return instructions;
    }

    @Test
    void initializeCarriesTheGuidanceBeforeTheBriefStarts() {
        JsonNode response = initialize(new McpStdioCommand());

        assertEquals(1, response.path("id").asInt());
        assertEquals(CodeIndexSessionBrief.GUIDANCE, response.path("result").path("instructions").asText());
    }

    @Test
    void initializeCarriesTheProjectStatusOnceResolved() throws Exception {
        McpStdioCommand command = new McpStdioCommand();
        CodeIndexSessionBrief brief = CodeIndexSessionBrief.start(workDir);
        command.sessionBrief = brief;
        String status = awaitStatus(brief);

        JsonNode response = initialize(command);

        assertTrue(status.startsWith(CodeIndexSessionBrief.GUIDANCE + "\n\nCode index: "), status);
        assertEquals(status, response.path("result").path("instructions").asText());
    }
}
