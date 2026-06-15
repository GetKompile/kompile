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

package ai.kompile.app.config;

import ai.kompile.a2a.server.A2AServerController;
import ai.kompile.a2a.service.A2AServerService;
import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Wires the A2A module into kompile-app-main by connecting the A2A server
 * to the AgentChatService backend and providing the agent list supplier.
 * <p>
 * This bridges the A2A protocol's abstract interfaces with kompile's concrete
 * agent execution infrastructure (subprocess-based CLI agents and API agents).
 * Runtime enable/disable is handled by {@link ai.kompile.a2a.config.A2AConfigService}.
 * <p>
 * Uses string-form {@link ConditionalOnClass} so Spring skips this entire class
 * when the {@code kompile-a2a} module is not on the classpath. When it IS present,
 * all direct imports resolve normally.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "ai.kompile.a2a.service.A2AServerService")
@ConditionalOnBean({AgentChatService.class})
public class A2AWiringConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(A2AWiringConfiguration.class);

    @Autowired
    private A2AServerService serverService;

    @Autowired
    private AgentChatService agentChatService;

    @Autowired
    private AgentRegistryService agentRegistryService;

    @Autowired(required = false)
    private A2AServerController serverController;

    @PostConstruct
    public void wireA2AToAgentChat() {
        // Bridge A2A task execution to kompile's AgentChatService
        serverService.setAgentBackend((agentName, prompt, chunkConsumer) -> {
            String resolvedAgent = resolveAgentName(agentName);

            AgentChatRequest request = new AgentChatRequest();
            request.setMessage(prompt);
            request.setAgentName(resolvedAgent);
            request.setSkipPermissions(true);
            request.setInjectMcpTools(true);
            request.setTimeoutSeconds(300);

            AgentChatService.SyncChatResult result = agentChatService.executeChatSync(request, 300);

            if (result.error() != null && !result.error().isEmpty()) {
                throw new RuntimeException("Agent execution failed: " + result.error());
            }

            return result.content();
        });

        // Provide the agent list to the A2A server controller
        if (serverController != null) {
            serverController.setAgentListSupplier(() ->
                    agentRegistryService.getAllAgents().stream()
                            .filter(AgentProvider::isAvailable)
                            .toList());
        }

        logger.info("A2A module wired to AgentChatService — A2A server ready");
    }

    private String resolveAgentName(String agentName) {
        if (agentName != null && !"default".equals(agentName)) {
            if (agentRegistryService.getAgent(agentName).isPresent()) {
                return agentName;
            }
        }
        return agentRegistryService.getDefaultAgent()
                .map(AgentProvider::getName)
                .orElse("claude-cli");
    }
}
