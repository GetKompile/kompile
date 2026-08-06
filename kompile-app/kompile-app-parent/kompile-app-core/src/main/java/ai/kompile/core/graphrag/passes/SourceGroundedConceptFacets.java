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

import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps graph and pre-pass concept hints back to exact spans in the current source shard.
 *
 * <p>The pre-pass may normalize punctuation and carry concepts learned elsewhere in the unified
 * corpus. A small model must not treat those normalized or cross-shard strings as facts, so this
 * class exposes only hints that can be aligned to the current source and preserves the exact source
 * surface. Categories and provenance are passed through verbatim. Java deliberately does not infer
 * an endpoint, predicate, temporal, scalar, or routing role from category words; the supplied graph,
 * schema, problem domain, and model own that semantic decision.</p>
 */
final class SourceGroundedConceptFacets {

    private static final int MAX_FACETS = 12;
    private static final int MAX_SURFACE_CHARS = 240;
    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{L}\\p{N}]+(?:[&'’][\\p{L}\\p{N}]+)*");

    record Facet(int start, int end, String exactSurface, String hintTerm,
                 String category, String provenance, boolean termAligned) {
    }

    private record TokenSpan(int start, int end, String normalized) {
    }

    private record MatchSpan(int start, int end) {
    }

    private SourceGroundedConceptFacets() {
    }

    static List<Facet> from(PassContext context) {
        return from(context, context == null ? null : context.sourceText());
    }

    /** Aligns facets to one engine-fixed focus event rather than its broader reference window. */
    static List<Facet> from(PassContext context, String sourceWindow) {
        if (context == null || sourceWindow == null || sourceWindow.isBlank()
                || context.conceptHints().isEmpty()) {
            return List.of();
        }
        String source = sourceWindow;
        Map<String, Facet> unique = new LinkedHashMap<>();
        for (ConceptHint hint : context.conceptHints()) {
            MatchSpan match = locate(source, hint.term());
            boolean termAligned = match != null;
            if (match == null) {
                // A deterministic pre-pass may emit a normalized concept label (for example,
                // "acquisition date") plus the exact source phrase that motivated it. Use that
                // phrase only when it aligns to this same source window; cross-shard context remains
                // withheld. This preserves the semantic hint without manufacturing source text.
                match = locate(source, hint.observedContext());
            }
            if (match == null) {
                continue;
            }
            String key = match.start() + ":" + match.end();
            unique.putIfAbsent(key, new Facet(match.start(), match.end(),
                    source.substring(match.start(), match.end()), hint.term(), hint.category(),
                    hint.provenance(), termAligned));
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt(Facet::start)
                        .thenComparing(Comparator.comparingInt(Facet::end).reversed()))
                .limit(Math.min(MAX_FACETS, context.promptConceptHintLimit()))
                .toList();
    }

    static String promptBlock(PassContext context) {
        return promptBlock(context, context == null ? null : context.sourceText());
    }

    /** Renders only concepts grounded in the engine-fixed source window supplied for this pass. */
    static String promptBlock(PassContext context, String sourceWindow) {
        List<Facet> facets = from(context, sourceWindow);
        int supplied = context == null ? 0 : context.conceptHints().size();
        if (facets.isEmpty()) {
            return supplied == 0
                    ? "SOURCE-GROUNDED CONCEPT FACETS: none supplied."
                    : "SOURCE-GROUNDED CONCEPT FACETS: none of the supplied routing hints aligned "
                    + "to this exact source window; their text is withheld so it cannot become a fact.";
        }
        StringBuilder rendered = new StringBuilder(
                "SOURCE-GROUNDED CONCEPT FACETS (engine-aligned to exact text in this source window):\n");
        for (int index = 0; index < facets.size(); index++) {
            Facet facet = facets.get(index);
            rendered.append("- [").append(index + 1).append("] exactSourceSurface=<<<")
                    .append(bound(facet.exactSurface())).append(">>> | alignment=")
                    .append(facet.termAligned() ? "TERM" : "OBSERVED_CONTEXT");
            append(rendered, "normalizedHint", facet.hintTerm());
            append(rendered, "category", facet.category());
            append(rendered, "provenance", facet.provenance());
            rendered.append('\n');
        }
        int withheld = Math.max(0, supplied - facets.size());
        if (withheld > 0) {
            rendered.append("- ").append(withheld)
                    .append(" supplied hint(s) did not align to this source window and are withheld.\n");
        }
        rendered.append("These facets add no facts. normalizedHint, category, and provenance are ")
                .append("graph/pre-pass metadata, not source text. Interpret them using the supplied graph, schema, ")
                .append("and problem domain. Java has assigned no semantic role from category words. ")
                .append("alignment=TERM means normalizedHint itself aligned to exactSourceSurface; ")
                .append("alignment=OBSERVED_CONTEXT means only the hint's source context aligned.");
        return rendered.toString();
    }

    private static MatchSpan locate(String source, String hint) {
        if (source == null || source.isBlank() || hint == null || hint.isBlank()) {
            return null;
        }
        String needle = hint.strip();
        int direct = indexOfIgnoreCase(source, needle);
        if (direct >= 0) {
            return new MatchSpan(direct, direct + needle.length());
        }

        List<TokenSpan> sourceTokens = tokens(source);
        List<TokenSpan> hintTokens = tokens(needle);
        if (sourceTokens.isEmpty() || hintTokens.isEmpty() || hintTokens.size() > sourceTokens.size()) {
            return null;
        }
        for (int start = 0; start <= sourceTokens.size() - hintTokens.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < hintTokens.size(); offset++) {
                if (!sourceTokens.get(start + offset).normalized()
                        .equals(hintTokens.get(offset).normalized())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return new MatchSpan(sourceTokens.get(start).start(),
                        sourceTokens.get(start + hintTokens.size() - 1).end());
            }
        }
        return null;
    }

    private static List<TokenSpan> tokens(String value) {
        List<TokenSpan> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            result.add(new TokenSpan(matcher.start(), matcher.end(),
                    matcher.group().toLowerCase(Locale.ROOT)));
        }
        return result;
    }

    private static int indexOfIgnoreCase(String source, String needle) {
        for (int index = 0; index <= source.length() - needle.length(); index++) {
            if (source.regionMatches(true, index, needle, 0, needle.length())) {
                return index;
            }
        }
        return -1;
    }

    private static void append(StringBuilder target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.append(" | ").append(name).append('=').append(bound(value));
        }
    }

    private static String bound(String value) {
        String oneLine = value == null ? "" : value.strip().replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= MAX_SURFACE_CHARS
                ? oneLine : oneLine.substring(0, MAX_SURFACE_CHARS) + "…";
    }
}
