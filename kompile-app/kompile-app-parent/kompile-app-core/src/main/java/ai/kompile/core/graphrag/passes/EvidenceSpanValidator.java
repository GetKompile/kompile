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

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;

import java.util.Arrays;

/**
 * Deterministic check that a cited span really occurs in the source chunk.
 *
 * <p>This is the engine's veto over fabricated evidence. Models miscount character offsets
 * constantly but quote text accurately, so the quote is treated as authoritative and the offsets
 * are recomputed. A quote that cannot be located — even after whitespace and case normalisation —
 * did not come from the source, and the proposal resting on it is dropped rather than repaired.</p>
 */
public final class EvidenceSpanValidator {

    /**
     * Shortest quote that can be meaningfully verified. Anything shorter matches by accident in
     * ordinary prose, which would make the check worthless.
     */
    public static final int MIN_QUOTE_CHARS = 4;

    private EvidenceSpanValidator() {
    }

    /** Outcome of checking one span. */
    public enum Status {
        /** Offsets and quote agree with the source as supplied. */
        VALID,
        /** Quote found in the source; offsets (and exact casing/whitespace) were corrected. */
        REPAIRED,
        /** No span or no usable quote was supplied. */
        MISSING,
        /** Quote is too short to verify. */
        TOO_SHORT,
        /** Quote does not occur in the source: fabricated evidence. */
        NOT_FOUND
    }

    /**
     * @param status what happened
     * @param span   the usable span when {@link #usable()}, otherwise the input span unchanged
     * @param detail human-readable explanation, recorded in pass diagnostics
     */
    public record SpanCheck(Status status, EvidenceSpan span, String detail) {

        /** True when the span may be attached to a proposal that reaches the graph. */
        public boolean usable() {
            return status == Status.VALID || status == Status.REPAIRED;
        }
    }

    /**
     * Verifies {@code span} against {@link PassContext#sourceText()}, repairing offsets when the
     * quote can be located.
     */
    public static SpanCheck check(EvidenceSpan span, PassContext context) {
        String source = context == null ? "" : context.sourceText();
        String chunkId = context == null ? null : context.chunkId();
        return check(span, source, chunkId);
    }

    /** Overload for callers that hold the raw text rather than a {@link PassContext}. */
    public static SpanCheck check(EvidenceSpan span, String sourceText, String chunkId) {
        if (span == null || !span.hasQuote()) {
            return new SpanCheck(Status.MISSING, span, "no evidence quote supplied");
        }
        String source = sourceText == null ? "" : sourceText;
        String quote = span.quote().strip();
        if (quote.length() < MIN_QUOTE_CHARS) {
            return new SpanCheck(Status.TOO_SHORT, span,
                    "quote shorter than " + MIN_QUOTE_CHARS + " characters: '" + quote + "'");
        }

        EvidenceSpan located = withChunk(span, chunkId);

        // Fast path: the model's own offsets already delimit exactly the quoted text.
        if (located.hasOffsets() && located.end() <= source.length()
                && source.substring(located.start(), located.end()).equals(quote)) {
            return new SpanCheck(Status.VALID, located, "offsets verified against source");
        }

        int exact = source.indexOf(quote);
        if (exact >= 0) {
            return new SpanCheck(Status.REPAIRED,
                    reanchor(located, source, exact, exact + quote.length()),
                    "offsets recomputed from exact quote match");
        }

        int[] relaxed = findNormalized(source, quote);
        if (relaxed != null) {
            return new SpanCheck(Status.REPAIRED,
                    reanchor(located, source, relaxed[0], relaxed[1]),
                    "offsets recomputed from whitespace/case-insensitive match");
        }

        return new SpanCheck(Status.NOT_FOUND, located,
                "quote not present in source chunk: '" + abbreviate(quote) + "'");
    }

    private static EvidenceSpan withChunk(EvidenceSpan span, String chunkId) {
        if (chunkId == null || chunkId.isBlank() || chunkId.equals(span.chunkId())) {
            return span;
        }
        return span.withChunkId(chunkId);
    }

    /**
     * Rebuilds the span at {@code [start,end)} and replaces the quote with the source text itself,
     * so every span that survives validation quotes the document verbatim.
     */
    private static EvidenceSpan reanchor(EvidenceSpan span, String source, int start, int end) {
        return new EvidenceSpan(span.chunkId(), start, end, source.substring(start, end),
                span.role());
    }

    /**
     * Locates {@code quote} in {@code source} ignoring case and whitespace runs, returning the
     * original {@code [start,end)} offsets, or {@code null} when absent.
     */
    private static int[] findNormalized(String source, String quote) {
        StringBuilder normalizedSource = new StringBuilder(source.length());
        int[] indexMap = normalize(source, normalizedSource);
        String normalizedQuote = normalizeToString(quote);
        if (normalizedQuote.isEmpty()) {
            return null;
        }
        int hit = normalizedSource.indexOf(normalizedQuote);
        if (hit < 0) {
            return null;
        }
        int start = indexMap[hit];
        int end = indexMap[hit + normalizedQuote.length() - 1] + 1;
        if (start < 0 || end > source.length() || end <= start) {
            return null;
        }
        return new int[] {start, end};
    }

    private static String normalizeToString(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        normalize(text, sb);
        return sb.toString().strip();
    }

    /**
     * Lower-cases and collapses whitespace runs into single spaces, recording for each emitted
     * character the index it came from in {@code text}.
     */
    private static int[] normalize(String text, StringBuilder out) {
        int[] map = new int[text.length() + 1];
        int emitted = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = emitted > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                map[emitted++] = i;
                pendingSpace = false;
            }
            out.append(Character.toLowerCase(c));
            map[emitted++] = i;
        }
        map[emitted] = text.length();
        return Arrays.copyOf(map, emitted + 1);
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }
}
