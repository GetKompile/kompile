/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.model;

import java.time.Instant;

/**
 * Allen's 13 mutually-exclusive interval relations between two {@link TemporalInterval}s
 * {@code A} and {@code B}.
 *
 * <p>Every pair of non-null intervals satisfies exactly one of these relations. The static
 * factory {@link #compute(TemporalInterval, TemporalInterval)} determines the relation from
 * the {@code start}/{@code end} bounds of both intervals using only {@link Instant#compareTo}
 * — no external dependencies.</p>
 *
 * <p>Open-ended bounds ({@code null}) are treated as −∞ for {@code start} and +∞ for
 * {@code end}, which is consistent with {@link TemporalInterval} semantics ("valid until
 * further notice" cannot precede anything).</p>
 *
 * <p>Naming follows the canonical Allen (1983) literature, with {@code BEFORE} / {@code AFTER}
 * as the strict precedence pair and {@code MEETS} / {@code MET_BY} as the adjacent pair.</p>
 *
 * @see <a href="https://doi.org/10.1145/182.358434">Allen, 1983 — Maintaining Knowledge about
 *      Temporal Intervals</a>
 */
public enum AllenRelation {

    /**
     * {@code A} ends strictly before {@code B} starts: {@code a.end < b.start}.
     * Inverse: {@link #AFTER}.
     */
    BEFORE,

    /**
     * {@code A} starts strictly after {@code B} ends: {@code b.end < a.start}.
     * Inverse: {@link #BEFORE}.
     */
    AFTER,

    /**
     * {@code A} ends exactly where {@code B} starts: {@code a.end == b.start}.
     * The two intervals are adjacent with no gap and no overlap.
     * Inverse: {@link #MET_BY}.
     */
    MEETS,

    /**
     * {@code B} ends exactly where {@code A} starts: {@code b.end == a.start}.
     * Inverse: {@link #MEETS}.
     */
    MET_BY,

    /**
     * {@code A} starts before {@code B}, they share a period, and {@code A} ends before
     * {@code B}: {@code a.start < b.start < a.end < b.end}.
     * Inverse: {@link #OVERLAPPED_BY}.
     */
    OVERLAPS,

    /**
     * {@code B} starts before {@code A}, they share a period, and {@code B} ends before
     * {@code A}: {@code b.start < a.start < b.end < a.end}.
     * Inverse: {@link #OVERLAPS}.
     */
    OVERLAPPED_BY,

    /**
     * {@code A} is entirely contained within {@code B}:
     * {@code b.start < a.start} and {@code a.end < b.end}.
     * Inverse: {@link #CONTAINS}.
     */
    DURING,

    /**
     * {@code A} entirely contains {@code B}:
     * {@code a.start < b.start} and {@code b.end < a.end}.
     * Inverse: {@link #DURING}.
     */
    CONTAINS,

    /**
     * {@code A} and {@code B} start at the same point but {@code A} ends first:
     * {@code a.start == b.start} and {@code a.end < b.end}.
     * Inverse: {@link #STARTED_BY}.
     */
    STARTS,

    /**
     * {@code A} and {@code B} start at the same point but {@code B} ends first:
     * {@code a.start == b.start} and {@code b.end < a.end}.
     * Inverse: {@link #STARTS}.
     */
    STARTED_BY,

    /**
     * {@code A} and {@code B} end at the same point but {@code B} starts first:
     * {@code b.start < a.start} and {@code a.end == b.end}.
     * Inverse: {@link #FINISHED_BY}.
     */
    FINISHES,

    /**
     * {@code A} and {@code B} end at the same point but {@code A} starts first:
     * {@code a.start < b.start} and {@code a.end == b.end}.
     * Inverse: {@link #FINISHES}.
     */
    FINISHED_BY,

    /**
     * {@code A} and {@code B} are identical: {@code a.start == b.start}
     * and {@code a.end == b.end}.
     */
    EQUALS;

    // ─── Semantic query ──────────────────────────────────────────────────────────

    /**
     * Whether this relation represents strict temporal precedence — i.e. {@code A}'s interval
     * ends no later than {@code B}'s interval begins, so {@code A} cannot causally depend on
     * {@code B}. Used by the attribution engine to enforce the causal arrow direction.
     *
     * @return {@code true} for {@link #BEFORE} and {@link #MEETS}
     */
    public boolean isPrecedence() {
        return this == BEFORE || this == MEETS;
    }

