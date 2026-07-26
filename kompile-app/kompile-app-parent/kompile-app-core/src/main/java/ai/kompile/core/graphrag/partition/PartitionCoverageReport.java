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

/**
 * What a set of partitions covers, and what they did not look at — the read side of the control
 * plane.
 *
 * <p>{@link EvidenceManifest} already answers this for one partition, but it is derived, not
 * stored, and it holds enums and computed accessors that no serialiser will project on its own. A
 * caller asking "how much of this fact sheet has actually been read" wants the roll-up as well as
 * the detail, wants it in a shape that survives a JSON boundary, and wants the gaps named rather
 * than netted out. That is this record.</p>
 *
 * <p>Every number is relative to the policy each partition was recorded under; two partitions with
 * different {@code policyVersion}s are two different claims and are reported as such rather than
 * averaged into one. The roll-up is a sum over whatever was asked for, which is why {@link #scope}
 * says what that was.</p>
 *
 * @param scope            what was asked for, in the caller's own terms
 * @param partitions       how many partitions the scope matched
 * @param complete         partitions that are provisionally complete under their own policy
 * @param completeWithGaps of those, the ones that finished holding material they could not read
 * @param open             partitions that still owe work
 * @param covered          chunks actually processed across the scope
 * @param admitted         chunks judged to be evidence — the denominator of {@link #coverage}
 * @param excluded         proposals the policy rejected outright; stated, never netted out
 * @param outstanding      chunks still owed work across the scope
 * @param deferred         chunks admitted but postponed
 * @param inaccessible     chunks the run was not permitted to read
 * @param invalidated      chunks whose source moved and which must be re-read
 * @param coverage         {@code covered / admitted} over the whole scope, in [0,1]
 * @param byState          membership state name to count, summed over the scope
 * @param byChannel        discovery channel name to count, summed over the scope
 * @param subjects         per-partition detail, least covered first
 */
