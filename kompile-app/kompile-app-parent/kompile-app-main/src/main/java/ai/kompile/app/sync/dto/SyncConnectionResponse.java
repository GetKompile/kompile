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

package ai.kompile.app.sync.dto;

import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncAuthMode;
import ai.kompile.app.sync.domain.SyncDirection;
import ai.kompile.app.sync.domain.SyncProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncConnectionResponse {
    private Long id;
    private Long factSheetId;
    private SyncProvider provider;
    private String externalScope;
    private SyncDirection direction;
    private String pollCron;
    private String webhookId;
    private String obsidianApiUrl;
    private String repositoryUrl;
    private String gitBranch;
    private String gitUsername;
    private SyncAuthMode authMode;
    private Boolean authSecretConfigured;
    private String authStatus;
    private String authStatusMessage;
    private Instant authLastCheckedAt;
    private Boolean autoCommit;
    private Boolean remoteSyncEnabled;
    private Boolean enabled;
    private Instant lastSyncAt;
    private String lastSyncStatus;
    private String lastSyncError;
    private Instant createdAt;
    private Instant updatedAt;

    public static SyncConnectionResponse from(NoteSyncConnection conn) {
        return SyncConnectionResponse.builder()
                .id(conn.getId())
                .factSheetId(conn.getFactSheetId())
                .provider(conn.getProvider())
                .externalScope(conn.getExternalScope())
                .direction(conn.getDirection())
                .pollCron(conn.getPollCron())
                .webhookId(conn.getWebhookId())
                .obsidianApiUrl(conn.getObsidianApiUrl())
                .repositoryUrl(conn.getRepositoryUrl())
                .gitBranch(conn.getGitBranch())
                .gitUsername(conn.getGitUsername())
                .authMode(conn.getAuthMode() != null ? conn.getAuthMode() : SyncAuthMode.NONE)
                .authSecretConfigured(hasConfiguredSecret(conn))
                .authStatus(conn.getAuthStatus() != null ? conn.getAuthStatus() : "UNKNOWN")
                .authStatusMessage(conn.getAuthStatusMessage())
                .authLastCheckedAt(conn.getAuthLastCheckedAt())
                .autoCommit(conn.getAutoCommit())
                .remoteSyncEnabled(conn.getRemoteSyncEnabled())
                .enabled(conn.getEnabled())
                .lastSyncAt(conn.getLastSyncAt())
                .lastSyncStatus(conn.getLastSyncStatus())
                .lastSyncError(conn.getLastSyncError())
                .createdAt(conn.getCreatedAt())
                .updatedAt(conn.getUpdatedAt())
                .build();
    }

    private static boolean hasConfiguredSecret(NoteSyncConnection conn) {
        if (conn.getObsidianTokenEncrypted() != null && !conn.getObsidianTokenEncrypted().isBlank()) {
            return true;
        }
        return conn.getGitTokenEncrypted() != null && !conn.getGitTokenEncrypted().isBlank();
    }
}
