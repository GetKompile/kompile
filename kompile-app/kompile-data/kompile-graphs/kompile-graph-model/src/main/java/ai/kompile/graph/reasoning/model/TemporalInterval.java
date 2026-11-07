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
import java.util.Objects;

/**
 * An immutable half-open valid-time interval {@code [start, end)} representing the period over
 * which a graph entity or relation is considered valid in the world.
 *
 * <p>Either bound may be {@code null}:
 * <ul>
 *   <li>{@code start == null} — open on the left (valid from the beginning of time).</li>
 *   <li>{@code end == null}   — open on the right (valid indefinitely / "until further notice").</li>
 * </ul>
 *
 * <p>This is a valid-time model only — transaction time (when a fact was recorded in the system)
 * is a client-layer concern and does not appear here. See the temporal reasoning design for the
 * rationale.</p>
 *
 * <p>Null-safe throughout; no dependencies beyond {@code java.time}.</p>
 */
public record TemporalInterval(Instant start, Instant end) {

    // ─── Validation ──────────────────────────────────────────────────────────────

    /**
     * Compact constructor: verifies that when both bounds are non-null, {@code start} is not
     * after {@code end}. A zero-width interval ({@code start.equals(end)}) is permitted (it
     * represents an instantaneous "point" when created via {@link #point(Instant)}); note that
     * the half-open semantics mean {@code contains(start)} would still return {@code true} for
     * the point factory, which adjusts {@code end} to {@code start + 1 ns}.
     */
    public TemporalInterval {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "TemporalInterval start must not be after end: " + start + " > " + end);
        }
    }

    // ─── Factories ───────────────────────────────────────────────────────────────

    /**
     * An interval spanning the single nanosecond {@code [t, t+1ns)}, representing an
     * instantaneous event. {@link #contains(Instant)} returns {@code true} only for {@code t}
     * itself.
     *
     * @param t the instant to wrap (never {@code null})
     * @return a one-nanosecond interval anchored at {@code t}
     */
    public static TemporalInterval point(Instant t) {
        Objects.requireNonNull(t, "t");
        return new TemporalInterval(t, t.plusNanos(1));
    }

    /**
     * An interval {@code [start, null)} — valid from {@code start} indefinitely.
     *
     * @param start the open-left bound (never {@code null})
     * @return an open-right interval
     */
    public static TemporalInterval since(Instant start) {
        Objects.requireNonNull(start, "start");
        return new TemporalInterval(start, null);
    }

    /**
     * An interval {@code [null, end)} — valid from the beginning of time until {@code end}.
     *
     * @param end the exclusive right bound (never {@code null})
     * @return an open-left interval
     */
    public static TemporalInterval until(Instant end) {
        Objects.requireNonNull(end, "end");
        return new TemporalInterval(null, end);
    }

    /**
     * An interval {@code [start, end)}.
     *
     * @param start the inclusive left bound (may be {@code null} for open-left)
     * @param end   the exclusive right bound (may be {@code null} for open-right)
     * @return the interval
     */
    public static TemporalInterval of(Instant start, Instant end) {
        return new TemporalInterval(start, end);
    }

    // ─── Queries ─────────────────────────────────────────────────────────────────

    /**
     * Whether this interval contains {@code t} under half-open semantics {@code [start, end)}.
     * A {@code null} start is treated as "−∞"; a {@code null} end is treated as "+∞".
     *
     * @param t the instant to test (never {@code null})
     * @return {@code true} if {@code t} falls within this interval
     */
    public boolean contains(Instant t) {
        Objects.requireNonNull(t, "t");
        // [start, end): start <= t < end
        boolean afterStart = (start == null) || !t.isBefore(start);
        boolean beforeEnd  = (end   == null) || t.isBefore(end);
        return afterStart && beforeEnd;
    }

    /**
     * Alias for {@link #contains(Instant)}: whether this interval's valid-time window includes
     * the instant {@code t}.
     *
     * @param t the instant to test (never {@code null})
     * @return {@code true} if {@code t} is within this interval
     */
    public boolean isValidAt(Instant t) {
        return contains(t);
    }

    /**
     * Whether this interval overlaps with {@code other} — i.e. there exists at least one instant
     * that belongs to both intervals. Two intervals overlap when neither strictly precedes the
     * other. Open bounds are treated as infinity.
     *
     * @param other the other interval (never {@code null})
     * @return {@code true} if the two intervals share at least one point
     */
    public boolean overlaps(TemporalInterval other) {
        Objects.requireNonNull(other, "other");
        // A=[s1,e1), B=[s2,e2): overlap iff s1 < e2 AND s2 < e1 (treating null as ±∞)
        boolean thisStartBeforeOtherEnd = (this.end   == null) || (other.start == null) || other.start.isBefore(this.end);
        boolean otherStartBeforeThisEnd = (other.end  == null) || (this.start  == null) || this.start.isBefore(other.end);
        return thisStartBeforeOtherEnd && otherStartBeforeThisEnd;
    }

    /**
     * Whether this interval has no duration (both bounds are non-null and equal), which can only
     * happen if the caller constructed it directly rather than via {@link #point(Instant)}.
     *
     * @return {@code true} for a zero-width interval
     */
    public boolean isEmpty() {
        return start != null && end != null && start.equals(end);
    }

    // ─── JSON serialization (hand-rolled; mirrors InferredFact.toJson style) ────

    /**
     * Serialize to a JSON string: {@code {"validFrom":"<ISO>","validUntil":"<ISO>"}} where
     * {@code null} bounds are written as JSON {@code null}.
     *
     * @return JSON representation
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"validFrom\":");
        if (start == null) {
            sb.append("null");
        } else {
            sb.append('"').append(start).append('"');
        }
        sb.append(",\"validUntil\":");
        if (end == null) {
            sb.append("null");
        } else {
            sb.append('"').append(end).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Deserialize a {@code TemporalInterval} from a JSON string produced by {@link #toJson()}.
     * Accepts {@code null} values for either bound.
     *
     * @param json the JSON string (never {@code null})
     * @return the deserialized interval
     * @throws IllegalArgumentException if the JSON cannot be parsed
     */
    public static TemporalInterval fromJson(String json) {
        Objects.requireNonNull(json, "json");
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new IllegalArgumentException("Expected JSON object: " + json);
        }
        s = s.substring(1, s.length() - 1).trim();

        Instant parsedStart = null;
        Instant parsedEnd   = null;

        // Parse two known keys
        int idx = 0;
        while (idx < s.length()) {
            // skip whitespace and commas
            while (idx < s.length() && (s.charAt(idx) <= ' ' || s.charAt(idx) == ',')) idx++;
            if (idx >= s.length() || s.charAt(idx) != '"') break;
            // read key
            int ks = idx + 1;
            int ke = findEndQuote(s, ks);
            String key = s.substring(ks, ke);
            idx = ke + 1; // skip closing quote
            // skip colon
            while (idx < s.length() && (s.charAt(idx) <= ' ' || s.charAt(idx) == ':')) idx++;
            // read value
            if (idx >= s.length()) break;
            char ch = s.charAt(idx);
            String value;
            if (ch == '"') {
                int vs = idx + 1;
                int ve = findEndQuote(s, vs);
                value = s.substring(vs, ve);
                idx = ve + 1;
            } else {
                // literal (null or number)
                int vstart = idx;
                while (idx < s.length() && s.charAt(idx) != ',' && s.charAt(idx) != '}') idx++;
                value = s.substring(vstart, idx).trim();
            }
            if ("validFrom".equals(key)) {
                parsedStart = "null".equals(value) ? null : Instant.parse(value);
            } else if ("validUntil".equals(key)) {
                parsedEnd   = "null".equals(value) ? null : Instant.parse(value);
            }
        }
        return new TemporalInterval(parsedStart, parsedEnd);
    }

    /** Find the index of the next unescaped {@code "} starting at {@code start}. */
    private static int findEndQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return s.length();
    }

    @Override
    public String toString() {
        return "[" + (start == null ? "-∞" : start) + ", " + (end == null ? "+∞" : end) + ")";
    }
}
