/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Snapshot of a note sync connection exported to the project's local format.
 * Written to {@code data/note-sync/project-note-sync.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KompileProjectNoteSyncConnection {
    private Long id;
    private String provider;
    private String factSheetName;
    private String externalScope;
    private String direction;
    private boolean enabled;
    private String repositoryUrl;
    private String gitBranch;
    private String lastSyncAt;
    private String lastSyncStatus;
    private String createdAt;
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getFactSheetName() { return factSheetName; }
    public void setFactSheetName(String factSheetName) { this.factSheetName = factSheetName; }

    public String getExternalScope() { return externalScope; }
    public void setExternalScope(String externalScope) { this.externalScope = externalScope; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }

    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }

    public String getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(String lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
