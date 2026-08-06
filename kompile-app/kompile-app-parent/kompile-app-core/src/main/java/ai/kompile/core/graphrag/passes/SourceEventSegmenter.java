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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.GraphConstructor.SourceSpan;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically turns one crawl chunk into small, ordered source events.
 *
 * <p>Prose is split at line and sentence boundaries even when a paragraph fits, so the proposition
 * model receives one local assertion-sized event rather than a small paragraph containing several
 * facts. Compact mail/header blocks remain intact. An oversized sentence is split at source
 * whitespace as a last resort. Every event is an exact substring of the original chunk and carries
 * its original offsets, so model evidence can be rebased without guessing.</p>
 */
final class SourceEventSegmenter {

    static final int DEFAULT_MAX_EVENT_CHARS = 1_200;
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("(?:\\r?\\n)[ \\t]*(?:\\r?\\n)+");
    private static final Pattern HEADER_LINE = Pattern.compile("^[\\p{L}][\\p{L}\\p{N}-]{0,24}:\\s*\\S.*$");
    private static final Pattern STRUCTURED_LIST_INTRODUCER =
            Pattern.compile("\\s+(?:—|–|--)\\s+");
    private static final Pattern NUMERIC_LIST_SEPARATOR =
            Pattern.compile("[,;]\\s+(?=[\\p{Sc}]?[0-9])");
    private static final Pattern CONTAINS_NUMBER = Pattern.compile("[0-9]");

    private SourceEventSegmenter() {
    }

    record SourceEvent(int ordinal, int start, int end, String text) {

        SourceEvent {
            if (ordinal < 1 || start < 0 || end <= start || text == null || text.isBlank()) {
                throw new IllegalArgumentException("invalid source event");
            }
        }

        String id() {
            return "event-" + ordinal;
        }
    }

    static List<SourceEvent> segment(String source) {
        return segment(source, DEFAULT_MAX_EVENT_CHARS);
    }

    static List<SourceEvent> segment(String source, int maxEventChars) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        int limit = Math.max(128, maxEventChars);
        List<Range> ranges = new ArrayList<>();
        Matcher separator = PARAGRAPH_SEPARATOR.matcher(source);
        int cursor = 0;
        while (separator.find()) {
            addBlock(source, cursor, separator.start(), limit, ranges);
            cursor = separator.end();
        }
        addBlock(source, cursor, source.length(), limit, ranges);

