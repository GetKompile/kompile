/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.telegram;

import ai.kompile.channel.api.TelegramPairingView;
import ai.kompile.gateway.core.gateway.channel.TelegramApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramPairingRegistryTest {

    @Test
    void capturesCandidateBeforeAllowlistAndRequiresExactChatApproval() {
        TelegramPairingRegistry registry = new TelegramPairingRegistry();
        var start = registry.start("ops", "support_bot");
        StubClient client = new StubClient();
        TelegramApiClient.TelegramMessage message = new TelegramApiClient.TelegramMessage(
                "9",
                new TelegramApiClient.TelegramUser(7L, "alice", "Alice", "Example"),
                new TelegramApiClient.TelegramChat(-100123L, "supergroup", "Operations"),
                start.command(),
                1_700_000_000L);

        assertTrue(registry.intercept("ops", message, client));
        TelegramPairingView pending = registry.get("ops", start.pairingId());
        assertEquals(TelegramPairingView.Status.CANDIDATE, pending.status());
        assertEquals(-100123L, pending.candidate().chatId());
        assertTrue(pending.candidate().authorizesEntireChat());
        assertEquals("-100123", client.lastTarget.get());

        assertThrows(IllegalArgumentException.class,
                () -> registry.approve("ops", start.pairingId(), -100124L));
        registry.approve("ops", start.pairingId(), -100123L);
        assertEquals(TelegramPairingView.Status.APPROVED,
                registry.get("ops", start.pairingId()).status());
    }

    private static final class StubClient implements TelegramApiClient {
        private final AtomicReference<String> lastTarget = new AtomicReference<>();

        @Override public List<TelegramUpdate> getUpdates(
                long offset, int limit, int timeout, List<String> allowedUpdates) { return List.of(); }
        @Override public TelegramBotIdentity getMe() { return new TelegramBotIdentity(1L, "bot", "Bot"); }
        @Override public TelegramWebhookInfo getWebhookInfo() {
            return new TelegramWebhookInfo(false, "", 0, null);
        }
        @Override public void deleteWebhook(boolean dropPendingUpdates) { }
        @Override public void sendMessage(String chatId, String text) { lastTarget.set(chatId); }
        @Override public void sendChatAction(String chatId, String action) { }
    }
}
