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

import java.util.List;

public interface TelegramApiClient {

    List<TelegramUpdate> getUpdates(long offset, int limit, int timeout, List<String> allowedUpdates);

    default List<TelegramUpdate> getUpdates(long offset, int timeout) {
        return getUpdates(offset, 100, timeout, List.of("message"));
    }

    TelegramBotIdentity getMe();

    TelegramWebhookInfo getWebhookInfo();

    void deleteWebhook(boolean dropPendingUpdates);

    void sendMessage(String chatId, String text);

    void sendChatAction(String chatId, String action);

    record TelegramUpdate(
            long updateId,
            TelegramMessage message
    ) {}

    record TelegramMessage(
            String messageId,
            TelegramUser from,
            TelegramChat chat,
            String text,
            long date,
            Long messageThreadId
    ) {
        public TelegramMessage(
                String messageId,
                TelegramUser from,
                TelegramChat chat,
                String text,
                long date) {
            this(messageId, from, chat, text, date, null);
        }
    }

    record TelegramUser(
            long id,
            String username,
            String firstName,
            String lastName
    ) {}

    record TelegramChat(
            long id,
            String type,
            String title
    ) {}

    record TelegramBotIdentity(long id, String username, String firstName) {}

    record TelegramWebhookInfo(
            boolean configured,
            String redactedHost,
            int pendingUpdateCount,
            String lastErrorMessage) {}

    final class TelegramApiException extends RuntimeException {
        private final int httpStatus;
        private final int errorCode;
        private final Integer retryAfterSeconds;

        public TelegramApiException(
                String message, int httpStatus, int errorCode, Integer retryAfterSeconds) {
            super(message);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int httpStatus() { return httpStatus; }
        public int errorCode() { return errorCode; }
        public Integer retryAfterSeconds() { return retryAfterSeconds; }
    }
}
