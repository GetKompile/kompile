/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFlagOverridesTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsCodexCurrentBypassFlagAsDefault() {
        assertEquals(
                List.of("--dangerously-bypass-approvals-and-sandbox"),
                AgentFlagOverrides.permissionBypassFlags("codex", tempDir));
    }

    @Test
    void systemPropertyOverrideReplacesDefaultFlags() {
        String property = "kompile.agent.flags.codex.permissionBypass";
        System.setProperty(property, "--ask-for-approval never");
        try {
            assertEquals(
                    List.of("--ask-for-approval", "never"),
                    AgentFlagOverrides.permissionBypassFlags("codex", tempDir));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void emptyOverrideDisablesDefaultFlags() {
        String property = "kompile.agent.flags.codex.permissionBypass";
        System.setProperty(property, "");
        try {
            assertTrue(AgentFlagOverrides.permissionBypassFlags("codex", tempDir).isEmpty());
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void projectConfigCanOverridePerAgentFlags() throws Exception {
        Path configDir = tempDir.resolve(".kompile");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("agent-flags.json"), """
                {
                  "codex": {
                    "permissionBypass": ["--sandbox", "danger-full-access"]
                  }
                }
                """);

        assertEquals(
                List.of("--sandbox", "danger-full-access"),
                AgentFlagOverrides.permissionBypassFlags("codex", tempDir.resolve("nested")));
    }

    @Test
    void openCodeGetsBypassFlagInRunModeButNotInteractive() {
        // Run/-p mode: opencode accepts --dangerously-skip-permissions.
        List<String> runCmd = new java.util.ArrayList<>(List.of("opencode"));
        AgentFlagOverrides.addPermissionBypassFlags(runCmd, "opencode", true, tempDir);
        assertEquals(List.of("opencode", "--dangerously-skip-permissions"), runCmd);

        // Interactive TUI: opencode auto-approves; the flag is only valid on `opencode run`, so
        // the interactive builder must add nothing (adding it would break the launch).
        List<String> tuiCmd = new java.util.ArrayList<>(List.of("opencode"));
        AgentFlagOverrides.addInteractivePermissionBypassFlags(tuiCmd, "opencode", true, tempDir);
        assertEquals(List.of("opencode"), tuiCmd);
    }

    @Test
    void claudeGetsSkipPermissionsInInteractiveMode() {
        List<String> cmd = new java.util.ArrayList<>(List.of("claude"));
        AgentFlagOverrides.addInteractivePermissionBypassFlags(cmd, "claude", true, tempDir);
        assertEquals(List.of("claude", "--dangerously-skip-permissions"), cmd);
    }

    @Test
    void interactiveBypassDisabledAddsNothing() {
        List<String> cmd = new java.util.ArrayList<>(List.of("claude"));
        AgentFlagOverrides.addInteractivePermissionBypassFlags(cmd, "claude", false, tempDir);
        assertEquals(List.of("claude"), cmd);
    }

    @Test
    void splitFlagsHandlesQuotedValues() {
        assertEquals(
                List.of("--config", "model=\"gpt-5 codex\"", "--flag"),
                AgentFlagOverrides.splitFlags("--config 'model=\"gpt-5 codex\"' --flag"));
    }
}
