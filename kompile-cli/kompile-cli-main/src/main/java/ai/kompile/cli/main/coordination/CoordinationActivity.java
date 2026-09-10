/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
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
 * Durable reservation for work that can materially increase host RAM or GPU use.
 *
 * <p>Unlike edit locks, activity reservations are user-wide rather than project-local:
 * two Kompile sessions in different folders still share the same physical machine. A
 * reservation can remain attached to a background process or asynchronous job after the
 * launching tool call returns.</p>
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoordinationActivity {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonProperty("schemaVersion")
    private int schemaVersion;

    @JsonProperty("activityId")
    private String activityId;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("projectRoot")
    private String projectRoot;

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("toolName")
    private String toolName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("ownerPid")
    private long ownerPid;

    @JsonProperty("processId")
    private String processId;

    @JsonProperty("processPid")
    private long processPid;

    @JsonProperty("externalId")
    private String externalId;

    @JsonProperty("startedAt")
    private Instant startedAt;

    @JsonProperty("lastHeartbeat")
    private Instant lastHeartbeat;

    @JsonProperty("expiresAt")
    private Instant expiresAt;

    @JsonProperty("ttlSeconds")
    private int ttlSeconds;

    public CoordinationActivity(String activityId, String sessionId, String agentName,
                                String projectRoot, String kind, String toolName,
                                String description, long ownerPid, Instant now,
                                int ttlSeconds) {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.activityId = activityId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.projectRoot = projectRoot;
        this.kind = kind;
        this.toolName = toolName;
        this.description = description;
        this.ownerPid = ownerPid;
        this.startedAt = now;
        this.lastHeartbeat = now;
        this.ttlSeconds = ttlSeconds;
        this.expiresAt = now.plusSeconds(ttlSeconds);
    }
}
