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
package ai.kompile.orchestrator.integration.cli;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovers available CLI agents at startup, registers each as a persistent
 * extraction LLM provider in the {@link ExtractionLlmServiceRegistry},
 * and shuts them down on application stop.
 *
 * <p>Agent definitions come from {@link CliAgentRegistry} (backed by cli-agents.json),
 * the single source of truth for all CLI agent metadata.</p>
 */
@Component
@Slf4j
public class CliAgentExtractionLlmServiceRegistrar {

    private int extractionTimeout = 300;

    @Autowired(required = false)
    private ExtractionLlmServiceRegistry registry;

    private final List<CliAgentExtractionLlmService> services = new ArrayList<>();

    @PostConstruct
    public void registerCliAgents() {
        if (registry == null) {
            log.debug("ExtractionLlmServiceRegistry not available, skipping CLI agent registration");
            return;
        }

        for (AgentProvider agent : CliAgentRegistry.loadAll()) {
            CliAgentConfig config = toCliAgentConfig(agent);
            if (config.checkAvailability()) {
                CliAgentExtractionLlmService service = new CliAgentExtractionLlmService(
                        config.getName(),
                        config.getDisplayName(),
                        config,
                        extractionTimeout
                );
                services.add(service);
                registry.register(service);
                log.info("Registered CLI extraction LLM provider: {}", config.getName());
            } else {
                log.debug("CLI agent {} not available, skipping", agent.getName());
            }
        }
    }

    /**
     * Convert an {@link AgentProvider} (from cli-agents.json) to a {@link CliAgentConfig}
     * used by the extraction subprocess launcher.
     */
    private static CliAgentConfig toCliAgentConfig(AgentProvider agent) {
        List<String> args = agent.getArgs() != null ? agent.getArgs() : List.of();
        // Detect output format args from the args list
        String outputFormatFlag = null;
        String outputFormat = null;
        String verboseFlag = null;
        for (int i = 0; i < args.size(); i++) {
            if ("--output-format".equals(args.get(i)) && i + 1 < args.size()) {
                outputFormatFlag = args.get(i);
                outputFormat = args.get(i + 1);
            }
            if ("--verbose".equals(args.get(i))) {
                verboseFlag = args.get(i);
            }
        }

        return CliAgentConfig.builder()
                .name(agent.getName())
                .displayName(agent.getDisplayName())
                .command(agent.getCommand())
                .skipPermissionsFlag(agent.getSkipPermissionsFlag())
                .outputFormatFlag(outputFormatFlag)
                .outputFormat(outputFormat)
                .verboseFlag(verboseFlag)
                .promptFlag(null)
                .defaultArgs(args)
                .environment(agent.getEnvironment() != null ? agent.getEnvironment() : Map.of())
                .mcpSupported(agent.isMcpSupported())
                .mcpServerFlag(agent.getMcpServerFlag())
                .mcpConfigFlag(agent.getMcpConfigFlag())
                .mcpAllowToolsFlag(agent.getMcpAllowToolsFlag())
                .defaultAgent(agent.isDefault())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        for (CliAgentExtractionLlmService service : services) {
            try {
                service.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down CLI extraction service {}: {}",
                        service.getId(), e.getMessage());
            }
        }
    }
}
