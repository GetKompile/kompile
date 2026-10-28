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

package ai.kompile.cli.main.chat.enforcer.deflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Curated deterministic corpus of deflection phrases (Tier 0), plus the two
 * false-positive guards that live at the text level: ownership-acknowledgment
 * language and quote/echo context.
 *
 * <p>A signal alone is never a verdict — {@link DeflectionDetector} combines it
 * with the receipts check. User-supplied extras and whitelist phrases come from
 * {@code EnforcerConfig} (project-specific phrases and exemptions).</p>
 */
public final class DeflectionPatternLibrary {

    /** Upper bound on signals collected from one output, so pathological text cannot spin us. */
    private static final int MAX_SIGNALS = 20;
    /** Matches per single pattern, so one broad regex cannot flood the list. */
    private static final int MAX_MATCHES_PER_PATTERN = 3;

    /** One compiled Tier-0 pattern with its canonical phrase and strength. */
    public record CompiledPattern(String phrase, Pattern pattern, double score) {
    }

    private final List<CompiledPattern> builtins;
    private final List<CompiledPattern> extras;
    private final List<CompiledPattern> whitelist;
    private final List<CompiledPattern> ownership;
    private final List<String> echoLineMarkers;

    private DeflectionPatternLibrary(List<CompiledPattern> builtins,
                                     List<CompiledPattern> extras,
                                     List<CompiledPattern> whitelist,
                                     List<CompiledPattern> ownership,
                                     List<String> echoLineMarkers) {
        this.builtins = List.copyOf(builtins);
        this.extras = List.copyOf(extras);
        this.whitelist = List.copyOf(whitelist);
        this.ownership = List.copyOf(ownership);
        this.echoLineMarkers = List.copyOf(echoLineMarkers);
    }

    // ── Construction ─────────────────────────────────────────────────────────

    public static DeflectionPatternLibrary create(List<String> extraPatterns,
                                                  List<String> whitelistPhrases) {
        List<String> safeExtras = extraPatterns == null ? List.of() : extraPatterns;
        List<String> safeWhitelist = whitelistPhrases == null ? List.of() : whitelistPhrases;
        return new DeflectionPatternLibrary(
                compileAll(BUILTIN_PATTERNS),
                compileLiteralAll(safeExtras, 0.9),
                compileLiteralAll(safeWhitelist, 1.0),
                compileAll(OWNERSHIP_PATTERNS),
                ECHO_LINE_MARKERS);
    }

    public static DeflectionPatternLibrary defaults() {
        return create(List.of(), List.of());
    }

    // ── Matching ─────────────────────────────────────────────────────────────

