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
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents an advisory edit lock on a file. Serialized as JSON to
 * {@code <workDir>/.kompile/coordination/edits/<sessionId>-<fileHash>.lock.json}.
 */
@Data
@NoArgsConstructor
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

    /**
     * Returns true if this entry has exceeded its TTL based on the last heartbeat.
     */
    public boolean isStale() {
        return Instant.now().isAfter(lastHeartbeat.plusSeconds(ttlSeconds));
    }
}
