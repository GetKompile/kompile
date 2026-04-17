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
package ai.kompile.cli.main.chat.permission;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PermissionService — three-tier permission resolution
 * (agent → user override → defaults) and session memory.
 */
@DisplayName("PermissionService")
class PermissionServiceTest {

    private PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService();
    }

    @Nested
    @DisplayName("Default permissions")
    class Defaults {

        @Test
        void readIsAllowedByDefault() {
            AgentConfig agent = AgentConfig.builder("test").build();
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "read", "Read a file"));
        }

        @Test
        void grepIsAllowedByDefault() {
            AgentConfig agent = AgentConfig.builder("test").build();
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "grep", "Search files"));
        }

        @Test
        void globIsAllowedByDefault() {
            AgentConfig agent = AgentConfig.builder("test").build();
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "glob", "Find files"));
        }

        @Test
        void bashReadonlyIsAllowedByDefault() {
            AgentConfig agent = AgentConfig.builder("test").build();
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "bash.readonly", "ls -la"));
        }

        @Test
        void taskIsAllowedByDefault() {
            AgentConfig agent = AgentConfig.builder("test").build();
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "task", "Spawn subagent"));
        }

        @Test
        void webfetchIsAllowedByDefault() {
            AgentConfig agent = AgentConfig.builder("test").build();
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "webfetch", "Fetch URL"));
        }
    }

    @Nested
    @DisplayName("User overrides")
    class UserOverrides {

        @Test
        void userOverrideCanDenyDefaultAllow() {
            service.setUserOverride("read", PermissionService.PermissionLevel.DENY);
            AgentConfig agent = AgentConfig.builder("test").build();

            assertEquals(PermissionService.PermissionResult.DENIED,
                    service.check(agent, "read", "Read a file"));
        }

        @Test
        void userOverrideCanAllowDefaultAsk() {
            service.setUserOverride("edit", PermissionService.PermissionLevel.ALLOW);
            AgentConfig agent = AgentConfig.builder("test").build();

            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "edit", "Edit a file"));
        }

        @Test
        void userOverrideCanDenyBashWrite() {
            service.setUserOverride("bash.write", PermissionService.PermissionLevel.DENY);
            AgentConfig agent = AgentConfig.builder("test").build();

            assertEquals(PermissionService.PermissionResult.DENIED,
                    service.check(agent, "bash.write", "mvn test"));
        }
    }

    @Nested
    @DisplayName("Agent overrides (highest priority)")
    class AgentOverrides {

        @Test
        void agentOverrideTakesPriorityOverUserOverride() {
            // User allows edit, but agent denies it
            service.setUserOverride("edit", PermissionService.PermissionLevel.ALLOW);

            AgentConfig agent = AgentConfig.builder("planner")
                    .permissionOverrides(Map.of("edit", PermissionService.PermissionLevel.DENY))
                    .build();

            assertEquals(PermissionService.PermissionResult.DENIED,
                    service.check(agent, "edit", "Edit a file"));
        }

        @Test
        void agentOverrideTakesPriorityOverDefault() {
            // Default allows read, but agent denies it
            AgentConfig agent = AgentConfig.builder("restricted")
                    .permissionOverrides(Map.of("read", PermissionService.PermissionLevel.DENY))
                    .build();

            assertEquals(PermissionService.PermissionResult.DENIED,
                    service.check(agent, "read", "Read a file"));
        }

        @Test
        void nullAgentFallsToUserOrDefault() {
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(null, "read", "Read a file"));
        }

        @Test
        void agentWithNoOverridesFallsThrough() {
            AgentConfig agent = AgentConfig.builder("basic")
                    .permissionOverrides(Map.of())
                    .build();

            // Should fall through to default: read = ALLOW
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "read", "Read a file"));
        }
    }

    @Nested
    @DisplayName("Session-level memory")
    class SessionMemory {

        @Test
        void allowAllGrantsAllKnownPermissions() {
            service.allowAll();
            AgentConfig agent = AgentConfig.builder("test").build();

            // Permissions that are normally ASK should now be ALLOWED
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "edit", "Edit file"));
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "write", "Write file"));
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "bash.write", "mvn test"));
            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "bash.destructive", "rm -rf"));
        }
    }

    @Nested
    @DisplayName("Unknown permission keys")
    class UnknownKeys {

        @Test
        void unknownKeyWithUserOverrideUsesOverride() {
            service.setUserOverride("custom_tool", PermissionService.PermissionLevel.ALLOW);
            AgentConfig agent = AgentConfig.builder("test").build();

            assertEquals(PermissionService.PermissionResult.ALLOWED,
                    service.check(agent, "custom_tool", "Custom tool"));
        }

        @Test
        void unknownKeyWithDenyOverride() {
            service.setUserOverride("custom_tool", PermissionService.PermissionLevel.DENY);
            AgentConfig agent = AgentConfig.builder("test").build();

            assertEquals(PermissionService.PermissionResult.DENIED,
                    service.check(agent, "custom_tool", "Custom tool"));
        }
    }
}
