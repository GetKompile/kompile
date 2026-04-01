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

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class ChannelConfig {

    private String channelId;
    private String channelType;
    private String agentId;
    private boolean enabled;

    private ChannelAdapter.AdapterConfig adapterConfig;
    
    private TelegramConfig telegram;
    private DiscordConfig discord;
    private SlackConfig slack;
    private WhatsAppConfig whatsapp;
    private EmailConfig email;

    @Data
    public static class TelegramConfig {
        private String botToken;
        private Set<Long> allowedChatIds = new HashSet<>();
    }

    @Data
    public static class DiscordConfig {
        private String botToken;
        private Set<String> allowedChannelIds = new HashSet<>();
        private Set<String> allowedGuildIds = new HashSet<>();
    }

    @Data
    public static class SlackConfig {
        private String botToken;
        private String appToken;
        private Set<String> allowedChannelIds = new HashSet<>();
        private boolean respondToAllMessages = false;
    }

    @Data
    public static class WhatsAppConfig {
        private String accessToken;
        private String phoneNumberId;
        private String verifyToken;
        private Set<String> allowedPhoneNumbers = new HashSet<>();
    }

    @Data
    public static class EmailConfig {
        private String imapHost;
        private int imapPort = 993;
        private String username;
        private String password;
        private boolean useSsl = true;
        private String smtpHost;
        private int smtpPort = 587;
        private String fromAddress;
        private String fromName = "OpenClaw Assistant";
        private int pollIntervalSeconds = 60;
        private Set<String> allowedSenders = new HashSet<>();
    }

    public static TelegramConfig telegram(String botToken) {
        TelegramConfig config = new TelegramConfig();
        config.setBotToken(botToken);
        return config;
    }

    public static DiscordConfig discord(String botToken) {
        DiscordConfig config = new DiscordConfig();
        config.setBotToken(botToken);
        return config;
    }

    public static SlackConfig slack(String botToken, String appToken) {
        SlackConfig config = new SlackConfig();
        config.setBotToken(botToken);
        config.setAppToken(appToken);
        return config;
    }

    public static WhatsAppConfig whatsapp(String accessToken, String phoneNumberId) {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setAccessToken(accessToken);
        config.setPhoneNumberId(phoneNumberId);
        return config;
    }

    public static EmailConfig email(String username, String password) {
        EmailConfig config = new EmailConfig();
        config.setUsername(username);
        config.setPassword(password);
        return config;
    }
}
