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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest {

    @Test
    void countsAreGroupedAndPluralized() {
        assertEquals("1,234,567", Format.count(1_234_567));
        assertEquals("1 verdict", Format.count(1, "verdict"));
        assertEquals("0 verdicts", Format.count(0, "verdict"));
        assertEquals("2 test classes", Format.count(2, "test class", "test classes"));
    }

    @Test
    void bytesUseBinaryUnits() {
        assertEquals("512 B", Format.bytes(512));
        assertEquals("1 KiB", Format.bytes(1024));
        assertEquals("4 MiB", Format.bytes(4L * 1024 * 1024));
        assertEquals("1.5 GiB", Format.bytes(3L * 512 * 1024 * 1024));
    }

    @Test
    void percentOfNothingIsADash() {
        assertEquals("33.3%", Format.percent(1, 3));
        assertEquals("100.0%", Format.percent(4, 4));
        assertEquals("-", Format.percent(0, 0));
        assertEquals("-", Format.percent(Double.NaN));
    }

    @Test
    void durationsPickTheirUnit() {
        assertEquals("850ms", Format.duration(850));
        assertEquals("1.8s", Format.duration(1_800));
        assertEquals("2m05s", Format.duration(125_000));
        assertEquals("1h02m", Format.duration(3_720_000));
        assertEquals("-", Format.duration(-1));
    }

    @Test
    void statusesReadAsWords() {
        assertEquals("override armed", Format.words("OVERRIDE_ARMED"));
        assertEquals("", Format.words(null));
    }

    @Test
    void clampKeepsOneLineAndCutsWithAnEllipsis() {
        assertEquals("a b c [0m", Format.clamp("a\tb\n c\u001b[0m", 40));
        assertEquals("abc…", Format.clamp("abcdef", 4));
        assertEquals("abcd", Format.clamp("abcd", 4));
        assertEquals("", Format.clamp(null, 4));
    }

    @Test
    void clampNeverSplitsASurrogatePair() {
        // The emoji is two chars; cutting after its first half would leave an unpaired surrogate.
        assertEquals("ab…", Format.clamp("ab😀cd", 4));
    }

    @Test
    void agoNamesTheLargestWholeUnit() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");

        assertEquals("just now", Format.ago(now.minusSeconds(59), now));
        assertEquals("just now", Format.ago(now.plusSeconds(30), now));
        assertEquals("1m ago", Format.ago(now.minusSeconds(60), now));
        assertEquals("59m ago", Format.ago(now.minusSeconds(3599), now));
        assertEquals("2h ago", Format.ago(now.minusSeconds(2 * 3600 + 3540), now));
        assertEquals("3d ago", Format.ago(now.minusSeconds(3 * 86_400 + 7200), now));
        assertEquals("-", Format.ago(null, now));
    }

    @Test
    void timesUseTheZone() {
        Instant at = Instant.parse("2026-10-03T01:30:00Z");
        assertEquals("10-03 01:30", Format.dateTime(at, ZoneOffset.UTC));
        assertEquals("10-02 21:30", Format.dateTime(at, ZoneId.of("America/New_York")));
        assertEquals("10-02", Format.date(at, ZoneId.of("America/New_York")));
        assertEquals("-", Format.date(null, ZoneOffset.UTC));
        assertEquals("-", Format.dateTime(null, ZoneOffset.UTC));
    }
}
