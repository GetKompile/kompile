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
 * {@code <workDir>/.kompile/coordination/edits/<lockId>.lock.json}.
 *
 * <p>Ownership is the pair ({@link #sessionId}, {@link #ownerAgent}). A null
 * {@code ownerAgent} is a session-level lock (the historical shape, and what
 * pre-ownership records deserialize to): every caller in that session shares it.
 * A non-null {@code ownerAgent} is the sanitized agent name that scopes the lock
 * to one agent inside the session, so subagents sharing a single MCP connection
 * conflict with each other. {@link #agentName} is only the display label.
 *
 * <p>Liveness has two parts. {@link #lastHeartbeat} + {@link #ttlSeconds} is SESSION
 * liveness: the owning session's heartbeat refreshes it while the session runs. An
 * agent-scoped lock also carries {@link #leaseExpiresAt}, its AGENT liveness: it is moved
 * forward only by that agent's own activity (an edit_coordinator call, an edit-tool call or
 * a register under its agent name), never by the session heartbeat. The lock is stale when
 * either has run out, so a lock a sibling agent forgot does not live as long as its session.
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

    /** Sanitized owning agent within {@link #sessionId}; null for a session-level lock. */
    @JsonProperty("ownerAgent")
    private String ownerAgent;

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

    /**
     * End of the owning agent's lease; null for a session-level lock (and for lock files
     * written before leases existed), which then only ages out through the TTL.
     */
    @JsonProperty("leaseExpiresAt")
    private Instant leaseExpiresAt;

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

    public EditLockEntry(String lockId, String sessionId, String ownerAgent, String agentName,
                         String filePath, String absolutePath, String editType,
                         Instant acquiredAt, int ttlSeconds) {
        this(lockId, sessionId, agentName, filePath, absolutePath, editType, acquiredAt, ttlSeconds);
        this.ownerAgent = ownerAgent;
    }

    /**
     * Returns true if this entry has exceeded its TTL based on the last heartbeat, or its
     * agent lease has expired.
     */
    public boolean isStale() {
        Instant now = Instant.now();
        return isHeartbeatExpired(now) || isLeaseExpired(now);
    }

    /** Session liveness: the owning session's heartbeat TTL has run out at {@code now}. */
    public boolean isHeartbeatExpired(Instant now) {
        return lastHeartbeat == null || now.isAfter(lastHeartbeat.plusSeconds(ttlSeconds));
    }

    /**
     * Agent liveness: the owning agent's lease has run out at {@code now}. Always false for a
     * session-level lock. A lease is moved forward only by the agent's own activity, so a
     * lock renewed within its lease never expires through the lease.
     */
    public boolean isLeaseExpired(Instant now) {
        return leaseExpiresAt != null && now.isAfter(leaseExpiresAt);
    }
}
