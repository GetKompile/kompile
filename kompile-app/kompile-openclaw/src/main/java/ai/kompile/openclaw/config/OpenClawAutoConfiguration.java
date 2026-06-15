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
package ai.kompile.openclaw.config;

import ai.kompile.openclaw.agent.OpenClawAgentService;
import ai.kompile.openclaw.agent.ToolkitRegistry;
import ai.kompile.openclaw.gateway.OpenClawWebSocketHandler;
import ai.kompile.openclaw.gateway.channel.*;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.model.AgentDefinition;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.HeartbeatScheduler;
import ai.kompile.gateway.core.service.PermissionService;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.gateway.core.service.impl.DefaultPermissionService;
import ai.kompile.gateway.core.service.impl.InMemoryAgentRegistry;
import ai.kompile.openclaw.service.impl.JsonlSessionService;
import ai.kompile.openclaw.tool.MemoryTool;
import ai.kompile.openclaw.tool.ShellExecutionTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class OpenClawAutoConfiguration implements WebSocketConfigurer {

    @Autowired(required = false)
    private OpenClawWebSocketHandler webSocketHandler;
    private OpenClawConfig config;

    @Bean
    @ConditionalOnMissingBean
    public OpenClawConfig openClawConfig() {
        this.config = OpenClawConfig.defaults();
        return config;
    }

    @Bean("openclawSessionService")
    @ConditionalOnMissingBean
    public SessionService sessionService(OpenClawConfig openClawConfig) {
        try {
            return new JsonlSessionService(openClawConfig.getWorkspace());
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize session service", e);
        }
    }

    @Bean("openclawAgentRegistry")
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry() {
        AgentRegistry registry = new InMemoryAgentRegistry();
        registerDefaultAgents(registry);
        return registry;
    }

    @Bean("openclawPermissionService")
    @ConditionalOnMissingBean
    public PermissionService permissionService() {
        return new DefaultPermissionService();
    }

    @Bean("openclawShellExecutionTool")
    public ShellExecutionTool shellExecutionTool(PermissionService permissionService) {
        return new ShellExecutionTool(permissionService);
    }

    @Bean("openclawMemoryTool")
    public MemoryTool memoryTool(OpenClawConfig openClawConfig, PermissionService permissionService) {
        MemoryTool tool = new MemoryTool(openClawConfig.getWorkspace(), permissionService);
        try {
            tool.init();
        } catch (IOException e) {
            log.warn("Failed to initialize memory tool", e);
        }
        return tool;
    }

    @Bean("openclawToolkitRegistry")
    public ToolkitRegistry toolkitRegistry(ShellExecutionTool shellTool, MemoryTool memoryTool) {
        return new ToolkitRegistry(shellTool, memoryTool);
    }

    @Bean
    @ConditionalOnBean(ai.kompile.react.service.ReActAgentService.class)
    public OpenClawAgentService openClawAgentService(
            SessionService sessionService,
            AgentRegistry agentRegistry,
            ToolkitRegistry toolkitRegistry,
            ai.kompile.react.service.ReActAgentService reActAgentService) {
        return new OpenClawAgentService(reActAgentService, agentRegistry, sessionService, toolkitRegistry);
    }

    @Bean("openclawChannelManager")
    @ConditionalOnBean(OpenClawAgentService.class)
    public ChannelManager channelManager(OpenClawAgentService agentService) {
        ChannelManager manager = new ChannelManager();
        
        manager.registerAdapter(new TelegramChannelAdapter(agentService));
        manager.registerAdapter(new DiscordChannelAdapter(agentService));
        manager.registerAdapter(new SlackChannelAdapter(agentService));
        manager.registerAdapter(new WhatsAppChannelAdapter(agentService));
        manager.registerAdapter(new EmailChannelAdapter(agentService));
        
        return manager;
    }

    @Bean
    @ConditionalOnBean(OpenClawAgentService.class)
    public OpenClawWebSocketHandler openClawWebSocketHandler(
            OpenClawAgentService agentService,
            ObjectMapper objectMapper) {
        return new OpenClawWebSocketHandler(agentService, objectMapper);
    }

    // OpenClawController and ChannelController are @RestController classes
    // with @ConditionalOnBean — they are created via component scanning, not here.

    private void registerDefaultAgents(AgentRegistry registry) {
        registry.registerAgent(AgentDefinition.builder()
                .name("jarvis")
                .description("Main personal AI assistant")
                .systemPrompt("""
                        You are Jarvis, a personal AI assistant.
                        Be genuinely helpful, not performatively helpful.
                        Skip the "Great question!" - just help.
                        Have opinions. You're allowed to disagree.
                        Be concise when needed, thorough when it matters.
                        Use tools when you need to take action or remember information.
                        """)
                .tools(java.util.List.of("run_command", "save_memory", "search_memory"))
                .maxSteps(20)
                .isDefault(true)
                .build());

        registry.registerAgent(AgentDefinition.builder()
                .name("researcher")
                .description("Research specialist for information gathering")
                .systemPrompt("""
                        You are Scout, a research specialist.
                        Your job: find information and cite sources.
                        Every claim needs evidence.
                        Save important findings to memory for other agents.
                        """)
                .tools(java.util.List.of("search_memory", "save_memory"))
                .maxSteps(15)
                .build());

        registry.registerAgent(AgentDefinition.builder()
                .name("coder")
                .description("Coding and development specialist")
                .systemPrompt("""
                        You are a coding assistant.
                        Write clean, well-documented code.
                        Use run_command for git operations and file system tasks.
                        """)
                .tools(java.util.List.of("run_command", "save_memory"))
                .maxSteps(25)
                .build());

        log.info("Registered {} default agents", registry.listAgents().size());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (webSocketHandler == null) {
            log.debug("OpenClaw WebSocket handler not available, skipping registration");
            return;
        }
        OpenClawConfig cfg = openClawConfig();
        if (cfg.getGateway().isWebsocketEnabled()) {
            String path = cfg.getGateway().getWebsocketPath();
            if (path == null || path.isEmpty()) {
                path = "/ws/openclaw";
            }
            registry.addHandler(webSocketHandler, path)
                    .setAllowedOrigins("*");
            log.info("WebSocket endpoint registered at {}", path);
        }
    }
}
