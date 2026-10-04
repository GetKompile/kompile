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

package ai.kompile.cli.insights;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;

/** Parsing and statistics shared by the insight sources. */
final class Values {

    private Values() {
    }

    /** Nearest-rank percentile ({@code p} in 0..100) of an ascending array; -1 when empty. */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return -1;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    /** The largest value, or 0 when there is none above it. */
    static long max(long[] values) {
        long max = 0;
        for (long value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    static long[] sorted(Collection<Long> values) {
        long[] result = new long[values.size()];
        int i = 0;
        for (Long value : values) {
            result[i++] = value;
        }
        Arrays.sort(result);
        return result;
    }

    /** An ISO-8601 instant or offset date-time, or null. */
    static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notInstant) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    /** {@link #instant(String)}, also reading a date-time without an offset as local to {@code zone}. */
    static Instant instant(String text, ZoneId zone) {
        Instant instant = instant(text);
        if (instant != null || text == null || text.isBlank()) {
            return instant;
        }
        try {
            return LocalDateTime.parse(text).atZone(zone).toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /** A JSON number, or a string holding one, as a long; null otherwise. */
    static Long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException notNumber) {
                return null;
            }
        }
        return null;
    }

    static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
