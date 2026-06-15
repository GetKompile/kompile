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

package ai.kompile.source.notion;

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
 * Source provider for Notion pages and databases.
 * Supports both OAuth and manual token configuration.
 */
public class NotionSourceProvider implements SourceProvider {

    @Value("${kompile.notion.enabled:true}")
    private boolean enabled;

    @Value("${kompile.oauth.notion.client-id:}")
    private String clientId;

    private final OAuthConnectionService oauthService;

    @Autowired
    public NotionSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "notion";
    }

    @Override
    public String getDisplayName() {
        return "Notion";
    }

    @Override
    public String getDescription() {
        return "Import pages and databases from Notion";
    }

    @Override
    public String getIcon() {
        return "auto_stories";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Notion integration is disabled. Set kompile.notion.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        if (oauthService != null && oauthService.getConnectionStatus("notion").isConnected()) {
            return false;
        }
        return true;
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
        return "notion";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("oauthConfigured", clientId != null && !clientId.isEmpty());

        if (oauthService != null) {
            OAuthConnectionStatus status = oauthService.getConnectionStatus("notion");
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
    public boolean hasCustomDialog() {
        return true;
    }

    @Override
    public String getCustomDialogComponent() {
        return "NotionDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("apiToken")
                        .label("Integration Token")
                        .type(SourceFormField.FieldType.PASSWORD)
                        .required(true)
                        .placeholder("secret_...")
                        .helpText("Create an integration at www.notion.so/my-integrations")
                        .prefixIcon("key")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("pageIds")
                        .label("Page IDs")
                        .type(SourceFormField.FieldType.HIDDEN)
                        .helpText("Selected page IDs")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("databaseIds")
                        .label("Database IDs")
                        .type(SourceFormField.FieldType.HIDDEN)
                        .helpText("Selected database IDs")
                        .order(3)
                        .build(),
                SourceFormField.builder()
                        .id("includeSubpages")
                        .label("Include Subpages")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Recursively import child pages")
                        .order(4)
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split content into chunks")
                        .order(5)
                        .group("advanced")
                        .build()
        );
    }
}
