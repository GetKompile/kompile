/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.channel;

import ai.kompile.channel.api.TelegramDiagnosticsView;
import ai.kompile.channel.api.TelegramWebhookInfoView;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.TelegramApiClient;
import ai.kompile.gateway.core.gateway.channel.TelegramPoller;
import ai.kompile.gateway.core.service.AgentExecutor;
import ai.kompile.kclaw.gateway.telegram.TelegramBotLeaseRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramPairingRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramRuntimeStateStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Telegram polling runtime with webhook preflight, durable offsets, pairing, and diagnostics. */
@Slf4j
public class TelegramChannelAdapter extends ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter {

    private TelegramApiClient apiClient;
    private TelegramPoller poller;
    private final Set<Long> allowedChatIds = new HashSet<>();
    private boolean allowAllInbound;
    private String connectionName = "telegram";
    private TelegramRuntimeStateStore stateStore;
    private TelegramPairingRegistry pairings;
    private TelegramBotLeaseRegistry leaseRegistry;
    private TelegramBotLeaseRegistry.Lease lease;
    private TelegramApiClient.TelegramBotIdentity identity;
    private TelegramApiClient.TelegramWebhookInfo webhookInfo;
    private volatile Instant lastSuccessfulPoll;
    private volatile Instant lastUpdateAt;
    private volatile int consecutiveFailures;
    private volatile Integer lastErrorCode;