    // ─── Factory ─────────────────────────────────────────────────────────────────

    /**
     * Compute the Allen relation between intervals {@code a} and {@code b}.
     *
     * <p>Open bounds are treated as extremes:
     * <ul>
     *   <li>{@code null} start → −∞ (earliest possible)</li>
     *   <li>{@code null} end   → +∞ (latest possible / still ongoing)</li>
     * </ul>
     *
     * <p>The algorithm is a direct case analysis over the six possible comparisons between
     * the four bound points, exactly as defined in Allen (1983). No floating-point arithmetic
     * is used — comparisons are performed via {@link Instant#compareTo}.
     *
     * @param a the first interval (never {@code null})
     * @param b the second interval (never {@code null})
     * @return the unique Allen relation satisfied by {@code a} and {@code b}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static AllenRelation compute(TemporalInterval a, TemporalInterval b) {
        // Sentinel instants for open bounds
        final Instant NEG_INF = Instant.MIN;
        final Instant POS_INF = Instant.MAX;

        Instant aStart = a.start() != null ? a.start() : NEG_INF;
        Instant aEnd   = a.end()   != null ? a.end()   : POS_INF;
        Instant bStart = b.start() != null ? b.start() : NEG_INF;
        Instant bEnd   = b.end()   != null ? b.end()   : POS_INF;

        // Compare all four boundary points
        int aStartVsAEnd   = aStart.compareTo(aEnd);   // always <= 0 (enforced by TemporalInterval)
        int bStartVsBEnd   = bStart.compareTo(bEnd);   // always <= 0

        int aEndVsBStart   = aEnd.compareTo(bStart);
        int bEndVsAStart   = bEnd.compareTo(aStart);
        int aStartVsBStart = aStart.compareTo(bStart);
        int aEndVsBEnd     = aEnd.compareTo(bEnd);

        // ── Case 1: BEFORE / AFTER ────────────────────────────────────────────
        // BEFORE: a.end < b.start
        if (aEndVsBStart < 0) return BEFORE;
        // AFTER: b.end < a.start
        if (bEndVsAStart < 0) return AFTER;

        // ── Case 2: MEETS / MET_BY ───────────────────────────────────────────
        // MEETS: a.end == b.start  (and a.start < b.end, implied by validity)
        if (aEndVsBStart == 0) return MEETS;
        // MET_BY: b.end == a.start
        if (bEndVsAStart == 0) return MET_BY;

        // From here: a and b genuinely overlap (a.end > b.start AND b.end > a.start)

        // ── Case 3: EQUALS ───────────────────────────────────────────────────
        if (aStartVsBStart == 0 && aEndVsBEnd == 0) return EQUALS;

        // ── Case 4: STARTS / STARTED_BY ──────────────────────────────────────
        // Same start, different end
        if (aStartVsBStart == 0) {
            // a.start == b.start; a.end < b.end → STARTS; a.end > b.end → STARTED_BY
            return aEndVsBEnd < 0 ? STARTS : STARTED_BY;
        }

        // ── Case 5: FINISHES / FINISHED_BY ───────────────────────────────────
        // Same end, different start
        if (aEndVsBEnd == 0) {
            // a.end == b.end; a.start > b.start → FINISHES; a.start < b.start → FINISHED_BY
            return aStartVsBStart > 0 ? FINISHES : FINISHED_BY;
        }

        // ── Case 6: DURING / CONTAINS / OVERLAPS / OVERLAPPED_BY ─────────────
        // a.start < b.start → a starts before b
        if (aStartVsBStart < 0) {
            // a starts first; does b end before a?
            if (aEndVsBEnd < 0) {
                // a.start < b.start AND a.end < b.end → OVERLAPS
                return OVERLAPS;
            } else {
                // a.start < b.start AND a.end > b.end → CONTAINS
                return CONTAINS;
            }
        } else {
            // b.start < a.start → b starts first
            if (aEndVsBEnd > 0) {
                // b.start < a.start AND b.end < a.end → OVERLAPPED_BY
                return OVERLAPPED_BY;
            } else {
                // b.start < a.start AND a.end < b.end → DURING
                return DURING;
            }
        }
    }
}