public record PartitionCoverageReport(
        String scope,
        int partitions,
        int complete,
        int completeWithGaps,
        int open,
        int covered,
        int admitted,
        int excluded,
        int outstanding,
        int deferred,
        int inaccessible,
        int invalidated,
        double coverage,
        Map<String, Integer> byState,
        Map<String, Integer> byChannel,
        List<SubjectCoverage> subjects) {

    public PartitionCoverageReport {
        scope = scope == null ? "" : scope;
        byState = byState == null ? Map.of() : Map.copyOf(byState);
        byChannel = byChannel == null ? Map.of() : Map.copyOf(byChannel);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
    }

    /**
     * Rolls a set of partitions into one report.
     *
     * <p>Ordering is deliberate: open partitions first, then by ascending coverage. The partition
     * that has read the least is the one an answer drawn from this corpus is most likely to be
     * wrong about, so it is the one that should not need scrolling to.</p>
     */
    public static PartitionCoverageReport of(String scope, Collection<EntityPartition> found) {
        List<SubjectCoverage> subjects = new ArrayList<>();
        Map<String, Integer> states = new LinkedHashMap<>();
        Map<String, Integer> channels = new LinkedHashMap<>();
        int complete = 0;
        int withGaps = 0;
        int covered = 0;
        int admitted = 0;
        int excluded = 0;
        int outstanding = 0;
        int deferred = 0;
        int inaccessible = 0;
        int invalidated = 0;

        for (EntityPartition partition : found == null ? List.<EntityPartition>of() : found) {
            if (partition == null) {
                continue;
            }
            EvidenceManifest manifest = partition.manifest();
            subjects.add(SubjectCoverage.of(partition, manifest));
            if (manifest.isProvisionallyComplete()) {
                complete++;
                if (manifest.isCompleteWithGaps()) {
                    withGaps++;
                }
            }
            covered += manifest.covered();
            admitted += manifest.admitted();
            excluded += manifest.count(MembershipState.EXCLUDED);
            outstanding += manifest.outstanding().size();
            deferred += manifest.deferred().size();
            inaccessible += manifest.inaccessible().size();
            invalidated += manifest.invalidated().size();
            manifest.byState().forEach((state, count) ->
                    states.merge(state.name(), count, Integer::sum));
            manifest.byChannel().forEach((channel, count) ->
                    channels.merge(channel.name(), count, Integer::sum));
        }

        subjects.sort(Comparator.comparing(SubjectCoverage::provisionallyComplete)
                .thenComparingDouble(SubjectCoverage::coverage)
                .thenComparing(SubjectCoverage::subject,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        return new PartitionCoverageReport(scope, subjects.size(), complete, withGaps,
                subjects.size() - complete, covered, admitted, excluded, outstanding, deferred,
                inaccessible, invalidated,
                admitted == 0 ? 0.0 : (double) covered / admitted,
                states, channels, subjects);
    }

    /** A scope nothing has been recorded for — an honest answer, not an error. */
    public static PartitionCoverageReport empty(String scope) {
        return of(scope, List.of());
    }

    /** One-line rendering, for logs and for a caller that only wants the headline. */
    public String describe() {
        if (partitions == 0) {
            return "no partitions recorded for " + scope;
        }
        return partitions + " partition(s) for " + scope + ": " + covered + "/" + admitted
                + String.format(" (%.0f%%)", coverage * 100) + ", " + complete + " complete"
                + (completeWithGaps > 0 ? " (" + completeWithGaps + " with gaps)" : "")
                + (outstanding > 0 ? ", " + outstanding + " chunk(s) outstanding" : "");
    }

    /**
     * A single partition's coverage claim in a form that crosses a serialisation boundary.
     *
     * <p>Enums become their names and the derived accessors of {@link EvidenceManifest} become
     * fields, because a reader on the far side of JSON has neither. The four gap lists are carried
     * whole rather than counted: "which chunks did this not read" is the question the whole
     * partition model exists to be able to answer, and a count cannot be acted on.</p>
     */
    public record SubjectCoverage(
            String partitionId,
            String subject,
            String entityId,
            String groupId,
            String category,
            String timeWindow,
            String policyVersion,
            String snapshotId,
            String phase,
            int round,
            boolean frontierExhausted,
            int covered,
            int admitted,
            int excluded,
            double coverage,
            boolean provisionallyComplete,
            boolean completeWithGaps,
            Map<String, Integer> byState,
            Map<String, Integer> byChannel,
            List<String> outstanding,
            List<String> deferred,
            List<String> inaccessible,
            List<String> invalidated,
            Map<String, String> pins,
            String summary) {

        public SubjectCoverage {
            byState = byState == null ? Map.of() : Map.copyOf(byState);
            byChannel = byChannel == null ? Map.of() : Map.copyOf(byChannel);
            outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
            deferred = deferred == null ? List.of() : List.copyOf(deferred);
            inaccessible = inaccessible == null ? List.of() : List.copyOf(inaccessible);
            invalidated = invalidated == null ? List.of() : List.copyOf(invalidated);
            pins = pins == null ? Map.of() : Map.copyOf(pins);
        }

        public static SubjectCoverage of(EntityPartition partition) {
            return of(partition, partition.manifest());
        }

        static SubjectCoverage of(EntityPartition partition, EvidenceManifest manifest) {
            PartitionKey key = partition.key();
            Map<String, Integer> states = new LinkedHashMap<>();
            manifest.byState().forEach((state, count) -> states.put(state.name(), count));
            Map<String, Integer> channels = new LinkedHashMap<>();
            manifest.byChannel().forEach((channel, count) -> channels.put(channel.name(), count));
            return new SubjectCoverage(key.id(), key.subject(), key.entityId(), key.groupId(),
                    key.category(), key.timeWindow(), key.policyVersion(), key.snapshotId(),
                    partition.phase().name(), manifest.round(), manifest.frontierExhausted(),
                    manifest.covered(), manifest.admitted(),
                    manifest.count(MembershipState.EXCLUDED), manifest.coverage(),
                    manifest.isProvisionallyComplete(), manifest.isCompleteWithGaps(),
                    states, channels, manifest.outstanding(), manifest.deferred(),
                    manifest.inaccessible(), manifest.invalidated(), partition.pins(),
                    manifest.describe());
        }
    }
}
