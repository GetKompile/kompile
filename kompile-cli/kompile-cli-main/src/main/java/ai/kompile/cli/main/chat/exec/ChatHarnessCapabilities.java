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
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.CustomAgentLoader;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ModelContextResolver;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine-readable description of the configured {@code kompile-cli-main} chat harness.
 *
 * <p>The browser and other thin clients use this contract instead of duplicating the
 * harness persona/role roster or provider context limits. The report deliberately contains
 * no credentials, environment values, system prompts, or tool policies.</p>
 */
public final class ChatHarnessCapabilities {

    private static final int MIN_INPUT_BUDGET = 512;
    private static final int MAX_SYSTEM_OVERHEAD = 1_024;

    private ChatHarnessCapabilities() {
    }

    public record Persona(
            String name,
            String displayName,
            String description,
            String selectorType,
            String selectorValue,
            boolean defaultPersona,
            boolean custom,
            boolean available) {
    }

    public record Report(
            String engine,
            boolean available,
            String status,
            String provider,
            String model,
            String chatMode,
            int contextWindow,
            int maxOutputTokens,
            int inputBudgetTokens,
            double compactTriggerRatio,
            boolean memoryEnabled,
            boolean ragEnabled,
            boolean workflowEnabled,
            boolean attachmentsSupported,
            List<Persona> personas) {
    }

    public static Report inspect(Path workingDirectory) {
        Path workDir = workingDirectory == null
                ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                : workingDirectory.toAbsolutePath().normalize();
        ChatConfig config = ChatConfig.loadOrFromEnv(workDir);
        return inspect(workDir, config);
    }

    static Report inspect(Path workingDirectory, ChatConfig config) {
        AgentRegistry registry = new AgentRegistry();
        for (AgentConfig custom : new CustomAgentLoader(workingDirectory).loadAll().values()) {
            registry.register(custom);
        }
        RoleManager roles = new RoleManager(workingDirectory);

        String defaultAgent = config != null && config.getDefaultAgent() != null
                && !config.getDefaultAgent().isBlank()
                ? config.getDefaultAgent() : "coder";
        boolean ready = isRunnable(config);
        List<Persona> personas = new ArrayList<>();
        for (AgentConfig agent : registry.getPrimaryAgents()) {
            personas.add(new Persona(
                    agent.getName(), agent.getDisplayName(), agent.getDescription(),
                    "agent", agent.getName(), agent.getName().equals(defaultAgent),
                    agent.isCustom(), ready));
        }
        for (RoleConfig role : roles.getAllRoles()) {
            personas.add(new Persona(
                    "role:" + role.getName(), role.getDisplayName(), role.getDescription(),
                    "role", role.getName(), false, role.isCustom(), ready));
        }

        int contextWindow = 0;
        int maxOutputTokens = 0;
        int inputBudgetTokens = 0;
        if (config != null && config.getModel() != null && !config.getModel().isBlank()) {
            ModelContextResolver.ModelLimits limits =
                    new ModelContextResolver().resolveLimits(config, null);
            contextWindow = limits.contextWindow();
            maxOutputTokens = Math.max(0, Math.min(
                    limits.maxOutputTokens(), Math.max(0, contextWindow / 2)));
            int systemOverhead = Math.min(
                    MAX_SYSTEM_OVERHEAD, Math.max(64, contextWindow / 8));
            inputBudgetTokens = Math.max(
                    MIN_INPUT_BUDGET, contextWindow - maxOutputTokens - systemOverhead);
        }

        String status = status(config);
        ObjectMapper mapper = JsonUtils.standardMapper();
        boolean workflowEnabled = HarnessConfig.load(mapper).isJudgeGlobalEnabled();
        boolean attachmentsSupported = false;
        if (ready) {
            try (DirectLlmClient client = new DirectLlmClient(
                    config, mapper, workingDirectory)) {
                attachmentsSupported = client.supportsAttachments(null);
            }
        }
        return new Report(
                "kompile-cli-main",
                ready,
                status,
                config == null ? "" : empty(config.getProvider()),
                config == null ? "" : empty(config.getModel()),
                config == null ? "" : empty(config.getChatMode()),
                contextWindow,
                maxOutputTokens,
                inputBudgetTokens,
                config == null ? 0.85d : config.getAutoCompactThreshold(),
                config == null || config.isDefaultMemory(),
                config != null && config.isDefaultRag(),
                workflowEnabled,
                attachmentsSupported,
                List.copyOf(personas));
    }

    public static String toJson(Report report) {
        if (report == null) {
            return "{\"engine\":\"kompile-cli-main\",\"available\":false,"
                    + "\"status\":\"capability serialization failed\",\"personas\":[]}";
        }
        ObjectNode root = JsonUtils.standardMapper().createObjectNode();
        root.put("engine", report.engine());
        root.put("available", report.available());
        root.put("status", report.status());
        root.put("provider", report.provider());
        root.put("model", report.model());
        root.put("chatMode", report.chatMode());
        root.put("contextWindow", report.contextWindow());
        root.put("maxOutputTokens", report.maxOutputTokens());
        root.put("inputBudgetTokens", report.inputBudgetTokens());
        root.put("compactTriggerRatio", report.compactTriggerRatio());
        root.put("memoryEnabled", report.memoryEnabled());
        root.put("ragEnabled", report.ragEnabled());
        root.put("workflowEnabled", report.workflowEnabled());
        root.put("attachmentsSupported", report.attachmentsSupported());
        ArrayNode personas = root.putArray("personas");
        for (Persona persona : report.personas()) {
            ObjectNode node = personas.addObject();
            node.put("name", persona.name());
            node.put("displayName", persona.displayName());
            node.put("description", persona.description());
            node.put("selectorType", persona.selectorType());
            node.put("selectorValue", persona.selectorValue());
            node.put("defaultPersona", persona.defaultPersona());
            node.put("custom", persona.custom());
            node.put("available", persona.available());
        }
        return root.toString();
    }

    private static boolean isRunnable(ChatConfig config) {
        return config != null
                && config.isValid()
                && !"passthrough".equalsIgnoreCase(config.getChatMode())
                && !config.isKompileServer();
    }

    private static String status(ChatConfig config) {
        if (config == null) {
            return "No standard chat configuration found. Run `kompile chat --setup`.";
        }
        if ("passthrough".equalsIgnoreCase(config.getChatMode())) {
            return "Web harness chat requires standard mode; configure a provider with `kompile chat --setup`.";
        }
        if (config.isKompileServer()) {
            return "Web harness chat cannot target the same Kompile chat server recursively; configure a direct or kompile-local provider.";
        }
        if (!config.isValid()) {
            return "The configured provider/model is incomplete. Run `kompile chat --setup`.";
        }
        return "ready";
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
