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

package ai.kompile.core.graphrag.partition;

/**
 * A chunk a discovery channel proposes as evidence for a partition.
 *
 * <p>A candidate is a proposal with a reason attached, not an admission. The coordinator decides
 * whether policy admits it, whether the requester may read it, and what state it enters in.</p>
 *
 * @param chunkId      identifier of the proposed chunk
 * @param documentId   source document, kept so invalidation can work document-at-a-time
 * @param channel      which channel proposed it
 * @param confidence   channel's own confidence in [0,1] that this chunk is about the subject
 * @param reason       why this chunk was proposed; shown in the manifest and in audits
 * @param accessScope  domains that may read the chunk; empty means unrestricted
 * @param chunkVersion source version fingerprint, so a changed chunk can be invalidated
 * @param orderKey     optional chronological key (timestamp, sequence) for batch ordering
 */
public record ChunkCandidate(
        String chunkId,
        String documentId,
        DiscoveryChannel channel,
        double confidence,
        String reason,
        AccessScope accessScope,
        String chunkVersion,
        String orderKey) {

    public ChunkCandidate {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("a chunk candidate needs a chunk id");
        }
        chunkId = chunkId.trim();
        channel = channel == null ? DiscoveryChannel.SEMANTIC : channel;
        confidence = clamp(confidence);
        reason = reason == null || reason.isBlank() ? channel.defaultReason() : reason.trim();
        accessScope = accessScope == null ? AccessScope.unrestricted() : accessScope;
    }

    public static ChunkCandidate of(String chunkId, DiscoveryChannel channel, double confidence) {
        return new ChunkCandidate(chunkId, null, channel, confidence, null,
                AccessScope.unrestricted(), null, null);
    }

    public static ChunkCandidate of(String chunkId, DiscoveryChannel channel, double confidence,
                                    String reason) {
        return new ChunkCandidate(chunkId, null, channel, confidence, reason,
                AccessScope.unrestricted(), null, null);
    }

    /**
     * Returns a copy naming the document this chunk came from.
     *
     * <p>Worth setting on every candidate a channel produces: selective invalidation keys on it,
     * so a chunk with no document link cannot be invalidated when its source changes — it goes on
     * looking processed forever.</p>
     */
    public ChunkCandidate inDocument(String documentId) {
        return new ChunkCandidate(chunkId, documentId, channel, confidence, reason, accessScope,
                chunkVersion, orderKey);
    }

    /** Returns a copy carrying an access scope. */
    public ChunkCandidate withAccessScope(AccessScope scope) {
        return new ChunkCandidate(chunkId, documentId, channel, confidence, reason, scope,
                chunkVersion, orderKey);
    }

    /** Returns a copy carrying a source version fingerprint. */
    public ChunkCandidate withVersion(String chunkVersion) {
        return new ChunkCandidate(chunkId, documentId, channel, confidence, reason, accessScope,
                chunkVersion, orderKey);
    }

    /** Returns a copy carrying a chronological ordering key. */
    public ChunkCandidate withOrderKey(String orderKey) {
        return new ChunkCandidate(chunkId, documentId, channel, confidence, reason, accessScope,
                chunkVersion, orderKey);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
