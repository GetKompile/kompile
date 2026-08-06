/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.agent;

import ai.kompile.cli.common.agent.AgentDefaultsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDefaultsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void standaloneAgentCliPersistsDefaultsConsumedByDelegation() {
        int exitCode = new CommandLine(new AgentDefaultsCommand())
                .execute("--agent", "opencode",
                        "--project-dir", tempDir.toString(),
                        "--model", "openai/gpt-5",
                        "--thinking", "high");

        assertEquals(0, exitCode);
        AgentDefaultsStore.Selection selected = AgentDefaultsStore.resolve(
                "opencode", tempDir, null, null);
        assertEquals("openai/gpt-5", selected.model());
        assertEquals("high", selected.thinking());
    }

    @Test
    void standaloneAgentCliRejectsUnsupportedAgents() {
        assertEquals(2, new CommandLine(new AgentDefaultsCommand())
                .execute("--agent", "qwen",
                        "--project-dir", tempDir.toString(),
                        "--model", "qwen-model"));
        assertEquals(2, new CommandLine(new AgentDefaultsCommand())
                .execute("--agent", "notcodex",
                        "--project-dir", tempDir.toString(),
                        "--model", "invalid-model"));
    }

    @Test
    void standaloneAgentCliRejectsShowWithUpdates() {
        int exitCode = new CommandLine(new AgentDefaultsCommand())
                .execute("--agent", "codex",
                        "--project-dir", tempDir.toString(),
                        "--show",
                        "--model", "gpt-codex");

        assertEquals(2, exitCode);
    }
}
