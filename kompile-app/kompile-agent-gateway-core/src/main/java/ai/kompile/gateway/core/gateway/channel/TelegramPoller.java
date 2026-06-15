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
package ai.kompile.gateway.core.gateway.channel;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
public class TelegramPoller {

    private final TelegramApiClient apiClient;
    private final ChannelAdapter.MessageHandler messageHandler;
    private final Set<Long> allowedChatIds;
    private final int pollIntervalMs;
    private final int longPollTimeout;

    private volatile boolean running = false;
    private volatile int lastUpdateId = 0;
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
        this.apiClient = apiClient;
        this.messageHandler = messageHandler;
        this.allowedChatIds = new CopyOnWriteArraySet<>(allowedChatIds);
        this.pollIntervalMs = pollIntervalMs;
        this.longPollTimeout = longPollTimeout;
    }

    public void addAllowedChat(Long chatId) {
        allowedChatIds.add(chatId);
    }

    public void removeAllowedChat(Long chatId) {
        allowedChatIds.remove(chatId);
    }

    public void start() {
        if (running) {
            log.warn("Telegram poller already running");
            return;
        }
        running = true;
        pollThread = new Thread(this::pollLoop, "telegram-poller");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("Telegram poller started");
    }

    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Telegram poller stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                pollUpdates();
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in Telegram poll loop", e);
                sleepOnError();
            }
        }
    }

    private void pollUpdates() {
        List<TelegramApiClient.TelegramUpdate> updates = apiClient.getUpdates(lastUpdateId + 1, longPollTimeout);

        for (TelegramApiClient.TelegramUpdate update : updates) {
            lastUpdateId = update.updateId();
            if (update.message() != null) {
                processMessage(update.message());
            }
        }
    }

    private void processMessage(TelegramApiClient.TelegramMessage message) {
        if (message.text() == null || message.text().isEmpty()) {
            return;
        }

        if (message.chat() == null || message.from() == null) {
            return;
        }

        Long chatId = message.chat().id();

        if (!allowedChatIds.isEmpty() && !allowedChatIds.contains(chatId)) {
            log.debug("Ignoring message from unauthorized chat: {}", chatId);
            return;
        }

        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.messageId(),
                String.valueOf(message.from().id()),
                resolveUserName(message.from()),
                message.text(),
                String.valueOf(chatId),
                message.date() * 1000L,
                null,
                java.util.Map.of("telegram_chat_id", chatId)
        );

        ChannelAdapter.MessageResponder responder = new TelegramMessageResponder(apiClient, chatId);
        messageHandler.handle(incoming, responder);
    }

    private String resolveUserName(TelegramApiClient.TelegramUser user) {
        if (user.username() != null && !user.username().isEmpty()) {
            return user.username();
        }
        if (user.firstName() != null) {
            return user.lastName() != null 
                    ? user.firstName() + " " + user.lastName()
                    : user.firstName();
        }
        return "User" + user.id();
    }

    private void sleepOnError() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
