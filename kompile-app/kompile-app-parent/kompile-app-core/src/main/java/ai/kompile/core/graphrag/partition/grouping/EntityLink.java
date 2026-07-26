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
 * Evidence that two subjects belong together.
 *
 * <p>Deliberately not a graph edge. Grouping does not care what the relation <em>means</em>, only
 * how strongly two subjects pull towards being read in the same partition — which can come from a
 * relation in the graph, co-occurrence in the same chunks, a shared identifier, or anything else
 * the caller can measure. Keeping it a plain weight is what lets the planner stay pure.</p>
 *
 * <p>Links are direction-free: the endpoints are ordered on construction, so the same pair is the
 * same link whichever way round it was observed and strengths from both directions accumulate
 * instead of producing two half-links.</p>
 *
 * @param from     one end, lexicographically the smaller after normalisation
 * @param to       the other end
 * @param strength how strongly the two pull together; never negative
 */
public record EntityLink(String from, String to, double strength) {

    public EntityLink {
        from = trimmed(from);
        to = trimmed(to);
        if (from == null || to == null) {
            throw new IllegalArgumentException("a link needs both ends");
        }
        if (from.equals(to)) {
            // A self-link would make an entity its own neighbour and inflate its degree, which is
            // exactly the measure bridge detection reads. Refusing it here keeps that honest.
            throw new IllegalArgumentException("an entity cannot be linked to itself: " + from);
        }
        if (from.compareTo(to) > 0) {
            String swap = from;
            from = to;
            to = swap;
        }
        strength = Double.isFinite(strength) ? Math.max(0.0, strength) : 0.0;
    }

    /** A link of unit strength — the right default when all you know is "these two are related". */
    public static EntityLink between(String a, String b) {
        return new EntityLink(a, b, 1.0);
    }

    public static EntityLink between(String a, String b, double strength) {
        return new EntityLink(a, b, strength);
    }

    public boolean touches(String entityId) {
        return from.equals(entityId) || to.equals(entityId);
    }

    /** The far end from {@code entityId}, or {@code null} when the link does not touch it. */
    public String other(String entityId) {
        if (from.equals(entityId)) {
            return to;
        }
        return to.equals(entityId) ? from : null;
    }

    /** The same pair, pulling harder. Used when several observations name the same pair. */
    public EntityLink plus(double moreStrength) {
        return new EntityLink(from, to, strength + Math.max(0.0, moreStrength));
    }

    public String describe() {
        return from + " <-> " + to + " (" + String.format("%.2f", strength) + ")";
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
