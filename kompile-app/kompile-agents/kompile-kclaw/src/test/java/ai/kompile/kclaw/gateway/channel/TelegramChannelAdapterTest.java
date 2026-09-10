/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.gateway.channel.TelegramApiClient;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.kclaw.gateway.telegram.TelegramBotLeaseRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramPairingRegistry;
import ai.kompile.kclaw.gateway.telegram.TelegramRuntimeStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramChannelAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void refusesToPollWhileAWebhookOwnsTheBot() throws Exception {
        TelegramChannelAdapter adapter = adapter(
                "ops", tempDir.resolve("ops.json"),
                new StubClient(true), new TelegramBotLeaseRegistry());

        IllegalStateException error = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(error.getMessage().contains("explicitly delete"));
        assertTrue(adapter.getLastError().contains("webhook"));
    }

    @Test
    void oneBotCannotBeConsumedByTwoNamedConnections() throws Exception {
        TelegramBotLeaseRegistry leases = new TelegramBotLeaseRegistry();
        TelegramChannelAdapter first = adapter(
                "ops", tempDir.resolve("ops.json"), new StubClient(false), leases);
        TelegramChannelAdapter second = adapter(
                "support", tempDir.resolve("support.json"), new StubClient(false), leases);
        try {
            first.start();
            IllegalStateException error = assertThrows(IllegalStateException.class, second::start);
            assertTrue(error.getMessage().contains("already owned by connection ops"));
        } finally {
            first.stop();
            second.stop();
        }
    }

    private TelegramChannelAdapter adapter(
            String name,
            Path checkpoint,
            TelegramApiClient client,
            TelegramBotLeaseRegistry leases) throws Exception {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(request -> AgentResponse.builder()
                .success(true).response("ok").build());
        adapter.setApiClient(client);
        adapter.configureRuntime(
                name,
                new TelegramRuntimeStateStore(checkpoint, JsonUtils.standardMapper()),
                new TelegramPairingRegistry(),
                leases);
        adapter.updateConfig(ai.kompile.gateway.core.gateway.channel.ChannelAdapter.AdapterConfig
                .defaults("*", "jarvis"));
        return adapter;
    }

    private static final class StubClient implements TelegramApiClient {
        private final boolean webhook;

        private StubClient(boolean webhook) {
            this.webhook = webhook;
        }

        @Override public List<TelegramUpdate> getUpdates(
                long offset, int limit, int timeout, List<String> allowedUpdates) { return List.of(); }
        @Override public TelegramBotIdentity getMe() {
            return new TelegramBotIdentity(77L, "support_bot", "Support");
        }
        @Override public TelegramWebhookInfo getWebhookInfo() {
            return new TelegramWebhookInfo(webhook, webhook ? "hooks.example" : "", 2, null);
        }
        @Override public void deleteWebhook(boolean dropPendingUpdates) { }
        @Override public void sendMessage(String chatId, String text) { }
        @Override public void sendChatAction(String chatId, String action) { }
    }
}
