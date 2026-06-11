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

package ai.kompile.cli.main.coordination;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Represents an advisory edit lock on a file. Serialized as JSON to
 * {@code <workDir>/.kompile/coordination/edits/<sessionId>-<fileHash>.lock.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditLockEntry {

    @JsonProperty("lockId")
    private String lockId;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("filePath")
    private String filePath;

    @JsonProperty("absolutePath")
    private String absolutePath;

    @JsonProperty("editType")
    private String editType;

    @JsonProperty("acquiredAt")
    private Instant acquiredAt;

    @JsonProperty("lastHeartbeat")
    private Instant lastHeartbeat;

    @JsonProperty("ttlSeconds")
    private int ttlSeconds;

    public EditLockEntry() {}

    public EditLockEntry(String lockId, String sessionId, String agentName,
                         String filePath, String absolutePath, String editType,
                         Instant acquiredAt, int ttlSeconds) {
        this.lockId = lockId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.filePath = filePath;
        this.absolutePath = absolutePath;
        this.editType = editType;
        this.acquiredAt = acquiredAt;
        this.lastHeartbeat = acquiredAt;
        this.ttlSeconds = ttlSeconds;
    }

    public String getLockId() { return lockId; }
    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }
    public String getFilePath() { return filePath; }
    public String getAbsolutePath() { return absolutePath; }
    public String getEditType() { return editType; }
    public Instant getAcquiredAt() { return acquiredAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public int getTtlSeconds() { return ttlSeconds; }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    /**
     * Returns true if this entry has exceeded its TTL based on the last heartbeat.
     */
    public boolean isStale() {
        return Instant.now().isAfter(lastHeartbeat.plusSeconds(ttlSeconds));
    }
}
