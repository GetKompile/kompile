/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.config;

import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelBrowserLoginView;
import ai.kompile.channel.api.ChannelBrowserSessionRequest;
import ai.kompile.channel.api.ChannelBrowserSessionView;
import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelConnectionUpdate;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.channel.api.ChannelTestResult;
import ai.kompile.channel.api.TelegramDiagnosticsView;
import ai.kompile.channel.api.TelegramPairingApprovalRequest;
import ai.kompile.channel.api.TelegramPairingStartView;
import ai.kompile.channel.api.TelegramPairingView;
import ai.kompile.channel.api.TelegramWebhookInfoView;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.gateway.integration.ChannelCredentialResolver;
import ai.kompile.kclaw.gateway.integration.ChannelEngineRegistry;
import ai.kompile.kclaw.gateway.integration.ChannelConnectionStore;
import ai.kompile.kclaw.gateway.integration.ChannelIntegrationService;
import ai.kompile.kclaw.gateway.integration.ChannelProviderCatalog;
import ai.kompile.kclaw.gateway.integration.ChannelRuntimeFactory;
import ai.kompile.kclaw.gateway.integration.KompileCliChannelExecutor;
import ai.kompile.kclaw.gateway.integration.WebChatChannelExecutor;
import ai.kompile.kclaw.task.KompileCliRunner;
import ai.kompile.kclaw.gateway.telegram.TelegramBotLeaseRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramPairingRegistry;
import ai.kompile.kclaw.gateway.security.ChannelControlSecurity;
import ai.kompile.kclaw.gateway.whatsapp.WhatsAppWebhookInbox;
import ai.kompile.oauth.service.OAuthConnectionService;
import ai.kompile.oauth.service.TokenEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Admin-persona ownership for channel persistence and live runtimes.
 *
 * <p>KClaw is also present in the crawl-manager classpath for enrichment agents. Keeping these
 * beans in the admin web module prevents two JVMs from restoring the same polling/socket
 * connections and racing over one credential store.</p>
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
        ChannelBrowserLoginView.class,
        ChannelBrowserSessionRequest.class,
        ChannelBrowserSessionView.class,
        ChannelConnectionRequest.class,
        ChannelConnectionUpdate.class,
        ChannelConnectionView.class,
        ChannelEngineDescriptor.class,
        ChannelProviderDescriptor.class,
        ChannelProviderAuthView.class,
        ChannelProviderAuthView.Mode.class,
        ChannelTestRequest.class,
        ChannelTestResult.class,
        TelegramDiagnosticsView.class,
        TelegramPairingApprovalRequest.class,
        TelegramPairingStartView.class,
        TelegramPairingView.class,
        TelegramWebhookInfoView.class
})
public class ChannelIntegrationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChannelProviderCatalog channelProviderCatalog() {
        return new ChannelProviderCatalog();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelCredentialResolver channelCredentialResolver(
            ChannelProviderCatalog catalog,
            ObjectProvider<OAuthConnectionService> oauth) {
        return new ChannelCredentialResolver(catalog, oauth.getIfAvailable(), System::getenv);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelConnectionStore channelConnectionStore(
            @Value("${kompile.data.dir:${user.home}/.kompile}") String kompileDataDir,
            ObjectMapper objectMapper) {
        try {
            return new ChannelConnectionStore(
                    Path.of(kompileDataDir, "kclaw", "channel-connections.json"),
                    objectMapper);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize channel connection store", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public KompileCliChannelExecutor kompileCliChannelExecutor(
            KompileCliRunner runner,
            @Qualifier("kclawSessionService") SessionService sessions) {
        return new KompileCliChannelExecutor(runner, sessions);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebChatChannelExecutor webChatChannelExecutor(ChannelControlSecurity security) {
        return new WebChatChannelExecutor(
                security::opaqueChannelConversationId,
                security::signInternalChat);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelEngineRegistry channelEngineRegistry(
            ObjectProvider<KClawAgentService> react,
            KompileCliChannelExecutor cli,
            WebChatChannelExecutor webChat) {
        return new ChannelEngineRegistry()
                .register(ChannelChatEngine.REACT, "ReAct agent",
                        "Kompile's in-process tool-using ReAct agent.",
                        react::getIfAvailable,
                        () -> react.getIfAvailable() != null,
                        () -> "ReAct model is not configured")
                .register(ChannelChatEngine.KOMPILE_CLI, "Kompile CLI",
                        "The configured `kompile exec --json` CLI agent.",
                        () -> cli, cli::isAvailable,
                        () -> "kompile executable not found")
                .register(ChannelChatEngine.WEB_CHAT, "Web chat",
                        "The conversational RAG service used by the web chat application.",
                        () -> webChat, webChat::isAvailable,
                        () -> "chat service or internal authentication is unavailable");
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramPairingRegistry telegramPairingRegistry() {
        return new TelegramPairingRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotLeaseRegistry telegramBotLeaseRegistry() {
        return new TelegramBotLeaseRegistry();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public WhatsAppWebhookInbox whatsAppWebhookInbox(
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir,
            ObjectMapper mapper,
            ChannelManager channelManager) {
        WhatsAppWebhookInbox inbox = new WhatsAppWebhookInbox(
                Path.of(dataDir, "kclaw", "whatsapp-inbox"), mapper, channelManager);
        inbox.start();
        return inbox;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelRuntimeFactory channelRuntimeFactory(
            ChannelEngineRegistry engines,
            ApplicationEventPublisher eventPublisher,
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir,
            ObjectMapper mapper,
            TelegramPairingRegistry pairings,
            TelegramBotLeaseRegistry leases) {
        return new ChannelRuntimeFactory(
                engines,
                eventPublisher,
                Path.of(dataDir, "kclaw", "telegram-state"),
                mapper,
                pairings,
                leases);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelIntegrationService channelIntegrationService(
            ChannelConnectionStore store,
            ChannelProviderCatalog catalog,
            ChannelEngineRegistry engines,
            @Qualifier("kclawAgentRegistry") AgentRegistry agentRegistry,
            TelegramPairingRegistry pairings,
            ChannelRuntimeFactory runtimeFactory,
            ChannelManager channelManager,
            TokenEncryptionService encryptionService,
            ChannelCredentialResolver credentialResolver) {
        return new ChannelIntegrationService(
                store, catalog, engines, agentRegistry, pairings,
                runtimeFactory, channelManager, encryptionService, credentialResolver);
    }
}
