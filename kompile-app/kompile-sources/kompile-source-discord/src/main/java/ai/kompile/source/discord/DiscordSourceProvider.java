/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.source.discord;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;

import java.util.*;

/**
 * Source provider that registers Discord as a data source in the Kompile UI.
 * Provides form fields for configuring the Discord bot token, server ID,
 * channel selection, and history options.
 */
public class DiscordSourceProvider implements SourceProvider {

    private boolean enabled = true;

    private String configuredToken = "";

    @Override
    public String getId() {
        return "discord";
    }

    @Override
    public String getDisplayName() {
        return "Discord";
    }

    @Override
    public String getDescription() {
        return "Import messages, threads, and attachments from Discord servers using a bot token";
    }

    @Override
    public String getIcon() {
        return "forum";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 6;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Discord integration is disabled. Set kompile.discord.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        return configuredToken == null || configuredToken.isEmpty();
    }

    @Override
    public String getAuthType() {
        return "token";
    }

    @Override
    public String getOAuthProvider() {
        return null;
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("tokenConfigured", configuredToken != null && !configuredToken.isEmpty());
        return config;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        List<SourceFormField> fields = new ArrayList<>();

        // Bot token
        fields.add(SourceFormField.builder()
                .id("botToken")
                .label("Bot Token")
                .type(SourceFormField.FieldType.PASSWORD)
                .placeholder("Enter your Discord bot token")
                .helpText("Create a bot at discord.com/developers/applications, enable MESSAGE CONTENT intent, and invite it to your server with read permissions")
                .required(true)
                .order(1)
                .build());

        // Guild/Server ID
        fields.add(SourceFormField.builder()
                .id("guildId")
                .label("Server ID")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. 123456789012345678")
                .helpText("Right-click the server name in Discord and select 'Copy Server ID' (requires Developer Mode in settings)")
                .required(true)
                .order(2)
                .build());

        // Channel IDs (optional filter)
        fields.add(SourceFormField.builder()
                .id("channelIds")
                .label("Channel IDs")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("Comma-separated channel IDs (empty = all text channels)")
                .helpText("Leave empty to crawl all text channels, or specify specific channel IDs separated by commas")
                .required(false)
                .order(3)
                .build());

        // Days of history
        fields.add(SourceFormField.builder()
                .id("daysBack")
                .label("Days of History")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("30")
                .min(1)
                .max(3650)
                .helpText("Number of days of message history to fetch")
                .order(4)
                .build());

        // Start date (overrides daysBack)
        fields.add(SourceFormField.builder()
                .id("startDate")
                .label("Start Date")
                .type(SourceFormField.FieldType.DATE)
                .helpText("Start date for message history (overrides 'Days of History' if set)")
                .group("advanced")
                .order(5)
                .build());

        // End date
        fields.add(SourceFormField.builder()
                .id("endDate")
                .label("End Date")
                .type(SourceFormField.FieldType.DATE)
                .helpText("End date for message history (empty = now)")
                .group("advanced")
                .order(6)
                .build());

        // Max messages per channel
        fields.add(SourceFormField.builder()
                .id("maxMessages")
                .label("Max Messages per Channel")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("0")
                .min(0)
                .max(100000)
                .helpText("Maximum messages to fetch per channel (0 = unlimited)")
                .group("advanced")
                .order(7)
                .build());

        // Include threads
        fields.add(SourceFormField.builder()
                .id("includeThreads")
                .label("Include Threads")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("true")
                .helpText("Also crawl messages from threads (active and archived)")
                .order(8)
                .build());

        // Include attachments
        fields.add(SourceFormField.builder()
                .id("includeAttachments")
                .label("Include Attachments")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("true")
                .helpText("Include file attachments in the crawl (PDFs, images, etc.)")
                .order(9)
                .build());

        // Rate limit delay
        fields.add(SourceFormField.builder()
                .id("rateLimitDelayMs")
                .label("Rate Limit Delay (ms)")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("500")
                .min(100)
                .max(5000)
                .helpText("Delay between API requests to respect Discord rate limits")
                .group("advanced")
                .order(10)
                .build());

        return fields;
    }
}
