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

package ai.kompile.source.slack;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source provider for Slack channel history.
 * Supports both OAuth and manual token configuration.
 */
public class SlackSourceProvider implements SourceProvider {

    @Value("${kompile.slack.enabled:true}")
    private boolean enabled;

    @Value("${kompile.slack.token:}")
    private String configuredToken;

    @Value("${kompile.oauth.slack.client-id:}")
    private String clientId;

    private final OAuthConnectionService oauthService;

    @Autowired
    public SlackSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "slack";
    }

    @Override
    public String getDisplayName() {
        return "Slack";
    }

    @Override
    public String getDescription() {
        return "Import messages from Slack channels";
    }

    @Override
    public String getIcon() {
        return "tag";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 4;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Slack integration is disabled. Set kompile.slack.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        if (oauthService != null && oauthService.getConnectionStatus("slack").isConnected()) {
            return false;
        }
        return configuredToken == null || configuredToken.isEmpty();
    }

    @Override
    public String getAuthType() {
        if (clientId != null && !clientId.isEmpty()) {
            return "oauth2";
        }
        return "token";
    }

    @Override
    public String getOAuthProvider() {
        return "slack";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("oauthConfigured", clientId != null && !clientId.isEmpty());
        config.put("tokenConfigured", configuredToken != null && !configuredToken.isEmpty());

        if (oauthService != null) {
            OAuthConnectionStatus status = oauthService.getConnectionStatus("slack");
            config.put("oauthConnected", status.isConnected());
            config.put("oauthStatus", status.getStatus().name());
            if (status.getUserEmail() != null) {
                config.put("connectedUserEmail", status.getUserEmail());
            }
            if (status.getUserName() != null) {
                config.put("connectedUserName", status.getUserName());
            }
        } else {
            config.put("oauthConnected", false);
            config.put("oauthStatus", "NOT_CONFIGURED");
        }

        return config;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("channelId")
                        .label("Channel ID")
                        .type(SourceFormField.FieldType.TEXT)
                        .required(true)
                        .placeholder("C0123456789")
                        .helpText("The Slack channel ID (right-click channel > Copy link)")
                        .prefixIcon("tag")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("token")
                        .label("Bot Token")
                        .type(SourceFormField.FieldType.PASSWORD)
                        .required(requiresAuth())
                        .placeholder("xoxb-...")
                        .helpText("Slack Bot OAuth token (optional if configured in settings)")
                        .prefixIcon("key")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("daysBack")
                        .label("Days Back")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(30)
                        .min(1)
                        .max(365)
                        .helpText("Number of days of history to import")
                        .order(3)
                        .build(),
                SourceFormField.builder()
                        .id("messageLimit")
                        .label("Message Limit")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(1000)
                        .min(0)
                        .max(10000)
                        .helpText("Maximum messages to import (0 = unlimited)")
                        .order(4)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("includeThreads")
                        .label("Include Threads")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Include thread replies")
                        .order(5)
                        .build(),
                SourceFormField.builder()
                        .id("loadAllChannels")
                        .label("Load All Channels")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(false)
                        .helpText("Import from all accessible channels")
                        .order(6)
                        .group("advanced")
                        .build()
        );
    }
}
