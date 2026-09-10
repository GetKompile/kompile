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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Restart-stable optimistic-concurrency token: SHA-256 of the exact current archive bytes. */
public record AgentGraphRevision(String sha256) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public AgentGraphRevision {
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Agent graph revision must be a lowercase SHA-256 digest");
        }
    }

    public static AgentGraphRevision fromArchive(byte[] archive) {
        Objects.requireNonNull(archive, "archive");
        try {
            return new AgentGraphRevision(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(archive)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", impossible);
        }
    }
}
