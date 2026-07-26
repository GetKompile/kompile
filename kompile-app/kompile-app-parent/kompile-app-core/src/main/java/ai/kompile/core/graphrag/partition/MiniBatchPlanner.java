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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Orders a partition's outstanding work into mini-batches.
 *
 * <p>Two different questions have been getting one answer. "Which chunk should be read first?" is
 * a question about evidence — authoritative records before rumours, so the priors exist before
 * anything has to be weighed against them. "Which chunks should share a batch?" is a question
 * about resources. Cost balancing answers the second well and the first not at all; running it
 * alone means a contradiction can be processed before the record it contradicts.</p>
 *
 * <p>So this planner does the first job only: it splits members into strata by
 * {@link DiscoveryChannel#priority()} and orders within a stratum by confidence, then chronology,
 * then id. Packing inside a stratum is delegated to whatever the caller already uses — the crawl
 * passes its cost-balanced packer straight through. The two concerns compose instead of
 * competing.</p>
 */
public final class MiniBatchPlanner {

    private MiniBatchPlanner() {
    }

    /**
     * A slice of one stratum, ready to process.
     *
     * @param index    1-based batch index across the whole plan
     * @param stratum  evidence stratum this batch belongs to
     * @param priority the stratum's {@link DiscoveryChannel#priority()}
     * @param members  members to process, in order
     */
    public record MiniBatch(int index, DiscoveryChannel stratum, int priority,
                            List<PartitionMember> members) {

        public MiniBatch {
            members = members == null ? List.of() : List.copyOf(members);
        }

        public int size() {
            return members.size();
        }

        public List<String> chunkIds() {
            return members.stream().map(PartitionMember::chunkId).toList();
        }
    }

    /**
     * Splits schedulable members into evidence strata, most authoritative first.
     *
     * <p>Ordering within a stratum is confidence descending, then {@link
     * PartitionMember#orderKey()} ascending so a process or time series is read in the order it
     * happened, then chunk id so the plan is reproducible.</p>
     */
    public static Map<DiscoveryChannel, List<PartitionMember>> stratify(EntityPartition partition) {
        Map<DiscoveryChannel, List<PartitionMember>> strata = new LinkedHashMap<>();
        if (partition == null) {
            return strata;
        }
        List<PartitionMember> schedulable = new ArrayList<>(partition.schedulable());
        schedulable.sort(ORDER);
        for (PartitionMember member : schedulable) {
            strata.computeIfAbsent(member.channel(), key -> new ArrayList<>()).add(member);
        }
        return strata;
    }

    /** Evidence priority, then confidence, then chronology, then id for determinism. */
    private static final Comparator<PartitionMember> ORDER =
            Comparator.comparingInt((PartitionMember m) -> m.channel().priority())
                    .thenComparing(Comparator.comparingDouble(PartitionMember::confidence).reversed())
                    .thenComparing(m -> m.orderKey() == null ? "" : m.orderKey())
                    .thenComparing(PartitionMember::chunkId);

    /**
     * Plans mini-batches with simple sequential packing inside each stratum.
     *
     * @param partition       partition to plan
     * @param maxItemsPerBatch hard cap on members per batch
     */
    public static List<MiniBatch> plan(EntityPartition partition, int maxItemsPerBatch) {
        int cap = Math.max(1, maxItemsPerBatch);
        return plan(partition, stratum -> sequential(stratum, cap));
    }

    /**
     * Plans mini-batches, delegating packing within each stratum to {@code packer}.
     *
     * <p>This is the composition point: pass the crawl's cost-balanced planner and each stratum
     * is packed by cost while the strata themselves stay in evidence order.</p>
     *
     * @param partition partition to plan
     * @param packer    turns one stratum's ordered members into batches; must not reorder across
     *                  strata, and may return empty
     */
    public static List<MiniBatch> plan(EntityPartition partition,
                                       Function<List<PartitionMember>, List<List<PartitionMember>>> packer) {
        List<MiniBatch> plan = new ArrayList<>();
        if (partition == null || packer == null) {
            return plan;
        }
        int index = 1;
        for (Map.Entry<DiscoveryChannel, List<PartitionMember>> entry : stratify(partition).entrySet()) {
            List<List<PartitionMember>> packed = packer.apply(entry.getValue());
            if (packed == null) {
                continue;
            }
            for (List<PartitionMember> batch : packed) {
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                plan.add(new MiniBatch(index++, entry.getKey(), entry.getKey().priority(), batch));
            }
        }
        return plan;
    }

    /** Default packer: fixed-size slices preserving order. */
    public static List<List<PartitionMember>> sequential(List<PartitionMember> members, int maxSize) {
        List<List<PartitionMember>> batches = new ArrayList<>();
        if (members == null || members.isEmpty()) {
            return batches;
        }
        int cap = Math.max(1, maxSize);
        for (int start = 0; start < members.size(); start += cap) {
            batches.add(new ArrayList<>(members.subList(start, Math.min(members.size(), start + cap))));
        }
        return batches;
    }
}
