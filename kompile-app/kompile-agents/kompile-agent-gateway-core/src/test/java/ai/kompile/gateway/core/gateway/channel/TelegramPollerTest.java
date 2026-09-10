/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramPollerTest {

    @Test
    void persistsOffsetBeforeDispatchingAnAllowedMessage() {
        AtomicLong offset = new AtomicLong();
        AtomicBoolean handled = new AtomicBoolean();
        StubClient client = new StubClient(List.of(update(7L, 42L, "hello")));
        TelegramPoller poller = new TelegramPoller(
                client,
                (message, responder) -> {
                    assertEquals(8L, offset.get(), "checkpoint must precede agent side effects");
                    handled.set(true);
                },
                Set.of(42L),
                0,
                0,
                offsets(offset),
                new TelegramPoller.Listener() { });

        poller.pollOnce();

        assertTrue(handled.get());
        assertEquals(8L, offset.get());
        assertEquals(0L, client.requestedOffset.get());
    }

    @Test
    void pairingInterceptRunsBeforeAllowlistAndStillAdvancesCheckpoint() {
        AtomicLong offset = new AtomicLong(11L);
        AtomicBoolean handled = new AtomicBoolean();
        AtomicBoolean intercepted = new AtomicBoolean();
        StubClient client = new StubClient(List.of(update(11L, 99L, "/pair code")));
        TelegramPoller poller = new TelegramPoller(
                client,
                (message, responder) -> handled.set(true),
                Set.of(),
                0,
                0,
                offsets(offset),
                new TelegramPoller.Listener() {
                    @Override
                    public boolean intercept(TelegramApiClient.TelegramMessage message) {
                        intercepted.set(true);
                        return true;
                    }
                });

        poller.pollOnce();

        assertTrue(intercepted.get());
        assertFalse(handled.get());
        assertEquals(12L, offset.get());
    }

    @Test
    void stopPreventsRemainingUpdatesAfterAnInflightTurn() throws Exception {
        AtomicLong offset = new AtomicLong();
        AtomicLong handled = new AtomicLong();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StubClient client = new StubClient(List.of(
                update(20L, 42L, "first"), update(21L, 42L, "second")));
        TelegramPoller poller = new TelegramPoller(
                client,
                (message, responder) -> {
                    handled.incrementAndGet();
                    firstStarted.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Simulate an engine that cannot cancel an already-started turn.
                        }
                    }
                },
                Set.of(42L), 0, 0, offsets(offset), new TelegramPoller.Listener() { });
        poller.start();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        Thread stopper = new Thread(poller::stop);
        stopper.start();
        Thread.sleep(50L);
        release.countDown();
        stopper.join(2000L);

        assertEquals(1L, handled.get());
        assertEquals(21L, offset.get());
        assertFalse(poller.isRunning());
    }

    private static TelegramPoller.OffsetStore offsets(AtomicLong offset) {
        return new TelegramPoller.OffsetStore() {
            @Override
            public long nextOffset() {
                return offset.get();
            }

            @Override
            public void advance(long nextOffset, Instant updateAt) {
                offset.accumulateAndGet(nextOffset, Math::max);
            }
        };
    }

    private static TelegramApiClient.TelegramUpdate update(long updateId, long chatId, String text) {
        return new TelegramApiClient.TelegramUpdate(
                updateId,
                new TelegramApiClient.TelegramMessage(
                        Long.toString(updateId),
                        new TelegramApiClient.TelegramUser(5L, "alice", "Alice", null),
                        new TelegramApiClient.TelegramChat(chatId, "private", null),
                        text,
                        1_700_000_000L));
    }

    private static final class StubClient implements TelegramApiClient {
        private final List<TelegramUpdate> updates;
        private final AtomicLong requestedOffset = new AtomicLong(Long.MIN_VALUE);

        private StubClient(List<TelegramUpdate> updates) {
            this.updates = updates;
        }

        @Override
        public List<TelegramUpdate> getUpdates(
                long offset, int limit, int timeout, List<String> allowedUpdates) {
            requestedOffset.set(offset);
            return updates;
        }

        @Override public TelegramBotIdentity getMe() { return new TelegramBotIdentity(1L, "bot", "Bot"); }
        @Override public TelegramWebhookInfo getWebhookInfo() {
            return new TelegramWebhookInfo(false, "", 0, null);
        }
        @Override public void deleteWebhook(boolean dropPendingUpdates) { }
        @Override public void sendMessage(String chatId, String text) { }
        @Override public void sendChatAction(String chatId, String action) { }
    }
}
