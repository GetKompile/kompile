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

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.PartitionMember;

/**
 * Which chunk taught the partition something, and on what authority.
 *
 * <p>Staging exists so the graph is written once, from a merged view, rather than once per chunk.
 * That merge destroys the one-to-one link between an extraction call and its output, so the link
 * is carried here instead: every staged entity and relationship names the chunks that proposed it,
 * the channel that found those chunks, and the round it happened in. Without this a merged node is
 * unattributable, and an unattributable node cannot be retracted when its source is invalidated.</p>
 *
 * @param chunkId    chunk whose extraction produced the staged item
 * @param documentId source document of that chunk, when known
 * @param channel    channel that admitted the chunk into the partition
 * @param round      discovery round the chunk was admitted in
 * @param confidence the member's confidence in belonging to the partition, not the extractor's
 *                   confidence in the claim
 */
public record StagedProvenance(
        String chunkId,
        String documentId,
        DiscoveryChannel channel,
        int round,
        double confidence) {

    public StagedProvenance {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("staged output has to name the chunk it came from");
        }
        chunkId = chunkId.trim();
        channel = channel == null ? DiscoveryChannel.SEMANTIC : channel;
        round = Math.max(0, round);
        confidence = Double.isNaN(confidence) ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
    }

    /** Provenance for output extracted from {@code member}. */
    public static StagedProvenance from(PartitionMember member) {
        if (member == null) {
            throw new IllegalArgumentException("staged output has to name the member it came from");
        }
        return new StagedProvenance(member.chunkId(), member.documentId(), member.channel(),
                member.discoveredRound(), member.confidence());
    }

    /** Provenance for output attributed to a bare chunk id, with nothing else known about it. */
    public static StagedProvenance ofChunk(String chunkId) {
        return new StagedProvenance(chunkId, null, DiscoveryChannel.SEMANTIC, 0, 0.0);
    }

    public String describe() {
        return chunkId + "@" + channel + "/r" + round;
    }
}
