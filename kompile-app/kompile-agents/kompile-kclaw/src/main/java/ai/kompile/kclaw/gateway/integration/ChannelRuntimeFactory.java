/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.DefaultEmailClient;
import ai.kompile.gateway.core.gateway.channel.DefaultTelegramApiClient;
import ai.kompile.gateway.core.gateway.channel.EmailClient;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.service.AgentExecutor;
import ai.kompile.kclaw.gateway.channel.DefaultDiscordApiClient;
import ai.kompile.kclaw.gateway.channel.DefaultSlackApiClient;
import ai.kompile.kclaw.gateway.channel.DefaultWhatsAppApiClient;
import ai.kompile.kclaw.gateway.channel.DiscordChannelAdapter;
import ai.kompile.kclaw.gateway.channel.EmailChannelAdapter;
import ai.kompile.kclaw.gateway.channel.SlackChannelAdapter;
import ai.kompile.kclaw.gateway.channel.TelegramChannelAdapter;
import ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter;
import ai.kompile.kclaw.gateway.telegram.TelegramBotLeaseRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramPairingRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramRuntimeStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Path;

/** Creates an isolated adapter runtime for each named connection. */
public class ChannelRuntimeFactory {

    private final ChannelEngineRegistry engines;
    private final ApplicationEventPublisher eventPublisher;
    private final Path telegramStateDirectory;
    private final ObjectMapper mapper;
    private final TelegramPairingRegistry pairings;
    private final TelegramBotLeaseRegistry telegramLeases;

    public ChannelRuntimeFactory(
            ChannelEngineRegistry engines,
            ApplicationEventPublisher eventPublisher,
            Path telegramStateDirectory,
            ObjectMapper mapper,
            TelegramPairingRegistry pairings,
            TelegramBotLeaseRegistry telegramLeases) {
        this.engines = engines;
        this.eventPublisher = eventPublisher;
        this.telegramStateDirectory = telegramStateDirectory;
        this.mapper = mapper;
        this.pairings = pairings;
        this.telegramLeases = telegramLeases;
    }

    public ChannelAdapter create(
            StoredChannelConnection connection,
            Map<String, String> secrets) {
        AgentExecutor delegate = engines.require(connection.engine());
        AgentExecutor executor = request -> delegate.execute(withConnectionMetadata(request, connection));

        BaseChannelAdapter adapter = switch (connection.providerId()) {
            case "telegram" -> telegram(executor, connection, secrets);
            case "slack" -> slack(executor, connection.settings(), secrets);
            case "discord" -> discord(executor, connection.settings(), secrets);
            case "whatsapp" -> whatsapp(executor, connection.settings(), secrets);
            case "email" -> email(executor, connection.settings(), secrets);
            default -> throw new IllegalArgumentException(
                    "Unsupported channel provider: " + connection.providerId());
        };
        adapter.setEventPublisher(eventPublisher);
        adapter.updateConfig(new ChannelAdapter.AdapterConfig(
                "*",
                connection.agentId(),
                true,
                connection.name() + ":",
                16_000,
                false,
                false));
        return adapter;
    }

    /** Remove the durable bot checkpoint when its named connection is permanently deleted. */
    public void deleteTelegramState(String connectionName) {
        try {
            new TelegramRuntimeStateStore(
                    telegramStateDirectory.resolve(connectionName + ".json"), mapper).delete();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not delete Telegram checkpoint", e);
        }
    }