    /**
     * All Tier-0 signals in the output. Per-signal filtering: whitelisted spans are
     * dropped, and spans inside quotes/code fences/compiler-echo lines are dropped
     * (quoting a compiler or the user is not deflecting). Unquoted matches survive.
     */
    public List<DeflectionSignal> findSignals(String output) {
        List<DeflectionSignal> signals = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return signals;
        }
        for (CompiledPattern p : builtins) {
            collect(p, output, signals);
            if (signals.size() >= MAX_SIGNALS) {
                break;
            }
        }
        if (signals.size() < MAX_SIGNALS) {
            for (CompiledPattern p : extras) {
                collect(p, output, signals);
                if (signals.size() >= MAX_SIGNALS) {
                    break;
                }
            }
        }
        return signals;
    }

    /** True when the output acknowledges ownership — suppresses the deflection verdict. */
    public boolean hasOwnershipLanguage(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        for (CompiledPattern p : ownership) {
            if (p.pattern().matcher(output).find()) {
                return true;
            }
        }
        return false;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void collect(CompiledPattern p, String output, List<DeflectionSignal> out) {
        Matcher m = p.pattern().matcher(output);
        int found = 0;
        while (m.find() && found < MAX_MATCHES_PER_PATTERN) {
            String matched = m.group();
            if (isWhitelisted(output, m.start(), matched)) {
                continue;
            }
            if (isQuotedContext(output, m.start())) {
                continue;
            }
            out.add(new DeflectionSignal(DeflectionSignal.TIER_PATTERN,
                    p.phrase(), matched, p.score()));
            found++;
        }
    }

    /** Whitelist phrase appearing inside the matched span exempts that span. */
    private boolean isWhitelisted(String output, int startIdx, String matched) {
        if (whitelist.isEmpty()) {
            return false;
        }
        String span = matched.toLowerCase(Locale.ROOT);
        for (CompiledPattern p : whitelist) {
            if (p.pattern().matcher(span).find()) {
                return true;
            }
        }
        // Also exempt when the whitelist phrase sits immediately before the span
        // (e.g. a module named in the whitelist being discussed).
        int windowStart = Math.max(0, startIdx - 40);
        String window = output.substring(windowStart, startIdx).toLowerCase(Locale.ROOT);
        for (CompiledPattern p : whitelist) {
            if (p.pattern().matcher(window).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quote/echo context detection for the span starting at {@code startIdx}:
     * inside a ``` fence, inside an inline `code` span, on a compiler/test-output
     * echo line, or inside a block quote. Unknown positions fail open (treated quoted).
     */
    private boolean isQuotedContext(String output, int startIdx) {
        if (startIdx < 0 || startIdx > output.length()) {
            return true;
        }
        String before = output.substring(0, startIdx);
        long fences = countOccurrences(before, "```");
        if (fences % 2 == 1) {
            return true;
        }
        String noFences = before.replace("```", "");
        if (countChar(noFences, '`') % 2 == 1) {
            return true;
        }
        int lineStart = before.lastIndexOf('\n') + 1;
        String linePrefix = before.substring(lineStart).toLowerCase(Locale.ROOT).trim();
        if (linePrefix.startsWith(">")) {
            return true;
        }
        for (String marker : echoLineMarkers) {
            if (linePrefix.startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    private static long countOccurrences(String s, String needle) {
        if (s.isEmpty()) {
            return 0;
        }
        long n = 0;
        int idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    private static long countChar(String s, char c) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static List<CompiledPattern> compileAll(List<String[]> specs) {
        List<CompiledPattern> out = new ArrayList<>();
        for (String[] spec : specs) {
            addCompiled(out, spec[0], Pattern.compile(spec[1],
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                    Double.parseDouble(spec[2]));
        }
        return out;
    }

    private static List<CompiledPattern> compileLiteralAll(List<String> phrases, double score) {
        List<CompiledPattern> out = new ArrayList<>();
        for (String phrase : phrases) {
            if (phrase == null || phrase.isBlank()) {
                continue;
            }
            try {
                addCompiled(out, phrase, Pattern.compile(Pattern.quote(phrase),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), score);
            } catch (PatternSyntaxException ignored) {
                // A bad user pattern must never break enforcement; skip it.
            }
        }
        return out;
    }

    private static void addCompiled(List<CompiledPattern> out, String phrase,
                                    Pattern pattern, double score) {
        out.add(new CompiledPattern(phrase, pattern, score));
    }

    // ── Builtin corpus ───────────────────────────────────────────────────────

    /** phrase, regex, score — curated, high-precision. */
    private static final List<String[]> BUILTIN_PATTERNS = List.of(
            new String[]{"not my error",
                    "\\bnot (?:my|our) (?:error|problem|bug|issue|fault|responsibility|concern)\\b", "0.9"},
            new String[]{"not mine",
                    "\\bnot mine\\b", "0.9"},
            new String[]{"won't fix",
                    "\\b(?:i'?m|we'?re)? ?won'?t (?:be )?fix(?:ing|) (?:this|that|it)\\b", "0.9"},
            new String[]{"out of scope",
                    "\\bout of (?:my |our |the )?scope\\b", "0.9"},
            new String[]{"outside my area",
                    "\\boutside (?:my|our) (?:area|scope|remit|domain)\\b", "0.9"},
            new String[]{"pre-existing",
                    "\\bpre-?existing\\b", "0.85"},
            new String[]{"upstream problem",
                    "\\bupstream (?:problem|issue|bug|regression)\\b", "0.85"},
            new String[]{"environment issue",
                    "\\b(?:environment|infrastructure|platform|setup|tooling) (?:issue|problem)\\b", "0.8"},
            new String[]{"works on my machine",
                    "\\bworks on my machine\\b", "0.9"},
            new String[]{"as designed",
                    "\\b(?:is|are|was|were|it'?s|that'?s) (?:working )?as (?:designed|intended)\\b", "0.8"},
            new String[]{"beyond my capabilities",
                    "\\bbeyond (?:my|our) (?:capabilities|control|ability)\\b", "0.9"},
            new String[]{"that's X's bug",
                    "\\bthat'?s [\\w .-]{0,30}(?:'s )?(?:bug|problem|issue)\\b", "0.8"},
            new String[]{"someone else's code",
                    "\\bsomeone else'?s (?:bug|problem|issue|code|module)\\b", "0.9"},
            new String[]{"another team's module",
                    "\\banother (?:team|team'?s|group|group'?s) (?:module|component|code|area)\\b", "0.85"},
            new String[]{"not responsible",
                    "\\b(?:i'?m|we'?re|am|are) not responsible for\\b", "0.9"},
            new String[]{"nothing I can do",
                    "\\bnothing (?:i|we) can do\\b", "0.9"},
            new String[]{"can't help with that",
                    "\\b(?:i|we) can'?t help with (?:that|this|it)\\b", "0.85"},
            new String[]{"can't reproduce",
                    "\\b(?:i|we) can'?t reproduce\\b", "0.75"}
    );

    /** Ownership acknowledgment language — suppresses the verdict entirely. */
    private static final List<String[]> OWNERSHIP_PATTERNS = List.of(
            new String[]{"i broke this",
                    "\\b(?:i|we) (?:broke|introduced|caused|regressed) (?:this|that|it|the)\\b", "1.0"},
            new String[]{"my mistake",
                    "\\bmy (?:mistake|error|bug|fault|regression)\\b", "1.0"},
            new String[]{"i own this",
                    "\\b(?:i|we) (?:own|take ownership of|take responsibility for) (?:this|that|it|the)\\b", "1.0"},
            new String[]{"i fixed my regression",
                    "\\b(?:i|we) (?:have )?fixed? (?:my|our) (?:bug|mistake|regression)\\b", "1.0"},
            new String[]{"you're right, it's mine",
                    "\\byou'?re right,? (?:that|this|it)'?s mine\\b", "1.0"},
            new String[]{"this was my change",
                    "\\bthis (?:was|is) (?:my|our) (?:change|regression|bug|mistake)\\b", "1.0"}
    );

    /** Compiler/test-output line prefixes — a match on such a line is an echo, not a claim. */
    private static final List<String> ECHO_LINE_MARKERS = List.of(
            "error:", "warning:", "note:", "fatal:", "exception", "caused by:",
            "expected:", "actual:", "assert", "at ", "junit", "failure", "failures:",
            "tests run:", "[error]", "[warning]"
    );
}
