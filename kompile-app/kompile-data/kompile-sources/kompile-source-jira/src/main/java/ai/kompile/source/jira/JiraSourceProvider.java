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

package ai.kompile.source.jira;

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
 * Source provider for Atlassian Jira issues.
 * Supports both OAuth and API token authentication.
 */
public class JiraSourceProvider implements SourceProvider {

    @Value("${kompile.jira.enabled:true}")
    private boolean enabled;

    @Value("${kompile.oauth.atlassian.client-id:}")
    private String clientId;

    private final OAuthConnectionService oauthService;

    @Autowired
    public JiraSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "jira";
    }

    @Override
    public String getDisplayName() {
        return "Jira";
    }

    @Override
    public String getDescription() {
        return "Import issues from Atlassian Jira";
    }

    @Override
    public String getIcon() {
        return "view_kanban";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Jira integration is disabled. Set kompile.jira.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        if (oauthService != null && oauthService.getConnectionStatus("atlassian").isConnected()) {
            return false;
        }
        return true;
    }

    @Override
    public String getAuthType() {
        if (clientId != null && !clientId.isEmpty()) {
            return "oauth2";
        }
        return "api_key";
    }

    @Override
    public String getOAuthProvider() {
        return "atlassian";
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
            OAuthConnectionStatus status = oauthService.getConnectionStatus("atlassian");
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
        return "JiraDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("baseUrl")
                        .label("Jira URL")
                        .type(SourceFormField.FieldType.URL)
                        .required(true)
                        .placeholder("https://your-company.atlassian.net")
                        .pattern("^https?://.+")
                        .patternError("Please enter a valid Jira URL")
                        .prefixIcon("link")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("email")
                        .label("Email")
                        .type(SourceFormField.FieldType.EMAIL)
                        .required(true)
                        .placeholder("your-email@company.com")
                        .helpText("Your Atlassian account email")
                        .prefixIcon("email")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("apiToken")
                        .label("API Token")
                        .type(SourceFormField.FieldType.PASSWORD)
                        .required(true)
                        .helpText("Generate at: id.atlassian.com/manage-profile/security/api-tokens")
                        .prefixIcon("key")
                        .order(3)
                        .build(),
                SourceFormField.builder()
                        .id("projectKey")
                        .label("Project Key")
                        .type(SourceFormField.FieldType.TEXT)
                        .placeholder("PROJ")
                        .helpText("Optional: Filter by project key")
                        .order(4)
                        .build(),
                SourceFormField.builder()
                        .id("jql")
                        .label("JQL Query")
                        .type(SourceFormField.FieldType.TEXT)
                        .placeholder("project = PROJ AND status = Done")
                        .helpText("Optional: Custom JQL to filter issues")
                        .order(5)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("includeComments")
                        .label("Include Comments")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Include issue comments")
                        .order(6)
                        .build(),
                SourceFormField.builder()
                        .id("includeAttachments")
                        .label("Include Attachments")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(false)
                        .helpText("Download and index attachments")
                        .order(7)
                        .build()
        );
    }
}