        List<SourceEvent> events = new ArrayList<>(ranges.size());
        for (Range range : ranges) {
            events.add(new SourceEvent(events.size() + 1, range.start(), range.end(),
                    source.substring(range.start(), range.end())));
        }
        return List.copyOf(events);
    }

    /**
     * Turns a complete upstream source-span plan into model events. An invalid, overlapping or
     * incomplete plan returns an empty list so the configured caller can deliberately choose its
     * fallback. Valid spans are preserved exactly unless one exceeds the configured event budget,
     * in which case only that span is deterministically subdivided.
     */
    static List<SourceEvent> segmentSourceSpans(String source, int maxEventChars,
                                                List<SourceSpan> sourceSpans) {
        if (source == null || source.isBlank() || sourceSpans == null || sourceSpans.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(128, maxEventChars);
        List<SourceSpan> ordered = sourceSpans.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(SourceSpan::start)
                        .thenComparingInt(SourceSpan::end))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<Range> ranges = new ArrayList<>();
        int coveredThrough = 0;
        for (SourceSpan span : ordered) {
            if (span.start() < coveredThrough || span.end() > source.length()
                    || hasNonWhitespace(source, coveredThrough, span.start())) {
                return List.of();
            }
            Range range = trim(source, span.start(), span.end());
            if (range != null) {
                if (range.length() <= limit) {
                    ranges.add(range);
                } else {
                    addBlock(source, range.start(), range.end(), limit, ranges);
                }
            }
            coveredThrough = span.end();
        }
        if (hasNonWhitespace(source, coveredThrough, source.length())) {
            return List.of();
        }

        List<SourceEvent> events = new ArrayList<>(ranges.size());
        for (Range range : ranges) {
            events.add(new SourceEvent(events.size() + 1, range.start(), range.end(),
                    source.substring(range.start(), range.end())));
        }
        return List.copyOf(events);
    }

    private static boolean hasNonWhitespace(String source, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(source.length(), end); i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void addBlock(String source, int rawStart, int rawEnd, int limit,
                                 List<Range> sink) {
        Range block = trim(source, rawStart, rawEnd);
        if (block == null) {
            return;
        }
        if (block.length() <= limit && isCompactHeaderBlock(source, block)) {
            sink.add(block);
            return;
        }

        List<Range> units = atomicRanges(source, block);
        if (units.isEmpty()) {
            units = List.of(block);
        }
        for (Range unit : units) {
            if (unit.length() > limit) {
                splitOversized(source, unit, limit, sink);
            } else {
                sink.add(unit);
            }
        }
    }

    private static List<Range> atomicRanges(String source, Range block) {
        List<Range> lines = lineRanges(source, block);
        List<Range> units = new ArrayList<>();
        for (Range line : lines) {
            List<Range> sentences = sentenceRanges(source, line);
            if (sentences.isEmpty()) {
                units.add(line);
            } else {
                units.addAll(sentences);
            }
        }
        return units;
    }

    private static boolean isCompactHeaderBlock(String source, Range block) {
        String[] lines = source.substring(block.start(), block.end()).split("\\R");
        if (lines.length < 2) {
            return false;
        }
        for (String line : lines) {
            if (!HEADER_LINE.matcher(line.strip()).matches()) {
                return false;
            }
        }
        return true;
    }

    private static List<Range> sentenceRanges(String source, Range block) {
        String text = source.substring(block.start(), block.end());
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(text);
        List<Range> breakRanges = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE;
             start = end, end = iterator.next()) {
            Range range = trim(source, block.start() + start, block.start() + end);
            if (range != null) {
                if (!breakRanges.isEmpty()
                        && isProtectedInitialNameBoundary(source,
                        breakRanges.get(breakRanges.size() - 1), range)) {
                    Range previous = breakRanges.remove(breakRanges.size() - 1);
                    breakRanges.add(new Range(previous.start(), range.end()));
                } else {
                    breakRanges.add(range);
                }
            }
        }
        List<Range> ranges = new ArrayList<>();
        for (Range range : breakRanges) {
            for (Range refined : refineLowercaseSentenceBoundaries(source, range)) {
                ranges.addAll(structuredFactRanges(source, refined));
            }
        }
        return ranges;
    }

    /**
     * Repairs a false {@link BreakIterator} boundary between a person's initial and surname.
     *
     * <p>Source material commonly includes aliases such as {@code "(S. Chen, ...)"}, and Java's
     * sentence iterator may put {@code "Chen"} in a new sentence. That destroys the source event
     * before the proposition model sees it. Protection is limited to the source forms seen in
     * crawls: a parenthesized alias, a timeline timestamp immediately before the initial, or an
     * initial at the start of a line. Legitimate forms such as {@code "Option A. Next step."}
     * therefore retain their sentence boundary.</p>
     */
    private static boolean isProtectedInitialNameBoundary(
            String source, Range previous, Range next) {
        int period = previous.end() - 1;
        if (period <= previous.start() || source.charAt(period) != '.'
                || !Character.isUpperCase(source.charAt(period - 1))) {
            return false;
        }
        int beforeInitial = period - 2;
        if (beforeInitial >= previous.start()
                && Character.isLetterOrDigit(source.charAt(beforeInitial))) {
            return false;
        }
        int groupingDepth = 0;
        for (int i = previous.start(); i < period; i++) {
            char character = source.charAt(i);
            if (character == '(') {
                groupingDepth++;
            } else if (character == ')' && groupingDepth > 0) {
                groupingDepth--;
            }
        }
        int lead = period - 2;
        while (lead >= previous.start() && Character.isWhitespace(source.charAt(lead))) {
            lead--;
        }
        boolean protectedContext = groupingDepth > 0
                || lead < previous.start()
                || Character.isDigit(source.charAt(lead))
                || hasPersonInitialIntroducer(source, previous.start(), lead);
        if (!protectedContext) {
            return false;
        }
        int nameStart = next.start();
        if (nameStart >= next.end() || !Character.isUpperCase(source.charAt(nameStart))) {
            return false;
        }
        int nameEnd = nameStart + 1;
        while (nameEnd < next.end()) {
            char character = source.charAt(nameEnd);
            if (!Character.isLetter(character) && character != '\''
                    && character != '-' && character != '’') {
                break;
            }
            nameEnd++;
        }
        return nameEnd - nameStart >= 2;
    }

    /** A preposition before a single capital is a strong local signal that the capital is a name initial. */
    private static boolean hasPersonInitialIntroducer(String source, int rangeStart, int lead) {
        int wordEnd = lead + 1;
        int wordStart = lead;
        while (wordStart >= rangeStart && Character.isLetter(source.charAt(wordStart))) {
            wordStart--;
        }
        String word = source.substring(wordStart + 1, wordEnd).toLowerCase(Locale.ROOT);
        return switch (word) {
            case "to", "by", "from", "with", "for", "cc", "bcc" -> true;
            default -> false;
        };
    }

    /**
     * {@link BreakIterator} commonly keeps a period followed by lower-case email/chat prose in one
     * sentence. Split that boundary when the preceding token is not a short abbreviation. Numeric
     * sentence endings are accepted so forms such as {@code "launches july 1. i have ..."} split.
     */
    private static List<Range> refineLowercaseSentenceBoundaries(String source, Range range) {
        List<Range> refined = new ArrayList<>();
        int start = range.start();
        for (int i = range.start(); i < range.end() - 1; i++) {
            char terminal = source.charAt(i);
            if (terminal != '.' && terminal != '!' && terminal != '?') {
                continue;
            }
            int next = i + 1;
            while (next < range.end() && Character.isWhitespace(source.charAt(next))) {
                next++;
            }
            if (next == i + 1 || next >= range.end()
                    || !Character.isLowerCase(source.charAt(next))) {
                continue;
            }
            int tokenStart = i - 1;
            while (tokenStart >= start && Character.isLetterOrDigit(source.charAt(tokenStart))) {
                tokenStart--;
            }
            int tokenLength = i - tokenStart - 1;
            char beforeTerminal = i > start ? source.charAt(i - 1) : '\0';
            if (!Character.isDigit(beforeTerminal) && tokenLength <= 2) {
                continue;
            }
            Range sentence = trim(source, start, i + 1);
            if (sentence != null) {
                refined.add(sentence);
            }
            start = next;
            i = next - 1;
        }
        Range last = trim(source, start, range.end());
        if (last != null) {
            refined.add(last);
        }
        return refined.isEmpty() ? List.of(range) : List.copyOf(refined);
    }

    /**
     * Splits a compact numeric fact list introduced by a dash into independent focus events.
     *
     * <p>This is intentionally conservative: the tail must contain at least two comma/semicolon
     * separated items and every item must contain a number. It therefore handles source forms such
     * as {@code "phasing — 4200 units in Jul, 6800 in Aug"} without treating ordinary prose commas,
     * names, addresses or thousands separators as proposition boundaries. The introducing clause is
     * retained as its own event; later pipeline scoping keeps it available as bounded reference
     * context for the numeric fragments.</p>
     */
    private static List<Range> structuredFactRanges(String source, Range sentence) {
        String text = source.substring(sentence.start(), sentence.end());
        Matcher introducer = STRUCTURED_LIST_INTRODUCER.matcher(text);
        while (introducer.find()) {
            int tailStart = sentence.start() + introducer.end();
            Range tail = trim(source, tailStart, sentence.end());
            if (tail == null) {
                continue;
            }
            List<Range> items = numericListRanges(source, tail);
            if (items.size() < 2) {
                continue;
            }
            List<Range> split = new ArrayList<>(items.size() + 1);
            Range introduction = trim(source, sentence.start(),
                    sentence.start() + introducer.start());
            if (introduction != null) {
                split.add(introduction);
            }
            split.addAll(items);
            return split;
        }
        return List.of(sentence);
    }

    private static List<Range> numericListRanges(String source, Range tail) {
        String text = source.substring(tail.start(), tail.end());
        Matcher separator = NUMERIC_LIST_SEPARATOR.matcher(text);
        List<Range> items = new ArrayList<>();
        int cursor = 0;
        while (separator.find()) {
            Range item = trim(source, tail.start() + cursor, tail.start() + separator.start());
            if (item != null) {
                items.add(item);
            }
            cursor = separator.end();
        }
        Range last = trim(source, tail.start() + cursor, tail.end());
        if (last != null) {
            items.add(last);
        }
        if (items.size() < 2 || items.stream().anyMatch(item ->
                !CONTAINS_NUMBER.matcher(source.substring(item.start(), item.end())).find())) {
            return List.of();
        }
        return List.copyOf(items);
    }

    private static List<Range> lineRanges(String source, Range block) {
        List<Range> ranges = new ArrayList<>();
        int start = block.start();
        for (int i = block.start(); i < block.end(); i++) {
            if (source.charAt(i) == '\n') {
                Range range = trim(source, start, i);
                if (range != null) {
                    ranges.add(range);
                }
                start = i + 1;
            }
        }
        Range last = trim(source, start, block.end());
        if (last != null) {
            ranges.add(last);
        }
        return ranges;
    }

    private static void splitOversized(String source, Range range, int limit, List<Range> sink) {
        int start = range.start();
        while (range.end() - start > limit) {
            int target = start + limit;
            int floor = start + limit / 2;
            int split = target;
            while (split > floor && !Character.isWhitespace(source.charAt(split - 1))) {
                split--;
            }
            if (split <= floor) {
                split = target;
            }
            Range part = trim(source, start, split);
            if (part != null) {
                sink.add(part);
            }
            start = split;
            while (start < range.end() && Character.isWhitespace(source.charAt(start))) {
                start++;
            }
        }
        Range last = trim(source, start, range.end());
        if (last != null) {
            sink.add(last);
        }
    }

    private static Range trim(String source, int start, int end) {
        while (start < end && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        return start < end ? new Range(start, end) : null;
    }

    private record Range(int start, int end) {

        int length() {
            return end - start;
        }
    }
}
