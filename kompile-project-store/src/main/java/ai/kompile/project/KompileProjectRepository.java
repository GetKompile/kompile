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

import java.util.LinkedHashMap;
import java.util.Map;

public class KompileProjectRepository {
    private KompileProjectStorageBackend backend = KompileProjectStorageBackend.LOCAL;
    private String remoteUrl;
    private String branch = "main";
    private boolean autoCommit = true;
    private boolean remoteSyncEnabled = true;
    private boolean gitXetEnabled;
    private Map<String, String> metadata = new LinkedHashMap<>();

    public KompileProjectStorageBackend getBackend() {
        return backend;
    }

    public void setBackend(KompileProjectStorageBackend backend) {
        this.backend = backend == null ? KompileProjectStorageBackend.LOCAL : backend;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch == null || branch.isBlank() ? "main" : branch;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public boolean isRemoteSyncEnabled() {
        return remoteSyncEnabled;
    }

    public void setRemoteSyncEnabled(boolean remoteSyncEnabled) {
        this.remoteSyncEnabled = remoteSyncEnabled;
    }

    public boolean isGitXetEnabled() {
        return gitXetEnabled;
    }

    public void setGitXetEnabled(boolean gitXetEnabled) {
        this.gitXetEnabled = gitXetEnabled;
        if (gitXetEnabled && backend == KompileProjectStorageBackend.LOCAL) {
            backend = KompileProjectStorageBackend.GIT_XET;
        }
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
