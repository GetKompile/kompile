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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a window into at most a configured number of equal buckets for sparklines and chart
 * series. Windows of two days or more use whole local days ("MM-dd" labels); shorter ones use
 * whole hours ("HH:mm"). An all-time window starts at the earliest data point instead of 1970.
 */
public final class TimeBuckets {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final ZoneId zone;
    private final boolean daily;
    private final LocalDate firstDay;
    private final ZonedDateTime firstHour;
    private final long unitsPerBucket;
    private final int size;

    private TimeBuckets(ZoneId zone, boolean daily, LocalDate firstDay, ZonedDateTime firstHour,
                        long unitsPerBucket, int size) {
        this.zone = zone;
        this.daily = daily;
        this.firstDay = firstDay;
        this.firstHour = firstHour;
        this.unitsPerBucket = unitsPerBucket;
        this.size = size;
    }

    /**
     * @param earliest the oldest data point, used only for an all-time window; null when there
     *                 is no data
     */
    public static TimeBuckets of(InsightsWindow window, Instant earliest, int maxBuckets, ZoneId zone) {
        Instant end = window.end();
        Instant start = window.allTime() ? (earliest != null ? earliest : end) : window.start();
        if (start.isAfter(end)) {
            start = end;
        }
        int max = Math.max(1, maxBuckets);
        if (Duration.between(start, end).compareTo(Duration.ofDays(2)) >= 0) {
            LocalDate first = start.atZone(zone).toLocalDate();
            long days = ChronoUnit.DAYS.between(first, end.atZone(zone).toLocalDate()) + 1;
            long per = ceilDiv(days, max);
            return new TimeBuckets(zone, true, first, null, per, (int) ceilDiv(days, per));
        }
        ZonedDateTime first = start.atZone(zone).truncatedTo(ChronoUnit.HOURS);
        long hours = ChronoUnit.HOURS.between(first, end.atZone(zone)) + 1;
        long per = ceilDiv(hours, max);
        return new TimeBuckets(zone, false, null, first, per, (int) ceilDiv(hours, per));
    }

    public int size() {
        return size;
    }

    /** The bucket holding {@code at}, or -1 when it falls outside every bucket. */
    public int indexOf(Instant at) {
        if (at == null) {
            return -1;
        }
        long units;
        if (daily) {
            units = ChronoUnit.DAYS.between(firstDay, at.atZone(zone).toLocalDate());
        } else {
            if (at.isBefore(firstHour.toInstant())) {
                return -1;
            }
            units = ChronoUnit.HOURS.between(firstHour, at.atZone(zone));
        }
        if (units < 0) {
            return -1;
        }
        long index = units / unitsPerBucket;
        return index < size ? (int) index : -1;
    }

    public int indexOf(long epochMs) {
        return indexOf(Instant.ofEpochMilli(epochMs));
    }

    /** The start of each bucket, oldest first. */
    public List<String> labels() {
        List<String> labels = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            labels.add(daily
                    ? DAY.format(firstDay.plusDays(i * unitsPerBucket))
                    : HOUR.format(firstHour.plusHours(i * unitsPerBucket)));
        }
        return labels;
    }

    /** The bucket width in words: "per day", "per 3 days", "per hour", "per 2 hours". */
    public String unit() {
        String word = daily ? "day" : "hour";
        return unitsPerBucket == 1 ? "per " + word : "per " + unitsPerBucket + " " + word + "s";
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
