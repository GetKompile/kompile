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

import ai.kompile.app.sync.domain.SyncAuthMode;
import ai.kompile.app.sync.domain.SyncDirection;
import ai.kompile.app.sync.domain.SyncProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncConnectionRequest {
    private Long factSheetId;
    private SyncProvider provider;
    private String externalScope;
    private SyncDirection direction;
    private String pollCron;
    /** Obsidian REST API base URL (e.g. "https://localhost:27124"). */
    private String obsidianApiUrl;
    /** Obsidian Bearer token -- write-only, never returned in responses. */
    private String obsidianToken;
    /** Explicit auth mode. If omitted, Kompile derives it from provider settings. */
    private SyncAuthMode authMode;
    /** Git HTTPS username for token-based auth. */
    private String gitUsername;
    /** Git HTTPS token/password -- write-only, never returned in responses. */
    private String gitToken;
    /** Git remote URL. externalScope remains the local working tree path. */
    private String repositoryUrl;
    /** Git branch to sync. Defaults to main when blank. */
    private String gitBranch;
    /** Whether git changes should be committed automatically. */
    private Boolean autoCommit;
    /** Whether git pull/push should run during sync. */
    private Boolean remoteSyncEnabled;
}
