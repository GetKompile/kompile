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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsQueryTest {

    /** A Saturday. */
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final List<String> NAMES =
            List.of("read", "read_batch", "Bash", "JudgementLogTest", "kompile-cli-main");

    private static InsightsQuery ask(String question) {
        return InsightsQuery.parse(null, question, List.of("chat-1"), NOW, ZoneOffset.UTC, 7);
    }

    private static InsightsWindow window(String question) {
        return ask(question).getWindow();
    }

    @Test
    void theDefaultWindowIsTheConfiguredDays() {
        InsightsWindow window = window("slowest tools");

        assertEquals(NOW.minus(Duration.ofDays(7)), window.start());
        assertEquals(NOW, window.end());
        assertEquals("last 7 days", window.label());
        assertFalse(window.explicit());
        assertEquals("last 24 hours",
                InsightsQuery.parse(null, "x", null, NOW, ZoneOffset.UTC, 1).getWindow().label());
    }

    @Test
    void calendarWindowsStartAtLocalMidnight() {
        InsightsWindow today = window("what did the judge block today");
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), today.start());
        assertEquals("today", today.label());
        assertTrue(today.explicit());

        InsightsWindow yesterday = window("errors yesterday");
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), yesterday.start());
        assertEquals(Instant.parse("2026-10-02T23:59:59.999Z"), yesterday.end());

        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), window("slowest tools this week").start());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), window("this month").start());

        ZoneId newYork = ZoneId.of("America/New_York");
        InsightsWindow localToday = InsightsQuery.parse(null, "today", null, NOW, newYork, 7).getWindow();
        assertEquals(Instant.parse("2026-10-03T04:00:00Z"), localToday.start());
    }

    @Test
    void relativeWindowsCountBackFromNow() {
        assertEquals(new InsightsWindow(NOW.minus(Duration.ofDays(3)), NOW, "last 3 days", true),
                window("judge flags over the last 3 days"));
        assertEquals(new InsightsWindow(NOW.minus(Duration.ofHours(12)), NOW, "last 12 hours", true),
                window("past 12 hours"));
        assertEquals("last hour", window("last 1 hour").label());
        assertEquals("last 14 days", window("2 weeks").label());
        assertEquals("last 30 days", window("30d").label());
        assertEquals("last 24 hours", window("1 day").label());
        assertEquals("last hour", window("in the last hour").label());
        assertEquals("last 24 hours", window("last day").label());
        assertEquals("last 7 days", window("last week").label());
        assertEquals(new InsightsWindow(NOW.minus(Duration.ofDays(30)), NOW, "last 30 days", true),
                window("last month"));
    }

    @Test
    void numbersInsideNamesAreNotWindows() {
        // "p95" and "top 5 tools" name no range.
        assertEquals("last 7 days", window("p95 by tool").label());
        assertFalse(window("top 5 tools").explicit());
    }

    @Test
    void allTimeStartsAtTheEpoch() {
        InsightsWindow window = window("pass rate over all time");

        assertTrue(window.allTime());
        assertEquals("all time", window.label());
        assertEquals("all time", window("ever").label());
    }

    @Test
    void thisSessionScopesToTheCallersIds() {
        InsightsQuery query = InsightsQuery.parse(null, "tool calls in this session",
                Arrays.asList("chat-1", " ", null, "mcp-2", "chat-1"), NOW, ZoneOffset.UTC, 7);

        assertTrue(query.sessionScoped());
        assertEquals(Set.of("chat-1", "mcp-2"), query.getSessionIds());
        // In the caller's order, so a report names the sessions the way they were given.
        assertEquals(List.of("chat-1", "mcp-2"), List.copyOf(query.getSessionIds()));
        assertTrue(query.inScope("mcp-2"));
        assertFalse(query.inScope("other"));
        assertFalse(query.inScope(null));
        // A session is its own window unless the question names one.
        assertTrue(query.getWindow().allTime());
        assertEquals("this session", query.getWindow().label());
        assertFalse(query.getWindow().explicit());
        assertEquals("today", ask("judge flags in the current chat today").getWindow().label());
    }

    @Test
    void withoutSessionWordsEverySessionIsInScope() {
        InsightsQuery query = ask("tool calls");

        assertFalse(query.sessionScoped());
        assertTrue(query.inScope("anything"));
        assertTrue(query.inScope(null));
        assertFalse(InsightsQuery.parse(null, "this session", null, NOW, ZoneOffset.UTC, 7).sessionScoped());
    }

    @Test
    void topicAndQuestionAreNormalized() {
        InsightsQuery query = InsightsQuery.parse(" Judge ", null, null, NOW, ZoneOffset.UTC, 7);

        assertEquals("judge", query.getTopic());
        assertEquals("", query.getQuestion());
        assertNull(InsightsQuery.parse("  ", "x", null, NOW, ZoneOffset.UTC, 7).getTopic());
    }

    @Test
    void mentionsIgnoresCase() {
        assertTrue(ask("Slowest TOOLS").mentions("slow"));
        assertFalse(ask("tool calls").mentions("slow", "latency"));
    }

    @Test
    void namesWithInnerMarksMatchBare() {
        assertEquals("read_batch", ask("slowest read_batch calls").matchSubject(NAMES));
        assertEquals("JudgementLogTest", ask("is judgementlogtest still failing").matchSubject(NAMES));
        assertEquals("kompile-cli-main", ask("kompile-cli-main pass rate").matchSubject(NAMES));
    }

    @Test
    void plainWordsNeedAQuoteOrAMarker() {
        assertNull(ask("how slow is read").matchSubject(NAMES));
        assertNull(ask("Bash is slow").matchSubject(NAMES));
        assertEquals("read", ask("how slow is the read tool?").matchSubject(NAMES));
        assertEquals("read", ask("latency for read").matchSubject(NAMES));
        assertEquals("read", ask("latency of 'read' this week").matchSubject(NAMES));
        assertEquals("Bash", ask("bash calls today").matchSubject(NAMES));
    }

    @Test
    void graphPhrasingMarksANode() {
        List<String> nodes = List.of("Alice", "Acme");
        assertEquals("Alice", ask("what is connected to Alice").matchSubject(nodes));
        assertEquals("Acme", ask("show the Acme node").matchSubject(nodes));
        assertEquals("Alice", ask("the graph around alice").matchSubject(nodes));
        assertNull(ask("is Alice in the graph").matchSubject(nodes));
    }

    @Test
    void namesMatchWholeTokensOnly() {
        assertNull(ask("the reader tool").matchSubject(NAMES));
        assertNull(ask("kompile-cli-main-extra pass rate").matchSubject(NAMES));
        assertNull(ask("tool calls").matchSubject(List.of()));
    }

    @Test
    void theLongestNamedSubjectWins() {
        assertEquals("read_batch", ask("compare read_batch with the read tool").matchSubject(NAMES));
    }
}
