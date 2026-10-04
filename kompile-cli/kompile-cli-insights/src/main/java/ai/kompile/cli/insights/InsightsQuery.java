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

import lombok.Builder;
import lombok.Value;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plain-language question, parsed into what every insight source needs: an optional topic,
 * the time window, and an optional scope to the current session.
 *
 * <p>The question is kept as asked so each source can find its own subject in it (a tool, a
 * module, a test class) with {@link #matchSubject(Collection)}, and its own intent with
 * {@link #mentions(String...)}.</p>
 */
@Value
@Builder(toBuilder = true)
public class InsightsQuery {

    private static final Pattern SESSION_SCOPE =
            Pattern.compile("\\b(?:this|current)\\s+(?:session|chat|conversation)\\b");
    private static final Pattern ALL_TIME =
            Pattern.compile("\\b(?:all[ -]time|ever|overall)\\b");
    private static final Pattern LAST_N = Pattern.compile(
            "\\b(?:(?:last|past|previous)\\s+)?(\\d{1,4})\\s*(hours?|hrs?|h|days?|d|weeks?|wks?|w)\\b");
    private static final Pattern LAST_UNIT =
            Pattern.compile("\\b(?:last|past|previous)\\s+(hour|day|week|month)\\b");
    private static final Pattern TODAY = Pattern.compile("\\btoday\\b");
    private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b");
    private static final Pattern THIS_WEEK = Pattern.compile("\\bthis\\s+week\\b");
    private static final Pattern THIS_MONTH = Pattern.compile("\\bthis\\s+month\\b");

    /** Words just before a lower-case word name that mark it as a subject ("for read", "connected to alice"). */
    private static final Set<String> MARKERS_BEFORE = Set.of("for", "of", "module", "to", "around", "about");
    /** Words just after a lower-case word name that mark it as a subject ("the read tool", "the alice node"). */
    private static final Set<String> MARKERS_AFTER =
            Set.of("tool", "calls", "call", "module", "test", "tests", "runs", "node", "entity");
    private static final String QUOTES = "'\"`";

    /** Topic named by the caller, lower-case; null to infer it from the question. */
    String topic;
    /** The question as asked; never null. */
    String question;
    InsightsWindow window;
    /**
     * The ids of the current session when the question scopes the report to it, else empty.
     * A chat and the MCP server it starts can know the same session by different ids, so a
     * record matches when it carries any of them.
     */
    Set<String> sessionIds;
    Instant now;
    ZoneId zone;

    /** @param currentSessionIds ids of the session asking; blanks and nulls are ignored */
    public static InsightsQuery parse(String topic, String question, Collection<String> currentSessionIds,
                                      Instant now, ZoneId zone, int defaultWindowDays) {
        String asked = question == null ? "" : question.trim();
        String lower = asked.toLowerCase(Locale.ROOT);
        Set<String> sessions = new LinkedHashSet<>();
        if (currentSessionIds != null && SESSION_SCOPE.matcher(lower).find()) {
            for (String id : currentSessionIds) {
                if (id != null && !id.isBlank()) {
                    sessions.add(id.trim());
                }
            }
        }
        return InsightsQuery.builder()
                .topic(topic == null || topic.isBlank() ? null : topic.trim().toLowerCase(Locale.ROOT))
                .question(asked)
                .window(parseWindow(lower, now, zone, defaultWindowDays, !sessions.isEmpty()))
                .sessionIds(Collections.unmodifiableSet(sessions))
                .now(now)
                .zone(zone)
                .build();
    }

    /** The live panel's query: everything session {@code sessionId} recorded, as of {@code now}. */
    public static InsightsQuery session(String sessionId, Instant now, ZoneId zone) {
        return parse(null, "this session", Collections.singletonList(sessionId), now, zone, 1);
    }

    public boolean sessionScoped() {
        return sessionIds != null && !sessionIds.isEmpty();
    }

    /** True when the report is not scoped to a session, or {@code sessionId} is one of its ids. */
    public boolean inScope(String sessionId) {
        return !sessionScoped() || (sessionId != null && sessionIds.contains(sessionId));
    }

    static InsightsWindow parseWindow(String lower, Instant now, ZoneId zone,
                                      int defaultWindowDays, boolean sessionScoped) {
        LocalDate today = now.atZone(zone).toLocalDate();
        if (ALL_TIME.matcher(lower).find()) {
            return new InsightsWindow(Instant.EPOCH, now, "all time", true);
        }
        Matcher lastN = LAST_N.matcher(lower);
        if (lastN.find()) {
            int amount = Math.max(1, Integer.parseInt(lastN.group(1)));
            char unit = lastN.group(2).charAt(0);
            if (unit == 'h') {
                return new InsightsWindow(now.minus(Duration.ofHours(amount)), now,
                        amount == 1 ? "last hour" : "last " + amount + " hours", true);
            }
            int days = unit == 'w' ? amount * 7 : amount;
            return new InsightsWindow(now.minus(Duration.ofDays(days)), now,
                    days == 1 ? "last 24 hours" : "last " + days + " days", true);
        }
        if (YESTERDAY.matcher(lower).find()) {
            Instant start = today.minusDays(1).atStartOfDay(zone).toInstant();
            Instant end = today.atStartOfDay(zone).toInstant().minusMillis(1);
            return new InsightsWindow(start, end, "yesterday", true);
        }
        if (TODAY.matcher(lower).find()) {
            return new InsightsWindow(today.atStartOfDay(zone).toInstant(), now, "today", true);
        }
        if (THIS_WEEK.matcher(lower).find()) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new InsightsWindow(monday.atStartOfDay(zone).toInstant(), now, "this week", true);
        }
        if (THIS_MONTH.matcher(lower).find()) {
            return new InsightsWindow(today.withDayOfMonth(1).atStartOfDay(zone).toInstant(), now,
                    "this month", true);
        }
        Matcher lastUnit = LAST_UNIT.matcher(lower);
        if (lastUnit.find()) {
            return switch (lastUnit.group(1)) {
                case "hour" -> new InsightsWindow(now.minus(Duration.ofHours(1)), now, "last hour", true);
                case "day" -> new InsightsWindow(now.minus(Duration.ofDays(1)), now, "last 24 hours", true);
                case "week" -> new InsightsWindow(now.minus(Duration.ofDays(7)), now, "last 7 days", true);
                default -> new InsightsWindow(now.minus(Duration.ofDays(30)), now, "last 30 days", true);
            };
        }
        if (sessionScoped) {
            // A session is its own window.
            return new InsightsWindow(Instant.EPOCH, now, "this session", false);
        }
        int days = Math.max(1, defaultWindowDays);
        return new InsightsWindow(now.minus(Duration.ofDays(days)), now,
                days == 1 ? "last 24 hours" : "last " + days + " days", false);
    }

    /** True when the question contains any of the words (case-insensitive substring match). */
    public boolean mentions(String... words) {
        String lower = question.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The longest of {@code names} that the question names, or null. A name counts when it
     * appears as a whole token, ignoring case. A plain word such as {@code read} or
     * {@code Bash} must also be quoted or sit next to a marker ("the read tool", "for bash",
     * "connected to Alice", "the Alice node"), so ordinary English in a question does not
     * narrow the report by accident. Names with
     * inner capitals, digits, dashes or underscores ({@code JudgementLogTest},
     * {@code kompile-cli-main}, {@code read_batch}) match bare.
     */
    public String matchSubject(Collection<String> names) {
        String lower = question.toLowerCase(Locale.ROOT);
        String best = null;
        for (String name : names) {
            if (name == null || name.isBlank() || (best != null && name.length() <= best.length())) {
                continue;
            }
            if (names(lower, name.toLowerCase(Locale.ROOT), isPlainWord(name))) {
                best = name;
            }
        }
        return best;
    }

    private static boolean names(String lower, String name, boolean word) {
        int from = 0;
        int at;
        while ((at = lower.indexOf(name, from)) >= 0) {
            int end = at + name.length();
            boolean whole = (at == 0 || !isNameChar(lower.charAt(at - 1)))
                    && (end == lower.length() || !isNameChar(lower.charAt(end)));
            if (whole && (!word || quoted(lower, at, end) || marked(lower, at, end))) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    /** Letters only, with at most the first one upper-case. */
    private static boolean isPlainWord(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetter(c) || (i > 0 && Character.isUpperCase(c))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    private static boolean quoted(String lower, int at, int end) {
        return at > 0 && end < lower.length()
                && QUOTES.indexOf(lower.charAt(at - 1)) >= 0 && QUOTES.indexOf(lower.charAt(end)) >= 0;
    }

    private static boolean marked(String lower, int at, int end) {
        String before = lower.substring(0, at).trim();
        int space = before.lastIndexOf(' ');
        String previous = space >= 0 ? before.substring(space + 1) : before;
        String after = lower.substring(end).trim();
        int next = after.indexOf(' ');
        String following = next >= 0 ? after.substring(0, next) : after;
        return MARKERS_BEFORE.contains(previous) || MARKERS_AFTER.contains(stripPunctuation(following));
    }

    private static String stripPunctuation(String word) {
        int end = word.length();
        while (end > 0 && !Character.isLetterOrDigit(word.charAt(end - 1))) {
            end--;
        }
        return word.substring(0, end);
    }
}
