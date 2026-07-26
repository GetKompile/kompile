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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Works out which subjects a partition is about.
 *
 * <p>An entity-keyed partition answers this from its own key. A group-keyed one cannot: a group id
 * is derived from its members rather than being one of them (see
 * {@link ai.kompile.core.graphrag.partition.grouping.EntityGroup#idFor}), so it names nothing a
 * channel could look up. The membership therefore travels as a pin, and this class is the one
 * place that reading is written down — the same shape, and for the same reason, as the fact-sheet
 * resolver on the graph side.</p>
 *
 * <p>The grouping version travels with it. A partition's key already records the <em>discovery</em>
 * policy it was found under; the grouping policy decided something earlier and more fundamental —
 * what the partition is about at all — and a claim that does not record it cannot say whether a
 * later run's differing coverage is a real change or just a different grouping.</p>
 */
public final class PartitionSubjects {

    /** Pin carrying every subject the partition reads, one per line. */
    public static final String SUBJECTS_PIN = "subjects";

    /** Pin carrying the grouping policy version that decided that list. */
    public static final String GROUPING_VERSION_PIN = "groupingVersion";

    /**
     * One subject per line.
     *
     * <p>Newline rather than comma because entity titles routinely contain commas, and a
     * comma-delimited pin would quietly split {@code "Acme, Inc."} into two subjects that name
     * nothing at all.</p>
     */
    private static final String DELIMITER = "\n";

    private PartitionSubjects() {
    }

    /** Resolver reading the pin, then the key's own entity id. Never throws; unknown means empty. */
    public static Function<EntityPartition, List<String>> fromPinOrKey() {
        return PartitionSubjects::resolve;
    }

    /** Resolver fixed to a known membership, for callers that already hold the group. */
    public static Function<EntityPartition, List<String>> fixed(Collection<String> subjects) {
        List<String> pinned = clean(subjects);
        return partition -> pinned;
    }

    /**
     * The subjects a partition is about, in pinned order, or empty when it does not say.
     *
     * <p>Empty rather than a guess: falling back to the group id would send a channel looking for
     * a node that was never in the graph, and whatever it happened to find would be attributed to
     * a claim nobody made.</p>
     */
    public static List<String> resolve(EntityPartition partition) {
        if (partition == null) {
            return List.of();
        }
        List<String> pinned = parse(partition.pins().get(SUBJECTS_PIN));
        if (!pinned.isEmpty()) {
            return pinned;
        }
        String entityId = partition.key().entityId();
        return entityId == null ? List.of() : List.of(entityId);
    }

    /** The grouping version a partition was decided under, or {@code null} when it does not say. */
    public static String groupingVersion(EntityPartition partition) {
        if (partition == null) {
            return null;
        }
        String pinned = partition.pins().get(GROUPING_VERSION_PIN);
        return pinned == null || pinned.isBlank() ? null : pinned.trim();
    }

    /** Renders a membership as the pin value. */
    public static String pin(Collection<String> subjects) {
        return String.join(DELIMITER, clean(subjects));
    }

    /** Reads a pin value back. Blank lines are dropped rather than becoming blank subjects. */
    public static List<String> parse(String pinned) {
        if (pinned == null || pinned.isBlank()) {
            return List.of();
        }
        // \R rather than the delimiter itself: a pin that has been round-tripped through a store
        // that normalises line endings is still the same membership.
        return clean(List.of(pinned.split("\\R")));
    }

    /** Returns a copy of {@code partition} recording the subjects it reads. */
    public static EntityPartition pinnedOn(EntityPartition partition, Collection<String> subjects) {
        List<String> cleaned = clean(subjects);
        if (partition == null || cleaned.isEmpty()) {
            // An empty pin would read as "says nothing" on the way back out, so it is not written:
            // the key's own entity id is a better answer than a pin that erases it.
            return partition;
        }
        return partition.withPin(SUBJECTS_PIN, String.join(DELIMITER, cleaned));
    }

    /**
     * Returns a copy recording both the subjects and the grouping that chose them. The two are
     * recorded independently: a per-entity grouping leaves a membership the key already states,
     * and that it ran is still worth knowing when the claim is read back.
     */
    public static EntityPartition pinnedOn(EntityPartition partition, Collection<String> subjects,
                                           String groupingVersion) {
        EntityPartition pinned = pinnedOn(partition, subjects);
        if (pinned == null || groupingVersion == null || groupingVersion.isBlank()) {
            return pinned;
        }
        return pinned.withPin(GROUPING_VERSION_PIN, groupingVersion.trim());
    }

    /** Trimmed, blank-free, first-occurrence-wins. Pinned order is the caller's, not sorted here. */
    private static List<String> clean(Collection<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String subject : subjects) {
            if (subject != null && !subject.isBlank()) {
                cleaned.add(subject.trim());
            }
        }
        return List.copyOf(cleaned);
    }
}
