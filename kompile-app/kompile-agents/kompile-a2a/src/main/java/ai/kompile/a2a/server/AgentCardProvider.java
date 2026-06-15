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

package ai.kompile.a2a.server;

import ai.kompile.a2a.model.AgentCard;
import ai.kompile.a2a.model.AgentCard.AgentCapabilities;
import ai.kompile.a2a.model.AgentCard.AgentSkill;
import ai.kompile.core.agent.AgentProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates A2A {@link AgentCard} instances from kompile {@link AgentProvider} configurations.
 * This bridges kompile's internal agent model to the A2A protocol's discovery format.
 */
public class AgentCardProvider {

    private static final String PROTOCOL_VERSION = "1.0";

    /**
     * Build an AgentCard from a kompile AgentProvider.
     *
     * @param provider the kompile agent configuration
     * @param baseUrl  the base URL where this agent's A2A endpoint is hosted
     * @return an A2A AgentCard ready to serve at .well-known/agent-card.json
     */
    public static AgentCard fromAgentProvider(AgentProvider provider, String baseUrl) {
        List<AgentSkill> skills = new ArrayList<>();

        // The agent's primary skill is executing tasks via chat
        skills.add(AgentSkill.builder()
                .id(provider.getName() + "_chat")
                .name("Chat with " + displayName(provider))
                .description("Send a task or question to " + displayName(provider) +
                        " for code generation, analysis, or general assistance")
                .tags(List.of("chat", "code", "agent", provider.getName()))
                .examples(List.of(
                        "Write a function that sorts a list of integers",
                        "Analyze this code for potential bugs",
                        "Explain how this module works"))
                .inputModes(List.of("text"))
                .outputModes(List.of("text"))
                .build());

        // If MCP is supported, add a tool-use skill
        if (provider.isMcpSupported()) {
            skills.add(AgentSkill.builder()
                    .id(provider.getName() + "_mcp_tools")
                    .name("Tool-augmented tasks")
                    .description("Execute tasks with access to MCP tools including RAG search, " +
                            "file system, and document management")
                    .tags(List.of("tools", "mcp", "rag"))
                    .inputModes(List.of("text"))
                    .outputModes(List.of("text"))
                    .build());
        }

        return AgentCard.builder()
                .name(provider.getName())
                .description(provider.getDescription() != null
                        ? provider.getDescription()
                        : displayName(provider) + " agent available via kompile A2A")
                .version("1.0.0")
                .url(baseUrl)
                .protocolVersion(PROTOCOL_VERSION)
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .stateTransitionHistory(true)
                        .build())
                .skills(skills)
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .build();
    }

    /**
     * Build a composite AgentCard that represents the kompile platform as a whole.
     */
    public static AgentCard platformCard(String baseUrl, List<AgentProvider> availableAgents) {
        List<AgentSkill> skills = new ArrayList<>();

        for (AgentProvider agent : availableAgents) {
            if (agent.isAvailable()) {
                skills.add(AgentSkill.builder()
                        .id(agent.getName())
                        .name(displayName(agent))
                        .description("Delegate tasks to " + displayName(agent))
                        .tags(List.of("agent", agent.getName()))
                        .inputModes(List.of("text"))
                        .outputModes(List.of("text"))
                        .build());
            }
        }

        // Add RAG skill
        skills.add(AgentSkill.builder()
                .id("rag_query")
                .name("RAG Query")
                .description("Search indexed documents using retrieval-augmented generation")
                .tags(List.of("rag", "search", "documents"))
                .inputModes(List.of("text"))
                .outputModes(List.of("text"))
                .build());

        return AgentCard.builder()
                .name("kompile")
                .description("Kompile AI platform — multi-agent orchestration with RAG, " +
                        "code generation, and document intelligence")
                .version("0.1.0")
                .url(baseUrl)
                .protocolVersion(PROTOCOL_VERSION)
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .stateTransitionHistory(true)
                        .build())
                .skills(skills)
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .build();
    }

    private static String displayName(AgentProvider provider) {
        return provider.getDisplayName() != null ? provider.getDisplayName() : provider.getName();
    }
}
