/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.main.chat.LocalServingRuntimePool;
import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;

import java.util.Locale;
import java.util.Objects;

/**
 * How a participant's {@link WorkflowTeam.ModelBinding} runs: an isolated chat
 * configuration for in-process delegation, or a CLI agent for delegation through
 * managed CLI agents.
 */
public final class WorkflowLaunch {

    /** Matches the default of {@code kompile chat --startup-timeout}. */
    static final int LOCAL_SERVING_STARTUP_TIMEOUT_SECONDS = 120;

    private WorkflowLaunch() {}

    /**
     * The chat configuration a participant runs on. Without a binding it is the
     * parent's own configuration. A binding on the parent's route keeps the
     * parent's credential selection; any other route resolves its own stored
     * credential, so the parent's key never reaches another provider. A bound
     * participant runs exactly its binding: the parent's fast mode, ultracode and
     * per-model token limits do not carry over to a different model.
     */
    public static ChatConfig configFor(ChatConfig parent, WorkflowTeam.ModelBinding binding) {
        Objects.requireNonNull(parent, "parent");
        ChatConfig child = parent.copy();
        if (binding == null) return child;
        if (binding.agent() != null) {
            throw new IllegalArgumentException(binding.label()
                    + " runs through a CLI agent, not an in-process chat");
        }
        if (sameRoute(parent, binding)) {
            child.setModel(binding.model());
            child.setThinking(binding.thinking());
        } else {
            ChatConfig route = new ChatConfig(binding.provider(), null, binding.model(), binding.baseUrl());
            route.setThinking(binding.thinking());
            route.setAuthenticationMethod(binding.authenticationMethod());
            child.applyLlmSettingsFrom(route);
        }
        child.setFastMode(false);
        child.setUltracode(false);
        if (!binding.model().equals(parent.getModel())) {
            child.setContextWindowTokens(0);
            child.setMaxOutputTokens(0);
        }
        if (child.isKompileLocalServing()) {
            // Lazy: the serving runtime starts on the participant's first request.
            LocalServingRuntimePool.Binding serving = parent.getLocalServingBinding();
            child.setLocalServingBinding(serving != null
                    ? serving.forChatConfig(child)
                    : LocalServingRuntimePool.bindingFor(child, LOCAL_SERVING_STARTUP_TIMEOUT_SECONDS));
        }
        return child;
    }

    /**
     * The CLI agent that runs a binding when delegation goes through managed CLI
     * agents: the binding's own agent, else its vendor's agent (the inverse of how
     * chat profiles name a CLI agent's vendor). Null when no supported agent runs
     * it; callers refuse rather than fall back to another agent.
     */
    public static String agentFor(WorkflowTeam.ModelBinding binding) {
        if (binding == null) return null;
        if (binding.agent() != null) return AgentLaunchDefaults.normalizeSupportedAgent(binding.agent());
        String vendor = SetupWizard.vendorForProvider(binding.provider());
        String agent = switch (vendor == null ? "" : vendor.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> "claude";
            case "openai" -> "codex";
            default -> vendor;
        };
        return AgentLaunchDefaults.normalizeSupportedAgent(agent);
    }

    /**
     * Whether a participant on this binding runs its own tools (a CLI agent, or
     * the Claude Code and OpenCode native routes), so the workflow's per-tool
     * restrictions cannot reach it. Delegation and gates still apply.
     */
    public static boolean runsOwnTools(WorkflowTeam.ModelBinding binding) {
        return binding != null && (binding.agent() != null
                || ChatConfig.isClaudeCliNative(binding.provider(), binding.authenticationMethod())
                || "opencode".equals(binding.provider()));
    }

    private static boolean sameRoute(ChatConfig parent, WorkflowTeam.ModelBinding binding) {
        return binding.provider().equals(lower(parent.getProvider()))
                && Objects.equals(binding.baseUrl(), clean(parent.getBaseUrl()))
                && lower(binding.authenticationMethod()).equals(lower(parent.getAuthenticationMethod()));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
