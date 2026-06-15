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

package ai.kompile.source.onedrive;

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
 * Source provider for Microsoft OneDrive files.
 * Integrates with the centralized OAuth2 connection service for authentication
 * against the "microsoft" provider.
 */
public class OneDriveSourceProvider implements SourceProvider {

    @Value("${kompile.onedrive.enabled:true}")
    private boolean enabled;

    @Value("${kompile.oauth.microsoft.client-id:}")
    private String clientId;

    private final OAuthConnectionService oauthService;

    @Autowired
    public OneDriveSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "onedrive";
    }

    @Override
    public String getDisplayName() {
        return "Microsoft OneDrive";
    }

    @Override
    public String getDescription() {
        return "Import files from Microsoft OneDrive";
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
        return 2;
    }

    @Override
    public boolean isAvailable() {
        return enabled && clientId != null && !clientId.isEmpty();
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "OneDrive integration is disabled. Set kompile.onedrive.enabled=true to enable.";
        }
        if (clientId == null || clientId.isEmpty()) {
            return "OneDrive requires OAuth client ID. Configure kompile.oauth.microsoft.client-id in application.properties";
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
        return "microsoft";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public boolean hasCustomDialog() {
        return false;
    }

    @Override
    public String getCustomDialogComponent() {
        return "OneDriveDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("itemIds")
                        .label("OneDrive Item IDs")
                        .type(SourceFormField.FieldType.TEXTAREA)
                        .helpText("Comma- or newline-separated list of OneDrive item ids to ingest. Obtain ids via the Graph API or share link.")
                        .required(true)
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("driveId")
                        .label("Drive ID (optional)")
                        .type(SourceFormField.FieldType.TEXT)
                        .helpText("Leave blank to use the signed-in user's default drive")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split documents into chunks")
                        .order(3)
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
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/html",
                "text/csv",
                "application/json"
        ));

        if (oauthService != null) {
            OAuthConnectionStatus status = oauthService.getConnectionStatus("microsoft");
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
