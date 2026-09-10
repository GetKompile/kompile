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
package ai.kompile.agent.graph;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted, non-secret agent manifest.
 *
 * <p>This record intentionally contains no credentials, tokens, environment values, channel
 * secrets, model-selected graph selectors, or filesystem paths.</p>
 */
public record AgentInstance(
        int manifestVersion,
        AgentPrincipal principal,
        String displayName,
        Instant createdAt,
        Instant updatedAt,
        long revision) {

    public static final int CURRENT_MANIFEST_VERSION = 1;
    public static final int MAX_DISPLAY_NAME_LENGTH = 512;

    public AgentInstance {
        if (manifestVersion != CURRENT_MANIFEST_VERSION) {
            throw new IllegalArgumentException("Unsupported agent manifest version: " + manifestVersion);
        }
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName exceeds " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }
}