    /** Mark explicit webhook takeover so first polling does not discard Telegram's queued updates. */
    public void initializeTelegramPolling(String connectionName, long botId) {
        try {
            TelegramRuntimeStateStore store = new TelegramRuntimeStateStore(
                    telegramStateDirectory.resolve(connectionName + ".json"), mapper);
            if (!store.forBot(botId).initialized()) {
                store.initialize(botId, 0L);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not initialize Telegram checkpoint", e);
        }
    }

    private static AgentRequest withConnectionMetadata(
            AgentRequest request, StoredChannelConnection connection) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getMetadata() != null) metadata.putAll(request.getMetadata());
        metadata.put("connectionName", connection.name());
        metadata.put("engine", connection.engine().name());
        if (connection.model() != null && !connection.model().isBlank()) {
            metadata.put("model", connection.model());
        }
        return AgentRequest.builder()
                .agentId(request.getAgentId())
                .sessionKey(request.getSessionKey())
                .message(request.getMessage())
                .stream(request.isStream())
                .metadata(Map.copyOf(metadata))
                .build();
    }

    private TelegramChannelAdapter telegram(
            AgentExecutor executor,
            StoredChannelConnection connection,
            Map<String, String> secrets) {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(executor);
        DefaultTelegramApiClient client = new DefaultTelegramApiClient(secrets.get("botToken"));
        try {
            adapter.configureRuntime(
                    connection.name(),
                    new TelegramRuntimeStateStore(
                            telegramStateDirectory.resolve(connection.name() + ".json"), mapper),
                    pairings,
                    telegramLeases);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load Telegram checkpoint", e);
        }
        adapter.setApiClient(client);
        longs(connection.settings(), "allowedChatIds").forEach(adapter::addAllowedChat);
        adapter.setAllowAllInbound(bool(connection.settings(), "allowAllInbound"));
        return adapter;
    }

    private static SlackChannelAdapter slack(
            AgentExecutor executor,
            Map<String, Object> settings,
            Map<String, String> secrets) {
        SlackChannelAdapter adapter = new SlackChannelAdapter(executor);
        DefaultSlackApiClient client = new DefaultSlackApiClient();
        if (!client.validateCredentials(secrets.get("botToken"), secrets.get("appToken"))) {
            throw new IllegalStateException("Slack rejected the configured credentials");
        }
        adapter.setApiClient(client);
        adapter.setBotToken(secrets.get("botToken"));
        adapter.setAppToken(secrets.get("appToken"));
        strings(settings, "allowedChannelIds").forEach(adapter::addAllowedChannel);
        adapter.setRespondToAllMessages(bool(settings, "respondToAllMessages"));
        adapter.setAllowAllInbound(bool(settings, "allowAllInbound"));
        return adapter;
    }

    private static DiscordChannelAdapter discord(
            AgentExecutor executor,
            Map<String, Object> settings,
            Map<String, String> secrets) {
        DiscordChannelAdapter adapter = new DiscordChannelAdapter(executor);
        DefaultDiscordApiClient client = new DefaultDiscordApiClient();
        if (!client.validateCredentials(secrets.get("botToken"))) {
            throw new IllegalStateException("Discord rejected the bot token");
        }
        adapter.setApiClient(client);
        adapter.setBotToken(secrets.get("botToken"));
        strings(settings, "allowedChannelIds").forEach(adapter::addAllowedChannel);
        strings(settings, "allowedGuildIds").forEach(adapter::addAllowedGuild);
        adapter.setAllowAllInbound(bool(settings, "allowAllInbound"));
        return adapter;
    }

    private static WhatsAppChannelAdapter whatsapp(
            AgentExecutor executor,
            Map<String, Object> settings,
            Map<String, String> secrets) {
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter(executor);
        DefaultWhatsAppApiClient client = new DefaultWhatsAppApiClient();
        if (!client.validateCredentials(
                secrets.get("accessToken"), string(settings, "phoneNumberId"))) {
            throw new IllegalStateException("WhatsApp rejected the configured credentials");
        }
        adapter.setApiClient(client);
        adapter.setAccessToken(secrets.get("accessToken"));
        adapter.setVerifyToken(secrets.get("verifyToken"));
        adapter.setAppSecret(secrets.get("appSecret"));
        adapter.setPhoneNumberId(string(settings, "phoneNumberId"));
        strings(settings, "allowedPhoneNumbers").forEach(adapter::addAllowedPhone);
        adapter.setAllowAllInbound(bool(settings, "allowAllInbound"));
        return adapter;
    }

    private static EmailChannelAdapter email(
            AgentExecutor executor,
            Map<String, Object> settings,
            Map<String, String> secrets) {
        EmailChannelAdapter adapter = new EmailChannelAdapter(executor);
        adapter.setEmailClient(new DefaultEmailClient());
        adapter.setEmailConfig(new EmailClient.EmailConfig(
                string(settings, "imapHost"),
                integer(settings, "imapPort"),
                string(settings, "username"),
                secrets.get("password"),
                "imaps",
                true,
                true,
                string(settings, "smtpHost"),
                integer(settings, "smtpPort"),
                string(settings, "fromAddress"),
                string(settings, "fromName"),
                integer(settings, "pollIntervalSeconds"),
                string(settings, "trustedAuthenticationServer")));
        strings(settings, "allowedSenders").forEach(adapter::addAllowedSender);
        adapter.setAllowAllInbound(bool(settings, "allowAllInbound"));
        return adapter;
    }

    private static String string(Map<String, Object> settings, String name) {
        return String.valueOf(settings.get(name));
    }

    private static int integer(Map<String, Object> settings, String name) {
        return ((Number) settings.get(name)).intValue();
    }

    private static boolean bool(Map<String, Object> settings, String name) {
        return Boolean.TRUE.equals(settings.get(name));
    }

    private static List<String> strings(Map<String, Object> settings, String name) {
        Object value = settings.get(name);
        if (!(value instanceof java.util.Collection<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private static List<Long> longs(Map<String, Object> settings, String name) {
        Object value = settings.get(name);
        if (!(value instanceof java.util.Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(item -> item instanceof Number number
                        ? number.longValue()
                        : Long.parseLong(String.valueOf(item)))
                .toList();
    }
}
