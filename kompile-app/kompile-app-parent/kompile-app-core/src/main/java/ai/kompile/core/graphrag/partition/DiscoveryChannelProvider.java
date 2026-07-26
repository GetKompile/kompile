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

import java.util.List;

/**
 * One way of finding evidence about a subject.
 *
 * <p>The channels already exist in kompile as separate services — entity resolution, graph
 * neighbourhood expansion, embedding search, process mining, contradiction detection. What was
 * missing was anything that ran them as a set and wrote down which one found what. This interface
 * is that seam: implementations wrap an existing retriever without changing it, and the
 * coordinator turns their proposals into auditable membership.</p>
 *
 * <p>Implementations are called once per discovery round and may use the partition's current
 * members to expand from the frontier — a structured-relationship channel, for instance, walks
 * out from chunks admitted in the previous round rather than re-walking the whole graph.</p>
 *
 * <p>Declared in {@code kompile-app-core} so the control plane stays dependency-free; the
 * graph-backed implementations live where the indexes do.</p>
 */
public interface DiscoveryChannelProvider {

    /** Which channel this provider supplies. Used for ordering, policy gating and audit. */
    DiscoveryChannel channel();

    /**
     * Proposes chunks for {@code partition}, best first.
     *
     * <p>Returning fewer than {@code limit} is normal and is how the frontier is signalled to be
     * exhausted. Implementations should not filter on confidence — the policy owns that decision
     * so that thresholds stay in one place and are recorded with the run.</p>
     *
     * @param partition current partition, including everything admitted so far
     * @param round     1-based discovery round
     * @param limit     hard upper bound on returned candidates
     */
    List<ChunkCandidate> discover(EntityPartition partition, int round, int limit);

    /** Provider that proposes nothing; useful as a placeholder and in tests. */
    static DiscoveryChannelProvider none(DiscoveryChannel channel) {
        return new DiscoveryChannelProvider() {
            @Override
            public DiscoveryChannel channel() {
                return channel;
            }

            @Override
            public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
                return List.of();
            }
        };
    }

    /**
     * Provider that proposes a fixed set on the first round and nothing after — the shape a seed
     * list takes.
     */
    static DiscoveryChannelProvider seeded(DiscoveryChannel channel,
                                           List<ChunkCandidate> candidates) {
        List<ChunkCandidate> fixed = candidates == null ? List.of() : List.copyOf(candidates);
        return new DiscoveryChannelProvider() {
            @Override
            public DiscoveryChannel channel() {
                return channel;
            }

            @Override
            public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
                if (round > 1) {
                    return List.of();
                }
                return fixed.size() <= limit ? fixed : fixed.subList(0, limit);
            }
        };
    }
}
