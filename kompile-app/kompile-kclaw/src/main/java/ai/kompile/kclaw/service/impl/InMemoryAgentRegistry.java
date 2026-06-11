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
package ai.kompile.kclaw.service.impl;

import ai.kompile.kclaw.model.AgentDefinition;
import ai.kompile.kclaw.service.AgentRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class InMemoryAgentRegistry implements AgentRegistry {

    private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();
    private volatile String defaultAgentName = "jarvis";

    @Override
    public void registerAgent(AgentDefinition agent) {
        agents.put(agent.getName(), agent);
        if (agent.isDefault()) {
            defaultAgentName = agent.getName();
        }
        log.info("Registered agent: {}", agent.getName());
    }

    @Override
    public void registerAgents(List<AgentDefinition> agentList) {
        for (AgentDefinition agent : agentList) {
            registerAgent(agent);
        }
    }

    @Override
    public Optional<AgentDefinition> getAgent(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    @Override
    public boolean hasAgent(String name) {
        return agents.containsKey(name);
    }

    @Override
    public List<AgentDefinition> listAgents() {
        return new ArrayList<>(agents.values());
    }

    @Override
    public boolean removeAgent(String name) {
        AgentDefinition removed = agents.remove(name);
        if (removed != null) {
            log.info("Removed agent: {}", name);
            return true;
        }
        return false;
    }

    @Override
    public AgentDefinition getDefaultAgent() {
        AgentDefinition def = agents.get(defaultAgentName);
        if (def == null && !agents.isEmpty()) {
            return agents.values().iterator().next();
        }
        return def;
    }

    @Override
    public void setDefaultAgent(String name) {
        if (agents.containsKey(name)) {
            this.defaultAgentName = name;
            log.info("Set default agent to: {}", name);
        } else {
            throw new IllegalArgumentException("Agent not found: " + name);
        }
    }
}
