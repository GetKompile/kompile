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
package ai.kompile.kclaw.config;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.agent.ToolkitRegistry;
import ai.kompile.kclaw.gateway.KClawWebSocketHandler;
import ai.kompile.kclaw.gateway.channel.*;
import ai.kompile.kclaw.model.AgentDefinition;
import ai.kompile.kclaw.service.AgentRegistry;
import ai.kompile.kclaw.service.HeartbeatScheduler;
import ai.kompile.kclaw.service.PermissionService;
import ai.kompile.kclaw.service.SessionService;
import ai.kompile.kclaw.service.impl.DefaultPermissionService;
import ai.kompile.kclaw.service.impl.InMemoryAgentRegistry;
import ai.kompile.kclaw.service.impl.JsonlSessionService;
import ai.kompile.kclaw.tool.MemoryTool;
import ai.kompile.kclaw.tool.ShellExecutionTool;
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
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSocket
public class KClawAutoConfiguration implements WebSocketConfigurer {

    @Autowired(required = false)
    private KClawWebSocketHandler webSocketHandler;
    private KClawConfig config;

    @Bean
    @ConditionalOnMissingBean
    public KClawConfig kClawConfig() {
        this.config = KClawConfig.defaults();
        return config;
    }

    @Bean("kclawSessionService")
    @ConditionalOnMissingBean
    public SessionService sessionService(KClawConfig kClawConfig) {
        try {
            return new JsonlSessionService(kClawConfig.getWorkspace());
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize session service", e);
        }
    }

    @Bean("kclawAgentRegistry")
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry() {
        AgentRegistry registry = new InMemoryAgentRegistry();
        registerDefaultAgents(registry);
        return registry;
    }

    @Bean("kclawPermissionService")
    @ConditionalOnMissingBean
    public PermissionService permissionService() {
        return new DefaultPermissionService();
    }

    @Bean("kclawShellExecutionTool")
    public ShellExecutionTool shellExecutionTool(PermissionService permissionService) {
        return new ShellExecutionTool(permissionService);
    }

    @Bean("kclawMemoryTool")
    public MemoryTool memoryTool(KClawConfig kClawConfig, PermissionService permissionService) {
        MemoryTool tool = new MemoryTool(kClawConfig.getWorkspace(), permissionService);
        try {
            tool.init();
        } catch (IOException e) {
            log.warn("Failed to initialize memory tool", e);
        }
        return tool;
    }

    @Bean("kclawToolkitRegistry")
    public ToolkitRegistry toolkitRegistry(
            ShellExecutionTool shellTool,
            MemoryTool memoryTool,
            org.springframework.context.ApplicationContext applicationContext) {
        ToolkitRegistry registry = new ToolkitRegistry(shellTool, memoryTool);

        // Auto-discover Spring AI @Tool-annotated beans on the classpath
        // Use ApplicationContext to avoid circular dependency with eager List<Object> injection
        try {
            for (String beanName : applicationContext.getBeanDefinitionNames()) {
                try {
                    Object bean = applicationContext.getBean(beanName);
                    if (hasToolAnnotation(bean)) {
                        registry.registerSpringAiTools(bean);
                    }
                } catch (Exception e) {
                    // Skip beans that can't be instantiated (circular refs, etc.)
                }
            }
        } catch (Exception e) {
            log.debug("Error during tool auto-discovery: {}", e.getMessage());
        }

        log.info("ToolkitRegistry initialized with {} tools: {}", registry.getToolNames().size(), registry.getToolNames());
        return registry;
    }

    private boolean hasToolAnnotation(Object bean) {
        try {
            Class<?> toolAnnotation = Class.forName("org.springframework.ai.tool.annotation.Tool");
            for (java.lang.reflect.Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) toolAnnotation)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException e) {
            // Spring AI not on classpath
        }
        return false;
    }

    @Bean
    @ConditionalOnBean(ai.kompile.react.service.ReActAgentService.class)
    public KClawAgentService kClawAgentService(
            SessionService sessionService,
            AgentRegistry agentRegistry,
            ToolkitRegistry toolkitRegistry,
            ai.kompile.react.service.ReActAgentService reActAgentService) {
        return new KClawAgentService(reActAgentService, agentRegistry, sessionService, toolkitRegistry);
    }

    @Bean("kclawChannelManager")
    @ConditionalOnBean(KClawAgentService.class)
    public ChannelManager channelManager(KClawAgentService agentService) {
        ChannelManager manager = new ChannelManager();
        
        manager.registerAdapter(new TelegramChannelAdapter(agentService));
        manager.registerAdapter(new DiscordChannelAdapter(agentService));
        manager.registerAdapter(new SlackChannelAdapter(agentService));
        manager.registerAdapter(new WhatsAppChannelAdapter(agentService));
        manager.registerAdapter(new EmailChannelAdapter(agentService));
        
        return manager;
    }

    @Bean
    @ConditionalOnBean(KClawAgentService.class)
    public KClawWebSocketHandler kClawWebSocketHandler(
            KClawAgentService agentService,
            ObjectMapper objectMapper) {
        return new KClawWebSocketHandler(agentService, objectMapper);
    }

    // KClawController and ChannelController are @RestController classes
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
                .tools(java.util.List.of("run_command", "save_memory", "search_memory",
                        "knowledge_search", "knowledge_status"))
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
                        Use knowledge_search to find information from indexed documents and knowledge graphs.
                        Save important findings to memory for other agents.
                        """)
                .tools(java.util.List.of("knowledge_search", "knowledge_status",
                        "search_memory", "save_memory"))
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
            log.debug("KClaw WebSocket handler not available, skipping registration");
            return;
        }
        KClawConfig cfg = kClawConfig();
        if (cfg.getGateway().isWebsocketEnabled()) {
            String path = cfg.getGateway().getWebsocketPath();
            if (path == null || path.isEmpty()) {
                path = "/ws/kclaw";
            }
            registry.addHandler(webSocketHandler, path)
                    .setAllowedOrigins("*");
            log.info("WebSocket endpoint registered at {}", path);
        }
    }
}
