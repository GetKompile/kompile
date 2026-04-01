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
package ai.kompile.openclaw.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of an agent personality with its configuration.
 */
@Data
@Builder
public class AgentDefinition {

    /**
     * Unique name for this agent.
     */
    private String name;

    /**
     * Human-readable description.
     */
    private String description;

    /**
     * System prompt / soul for this agent.
     */
    private String systemPrompt;

    /**
     * List of tool names this agent can use.
     */
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    /**
     * Model preferences (provider, model name, temperature, etc.).
     */
    @Builder.Default
    private Map<String, Object> modelPreferences = new HashMap<>();

    /**
     * Maximum steps before requiring user input.
     */
    @Builder.Default
    private int maxSteps = 20;

    /**
     * Whether this agent is the default.
     */
    @Builder.Default
    private boolean isDefault = false;

    /**
     * Agent type for multi-agent orchestration.
     */
    @Builder.Default
    private AgentType type = AgentType.SINGLE;

    /**
     * Capabilities this agent provides.
     */
    @Builder.Default
    private List<String> capabilities = new ArrayList<>();

    /**
     * Tags for categorization.
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Agent type enumeration.
     */
    public enum AgentType {
        SINGLE,
        SUPERVISOR,
        WORKER,
        SPECIALIST
    }

    public static AgentDefinitionBuilder defaultAgent() {
        return AgentDefinition.builder()
                .name("default")
                .description("Default AI assistant")
                .systemPrompt("You are a helpful AI assistant.")
                .isDefault(true);
    }
}
