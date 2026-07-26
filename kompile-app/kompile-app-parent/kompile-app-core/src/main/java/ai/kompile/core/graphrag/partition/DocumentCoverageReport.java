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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the partition pass did with one document — the other axis of the coverage question.
 *
 * <p>{@link PartitionCoverageReport} answers "how much has been read about this subject". This
 * answers "how much of this document was read, and for whom". The two are not the same question
 * and neither implies the other: a document can be fully processed and still leave a subject
 * uncovered, and a subject can be provisionally complete while most of a document it barely
 * touches goes unread.</p>
 *
 * <p>Counted by distinct chunk, not by membership record. The same chunk is deliberately a member
 * of every partition it is evidence for — that overlap is the point of partitioning — so counting
 * memberships would report a document as several times its own size. Where partitions disagree
 * about a chunk, {@link MembershipState#merge(MembershipState, MembershipState)} decides, because
 * that is the model's own answer to the same chunk seen twice.</p>
 *
 * @param documentId   the document asked about
 * @param partitions   partitions holding at least one chunk of it
 * @param chunks       distinct chunks of it any partition has seen
 * @param read         distinct chunks processed by at least one partition
 * @param admitted     distinct chunks judged evidence for something — the {@link #coverage} denominator
 * @param excluded     distinct chunks every partition that saw them rejected
 * @param outstanding  distinct chunks still owed work
 * @param inaccessible distinct chunks nobody was permitted to read
 * @param invalidated  distinct chunks whose source moved since they were read
 * @param coverage     {@code read / admitted}, in [0,1]
 * @param byState      merged membership state name to distinct chunk count
 * @param subjects     subjects of the partitions holding it, sorted
 * @param chunkDetail  per-chunk detail, unread first
 */
public record DocumentCoverageReport(
        String documentId,
        int partitions,
        int chunks,
        int read,
        int admitted,
        int excluded,
        int outstanding,
        int inaccessible,
        int invalidated,
        double coverage,
        Map<String, Integer> byState,
        List<String> subjects,
        List<ChunkCoverage> chunkDetail) {

    public DocumentCoverageReport {
        documentId = documentId == null ? "" : documentId;
        byState = byState == null ? Map.of() : Map.copyOf(byState);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        chunkDetail = chunkDetail == null ? List.of() : List.copyOf(chunkDetail);
    }

    /**
     * Rolls whatever partitions were found into one document's view.
     *
     * <p>Partitions that never saw the document are ignored rather than reported as zero: they are
     * not evidence that it went unread, they are simply about something else.</p>
     */
    public static DocumentCoverageReport of(String documentId, Collection<EntityPartition> found) {
        Map<String, MembershipState> stateByChunk = new LinkedHashMap<>();
        Map<String, DiscoveryChannel> channelByChunk = new LinkedHashMap<>();
        Map<String, TreeSet<String>> holdersByChunk = new LinkedHashMap<>();
        TreeSet<String> subjects = new TreeSet<>();
        int holding = 0;

        for (EntityPartition partition : found == null ? List.<EntityPartition>of() : found) {
            if (partition == null) {
                continue;
            }
            boolean touched = false;
            for (PartitionMember member : partition.memberList()) {
                if (documentId == null || !documentId.equals(member.documentId())) {
                    continue;
                }
                touched = true;
                String chunkId = member.chunkId();
                stateByChunk.merge(chunkId, member.state(), MembershipState::merge);
                channelByChunk.merge(chunkId, member.channel(), DiscoveryChannel::strongest);
                String subject = partition.key().subject();
                holdersByChunk.computeIfAbsent(chunkId, k -> new TreeSet<>())
                        .add(subject == null ? partition.key().id() : subject);
            }
            if (touched) {
                holding++;
                String subject = partition.key().subject();
                subjects.add(subject == null ? partition.key().id() : subject);
            }
        }

        Map<String, Integer> byState = new TreeMap<>();
        List<ChunkCoverage> detail = new ArrayList<>();
        int read = 0;
        int excluded = 0;
        int outstanding = 0;
        int inaccessible = 0;
        int invalidated = 0;
        for (Map.Entry<String, MembershipState> entry : stateByChunk.entrySet()) {
            MembershipState state = entry.getValue();
            byState.merge(state.name(), 1, Integer::sum);
            if (state.isCovered()) {
                read++;
            }
            if (state == MembershipState.EXCLUDED) {
                excluded++;
            }
            if (state.isOutstanding()) {
                outstanding++;
            }
            if (state == MembershipState.INACCESSIBLE) {
                inaccessible++;
            }
            if (state == MembershipState.INVALIDATED) {
                invalidated++;
            }
            DiscoveryChannel channel = channelByChunk.get(entry.getKey());
            detail.add(new ChunkCoverage(entry.getKey(), state.name(),
                    channel == null ? null : channel.name(),
                    List.copyOf(holdersByChunk.getOrDefault(entry.getKey(), new TreeSet<>()))));
        }

        // Unread first, then by chunk id: the chunks an answer is missing are the ones worth
        // seeing without scrolling, and a stable secondary key keeps the page from reshuffling.
        detail.sort(Comparator.comparing(ChunkCoverage::read)
                .thenComparing(ChunkCoverage::chunkId,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        int chunks = stateByChunk.size();
        int admitted = chunks - excluded;
        return new DocumentCoverageReport(documentId, holding, chunks, read, admitted, excluded,
                outstanding, inaccessible, invalidated,
                admitted == 0 ? 0.0 : (double) read / admitted,
                byState, List.copyOf(subjects), detail);
    }

    /**
     * A document no partition has seen.
     *
     * <p>Not an error, and specifically not the same as fully read: it means no subject has
     * claimed this document as evidence, so nothing in the graph rests on it.</p>
     */
    public static DocumentCoverageReport empty(String documentId) {
        return of(documentId, List.of());
    }

    /** One-line rendering, for logs and for a caller that only wants the headline. */
    public String describe() {
        if (partitions == 0) {
            return "no partition has read " + documentId;
        }
        return documentId + ": " + read + "/" + admitted + String.format(" (%.0f%%)", coverage * 100)
                + " chunk(s) read across " + partitions + " partition(s)"
                + (excluded > 0 ? ", " + excluded + " excluded" : "")
                + (outstanding > 0 ? ", " + outstanding + " outstanding" : "")
                + (inaccessible > 0 ? ", " + inaccessible + " unreadable" : "");
    }

    /**
     * One chunk of the document, and what became of it.
     *
     * @param chunkId the chunk
     * @param state   merged membership state name across every partition holding it
     * @param channel strongest channel that proposed it anywhere
     * @param heldBy  subjects of the partitions holding it, sorted
     */
    public record ChunkCoverage(String chunkId, String state, String channel, List<String> heldBy) {

        public ChunkCoverage {
            heldBy = heldBy == null ? List.of() : List.copyOf(heldBy);
        }

        /** True when some partition processed this chunk — the only state that is coverage. */
        public boolean read() {
            return MembershipState.PROCESSED.name().equals(state);
        }
    }
}
