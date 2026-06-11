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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.source.gmail;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source provider for Gmail inbox indexing.
 * Registers Gmail as a data source in the UI with configuration form fields
 * for search queries, date ranges, label filtering, and attachment handling.
 */
public class GmailSourceProvider implements SourceProvider {

    private boolean enabled = true;

    private String clientId = "";

    private final OAuthConnectionService oauthService;

    @Autowired
    public GmailSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "gmail";
    }

    @Override
    public String getDisplayName() {
        return "Gmail";
    }

    @Override
    public String getDescription() {
        return "Index emails from your Gmail inbox using OAuth. Supports search queries, " +
                "label filtering, thread grouping, and attachment indexing.";
    }

    @Override
    public String getIcon() {
        return "mail";
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
        return enabled && clientId != null && !clientId.isEmpty();
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Gmail integration is disabled. Set kompile.gmail.enabled=true to enable.";
        }
        if (clientId == null || clientId.isEmpty()) {
            return "Gmail requires Google OAuth client ID. Configure via Settings > Connections > Google.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public String getAuthType() {
        return "oauth2";
    }

    @Override
    public String getOAuthProvider() {
        return "google";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("oauthProvider", "google");
        config.put("requiredScopes", List.of("https://www.googleapis.com/auth/gmail.readonly"));

        if (oauthService != null) {
            OAuthConnectionStatus status = oauthService.getConnectionStatus("google");
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
        List<SourceFormField> fields = new ArrayList<>();

        // Search query
        fields.add(SourceFormField.builder()
                .id("gmailQuery")
                .label("Search Query")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. label:inbox, from:user@example.com, subject:report")
                .helpText("Gmail search query. Leave empty to index all mail. "
                        + "Supports all Gmail search operators.")
                .order(1)
                .group("search")
                .build());

        // Days back
        fields.add(SourceFormField.builder()
                .id("daysBack")
                .label("Days Back")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("30")
                .helpText("How many days of email history to index on the first crawl. "
                        + "Subsequent crawls only fetch new messages.")
                .min(1)
                .max(3650)
                .order(2)
                .group("search")
                .build());

        // Max messages
        fields.add(SourceFormField.builder()
                .id("maxMessages")
                .label("Max Messages")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("500")
                .helpText("Maximum number of messages to index per crawl.")
                .min(1)
                .max(50000)
                .order(3)
                .group("search")
                .build());

        // Label filter
        fields.add(SourceFormField.builder()
                .id("labelFilter")
                .label("Include Labels")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. inbox, important, work")
                .helpText("Comma-separated list of Gmail labels to include. Leave empty for all labels.")
                .order(4)
                .group("labels")
                .build());

        // Exclude labels
        fields.add(SourceFormField.builder()
                .id("excludeLabels")
                .label("Exclude Labels")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. spam, trash, promotions")
                .helpText("Comma-separated list of Gmail labels to exclude.")
                .order(5)
                .group("labels")
                .build());

        // Thread mode
        fields.add(SourceFormField.builder()
                .id("threadMode")
                .label("Thread Mode")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("false")
                .helpText("When enabled, messages are fetched and grouped by thread "
                        + "for better conversation context.")
                .order(6)
                .group("advanced")
                .build());

        // Include attachments
        fields.add(SourceFormField.builder()
                .id("includeAttachments")
                .label("Index Attachments")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("true")
                .helpText("Download and index text-based email attachments "
                        + "(text, CSV, JSON, XML files).")
                .order(7)
                .group("advanced")
                .build());

        return fields;
    }
}
