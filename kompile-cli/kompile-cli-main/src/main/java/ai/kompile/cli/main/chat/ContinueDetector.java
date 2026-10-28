/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stall evidence read from a finished assistant turn.
 *
 * <p>{@link ContinueManager}'s keyword lane answers an agent that <em>asks</em> before
 * acting ("Shall I proceed?"). It cannot see the opposite failure: a turn that
 * <em>stops</em> without asking, leaving work it just promised to do. Two such
 * closings are recognizable from the turn text itself, so they are detected here
 * rather than being guessed at from a keyword list:</p>
 *
 * <ul>
 *   <li>{@link Kind#PROMISED_ACTION} — the turn's last sentence announces work
 *       ("Let me do that now.", "Now I'll fix the test."). A promise followed by
 *       anything else — a recap, a list, fenced output, a result — was acted on,
 *       so it is not a stall.</li>
 *   <li>{@link Kind#REPORTED_GAP} — the turn's final block files outstanding work
 *       ("Remaining gap: no test drives the failover path.", "- Not yet verified:
 *       the Triton lane"). An explicit all-clear ("no remaining gaps", "nothing
 *       remaining") is not a gap.</li>
 * </ul>
 *
 * <p>Both detectors only read where the turn <em>ended</em>: a promise that is not
 * the last sentence, or a gap followed by more paragraphs, says nothing about how
 * the turn stopped. Detection is a pure function of the text, so every hit is
 * reproducible, and a closing question is deliberately left to the keyword lane —
 * there the agent is asking, not stalling. A hit is evidence, not a verdict:
 * {@code ContinueManager} still applies its interaction, plan-mode and budget
 * gates before anything is sent.</p>
 *
 * <p>Disable this layer with {@code -Dkompile.chat.autocontinue.detectors=false};
 * keyword auto-replies are unaffected.</p>
 */
public final class ContinueDetector {

    /** Why a finished turn looks unfinished. */
    public enum Kind {
        /** The turn closes by promising work it never produced. */
        PROMISED_ACTION("promised action"),
        /** The turn reports an outstanding gap as its closing state. */
        REPORTED_GAP("reported gap");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One detected stall: which detector fired, and the text that proves it. */
    public record Hit(Kind kind, String evidence) {
        /** Short human form for notices, logs and {@code /continue test}. */
        public String describe() {
            return kind.label() + " — \"" + evidence + "\"";
        }
    }

    static final String ENABLE_PROPERTY = "kompile.chat.autocontinue.detectors";

    /** A gap marker further back than this is followed by work, not a stop. */
    private static final int GAP_CLOSER_CHARS = 2_000;
    private static final int MAX_EVIDENCE_CHARS = 120;

    /** Promises of imminent action; "let me know" is a courtesy, not a promise. */
    private static final Pattern PROMISE = Pattern.compile("(?:let me|let's|i'll|i will)\\b");

    /**
     * Closing courtesies: the agent hands the next move back to the reader, so there is
     * no work to continue. Covers "let me know", "I'll let you know", "let me hear".
     */
    private static final Pattern COURTESY = Pattern.compile(
            "\\b(?:let me know|let me hear|let(?:'s| us) leave it"
                    + "|(?:i'll|i will) let you know|(?:i'll|i will) leave it)\\b");

    /** Markers that file outstanding work as the turn's closing state. */
    private static final Pattern GAP_MARKER = Pattern.compile(
            "\\b(?:remaining gaps?|remaining work|outstanding gaps?|still not verified"
                    + "|still unverified|not yet implemented|not yet verified"
                    + "|still outstanding|left unverified|remaining issues?)\\b");

    /** Explicit all-clears; anything else ("still not closed") remains a gap. */
    private static final Pattern GAP_NEGATED = Pattern.compile(
            "\\b(?:no|zero|without)\\b[^.\\n]{0,40}\\bgaps?\\b"
                    + "|\\bnone of the gaps\\b"
                    + "|\\bnothing remaining\\b"
                    + "|\\bgaps? (?:is|are) (?:closed|resolved|addressed)\\b"
                    + "|\\ball gaps (?:closed|resolved|addressed)\\b");

    /** Sentence or line terminators, kept with the sentence they end. */
    private static final Pattern TERMINATOR = Pattern.compile("[.\\n!?]");

    private ContinueDetector() {
    }

    /** Static switch shared by both detectors; default on. */
    static boolean enabled() {
        String property = System.getProperty(ENABLE_PROPERTY);
        if (property == null) {
            return true;
        }
        String value = property.trim();
        return !value.isEmpty() && !"false".equalsIgnoreCase(value) && !"0".equals(value)
                && !"off".equalsIgnoreCase(value);
    }

    /** Every detector that fires for {@code response}, in declaration order. */
    static List<Hit> detect(String response) {
        List<Hit> hits = new ArrayList<>(2);
        Hit promised = promisedAction(response);
        if (promised != null) hits.add(promised);
        Hit gap = reportedGap(response);
        if (gap != null) hits.add(gap);
        return hits;
    }

    /**
     * The turn's final sentence, when that sentence promises action. Returns null
     * when the turn ends on anything else: a closing question (the keyword lane owns
     * it), or a promise followed by a recap, list, fenced output or result.
     */
    static Hit promisedAction(String response) {
        if (!enabled() || response == null) return null;
        String text = response.strip();
        if (text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);

        // Judge by the LAST promise: an earlier one is followed by the rest of the turn.
        Matcher promise = PROMISE.matcher(lower);
        int start = -1;
        while (promise.find()) {
            // "Let me know if..." closes politely and expects no follow-up.
            if (lower.startsWith(" know", promise.end())) continue;
            start = promise.start();
        }
        if (start < 0) return null;

        String promiseText = text.substring(start).strip();
        // "Let me know if...", "I'll let you know once...": closing courtesies that
        // expect no follow-up. Matched on the promise text so every phrasing of the
        // courtesy is covered, not just "let me know".
        if (COURTESY.matcher(promiseText.toLowerCase(Locale.ROOT)).find()) return null;
        // A closing question asks for input; it is not a silent stop.
        if (promiseText.indexOf('?') >= 0) return null;
        String sentence = firstSentence(promiseText);
        // Anything after the promise sentence means the agent kept going.
        if (!promiseText.substring(sentence.length()).isBlank()) return null;
        return new Hit(Kind.PROMISED_ACTION, evidence(sentence));
    }

    /**
     * The turn's final block, when it files outstanding work. Returns null when the
     * closing block names no gap, or explicitly reports that none remain.
     */
    static Hit reportedGap(String response) {
        if (!enabled() || response == null) return null;
        String closer = closingBlock(response);
        if (closer.isBlank()) return null;

        Matcher marker = GAP_MARKER.matcher(closer.toLowerCase(Locale.ROOT));
        while (marker.find()) {
            String sentence = sentenceAt(closer, marker.end());
            if (sentence.isBlank() || GAP_NEGATED.matcher(sentence.toLowerCase(Locale.ROOT)).find()) {
                continue;
            }
            return new Hit(Kind.REPORTED_GAP, evidence(sentence));
        }
        return null;
    }

    /**
     * Where the turn ended: the last block, widened only to swallow a bare gap
     * <em>heading</em> directly above it ({@code "Remaining gaps"} over its bullets).
     *
     * <p>The heading is the one form that carries no evidence of its own, so it must be
     * pulled in for the marker to be found at all. Any other opener is left where it
     * is: a labelled gap that truly ends the turn is already the last block, whereas a
     * sentence that merely mentions a gap ("Remaining gaps: two of the checks need
     * dtypes.") is followed by the work that finished the turn.</p>
     */
    private static String closingBlock(String response) {
        String body = response.strip();
        if (body.isBlank()) return "";
        int from = body.lastIndexOf("\n\n");
        from = from < 0 ? 0 : from + 2;
        if (from > 0) {
            int above = body.lastIndexOf("\n\n", Math.max(from - 3, 0));
            int start = above < 0 ? 0 : above + 2;
            if (isBareGapHeading(body.substring(start, from).strip())) {
                from = start;
            }
        }
        String closer = body.substring(from);
        if (closer.length() > GAP_CLOSER_CHARS) {
            // Keep the tail whole-line, never a mid-sentence slice.
            int cut = closer.lastIndexOf('\n', closer.length() - GAP_CLOSER_CHARS);
            closer = closer.substring(cut < 0 ? 0 : cut + 1);
        }
        return closer.strip();
    }

    /** True when the block is only a gap heading, e.g. "## Remaining gaps" or "Remaining work". */
    private static boolean isBareGapHeading(String opener) {
        String first = firstLine(opener);
        if (first.isEmpty()) return false;
        String heading = first.replaceAll("^#+\\s*", "").replaceAll("[*_`]", "").strip();
        Matcher marker = GAP_MARKER.matcher(heading.toLowerCase(Locale.ROOT));
        if (!marker.find()) return false;
        // A heading labels; a sentence explains. A colon or a clause means it reads as
        // prose, and prose that mentions a gap is not the turn's closing statement.
        return heading.indexOf(':') < 0
                && heading.length() <= 60
                && !heading.matches(".*\\b(?:need|needs|needed|requires?|because|since|is|are|was|were)\\b.*");
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("").strip();
    }

    /** The first sentence (or list line) of {@code text}. */
    private static String firstSentence(String text) {
        Matcher terminator = TERMINATOR.matcher(text);
        while (terminator.find()) {
            if (terminator.end() >= text.length()) break;
            return text.substring(0, terminator.end()).strip();
        }
        return text.strip();
    }

    /**
     * The line or sentence containing the marker just before {@code markerEnd},
     * including its list bullet, bounded by the next terminator.
     */
    private static String sentenceAt(String text, int markerEnd) {
        int lineStart = text.lastIndexOf('\n', Math.max(markerEnd - 1, 0)) + 1;
        int end = text.length();
        Matcher terminator = TERMINATOR.matcher(text);
        if (terminator.find(markerEnd)) end = Math.min(end, terminator.end());
        String line = text.substring(lineStart, Math.max(end, markerEnd)).strip();
        // A bare heading carries no evidence on its own; fall back to the marker text.
        return line.isEmpty() ? "" : line;
    }

    private static String evidence(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MAX_EVIDENCE_CHARS
                ? oneLine.substring(0, MAX_EVIDENCE_CHARS - 3) + "..."
                : oneLine;
    }
}
