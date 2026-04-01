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
package ai.kompile.openclaw.service;

import ai.kompile.openclaw.model.AgentDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Registry for managing agent definitions and instances.
 * Supports multiple agent personalities with different tools and prompts.
 */
public interface AgentRegistry {

    /**
     * Register a new agent definition.
     *
     * @param agent The agent definition to register
     */
    void registerAgent(AgentDefinition agent);

    /**
     * Register multiple agent definitions.
     *
     * @param agents The agent definitions to register
     */
    void registerAgents(List<AgentDefinition> agents);

    /**
     * Get an agent definition by name.
     *
     * @param name The agent name
     * @return The agent definition, or empty if not found
     */
    Optional<AgentDefinition> getAgent(String name);

    /**
     * Check if an agent exists.
     *
     * @param name The agent name
     * @return true if the agent is registered
     */
    boolean hasAgent(String name);

    /**
     * List all registered agent definitions.
     *
     * @return List of all agent definitions
     */
    List<AgentDefinition> listAgents();

    /**
     * Remove an agent from the registry.
     *
     * @param name The agent name
     * @return true if the agent was removed
     */
    boolean removeAgent(String name);

    /**
     * Get the default agent.
     *
     * @return The default agent definition
     */
    AgentDefinition getDefaultAgent();

    /**
     * Set the default agent by name.
     *
     * @param name The agent name to set as default
     */
    void setDefaultAgent(String name);
}
