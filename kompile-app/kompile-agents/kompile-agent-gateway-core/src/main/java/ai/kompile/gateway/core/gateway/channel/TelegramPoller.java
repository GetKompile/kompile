/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/** Long-polling Telegram update consumer with durable checkpoint and health hooks. */
@Slf4j
public class TelegramPoller {

    public interface OffsetStore {
        long nextOffset();
        void advance(long nextOffset, Instant updateAt);
    }

    public interface Listener {
        default boolean intercept(TelegramApiClient.TelegramMessage message) { return false; }
        default void onPollSuccess(long nextOffset, boolean receivedUpdate) {}
        default void onPollError(Throwable error) {}
        default void onStopped() {}
    }

    private final TelegramApiClient apiClient;
    private final ChannelAdapter.MessageHandler messageHandler;
    private final Set<Long> allowedChatIds;
    private final int pollIntervalMs;
    private final int longPollTimeout;
    private final OffsetStore offsets;
    private final Listener listener;
    private volatile boolean allowAllChats;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private Thread pollThread;

    public TelegramPoller(TelegramApiClient apiClient, ChannelAdapter.MessageHandler messageHandler) {
        this(apiClient, messageHandler, Set.of(), 1000, 30);
    }

    public TelegramPoller(
            TelegramApiClient apiClient,
            ChannelAdapter.MessageHandler messageHandler,
            Set<Long> allowedChatIds,
            int pollIntervalMs,
            int longPollTimeout) {
        AtomicLong offset = new AtomicLong();
        this.apiClient = apiClient;
        this.messageHandler = messageHandler;
        this.allowedChatIds = new CopyOnWriteArraySet<>(allowedChatIds);
        this.pollIntervalMs = pollIntervalMs;
        this.longPollTimeout = longPollTimeout;
        this.offsets = new OffsetStore() {
            @Override public long nextOffset() { return offset.get(); }
            @Override public void advance(long nextOffset, Instant updateAt) {
                offset.accumulateAndGet(nextOffset, Math::max);
            }
        };
        this.listener = new Listener() {};
    }

    public TelegramPoller(
            TelegramApiClient apiClient,
            ChannelAdapter.MessageHandler messageHandler,
            Set<Long> allowedChatIds,
            int pollIntervalMs,
            int longPollTimeout,
            OffsetStore offsets,
            Listener listener) {
        this.apiClient = apiClient;
        this.messageHandler = messageHandler;
        this.allowedChatIds = new CopyOnWriteArraySet<>(allowedChatIds);
        this.pollIntervalMs = pollIntervalMs;
        this.longPollTimeout = longPollTimeout;
        this.offsets = offsets;
        this.listener = listener == null ? new Listener() {} : listener;
    }

    public void addAllowedChat(Long chatId) { allowedChatIds.add(chatId); }
    public void removeAllowedChat(Long chatId) { allowedChatIds.remove(chatId); }
    public void setAllowAllChats(boolean allowAllChats) { this.allowAllChats = allowAllChats; }

    public void start() {
        if (running) return;
        stopRequested = false;
        running = true;
        pollThread = new Thread(this::pollLoop, "telegram-poller");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("Telegram poller started at offset {}", offsets.nextOffset());
    }

    /** @return true when the worker has fully terminated before the bounded join expires. */
    public boolean stop() {
        stopRequested = true;
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (pollThread.isAlive()) {
                log.warn("Telegram poller is still finishing an in-flight message");
                return false;
            }
            pollThread = null;
        }
        log.info("Telegram poller stopped");
        return true;
    }

    public boolean isRunning() { return running && pollThread != null && pollThread.isAlive(); }
    public long nextOffset() { return offsets.nextOffset(); }

    private void pollLoop() {
        try {
            while (running && !stopRequested) {
                try {
                    pollOnce();
                    if (pollIntervalMs > 0) Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    listener.onPollError(e);
                    sleepOnError(e);
                }
            }
        } finally {
            running = false;
            listener.onStopped();
        }
    }

    void pollOnce() {
        long requestedOffset = offsets.nextOffset();
        List<TelegramApiClient.TelegramUpdate> updates = apiClient.getUpdates(
                requestedOffset, 100, longPollTimeout, List.of("message"));
        boolean received = false;
        for (TelegramApiClient.TelegramUpdate update : updates.stream()
                .sorted(Comparator.comparingLong(TelegramApiClient.TelegramUpdate::updateId))
                .toList()) {
            if (stopRequested) break;
            if (update.updateId() < offsets.nextOffset()) continue;
            Instant updateAt = Instant.now();
            offsets.advance(update.updateId() + 1L, updateAt); // persist before any side effect
            received = true;
            if (stopRequested) break;
            if (update.message() != null && !listener.intercept(update.message())) {
                processMessage(update.message());
            }
        }
        if (!stopRequested) listener.onPollSuccess(offsets.nextOffset(), received);
    }

    private void processMessage(TelegramApiClient.TelegramMessage message) {
        if (message.text() == null || message.text().isEmpty()
                || message.chat() == null || message.from() == null) return;
        Long chatId = message.chat().id();
        if (!allowAllChats && !allowedChatIds.contains(chatId)) return;
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("telegram_chat_id", chatId);
        metadata.put("conversation_key", message.messageThreadId() == null
                ? Long.toString(chatId)
                : chatId + ":" + message.messageThreadId());
        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.messageId(),
                String.valueOf(message.from().id()),
                resolveUserName(message.from()),
                message.text(),
                String.valueOf(chatId),
                message.date() * 1000L,
                null,
                java.util.Map.copyOf(metadata));
        messageHandler.handle(incoming, new TelegramMessageResponder(apiClient, chatId));
    }

    private String resolveUserName(TelegramApiClient.TelegramUser user) {
        if (user.username() != null && !user.username().isEmpty()) return user.username();
        if (user.firstName() != null) {
            return user.lastName() != null ? user.firstName() + " " + user.lastName() : user.firstName();
        }
        return "User" + user.id();
    }

    private void sleepOnError(Exception error) {
        long millis = 5000L;
        if (error instanceof TelegramApiClient.TelegramApiException api
                && api.retryAfterSeconds() != null) {
            millis = Math.max(1000L, api.retryAfterSeconds() * 1000L);
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
