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
 * Immutable message exchanged through the project-local coordination mailbox.
 * Messages remain pending until the recipient explicitly acknowledges them.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoordinationMessage {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonProperty("schemaVersion")
    private int schemaVersion;

    @JsonProperty("messageId")
    private String messageId;

    @JsonProperty("senderSessionId")
    private String senderSessionId;

    @JsonProperty("targetSessionId")
    private String targetSessionId;

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("message")
    private String message;

    @JsonProperty("replyTo")
    private String replyTo;

    @JsonProperty("sentAt")
    private Instant sentAt;

    @JsonProperty("expiresAt")
    private Instant expiresAt;

    public CoordinationMessage(String messageId, String senderSessionId,
                               String targetSessionId, String kind, String message,
                               String replyTo, Instant sentAt, Instant expiresAt) {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.messageId = messageId;
        this.senderSessionId = senderSessionId;
        this.targetSessionId = targetSessionId;
        this.kind = kind;
        this.message = message;
        this.replyTo = replyTo;
        this.sentAt = sentAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