    public TelegramChannelAdapter(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override public String getChannelName() { return "telegram"; }
    public void setApiClient(TelegramApiClient apiClient) { this.apiClient = apiClient; }
    public TelegramApiClient getApiClient() { return apiClient; }

    public void configureRuntime(
            String connectionName,
            TelegramRuntimeStateStore stateStore,
            TelegramPairingRegistry pairings,
            TelegramBotLeaseRegistry leaseRegistry) {
        this.connectionName = connectionName;
        this.stateStore = stateStore;
        this.pairings = pairings;
        this.leaseRegistry = leaseRegistry;
    }

    public void addAllowedChat(Long chatId) {
        allowedChatIds.add(chatId);
        if (poller != null) poller.addAllowedChat(chatId);
    }

    public void removeAllowedChat(Long chatId) {
        allowedChatIds.remove(chatId);
        if (poller != null) poller.removeAllowedChat(chatId);
    }

    public void setAllowAllInbound(boolean allowAllInbound) {
        this.allowAllInbound = allowAllInbound;
        if (poller != null) poller.setAllowAllChats(allowAllInbound);
    }

    @Override
    protected void doStart() {
        if (apiClient == null) throw new IllegalStateException("Telegram API client is not configured");
        identity = apiClient.getMe();
        webhookInfo = apiClient.getWebhookInfo();
        if (webhookInfo.configured()) {
            throw new IllegalStateException(
                    "Telegram webhook is active on " + webhookInfo.redactedHost()
                            + "; explicitly delete it before enabling polling");
        }
        if (leaseRegistry != null) lease = leaseRegistry.acquire(identity.id(), connectionName);

        TelegramPoller.OffsetStore offsets = offsetStore();
        bootstrap(offsets);
        poller = new TelegramPoller(
                apiClient, createAgentHandler(), allowedChatIds, 1000, 30, offsets,
                new TelegramPoller.Listener() {
                    @Override
                    public boolean intercept(TelegramApiClient.TelegramMessage message) {
                        return pairings != null && pairings.intercept(connectionName, message, apiClient);
                    }

                    @Override
                    public void onPollSuccess(long nextOffset, boolean receivedUpdate) {
                        lastSuccessfulPoll = Instant.now();
                        if (receivedUpdate) lastUpdateAt = lastSuccessfulPoll;
                        consecutiveFailures = 0;
                        lastErrorCode = null;
                        markReady();
                    }

                    @Override
                    public void onPollError(Throwable error) {
                        consecutiveFailures++;
                        if (error instanceof TelegramApiClient.TelegramApiException api) {
                            lastErrorCode = api.errorCode();
                        }
                        recordError(error);
                    }

                    @Override
                    public void onStopped() {
                        releaseLease();
                    }
                });
        poller.setAllowAllChats(allowAllInbound);
        poller.start();
        markReady(); // getMe, webhook preflight, and checkpoint bootstrap all succeeded
    }

    private TelegramPoller.OffsetStore offsetStore() {
        if (stateStore == null) {
            java.util.concurrent.atomic.AtomicLong offset = new java.util.concurrent.atomic.AtomicLong();
            return new TelegramPoller.OffsetStore() {
                @Override public long nextOffset() { return offset.get(); }
                @Override public void advance(long nextOffset, Instant updateAt) {
                    offset.accumulateAndGet(nextOffset, Math::max);
                }
            };
        }
        return new TelegramPoller.OffsetStore() {
            @Override public long nextOffset() {
                return stateStore.forBot(identity.id()).nextOffset();
            }
            @Override public void advance(long nextOffset, Instant updateAt) {
                stateStore.advance(identity.id(), nextOffset, updateAt);
            }
        };
    }

    private void bootstrap(TelegramPoller.OffsetStore offsets) {
        if (stateStore == null) return;
        TelegramRuntimeStateStore.State state = stateStore.forBot(identity.id());
        if (state.initialized()) return;
        List<TelegramApiClient.TelegramUpdate> tail = apiClient.getUpdates(
                -1L, 1, 0, List.of("message"));
        long next = tail.stream().mapToLong(TelegramApiClient.TelegramUpdate::updateId)
                .max().stream().map(id -> id + 1L).findFirst().orElse(0L);
        stateStore.initialize(identity.id(), next);
    }

    @Override
    protected void doStop() {
        if (poller != null) {
            if (poller.stop()) {
                poller = null;
                releaseLease();
            }
        } else {
            releaseLease();
        }
    }

    private synchronized void releaseLease() {
        if (lease != null) {
            lease.close();
            lease = null;
        }
    }

    @Override public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    @Override
    public DeliveryResult send(String target, String content) {
        if (apiClient == null || !isRunning()) {
            throw new IllegalStateException("Telegram connection is not running");
        }
        apiClient.sendMessage(target, content);
        return DeliveryResult.accepted("Telegram accepted the message");
    }

    public TelegramWebhookInfoView webhookInfo() {
        TelegramApiClient.TelegramWebhookInfo info = apiClient.getWebhookInfo();
        webhookInfo = info;
        return new TelegramWebhookInfoView(
                info.configured(), info.redactedHost(), info.pendingUpdateCount(), info.lastErrorMessage());
    }

    public TelegramWebhookInfoView deleteWebhook(boolean dropPendingUpdates) {
        apiClient.deleteWebhook(dropPendingUpdates);
        return webhookInfo();
    }

    public String botUsername() { return identity == null ? null : identity.username(); }

    public TelegramDiagnosticsView diagnostics() {
        TelegramRuntimeStateStore.State state = stateStore == null ? null : stateStore.current();
        return new TelegramDiagnosticsView(
                connectionName,
                identity == null ? null : identity.id(),
                botUsername(),
                poller != null && poller.isRunning(),
                isReady(),
                state == null ? (poller == null ? 0L : poller.nextOffset()) : state.nextOffset(),
                state == null ? null : state.persistedAt(),
                lastSuccessfulPoll,
                lastUpdateAt,
                consecutiveFailures,
                lastErrorCode,
                getLastError(),
                webhookInfo != null && webhookInfo.configured(),
                webhookInfo == null ? null : webhookInfo.redactedHost(),
                webhookInfo == null ? 0 : webhookInfo.pendingUpdateCount(),
                pairings == null ? 0 : pairings.activeCount(connectionName));
    }
}
