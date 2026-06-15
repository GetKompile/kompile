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

package ai.kompile.cli.main.serve;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.CustomAgentLoader;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Shared resources allocated once by the daemon and used by all sessions.
 * <p>
 * This is the core of the memory savings: instead of each kompile-cli process
 * independently loading AgentRegistry (5 MB), SkillRegistry (3 MB),
 * RoleManager (2 MB), Jackson ObjectMapper + type caches (25 MB), etc.,
 * they are allocated once here and shared across all MCP and chat connections.
 */
public class SharedResourcePool implements Closeable {

    private final ObjectMapper objectMapper;
    private final AgentRegistry agentRegistry;
    private final SkillRegistry skillRegistry;
    private final RoleManager roleManager;
    private final PermissionService permissionService;
    private final ChatConfig chatConfig;
    private final Path defaultWorkDir;

    public SharedResourcePool() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public SharedResourcePool(Path defaultWorkDir) {
        this.defaultWorkDir = defaultWorkDir;
        this.objectMapper = new ObjectMapper();

        // Agent registry — load built-in + custom agents
        this.agentRegistry = new AgentRegistry();
        CustomAgentLoader agentLoader = new CustomAgentLoader(defaultWorkDir);
        Map<String, AgentConfig> customAgents = agentLoader.loadAll();
        for (AgentConfig custom : customAgents.values()) {
            agentRegistry.register(custom);
        }

        // Skill registry — load built-in + custom skills
        this.skillRegistry = new SkillRegistry();
        CustomSkillLoader skillLoader = new CustomSkillLoader(defaultWorkDir);
        Map<String, SkillConfig> customSkills = skillLoader.loadAll();
        for (SkillConfig custom : customSkills.values()) {
            skillRegistry.register(custom);
        }

        // Role manager — load built-in + custom roles, register in agent registry
        this.roleManager = new RoleManager(defaultWorkDir);
        for (RoleConfig role : roleManager.getAllRoles()) {
            agentRegistry.registerRole(role);
        }

        // Permission service — shared across sessions
        this.permissionService = new PermissionService();

        // Chat config — shared LLM provider settings
        this.chatConfig = ChatConfig.loadOrFromEnv();
    }

    public ObjectMapper objectMapper() { return objectMapper; }
    public AgentRegistry agentRegistry() { return agentRegistry; }
    public SkillRegistry skillRegistry() { return skillRegistry; }
    public RoleManager roleManager() { return roleManager; }
    public PermissionService permissionService() { return permissionService; }
    public ChatConfig chatConfig() { return chatConfig; }
    public Path defaultWorkDir() { return defaultWorkDir; }

    @Override
    public void close() {
        // No heavyweight resources to release — registries are pure Java objects.
        // If we add HttpClient pooling later, close it here.
    }
}
