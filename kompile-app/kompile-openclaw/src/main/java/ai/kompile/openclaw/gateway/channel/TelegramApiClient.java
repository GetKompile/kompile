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
package ai.kompile.openclaw.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public interface TelegramApiClient {

    List<TelegramUpdate> getUpdates(int offset, int timeout);

    void sendMessage(String chatId, String text);

    void sendChatAction(String chatId, String action);

    record TelegramUpdate(
            int updateId,
            TelegramMessage message
    ) {}

    record TelegramMessage(
            String messageId,
            TelegramUser from,
            TelegramChat chat,
            String text,
            long date
    ) {}

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
}
