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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.grouping.BridgePolicy;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How the entity-partition pass groups subjects and looks for their evidence.
 *
 * <p>The pass used to run {@link GroupingPolicy#defaults()} with no way to say otherwise, which
 * meant the two decisions that define what a coverage claim <em>means</em> — what a partition is
 * about, and where it looked — were not settable by the people the claim is for. This is that
 * surface. Its defaults reproduce the stock policies exactly, so a configuration nobody has touched
 * produces byte-identical partitions to the ones the pass produced before it existed.</p>
 *
 * <h3>Why the version fields matter more than the numbers</h3>
 * <p>Both policies pin a version into {@link ai.kompile.core.graphrag.partition.PartitionKey}, and
 * that version is the whole basis for saying two claims are comparable. Changing a threshold while
 * leaving the version alone would file a differently-decided claim under the stock policy's name
 * and invite exactly the comparison the design exists to prevent. So: name a version and it is used
 * verbatim — a deliberate re-grouping stays legible, which is what {@link GroupingPolicy} asks for.
 * Leave it unset and move a knob, and the derived version carries a fingerprint of the settings, so
 * the tuned run is a new partition rather than a quiet redefinition of an old one.</p>
 *
 * <p>Batch sizes are deliberately <em>not</em> part of either version: how work was cut into
 * mini-batches does not change what was looked at.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartitionPassConfig {

    /** Marks a version derived from tuned settings rather than named by a person. */
    public static final String TUNED_MARKER = "+tuned-";

    /** Named grouping version; null or blank derives one from the settings below. */
    private String groupingVersion;

    /** Most subjects in one partition; 0 or less means unbounded. */
    @Builder.Default
    private int maxGroupSize = GroupingPolicy.DEFAULT_MAX_GROUP_SIZE;

    /** Links weaker than this do not pull two subjects into the same partition. */
    @Builder.Default
    private double minLinkStrength = 0.0;

    /**
     * Degree at which a subject counts as a bridge; 0 or less turns bridge detection off, which
     * lets a hub glue the whole corpus into one partition that never closes.
     */
    @Builder.Default
    private int bridgeDegree = GroupingPolicy.DEFAULT_BRIDGE_DEGREE;

    /** What happens to the bridges that were found. */
    @Builder.Default
    private BridgePolicy bridges = BridgePolicy.SEPARATE;

    /** Named discovery version; null or blank derives one from the settings below. */
    private String discoveryVersion;

    /**
     * Channels each partition runs, by {@link DiscoveryChannel} name. Empty keeps the policy's own
     * defaults. An unrecognised name is rejected rather than dropped: silently running one fewer
     * way of looking is how a partition comes to report coverage it never had.
     */
    @Builder.Default
    private List<String> channels = new ArrayList<>();

    /** Per-channel retrieval cap for a single round. */
    @Builder.Default
    private int maxCandidatesPerChannel = DiscoveryPolicy.DEFAULT_MAX_CANDIDATES_PER_CHANNEL;

    /** Confidence under which a proposal is rejected outright. */
    @Builder.Default
    private double excludeBelow = DiscoveryPolicy.DEFAULT_EXCLUDE_BELOW;

    /** Confidence under which a proposal is admitted but postponed. */
    @Builder.Default
    private double deferBelow = DiscoveryPolicy.DEFAULT_DEFER_BELOW;

    /** How many discover/process/expand rounds a partition may run. */
    @Builder.Default
    private int maxRounds = DiscoveryPolicy.DEFAULT_MAX_ROUNDS;

    /** Hard cap on admitted members; overflow is deferred, never dropped. */
    @Builder.Default
    private int maxMembers = DiscoveryPolicy.DEFAULT_MAX_MEMBERS;

    /** Members per mini-batch; 0 or less keeps each request's own default. */
    @Builder.Default
    private int maxItemsPerBatch = 0;

    /** Soft cost cap per mini-batch; 0 for none. */
    @Builder.Default
    private long targetCostPerBatch = 0L;

    /** The settings a configuration nobody has touched describes. */
    public static PartitionPassConfig defaults() {
        return PartitionPassConfig.builder().build();
    }

    /**
     * The grouping policy these settings describe.
     *
     * @return the stock hybrid policy when nothing has been changed, the named policy when a
     *         version was given, and a fingerprinted policy when knobs moved without a rename
     */
    public GroupingPolicy groupingPolicy() {
        GroupingPolicy stock = GroupingPolicy.defaults();
        GroupingPolicy configured = new GroupingPolicy(groupingVersion, maxGroupSize,
                minLinkStrength, bridgeDegree, bridges);
        if (groupingVersion != null && !groupingVersion.isBlank()) {
            return configured;
        }
        if (sameGrouping(configured, stock)) {
            return stock;
        }
        return configured.withVersion(stock.version() + TUNED_MARKER
                + fingerprint(configured.maxGroupSize(), configured.minLinkStrength(),
                        configured.bridgeDegree(), configured.bridges()));
    }

    /**
     * The discovery policy these settings describe, or {@code null} when they describe the stock
     * one.
     *
     * <p>Null is not "no policy" — it means the caller should keep whatever policy the partition
     * request already carries, which is how the pass stays able to narrow its own policy when the
     * vector index this run wrote is not searchable. Returning the stock policy here instead would
     * override that narrowing and claim coverage of an index nobody searched.</p>
     */
    public DiscoveryPolicy discoveryOverride() {
        DiscoveryPolicy stock = DiscoveryPolicy.defaults();
        Set<DiscoveryChannel> requested = channelSet();
        DiscoveryPolicy configured = new DiscoveryPolicy(discoveryVersion, requested,
                maxCandidatesPerChannel, excludeBelow, deferBelow, maxRounds, maxMembers,
                stock.readerScope());
        if (discoveryVersion != null && !discoveryVersion.isBlank()) {
            return configured;
        }
        if (sameDiscovery(configured, stock)) {
            return null;
        }
        String version = stock.version() + TUNED_MARKER
                + fingerprint(configured.channels().stream().map(Enum::name).sorted().toList(),
                        configured.maxCandidatesPerChannel(), configured.excludeBelow(),
                        configured.deferBelow(), configured.maxRounds(), configured.maxMembers());
        return new DiscoveryPolicy(version, configured.channels(),
                configured.maxCandidatesPerChannel(), configured.excludeBelow(),
                configured.deferBelow(), configured.maxRounds(), configured.maxMembers(),
                configured.readerScope());
    }

    /** True when batch caps were configured and are worth applying to a request. */
    public boolean hasBatchCaps() {
        return maxItemsPerBatch > 0 || targetCostPerBatch > 0L;
    }

    /**
     * The configured channels, or {@code null} to keep the policy's own defaults.
     *
     * @throws IllegalArgumentException if a name is not a {@link DiscoveryChannel}; the pass would
     *                                  otherwise run fewer ways of looking than it was told to and
     *                                  still call the result complete
     */
    public Set<DiscoveryChannel> channelSet() {
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        Set<DiscoveryChannel> resolved = EnumSet.noneOf(DiscoveryChannel.class);
        List<String> unknown = new ArrayList<>();
        for (String name : channels) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                resolved.add(DiscoveryChannel.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown discovery channel(s) " + unknown
                    + "; valid channels are " + List.of(DiscoveryChannel.values()));
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private static boolean sameGrouping(GroupingPolicy configured, GroupingPolicy stock) {
        return configured.maxGroupSize() == stock.maxGroupSize()
                && Double.compare(configured.minLinkStrength(), stock.minLinkStrength()) == 0
                && configured.bridgeDegree() == stock.bridgeDegree()
                && configured.bridges() == stock.bridges();
    }

    private static boolean sameDiscovery(DiscoveryPolicy configured, DiscoveryPolicy stock) {
        return configured.channels().equals(stock.channels())
                && configured.maxCandidatesPerChannel() == stock.maxCandidatesPerChannel()
                && Double.compare(configured.excludeBelow(), stock.excludeBelow()) == 0
                && Double.compare(configured.deferBelow(), stock.deferBelow()) == 0
                && configured.maxRounds() == stock.maxRounds()
                && configured.maxMembers() == stock.maxMembers();
    }

    /**
     * A short, stable digest of the settings that decided a policy.
     *
     * <p>Built from {@link String#hashCode()} rather than the records' own, because a partition id
     * derived from it has to mean the same thing on the next JVM: {@code String.hashCode} is
     * specified down to the arithmetic, whereas a record's is explicitly not.</p>
     */
    private static String fingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part).append('|');
        }
        return String.format("%08x", sb.toString().hashCode());
    }
}
