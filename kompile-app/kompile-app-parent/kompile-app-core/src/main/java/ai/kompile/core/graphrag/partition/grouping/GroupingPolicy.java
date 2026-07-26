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

package ai.kompile.core.graphrag.partition.grouping;

/**
 * How subjects are grouped into partitions.
 *
 * <p>Like {@link ai.kompile.core.graphrag.partition.DiscoveryPolicy}, this carries a version, and
 * for the same reason: grouping decides <em>what a partition is about</em>, so two runs that
 * grouped differently produced different claims and must not be compared. The version is recorded
 * with the plan rather than inferred from the settings, so a deliberate re-grouping is legible.</p>
 *
 * @param version         name of this grouping, part of the record of how a partition was decided
 * @param maxGroupSize    most subjects in one group; 0 or less means unbounded
 * @param minLinkStrength links weaker than this do not pull subjects together
 * @param bridgeDegree    a subject linked to at least this many others is a bridge; 0 or less
 *                        turns bridge detection off, which lets hubs glue everything into one
 *                        group — occasionally what a small, dense corpus wants
 * @param bridges         what happens to the bridges that were found
 */
public record GroupingPolicy(
        String version,
        int maxGroupSize,
        double minLinkStrength,
        int bridgeDegree,
        BridgePolicy bridges) {

    /** Version of the default hybrid grouping. */
    public static final String HYBRID_VERSION = "grouping-v1-hybrid";

    /** Version under which every subject is its own partition. */
    public static final String PER_ENTITY_VERSION = "grouping-v1-per-entity";

    /**
     * Default cap on a group.
     *
     * <p>A group is a unit of work that has to close: everything in it is discovered, batched and
     * committed together. Small enough that a group can finish and be re-run cheaply; large enough
     * that the subjects sharing a neighbourhood usually still share a partition.</p>
     */
    public static final int DEFAULT_MAX_GROUP_SIZE = 12;

    /**
     * Default degree at which a subject counts as a bridge.
     *
     * <p>Half the group cap: a subject linked to more subjects than half a group could hold is
     * connective tissue rather than a member of any one neighbourhood.</p>
     */
    public static final int DEFAULT_BRIDGE_DEGREE = DEFAULT_MAX_GROUP_SIZE / 2;

    public GroupingPolicy {
        version = version == null || version.isBlank() ? HYBRID_VERSION : version.trim();
        maxGroupSize = Math.max(0, maxGroupSize);
        minLinkStrength = Double.isFinite(minLinkStrength) ? Math.max(0.0, minLinkStrength) : 0.0;
        bridgeDegree = Math.max(0, bridgeDegree);
        bridges = bridges == null ? BridgePolicy.SEPARATE : bridges;
    }

    /**
     * The default: group densely connected subjects, cap the group, and keep bridges out of it.
     *
     * <p>This is what the spec means by hybrid — neither one partition per subject (which re-reads
     * every shared chunk once per subject) nor one partition per corpus (which never closes).</p>
     */
    public static GroupingPolicy defaults() {
        return new GroupingPolicy(HYBRID_VERSION, DEFAULT_MAX_GROUP_SIZE, 0.0,
                DEFAULT_BRIDGE_DEGREE, BridgePolicy.SEPARATE);
    }

    /**
     * One partition per subject.
     *
     * <p>Not a degenerate case to be avoided — it is the right shape when subjects genuinely have
     * nothing to do with each other, and it is the only shape under which a per-subject coverage
     * claim is exactly a partition. It carries its own version so its coverage is never compared
     * to a grouped run's.</p>
     */
    public static GroupingPolicy perEntity() {
        return new GroupingPolicy(PER_ENTITY_VERSION, 1, 0.0, 0, BridgePolicy.SEPARATE);
    }

    public GroupingPolicy withVersion(String newVersion) {
        return new GroupingPolicy(newVersion, maxGroupSize, minLinkStrength, bridgeDegree, bridges);
    }

    public GroupingPolicy withMaxGroupSize(int newMax) {
        return new GroupingPolicy(version, newMax, minLinkStrength, bridgeDegree, bridges);
    }

    public GroupingPolicy withMinLinkStrength(double newMinimum) {
        return new GroupingPolicy(version, maxGroupSize, newMinimum, bridgeDegree, bridges);
    }

    public GroupingPolicy withBridgeDegree(int newDegree) {
        return new GroupingPolicy(version, maxGroupSize, minLinkStrength, newDegree, bridges);
    }

    public GroupingPolicy withBridges(BridgePolicy newBridges) {
        return new GroupingPolicy(version, maxGroupSize, minLinkStrength, bridgeDegree, newBridges);
    }

    /** True when a link this strong is allowed to pull two subjects together. */
    public boolean admits(double strength) {
        return strength >= minLinkStrength;
    }

    /** True when a subject with this many distinct neighbours is a bridge. */
    public boolean isBridge(int degree) {
        return bridgeDegree > 0 && degree >= bridgeDegree;
    }

    /** True when a group of this size has room for one more subject. */
    public boolean hasRoom(int currentSize) {
        return maxGroupSize <= 0 || currentSize < maxGroupSize;
    }

    public String describe() {
        return version + ": groups of at most "
                + (maxGroupSize <= 0 ? "any size" : String.valueOf(maxGroupSize))
                + ", links from " + String.format("%.2f", minLinkStrength)
                + ", bridges at degree "
                + (bridgeDegree <= 0 ? "never" : String.valueOf(bridgeDegree))
                + " handled by " + bridges;
    }
}
