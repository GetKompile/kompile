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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeStreamParserTest {

    private final ClaudeStreamParser parser = new ClaudeStreamParser();

    @Test
    void supportsStreamJson_shouldIncludeCodex() {
        assertTrue(parser.supportsStreamJson("codex-cli"));
        assertTrue(parser.supportsStreamJson("claude-cli"));
        assertFalse(parser.supportsStreamJson("gemini-cli"));
    }

    @Test
    void parseLine_shouldStreamCodexAgentMessageUpdatesAsDeltas() {
        parser.parseLine("proc-1", "{\"type\":\"item.started\",\"item\":{\"id\":\"msg-1\",\"type\":\"agent_message\",\"text\":\"\"}}");

        ClaudeStreamParser.ParseResult first = parser.parseLine("proc-1",
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg-1\",\"type\":\"agent_message\",\"text\":\"Hello\"}}");
        ClaudeStreamParser.ParseResult second = parser.parseLine("proc-1",
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg-1\",\"type\":\"agent_message\",\"text\":\"Hello world\"}}");
        ClaudeStreamParser.ParseResult completed = parser.parseLine("proc-1",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"msg-1\",\"type\":\"agent_message\",\"text\":\"Hello world\"}}");

        assertNotNull(first);
        assertEquals("Hello", first.textContent());
        assertNotNull(second);
        assertEquals(" world", second.textContent());
        assertNull(completed, "Completed event should not duplicate text already streamed by updates");
    }

    @Test
    void parseLine_shouldExtractCodexResponseItemContentArray() {
        ClaudeStreamParser.ParseResult result = parser.parseLine("proc-2",
                "{\"type\":\"response_item\",\"payload\":{\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}}");

        assertNotNull(result);
        assertEquals("done", result.textContent());
    }
}
