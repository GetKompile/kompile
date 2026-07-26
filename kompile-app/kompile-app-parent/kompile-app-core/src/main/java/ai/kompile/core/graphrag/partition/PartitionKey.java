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
import java.util.List;
import java.util.Locale;

/**
 * Identity of an entity partition.
 *
 * <p>The identity deliberately includes the discovery policy version and the graph snapshot the
 * partition was built against. A partition is never "the evidence about Acme"; it is "the
 * evidence about Acme that policy P found in snapshot S". Two runs that disagree are then a
 * legible difference rather than a contradiction, and re-running under a new policy produces a
 * new partition instead of silently overwriting the old verdict.</p>
 *
 * @param entityId      canonical entity the partition is about, or {@code null} for a group
 * @param groupId       entity group (community, cluster) when the partition spans several
 * @param category      optional sub-partition, e.g. a relation family or evidence category
 * @param timeWindow    optional temporal bound, e.g. {@code 2019-Q1}
 * @param policyVersion discovery policy in force; part of the identity, never metadata
 * @param snapshotId    graph snapshot the partition was discovered against
 */
public record PartitionKey(
        String entityId,
        String groupId,
        String category,
        String timeWindow,
        String policyVersion,
        String snapshotId) {

    public PartitionKey {
        entityId = trimToNull(entityId);
        groupId = trimToNull(groupId);
        category = trimToNull(category);
        timeWindow = trimToNull(timeWindow);
        policyVersion = trimToNull(policyVersion);
        snapshotId = trimToNull(snapshotId);
        if (entityId == null && groupId == null) {
            throw new IllegalArgumentException(
                    "a partition must be about an entity or a group; both were blank");
        }
    }

    /** Single-entity partition under a named policy and snapshot. */
    public static PartitionKey forEntity(String entityId, String policyVersion, String snapshotId) {
        return new PartitionKey(entityId, null, null, null, policyVersion, snapshotId);
    }

    /** Entity-group partition — the default grouping for densely connected neighbourhoods. */
    public static PartitionKey forGroup(String groupId, String policyVersion, String snapshotId) {
        return new PartitionKey(null, groupId, null, null, policyVersion, snapshotId);
    }

    /** Returns a copy narrowed to a category sub-partition. */
    public PartitionKey withCategory(String category) {
        return new PartitionKey(entityId, groupId, category, timeWindow, policyVersion, snapshotId);
    }

    /** Returns a copy narrowed to a time window. */
    public PartitionKey withTimeWindow(String timeWindow) {
        return new PartitionKey(entityId, groupId, category, timeWindow, policyVersion, snapshotId);
    }

    /** Returns a copy pinned to a different policy version — which makes it a different partition. */
    public PartitionKey withPolicyVersion(String policyVersion) {
        return new PartitionKey(entityId, groupId, category, timeWindow, policyVersion, snapshotId);
    }

    /** Returns a copy pinned to a different snapshot — also a different partition. */
    public PartitionKey withSnapshot(String snapshotId) {
        return new PartitionKey(entityId, groupId, category, timeWindow, policyVersion, snapshotId);
    }

    /**
     * Stable, human-legible identifier. Deterministic across runs so a partition can be looked up,
     * logged and diffed without a side table.
     */
    public String id() {
        List<String> parts = new ArrayList<>(6);
        if (entityId != null) {
            parts.add("e=" + entityId);
        }
        if (groupId != null) {
            parts.add("g=" + groupId);
        }
        if (category != null) {
            parts.add("c=" + category.toLowerCase(Locale.ROOT));
        }
        if (timeWindow != null) {
            parts.add("t=" + timeWindow);
        }
        parts.add("p=" + (policyVersion == null ? "unversioned" : policyVersion));
        if (snapshotId != null) {
            parts.add("s=" + snapshotId);
        }
        return String.join("|", parts);
    }

    /** The entity or group this partition is about, whichever it was keyed on. */
    public String subject() {
        return entityId != null ? entityId : groupId;
    }

    @Override
    public String toString() {
        return id();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
