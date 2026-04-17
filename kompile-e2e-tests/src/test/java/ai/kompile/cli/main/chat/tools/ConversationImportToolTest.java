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

import static org.junit.jupiter.api.Assertions.*;

public class ConversationImportToolTest {

    private final ConversationImportTool tool = new ConversationImportTool();

    @Test
    public void testToolIdAndDescription() {
        assertEquals("conversation_import", tool.id());
        assertTrue(tool.description().contains("Import conversations"));
        assertTrue(tool.description().contains("Claude Code"));
        assertTrue(tool.description().contains("OpenCode"));
        assertTrue(tool.description().contains("Codex"));
        assertTrue(tool.description().contains("Qwen"));
    }

    @Test
    public void testSanitizeId() throws Exception {
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "sanitizeId", String.class);
        method.setAccessible(true);

        assertEquals("test-id-123", method.invoke(tool, "test-id-123"));
        assertEquals("test_id_123", method.invoke(tool, "test@id#123"));
        assertEquals("test_id", method.invoke(tool, "test__id"));
        assertEquals("test", method.invoke(tool, "_test_"));
    }

    @Test
    public void testGetAgentName() throws Exception {
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "getAgentName", String.class);
        method.setAccessible(true);

        assertEquals("claude", method.invoke(tool, "claude-code"));
        assertEquals("opencode", method.invoke(tool, "opencode"));
        assertEquals("codex", method.invoke(tool, "codex"));
        assertEquals("qwen", method.invoke(tool, "qwen"));
        assertEquals("unknown", method.invoke(tool, "unknown"));
    }
}
