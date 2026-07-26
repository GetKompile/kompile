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

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.partition.MiniBatchPlanner;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Packs one evidence stratum into batches using the crawl's own cost planner.
 *
 * <p>This is the join between two things that were never the same question. {@link
 * MiniBatchPlanner} decides <em>what order</em> evidence should be read in — authoritative records
 * before rumours — and delegates <em>which chunks share a batch</em> to whatever the caller already
 * uses for that. On the crawl side that is {@link CrawlBatchPlanner}, which balances cost so one
 * batch is not three times the work of its neighbour. Composed this way, neither has to know about
 * the other's concern.</p>
 *
 * <p><strong>Balancing decides grouping, not sequence.</strong> Cost balancing sorts a stratum
 * heaviest-first and fills the lightest batch, so it also decides — incidentally — what order the
 * work comes back in. That part is thrown away and the stratum's own confidence order is put back,
 * inside each batch and across them.</p>
 *
 * <p><strong>Where balancing is refused outright.</strong> Restoring the order cannot undo the
 * grouping: if balancing puts the first and last members of a sequence in one batch, they are
 * still read back to back. That is fatal for members carrying a {@link PartitionMember#orderKey()},
 * which exists so a process or time series is read in the order it happened, so a stratum with any
 * order key is packed sequentially instead. The price is some imbalance in one stratum; the
 * alternative is silently reading a sequence out of order, which a downstream reasoner has no way
 * to detect has happened.</p>
 *
 * <p>Package-scoped because {@link CrawlBatchPlanner} is: callers outside the crawl reach this
 * through {@code EntityPartitionCrawlService} rather than naming an internal planner.</p>
 */
final class PartitionBatchPacker {

    /** Uniform cost: every member weighs the same, so packing reduces to a size cap. */
    static final Function<PartitionMember, Long> UNIFORM_COST = member -> 1L;

    private final CrawlBatchPlanner planner;
    private final Function<PartitionMember, Long> costEstimator;
    private final int maxItemsPerBatch;
    private final long targetCostPerBatch;
    private final boolean balanceByCost;

    private PartitionBatchPacker(CrawlBatchPlanner planner,
                                 Function<PartitionMember, Long> costEstimator,
                                 int maxItemsPerBatch, long targetCostPerBatch,
                                 boolean balanceByCost) {
        this.planner = Objects.requireNonNull(planner, "a packer needs the crawl batch planner");
        this.costEstimator = costEstimator == null ? UNIFORM_COST : costEstimator;
        this.maxItemsPerBatch = Math.max(1, maxItemsPerBatch);
        this.targetCostPerBatch = Math.max(0L, targetCostPerBatch);
        this.balanceByCost = balanceByCost;
    }

    /**
     * A packer that caps batch size and treats every member as equal work.
     *
     * <p>Balancing is off rather than fed uniform weights: with nothing to balance it could only
     * shuffle, and shuffling a stratum that is already in confidence order is a loss with no
     * matching gain.</p>
     */
    static PartitionBatchPacker bySize(CrawlBatchPlanner planner, int maxItemsPerBatch) {
        return new PartitionBatchPacker(planner, UNIFORM_COST, maxItemsPerBatch, 0L, false);
    }

    /**
     * A packer that balances by estimated cost.
     *
     * @param costEstimator      cost of one member; a member it cannot price falls back to 1
     * @param targetCostPerBatch soft cost cap per batch, 0 for none
     */
    static PartitionBatchPacker byCost(CrawlBatchPlanner planner,
                                       Function<PartitionMember, Long> costEstimator,
                                       int maxItemsPerBatch, long targetCostPerBatch) {
        return new PartitionBatchPacker(planner, costEstimator, maxItemsPerBatch,
                targetCostPerBatch, true);
    }

    /**
     * Prices members by the text of the chunk they name, using the same estimator the crawl uses
     * for documents — so a table-heavy chunk is not treated as though it were a paragraph.
     *
     * @param chunks chunk id to the document it came from; an id it does not know costs 1
     */
    static Function<PartitionMember, Long> costOfChunks(CrawlBatchPlanner planner,
                                                        Map<String, Document> chunks) {
        if (planner == null || chunks == null || chunks.isEmpty()) {
            return UNIFORM_COST;
        }
        return member -> {
            Document document = member == null ? null : chunks.get(member.chunkId());
            return document == null ? 1L : planner.estimateDocumentCost(document);
        };
    }

    /** The seam {@code PartitionLifecycle} and {@code MiniBatchPlanner.plan} take. */
    Function<List<PartitionMember>, List<List<PartitionMember>>> asPacker() {
        return this::pack;
    }

    /** Packs one stratum. Members are already in evidence order when this is called. */
    List<List<PartitionMember>> pack(List<PartitionMember> stratum) {
        if (stratum == null || stratum.isEmpty()) {
            return List.of();
        }
        boolean balance = balanceByCost && !preservesOrder(stratum);
        List<CrawlBatchPlanner.CostBatch<PartitionMember>> batches = planner.planCostBatches(
                stratum, costEstimator, maxItemsPerBatch, targetCostPerBatch, balance);
        List<List<PartitionMember>> packed = new ArrayList<>(batches.size());
        for (CrawlBatchPlanner.CostBatch<PartitionMember> batch : batches) {
            if (batch != null && !batch.items().isEmpty()) {
                packed.add(new ArrayList<>(batch.items()));
            }
        }
        return balance ? inEvidenceOrder(packed, stratum) : packed;
    }

    /**
     * Restores the stratum's own order after cost balancing.
     *
     * <p>Balancing answers "which chunks share a batch". It answers that by sorting heaviest-first
     * and filling the lightest batch, which also — incidentally, and unhelpfully — decides what
     * order the work comes back in. Those are different questions, and the second already has an
     * answer: the stratum arrives sorted by confidence. So the grouping is kept and the sequence
     * is put back, both inside each batch and across them. Without this a low-confidence chunk can
     * be read before a high-confidence one purely because it happened to be long.</p>
     */
    private static List<List<PartitionMember>> inEvidenceOrder(List<List<PartitionMember>> packed,
                                                               List<PartitionMember> stratum) {
        Map<String, Integer> rank = new HashMap<>(Math.max(16, stratum.size() * 2));
        for (int i = 0; i < stratum.size(); i++) {
            PartitionMember member = stratum.get(i);
            if (member != null) {
                rank.putIfAbsent(member.chunkId(), i);
            }
        }
        Comparator<PartitionMember> byRank =
                Comparator.comparingInt(member -> rank.getOrDefault(member.chunkId(),
                        Integer.MAX_VALUE));
        for (List<PartitionMember> batch : packed) {
            batch.sort(byRank);
        }
        packed.sort(Comparator.comparingInt(batch -> batch.isEmpty()
                ? Integer.MAX_VALUE : rank.getOrDefault(batch.get(0).chunkId(), Integer.MAX_VALUE)));
        return packed;
    }

    /**
     * True when this stratum's order is load-bearing and must survive packing.
     *
     * <p>An order key is only ever set by a channel that means it — a process trace, a time
     * series — so one member carrying one is enough to make the whole stratum's sequence
     * meaningful.</p>
     */
    static boolean preservesOrder(List<PartitionMember> stratum) {
        for (PartitionMember member : stratum) {
            if (member != null && member.orderKey() != null && !member.orderKey().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
