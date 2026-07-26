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
 * What to do with a subject that connects otherwise-separate groups.
 *
 * <p>Bridges are the reason naive grouping fails. A handful of high-degree subjects — a parent
 * company, a currency, a country, a standard everyone cites — touch everything, and any grouping
 * that lets them glue components together collapses the whole corpus into one partition. That
 * partition can never close, never reports partial coverage usefully, and cannot be re-run
 * incrementally, which defeats the point of partitioning.</p>
 *
 * <p>So bridges never glue. What remains is a real choice about where their evidence is read, and
 * every option costs something; there is no default that is right for every corpus, which is why
 * this is a policy and not a heuristic buried in the planner.</p>
 */
public enum BridgePolicy {

    /**
     * The bridge gets a partition of its own.
     *
     * <p>Every subject is read exactly once and the groups stay independent. The cost is that no
     * group sees the bridge's evidence while it is being read, so a relation that only makes sense
     * with both ends in view is resolved later, when the bridge's own partition lands.</p>
     */
    SEPARATE,

    /**
     * The bridge joins the group it pulls hardest towards, as a full member.
     *
     * <p>Still exactly one read per subject, and one group gets the benefit of having the bridge in
     * view. The cost is asymmetry: the groups that lost the tie-break are no better off than under
     * {@link #SEPARATE}, and the winning group's identity now depends on a strength comparison.</p>
     */
    ATTACH,

    /**
     * The bridge is added to every group it touches.
     *
     * <p>Every group sees it, which is the best answer for extraction quality. The cost is real
     * duplicate work — the bridge's chunks are read once per group — so this is for corpora where
     * a few bridges matter enough to pay for it, not a default.</p>
     */
    SHARE;

    /** True when one subject may be read by several partitions under this policy. */
    public boolean duplicates() {
        return this == SHARE;
    }

    /** True when the bridge ends up owned by a group rather than standing alone. */
    public boolean joinsAGroup() {
        return this == ATTACH || this == SHARE;
    }
}
