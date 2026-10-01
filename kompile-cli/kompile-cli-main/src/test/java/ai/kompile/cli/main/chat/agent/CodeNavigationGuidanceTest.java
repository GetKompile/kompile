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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel;
import ai.kompile.cli.main.chat.roles.BuiltInRoles;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.CodeSearchTool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Built-in prompts and shipped templates point agents at the code index before grep. */
@TemporaryUserHome
class CodeNavigationGuidanceTest {

    private static final List<String> READ_ONLY_AGENTS =
            List.of("planner", "explore-quick", "explore-deep", "explorer", "code-reviewer", "architect");

    private final AgentRegistry agents = new AgentRegistry();

    @Test
    void codingAgentsCarryTheRule() {
        for (String name : List.of("coder", "general")) {
            assertTrue(agents.get(name).getSystemPrompt().contains(CodeNavigationGuidance.RULE), name);
        }
    }

    @Test
    void readOnlyAgentsCarryTheReadOnlyRuleAndItsTools() {
        for (String name : READ_ONLY_AGENTS) {
            AgentConfig agent = agents.get(name);
            assertTrue(agent.getSystemPrompt().contains(CodeNavigationGuidance.READ_ONLY_RULE), name);
            assertTrue(agent.getEnabledTools().containsAll(List.of("code_search", "file_context")), name);
            assertEquals(PermissionLevel.DENY,
                    agent.getPermissionOverrides().get(CodeSearchTool.INDEX_PERMISSION_KEY), name);
        }
    }

    @Test
    void builtInRolesCarryTheRuleForTheirAccess() {
        assertTrue(BuiltInRoles.CODER.getSystemPrompt().contains(CodeNavigationGuidance.RULE));
        assertTrue(BuiltInRoles.ARCHITECT.getSystemPrompt().contains(CodeNavigationGuidance.RULE));
        assertTrue(BuiltInRoles.REVIEWER.getSystemPrompt().contains(CodeNavigationGuidance.READ_ONLY_RULE));
    }

    @Test
    void shippedTemplatesCarryTheRule() throws IOException {
        for (String template : List.of("/templates/AGENTS.md", "/templates/system-prompt.md")) {
            try (InputStream in = CodeNavigationGuidanceTest.class.getResourceAsStream(template)) {
                assertNotNull(in, template);
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains(CodeNavigationGuidance.RULE), template);
            }
        }
    }
}
