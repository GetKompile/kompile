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

package ai.kompile.source.gdrive;

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
 * Source provider for Google Drive files.
 * Integrates with the centralized OAuth2 connection service for authentication.
 */
public class GoogleDriveSourceProvider implements SourceProvider {

    @Value("${kompile.gdrive.enabled:true}")
    private boolean enabled;

    @Value("${kompile.oauth.google.client-id:}")
    private String clientId;

    private final OAuthConnectionService oauthService;

    @Autowired
    public GoogleDriveSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "gdrive";
    }

    @Override
    public String getDisplayName() {
        return "Google Drive";
    }

    @Override
    public String getDescription() {
        return "Import files from Google Drive";
    }

    @Override
    public String getIcon() {
        return "cloud";
    }

    @Override
    public String getCategory() {
        return "cloud";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean isAvailable() {
        return enabled && clientId != null && !clientId.isEmpty();
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Google Drive integration is disabled. Set kompile.gdrive.enabled=true to enable.";
        }
        if (clientId == null || clientId.isEmpty()) {
            return "Google Drive requires OAuth client ID. Configure kompile.gdrive.clientId in application.properties";
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
    public boolean hasCustomDialog() {
        return true;
    }

    @Override
    public String getCustomDialogComponent() {
        return "GoogleDriveDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("fileIds")
                        .label("Selected Files")
                        .type(SourceFormField.FieldType.HIDDEN)
                        .helpText("Files selected from Google Drive picker")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split documents into chunks")
                        .order(2)
                        .group("advanced")
                        .build()
        );
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("pickerEnabled", clientId != null && !clientId.isEmpty());
        config.put("supportedMimeTypes", Arrays.asList(
                "application/pdf",
                "application/vnd.google-apps.document",
                "application/vnd.google-apps.spreadsheet",
                "application/vnd.google-apps.presentation",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/html"
        ));

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
}
