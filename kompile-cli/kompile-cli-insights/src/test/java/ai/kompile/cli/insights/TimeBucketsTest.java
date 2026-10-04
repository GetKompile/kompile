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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeBucketsTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    private static InsightsWindow last(Duration duration) {
        return new InsightsWindow(NOW.minus(duration), NOW, "test", true);
    }

    @Test
    void aWeekIsOneBucketPerDayIncludingBothEnds() {
        TimeBuckets buckets = TimeBuckets.of(last(Duration.ofDays(7)), null, 14, ZoneOffset.UTC);

        assertEquals(8, buckets.size());
        assertEquals("per day", buckets.unit());
        assertEquals(List.of("09-26", "09-27", "09-28", "09-29", "09-30", "10-01", "10-02", "10-03"),
                buckets.labels());
        assertEquals(0, buckets.indexOf(Instant.parse("2026-09-26T13:00:00Z")));
        assertEquals(7, buckets.indexOf(NOW));
        assertEquals(-1, buckets.indexOf(Instant.parse("2026-09-25T23:59:59Z")));
        assertEquals(-1, buckets.indexOf(Instant.parse("2026-10-04T00:00:00Z")));
        assertEquals(-1, buckets.indexOf((Instant) null));
    }

    @Test
    void aLongWindowGroupsDays() {
        TimeBuckets buckets = TimeBuckets.of(last(Duration.ofDays(30)), null, 14, ZoneOffset.UTC);

        // 31 calendar days at 3 per bucket.
        assertEquals("per 3 days", buckets.unit());
        assertEquals(11, buckets.size());
        assertEquals("09-03", buckets.labels().get(0));
        assertEquals("09-06", buckets.labels().get(1));
        assertEquals(10, buckets.indexOf(NOW));
    }

    @Test
    void aShortWindowUsesHours() {
        TimeBuckets buckets = TimeBuckets.of(last(Duration.ofHours(24)), null, 14, ZoneOffset.UTC);

        // 25 clock hours at 2 per bucket.
        assertEquals("per 2 hours", buckets.unit());
        assertEquals(13, buckets.size());
        assertEquals("12:00", buckets.labels().get(0));
        assertEquals("14:00", buckets.labels().get(1));
        assertEquals(12, buckets.indexOf(NOW));
        assertEquals(-1, buckets.indexOf(Instant.parse("2026-10-02T11:59:59Z")));
    }

    @Test
    void todayIsOneBucketPerHourSoFar() {
        InsightsWindow today = new InsightsWindow(Instant.parse("2026-10-03T00:00:00Z"), NOW, "today", true);
        TimeBuckets buckets = TimeBuckets.of(today, null, 14, ZoneOffset.UTC);

        assertEquals("per hour", buckets.unit());
        assertEquals(13, buckets.size());
        assertEquals("00:00", buckets.labels().get(0));
        assertEquals(12, buckets.indexOf(NOW));
    }

    @Test
    void allTimeStartsAtTheEarliestData() {
        InsightsWindow allTime = new InsightsWindow(Instant.EPOCH, NOW, "all time", true);

        TimeBuckets buckets = TimeBuckets.of(allTime, NOW.minus(Duration.ofDays(3)), 14, ZoneOffset.UTC);
        assertEquals(List.of("09-30", "10-01", "10-02", "10-03"), buckets.labels());

        TimeBuckets empty = TimeBuckets.of(allTime, null, 14, ZoneOffset.UTC);
        assertEquals(1, empty.size());
    }

    @Test
    void daysAreLocalToTheZone() {
        ZoneId newYork = ZoneId.of("America/New_York");
        TimeBuckets buckets = TimeBuckets.of(last(Duration.ofDays(2)), null, 14, newYork);

        // 2026-10-01T12:00Z is 08:00 in New York, so the first local day is 10-01.
        assertEquals(List.of("10-01", "10-02", "10-03"), buckets.labels());
        // 01:00Z on 10-03 is still 10-02 in New York.
        assertEquals(1, buckets.indexOf(Instant.parse("2026-10-03T01:00:00Z")));
    }
}
