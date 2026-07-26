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

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * What "we looked for evidence" means for a given run.
 *
 * <p>Coverage is only ever relative to a policy. Naming the policy — which channels ran, how
 * confident a proposal had to be, how far the frontier was allowed to expand — is what turns
 * "the partition is complete" from a claim nobody can check into one anybody can. The
 * {@link #version()} is pinned into {@link PartitionKey}, so changing any of these values
 * produces a new partition rather than quietly redefining an existing verdict.</p>
 *
 * @param version                 identifier pinned into the partition key; change it when any
 *                                other field changes
 * @param channels                channels this policy runs, in {@link DiscoveryChannel#priority()}
 *                                order regardless of the order given here
 * @param maxCandidatesPerChannel per-channel retrieval cap for a single round
 * @param excludeBelow            confidence under which a proposal is rejected outright
 * @param deferBelow              confidence under which a proposal is admitted but postponed
 * @param maxRounds               how many discover/process/expand rounds may run
 * @param maxMembers              hard cap on admitted members; overflow is deferred, never dropped
 * @param readerScope             access domains the run holds; chunks it cannot read become
 *                                {@link MembershipState#INACCESSIBLE} rather than disappearing
 */
public record DiscoveryPolicy(
        String version,
        Set<DiscoveryChannel> channels,
        int maxCandidatesPerChannel,
        double excludeBelow,
        double deferBelow,
        int maxRounds,
        int maxMembers,
        AccessScope readerScope) {

    public static final String DEFAULT_VERSION = "discovery-v1";
    public static final int DEFAULT_MAX_CANDIDATES_PER_CHANNEL = 200;
    public static final double DEFAULT_EXCLUDE_BELOW = 0.2;
    public static final double DEFAULT_DEFER_BELOW = 0.5;
    public static final int DEFAULT_MAX_ROUNDS = 3;
    public static final int DEFAULT_MAX_MEMBERS = 2_000;

    public DiscoveryPolicy {
        version = version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
        channels = channels == null || channels.isEmpty()
                ? defaultChannels()
                : Set.copyOf(EnumSet.copyOf(channels));
        maxCandidatesPerChannel = maxCandidatesPerChannel > 0
                ? maxCandidatesPerChannel : DEFAULT_MAX_CANDIDATES_PER_CHANNEL;
        maxRounds = maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
        maxMembers = maxMembers > 0 ? maxMembers : DEFAULT_MAX_MEMBERS;
        excludeBelow = clamp(excludeBelow, DEFAULT_EXCLUDE_BELOW);
        deferBelow = clamp(deferBelow, DEFAULT_DEFER_BELOW);
        // A defer threshold below the exclude threshold would make the deferred band empty and
        // silently turn "not sure yet" into "rejected". Keep the bands ordered instead.
        if (deferBelow < excludeBelow) {
            deferBelow = excludeBelow;
        }
        readerScope = readerScope == null ? AccessScope.unrestricted() : readerScope;
    }

    /**
     * The conservative default: identifiers and structured relationships are trusted, semantic
     * similarity proposes, contradictions are surfaced last. Topic and process channels are opt-in
     * because they need corpus-specific configuration to mean anything.
     */
    public static DiscoveryPolicy defaults() {
        return new DiscoveryPolicy(DEFAULT_VERSION, defaultChannels(),
                DEFAULT_MAX_CANDIDATES_PER_CHANNEL, DEFAULT_EXCLUDE_BELOW, DEFAULT_DEFER_BELOW,
                DEFAULT_MAX_ROUNDS, DEFAULT_MAX_MEMBERS, AccessScope.unrestricted());
    }

    private static Set<DiscoveryChannel> defaultChannels() {
        return Set.copyOf(EnumSet.of(
                DiscoveryChannel.SEED,
                DiscoveryChannel.MANUAL,
                DiscoveryChannel.DIRECT_IDENTIFIER,
                DiscoveryChannel.STRUCTURED_RELATIONSHIP,
                DiscoveryChannel.SEMANTIC,
                DiscoveryChannel.CONTRADICTION));
    }

    /** Returns a copy running exactly {@code channels}, under a new version. */
    public DiscoveryPolicy withChannels(String newVersion, Collection<DiscoveryChannel> channels) {
        // EnumSet.copyOf rejects an empty collection; an empty request means "use the defaults",
        // which is what the compact constructor does with null.
        Set<DiscoveryChannel> requested = channels == null || channels.isEmpty()
                ? null : EnumSet.copyOf(channels);
        return new DiscoveryPolicy(newVersion, requested, maxCandidatesPerChannel, excludeBelow,
                deferBelow, maxRounds, maxMembers, readerScope);
    }

    /** Returns a copy running exactly {@code channels}, under a new version. */
    public DiscoveryPolicy withChannels(String newVersion, DiscoveryChannel... channels) {
        return withChannels(newVersion, channels == null ? null : Arrays.asList(channels));
    }

    /**
     * Returns a copy holding {@code scope}, under a new version. The version must change: a run
     * that can read less is not the same run, and its coverage must not be compared to one that
     * could read more.
     */
    public DiscoveryPolicy withReaderScope(String newVersion, AccessScope scope) {
        return new DiscoveryPolicy(newVersion, channels, maxCandidatesPerChannel, excludeBelow,
                deferBelow, maxRounds, maxMembers, scope);
    }

    /** Returns a copy with new round and member budgets, under a new version. */
    public DiscoveryPolicy withBudget(String newVersion, int maxRounds, int maxMembers) {
        return new DiscoveryPolicy(newVersion, channels, maxCandidatesPerChannel, excludeBelow,
                deferBelow, maxRounds, maxMembers, readerScope);
    }

    /**
     * Returns a copy capping how many candidates any one channel may propose per round, under a
     * new version. Narrowing this narrows what was looked at, which is why it moves the version.
     */
    public DiscoveryPolicy withCandidateCap(String newVersion, int maxCandidatesPerChannel) {
        return new DiscoveryPolicy(newVersion, channels, maxCandidatesPerChannel, excludeBelow,
                deferBelow, maxRounds, maxMembers, readerScope);
    }

    /** Returns a copy with new confidence bands, under a new version. */
    public DiscoveryPolicy withThresholds(String newVersion, double excludeBelow, double deferBelow) {
        return new DiscoveryPolicy(newVersion, channels, maxCandidatesPerChannel, excludeBelow,
                deferBelow, maxRounds, maxMembers, readerScope);
    }

    public boolean runs(DiscoveryChannel channel) {
        return channel != null && channels.contains(channel);
    }

    /**
     * Classifies a proposal's confidence into an entry state. Below {@link #excludeBelow} the
     * proposal is rejected and recorded as such; between the bands it is admitted but postponed;
     * at or above {@link #deferBelow} it is schedulable.
     */
    public MembershipState classify(double confidence) {
        if (confidence < excludeBelow) {
            return MembershipState.EXCLUDED;
        }
        if (confidence < deferBelow) {
            return MembershipState.DEFERRED;
        }
        return MembershipState.DISCOVERED;
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            return fallback;
        }
        return value;
    }
}
