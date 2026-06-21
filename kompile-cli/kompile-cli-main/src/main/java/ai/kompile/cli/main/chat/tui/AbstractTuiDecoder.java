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

package ai.kompile.cli.main.chat.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared base for decoder-owned agent TUIs (OpenCode, Claude Code, Codex, ...).
 * <p>
 * Concrete decoders only declare what is unique to their layout: the agent name,
 * which rows are chrome ({@link #isChrome(String)}), and responding/idle markers.
 * Everything structural — extracting content from the VT, stripping box borders,
 * detecting separators / decorative art / status bars, and answering terminal
 * queries — lives here so it stays consistent across agents.
 * <p>
 * Rendering model: these decoders return {@code renderRawTui() == false}. Kompile
 * shadows the agent's alternate screen in a {@link VirtualTerminal}, answers its
 * terminal probes via {@link #buildResponses}, and renders only the extracted
 * assistant text into the Kompile scroll area (which is scrollable to the top).
 */
public abstract class AbstractTuiDecoder implements AgentTuiDecoder {

    protected static final int MIN_CONTENT_LENGTH = 2;

    // Matches a trailing version token: full semver (1.14.48) or a v-prefixed
    // version (v2.1). A bare two-part number like "2.5" is intentionally NOT a
    // match, so prose ending in a decimal is not mistaken for a status bar.
    private static final Pattern TRAILING_VERSION =
            Pattern.compile(".*?\\b(?:v\\d+\\.\\d+(?:\\.\\d+)?|\\d+\\.\\d+\\.\\d+)\\)?\\s*$");

    /** Decoder-owned agents render extracted text, not raw PTY bytes. */
    @Override
    public boolean renderRawTui() {
        return false;
    }

    @Override
    public int[] contentRowRange(int totalRows) {
        return new int[]{0, Math.max(0, totalRows - 1)};
    }

    @Override
    public String extractStreamingContent(VirtualTerminal vt) {
        return extractContent(vt);
    }

    @Override
    public String extractContent(VirtualTerminal vt) {
        if (vt == null) return "";
        int[] range = contentRowRange(vt.getRows());
        StringBuilder sb = new StringBuilder();
        for (int r = range[0]; r <= range[1]; r++) {
            String row = normalizeRow(stripBoxBorders(vt.getRow(r)));
            if (!isResponseRow(row)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(row);
        }
        return sb.toString();
    }

    /**
     * A row is response content when it survives the shared structural filters
     * (separators, decorative art) and the agent-specific {@link #isChrome}.
     */
    protected boolean isResponseRow(String row) {
        if (row == null || row.length() < MIN_CONTENT_LENGTH) return false;
        if (!containsContentCharacter(row)) return false;
        if (isSeparatorOrBorder(row)) return false;
        if (containsMostlyDecorative(row)) return false;
        if (hasInternalBoxRule(row)) return false;   // mid-redraw jumble: a rule bled into text
        if (isProgressLine(row)) return false;   // transient spinners are never content
        return !isChrome(row);
    }

    /**
     * Agent-specific chrome test. Receives a row that has already had box borders
     * stripped and whitespace normalized. Return true to drop the row.
     */
    protected abstract boolean isChrome(String normalizedRow);

    // ------------------------------------------------------------------
    // Message history (transcript reconstruction across fixed-viewport snapshots)
    // ------------------------------------------------------------------

    private final List<HistoryEntry> history = new ArrayList<>();
    private volatile String currentProgress = "";

    /** Cap how far back we search for a scroll alignment (bounds cost). */
    private static final int ALIGN_LOOKBACK = 400;

    @Override
    public void resetHistory() {
        synchronized (history) {
            history.clear();
            currentProgress = "";
        }
    }

    @Override
    public java.util.List<HistoryEntry> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    @Override
    public String currentProgress() {
        return currentProgress;
    }

    @Override
    public void observe(VirtualTerminal vt) {
        if (vt == null) return;
        List<HistoryEntry> visible = new ArrayList<>();
        String progress = "";
        int[] range = contentRowRange(vt.getRows());
        for (int r = range[0]; r <= range[1]; r++) {
            String row = normalizeRow(stripBoxBorders(vt.getRow(r)));
            if (row.isEmpty()) continue;
            TuiLineKind kind = classify(row);
            if (kind == TuiLineKind.PROGRESS) {
                progress = row;
                continue;
            }
            if (kind == TuiLineKind.CHROME) continue;
            // Preserve the agent's own foreground colour / attributes for prose;
            // tool lines are re-rendered as panels so their plain text suffices.
            String styled = (kind == TuiLineKind.CONTENT) ? vt.getStyledRow(r) : row;
            if (styled == null || styled.isBlank()) styled = row;
            visible.add(new HistoryEntry(kind, row, styled));
        }
        synchronized (history) {
            currentProgress = progress;
            alignAndMerge(visible);
        }
    }

    /**
     * Merge the currently-visible content/tool lines into the transcript.
     * <p>
     * The agent's screen is a sliding window that only appends new lines at the
     * bottom, grows its last line in place, and scrolls old lines off the top.
     * We find where the visible window aligns against the tail of history
     * (allowing the last matched line to have grown), then append the rest.
     */
    private void alignAndMerge(List<HistoryEntry> visible) {
        if (visible.isEmpty()) return;
        if (history.isEmpty()) {
            history.addAll(visible);
            return;
        }
        // The visible window is the agent's CURRENT screen — a re-rendered view of the
        // conversation tail. Find the anchor: the most-recent position p where history's
        // tail history[p..end] is FULLY reproduced by the head of the visible window (the
        // live region the agent re-rendered; the boundary line may have grown). Requiring
        // the WHOLE tail to match — not just the longest coincidental run — prevents
        // chopping committed history on a stray early match (which dropped text).
        int hSize = history.size();
        int low = Math.max(0, hSize - ALIGN_LOOKBACK);
        int anchor = -1;
        for (int p = hSize - 1; p >= low; p--) {
            int n = hSize - p;
            if (n > visible.size()) continue;          // tail longer than the screen — not a full match
            boolean full = true;
            for (int i = 0; i < n; i++) {
                String h = history.get(p + i).text();
                String v = visible.get(i).text();
                if (h.equals(v)) continue;
                if (i == n - 1 && !h.isBlank() && v.startsWith(h)) continue;  // boundary line grew
                full = false;
                break;
            }
            if (full) { anchor = p; break; }           // smallest n = most-recent anchor
        }
        if (anchor >= 0) {
            // The live tail re-rendered (grew / scrolled / expanded). Replace it with the
            // current screen; committed history above the anchor is preserved.
            while (history.size() > anchor) history.remove(history.size() - 1);
            history.addAll(visible);
            return;
        }
        // No full-tail anchor — a collapsed or re-laid-out screen (e.g. a global
        // detailed-transcript toggle). NEVER clear committed history (that loses the
        // user's text). Append only lines the screen adds that aren't already in the
        // recent tail: a collapse adds nothing, a detail toggle adds only its new lines.
        appendNovelTail(visible);
    }

    /**
     * Append only the entries of {@code visible} whose text isn't already present in the
     * recent tail of history — so a re-laid-out / collapsed screen neither duplicates the
     * transcript nor drops committed lines.
     */
    private void appendNovelTail(List<HistoryEntry> visible) {
        int from = Math.max(0, history.size() - ALIGN_LOOKBACK);
        java.util.Set<String> recent = new java.util.HashSet<>();
        for (int i = from; i < history.size(); i++) recent.add(history.get(i).text());
        for (HistoryEntry e : visible) {
            if (recent.add(e.text())) history.add(e);   // add() is false when already present
        }
    }

    /**
     * Render the transcript to text the markdown renderer can format. Prose is
     * emitted as-is (markdown is applied downstream); consecutive detected tool
     * lines are grouped into {@code [tool:Name]…[/tool]} / {@code [tool-result]}
     * blocks, which the renderer turns into bordered panels.
     */
    @Override
    public String renderHistory() {
        synchronized (history) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < history.size()) {
                HistoryEntry e = history.get(i);
                if (e.kind() != TuiLineKind.TOOL) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(e.styled());   // prose carries the agent's ANSI styling
                    i++;
                    continue;
                }
                int j = i;
                List<String> group = new ArrayList<>();
                group.add(history.get(j).text());   // the tool-call header
                j++;
                // Only following DETAIL lines (tree branches, results) join this call; a NEW tool
                // HEADER (● Name(...)) starts its OWN panel instead of merging into this one.
                while (j < history.size() && history.get(j).kind() == TuiLineKind.TOOL
                        && !isToolHeader(history.get(j).text())) {
                    group.add(history.get(j).text());
                    j++;
                }
                appendToolMarkup(sb, group);
                i = j;
            }
            return sb.toString();
        }
    }

    /** Emit a detected tool-line group as markup the markdown renderer formats into a panel. */
    private void appendToolMarkup(StringBuilder sb, List<String> group) {
        if (group.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');

        // kompile-injected tool results render as a result panel.
        if (group.get(0).strip().startsWith("[kompile]")) {
            sb.append("[tool-result]\n");
            for (int k = 0; k < group.size(); k++) {
                if (k > 0) sb.append('\n');
                sb.append(group.get(k).strip());
            }
            sb.append("\n[/tool-result]");
            return;
        }

        String first = stripToolMarker(group.get(0));
        String name = firstWordOrDefault(first, "tool");
        StringBuilder body = new StringBuilder();
        String firstRest = first.length() > name.length() ? first.substring(name.length()).strip() : "";
        if (!firstRest.isEmpty()) body.append(firstRest);
        for (int k = 1; k < group.size(); k++) {
            String detail = stripToolMarker(group.get(k));
            if (detail.isEmpty()) continue;
            if (body.length() > 0) body.append('\n');
            body.append(detail);
        }
        sb.append("[tool:").append(name).append("]\n");
        if (body.length() > 0) sb.append(body).append('\n');
        sb.append("[/tool]");
    }

    private String stripToolMarker(String line) {
        if (line == null) return "";
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t' || c == '•' || c == '└' || c == '├' || c == '╰'
                    || c == '┗' || c == '┣' || c == '→' || c == '↳' || c == '⮕'
                    || c == '●' || c == '◦' || c == '·') {
                i++;
                continue;
            }
            break;
        }
        return line.substring(i).strip();
    }

    private String firstWordOrDefault(String s, String dflt) {
        int start = 0;
        while (start < s.length() && !isWordChar(s.charAt(start))) start++;
        int end = start;
        while (end < s.length() && isWordChar(s.charAt(end))) end++;
        return end > start ? s.substring(start, end) : dflt;
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    @Override
    public TuiLineKind classify(String row) {
        if (row == null) return TuiLineKind.CHROME;
        String r = row.strip();
        if (r.length() < MIN_CONTENT_LENGTH || !containsContentCharacter(r)) return TuiLineKind.CHROME;
        if (isSeparatorOrBorder(r) || containsMostlyDecorative(r) || hasInternalBoxRule(r)) return TuiLineKind.CHROME;
        if (isProgressLine(r)) return TuiLineKind.PROGRESS;
        if (isChrome(r)) return TuiLineKind.CHROME;
        if (isToolLine(r)) return TuiLineKind.TOOL;
        return TuiLineKind.CONTENT;
    }

    /** Transient progress/spinner line (excluded from the transcript). */
    protected boolean isProgressLine(String row) {
        String lower = row.toLowerCase(Locale.ROOT);
        if (lower.contains("esc to interrupt") || lower.contains("esc to cancel")
                || lower.contains("esc interrupt") || lower.contains("ctrl+c to interrupt")
                || lower.contains("thinking with max effort")) {
            return true;
        }
        String text = stripLeadingChromeGlyphs(lower);
        if ((text.startsWith("working") || text.startsWith("thinking") || text.startsWith("generating")
                || text.startsWith("loading") || text.startsWith("processing") || text.startsWith("compacting")
                || text.startsWith("waiting") || text.startsWith("running") && text.contains("("))
                && (text.contains("(") || hasBrailleSpinner(row))) {
            return true;
        }
        if (hasBrailleSpinner(row) && row.strip().length() <= 24) return true;
        // Claude's thinking spinner leads with a rotating star/asterisk dingbat (✻ ✶ ✦ ✤),
        // and mid-redraw frames merge it with a horizontal rule and a cycling word fragment
        // ("✻─Flam─", "·─Flambéing…─"). A star-dingbat lead is ALWAYS the spinner; a middle-dot/
        // bullet lead combined with a box rule is the jumbled spinner. Treat both — plus any
        // "(Ns" elapsed timer — as transient progress so the cycling words never leak.
        // Claude's spinner cycles asterisk/star glyphs across several Unicode blocks and,
        // mid-redraw, merges with box rules + a token counter ("✶─Mustering…──3──↑─450──").
        // Detect it MULTI-SIGNAL so it survives the exact glyph: a star/asterisk lead, an
        // elapsed "(Ns" timer, a "↑N" token counter (which the jumbled frames carry), or —
        // after stripping leading symbols/rules — a lone whimsical word ending in an ellipsis.
        char lead = firstVisibleChar(row);
        // True star/asterisk dingbats (U+2720–U+274F, ∗, ⁕) are NEVER prose leads —
        // treat them as spinner unconditionally.  The middle dot (·) is ambiguous: it
        // is also used as a bullet prefix in content ("· item").  Guard it with the
        // same short-length threshold used for braille spinners, or allow it only when
        // it is immediately followed by a box rule (mid-redraw merged frame).
        if (lead != '·' && isStarAsteriskGlyph(lead)) return true;
        if (lead == '·' && (row.strip().length() <= 24 || containsRuleChar(row))) return true;
        if (hasElapsedTimer(text) || hasTokenCounter(row)) return true;
        // A lone whimsical gerund word optionally wrapped in box rules/symbols
        // ("─Flambéing…─", "✻─Mustering…──", "Herding...", "Sautéing…").
        // Strip leading non-alphanumeric chars from the whole row, isolate the
        // first space-delimited token, then strip trailing non-letter chars
        // (box rules, dashes, dots — everything except the "…"/"..." marker)
        // from that token.  Match when the bare word consists entirely of
        // Unicode letters (handles accented: é, ö, …) and an ellipsis is
        // present anywhere in the original token.
        String core = stripLeadingSymbolsAndRules(row).strip();
        int sp = core.indexOf(' ');
        String firstToken = sp < 0 ? core : core.substring(0, sp);
        // Check for ellipsis in the raw token BEFORE stripping trailing symbols.
        boolean hasEllipsis = firstToken.contains("…") || firstToken.contains("...");
        // Strip trailing non-letter chars to get the bare word.
        String bareWord = stripTrailingNonLetters(firstToken);
        if (hasEllipsis && !bareWord.isEmpty() && isAllLetters(bareWord)) {
            return true;
        }
        return false;
    }

    /**
     * Claude's spinner glyph family.
     *
     * Covers every glyph observed in Claude Code's cycling spinner and their
     * common mid-redraw aliases:
     *   · U+00B7  MIDDLE DOT         — appears as the "·" frame in the cycle
     *   ∗ U+2217  ASTERISK OPERATOR  — Math-Operators asterisk (explicit)
     *   ⁕ U+2055  FLOWER PUNCTUATION
     *   ✱ U+2731  HEAVY ASTERISK     — explicit, also falls in the range below
     *   U+2720–U+274F Dingbats stars/asterisks (✠ ✢ ✣ ✤ ✥ ✦ ✧ ✩ ✪ ✫ ✬ ✭ ✮ ✯
     *             ✰ ✱ ✲ ✳ ✴ ✵ ✶ ✷ ✸ ✹ ✺ ✻ ✼ ✽ ✾ ✿ ❀ ❁ ❂ ❃ ❄ ❅ ❆ ❇ ❈ ❉ ❊ ❋ ❏)
     *
     * Deliberately excluded: ✅ (U+2705), ⚠ (U+26A0), → (U+2192) and other
     * indicator/arrow symbols outside the star/asterisk blocks.
     */
    private boolean isStarAsteriskGlyph(char c) {
        return c == '·'                // · MIDDLE DOT (spinner frame)
                || c == '∗'           // ∗ ASTERISK OPERATOR
                || c == '⁕'           // ⁕ FLOWER PUNCTUATION MARK
                || (c >= '✠' && c <= '❏');  // Dingbats star/asterisk block
    }

    /**
     * A token-counter annotation as shown in Claude's spinner footer.
     *
     * Handles three observed variants:
     *  1. "↑N" / "↓N"   — arrow immediately followed (possibly through box rules)
     *                       by one or more digits; also catches jumbled frames like
     *                       "↑─450──" where box rules separate arrow from digits.
     *  2. "N tokens"     — a plain digit run followed by the word "tokens" (case-insensitive).
     *  3. "N.Nk tokens"  — same with a decimal+k suffix (e.g. "1.4k tokens").
     */
    private boolean hasTokenCounter(String row) {
        // Variant 1: ↑/↓ arrow present anywhere + at least one digit in the row.
        if ((row.indexOf('↑') >= 0 || row.indexOf('↓') >= 0)) {
            for (int i = 0; i < row.length(); i++) {
                if (Character.isDigit(row.charAt(i))) return true;
            }
        }
        // Variants 2 & 3: "N tokens" or "N.Nk tokens".
        String lower = row.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("tokens");
        if (idx > 0) {
            // Walk backward past spaces/box rules to find digit(s).
            int j = idx - 1;
            while (j >= 0 && (row.charAt(j) == ' ' || isBoxHorizontalRule(row.charAt(j))
                    || row.charAt(j) == 'k' || row.charAt(j) == '.')) j--;
            if (j >= 0 && Character.isDigit(row.charAt(j))) return true;
        }
        return false;
    }

    private boolean isAllLetters(String w) {
        if (w.isEmpty()) return false;
        for (int i = 0; i < w.length(); i++) if (!Character.isLetter(w.charAt(i))) return false;
        return true;
    }

    /** Drop leading non-alphanumeric glyphs (spinner stars, box rules, spaces). */
    private String stripLeadingSymbolsAndRules(String row) {
        int i = 0;
        while (i < row.length() && !Character.isLetterOrDigit(row.charAt(i))) i++;
        return row.substring(i);
    }

    /**
     * Drop trailing non-letter chars (box rules, dashes, digits, punctuation)
     * from a token, returning just the leading letter run.  Used to recover a
     * bare word from a spinner token like "Flambéing…──3──".
     */
    private String stripTrailingNonLetters(String token) {
        int end = token.length();
        while (end > 0 && !Character.isLetter(token.charAt(end - 1))) end--;
        return token.substring(0, end);
    }

    /**
     * A horizontal-line glyph jammed against a letter (no separating space) means the frame
     * was captured mid-redraw — Claude's rule/dash bled into a text line ("l─st", "──read──read").
     * Two glyph classes, two thresholds, so prose is spared:
     *  - BOX-drawing rules (─ ━ ═ …) never occur inside prose → a single jammed one is a jumble.
     *  - LONG dashes (— – ―) are Claude's own separator ("read — read files", SPACED → fine);
     *    only when ≥2 are jammed against letters (the mid-redraw duplication) is it a jumble,
     *    so legit spaced dashes and a lone "word—word" are NOT matched.
     * Tree/tool markers ("└─ List" — a space follows the rule) are spared either way.
     */
    private boolean hasInternalBoxRule(String row) {
        int jammedDashes = 0;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            boolean box = isBoxHorizontalRule(c);
            boolean dash = isLongDash(c);
            if (!box && !dash) continue;
            boolean letterBefore = i > 0 && Character.isLetter(row.charAt(i - 1));
            boolean letterAfter = i + 1 < row.length() && Character.isLetter(row.charAt(i + 1));
            if (!letterBefore && !letterAfter) continue;   // spaced = prose, not a jumble
            if (box) return true;                          // box rule jammed in text = definite jumble
            jammedDashes++;
        }
        return jammedDashes >= 2;                           // ≥2 jammed long-dashes = mid-redraw jumble
    }

    /** Box-drawing horizontal rules — never appear inside prose. */
    private boolean isBoxHorizontalRule(char c) {
        return c == '─' || c == '━' || c == '═' || c == '┄' || c == '┅'
                || c == '┈' || c == '┉' || c == '╌' || c == '╍';
    }

    /** Long dashes (figure/en/em/horizontal-bar/minus) — Claude's separator; ASCII hyphen excluded. */
    private boolean isLongDash(char c) {
        return c == '‒' || c == '–' || c == '—' || c == '―' || c == '−';
    }

    private char firstVisibleChar(String row) {
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (!Character.isWhitespace(c)) return c;
        }
        return ' ';
    }

    private boolean containsRuleChar(String row) {
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c >= '─' && c <= '╿') return true;
        }
        return false;
    }

    /** First visible glyph is a Dingbats star/asterisk (U+2720–U+274F) — Claude's spinner family. */
    private boolean leadsWithStarSpinner(String row) {
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return c >= '✠' && c <= '❏';
        }
        return false;
    }

    /** True when the text carries an elapsed-time counter like "(2s", "(13s", "(1m". */
    private boolean hasElapsedTimer(String text) {
        int p = text.indexOf('(');
        while (p >= 0 && p + 1 < text.length()) {
            int q = p + 1;
            while (q < text.length() && Character.isDigit(text.charAt(q))) q++;
            if (q > p + 1 && q < text.length() && (text.charAt(q) == 's' || text.charAt(q) == 'm')) {
                return true;
            }
            p = text.indexOf('(', p + 1);
        }
        return false;
    }

    private boolean hasBrailleSpinner(String row) {
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c >= '⠀' && c <= '⣿') return true;
        }
        return false;
    }

    /** Tool-call header or detail/result line. Decoders override to add specifics. */
    protected boolean isToolLine(String row) {
        String trimmed = row.strip();
        if (trimmed.isEmpty()) return false;
        char c0 = trimmed.charAt(0);
        // Tree-branch detail (└ ├ │ ╰) or action arrows (→ ↳ ⮕).
        if (c0 == '└' || c0 == '├' || c0 == '│' || c0 == '╰'
                || c0 == '→' || c0 == '↳' || c0 == '⮕') {
            return true;
        }
        // kompile-injected MCP tool result lines.
        if (trimmed.startsWith("[kompile]")) return true;
        // A TOOL bullet (● ⏺ •) followed by a tool verb — e.g. "● Read(...)", "• Explored …".
        // A BARE verb, a markdown bullet ("- read — desc"), a table cell ("│ read │ …", whose
        // leading │ is stripped upstream to "read │ …"), or plain prose ("read the file") is
        // CONTENT — matching those produced false/mangled tool panels. Require the tool bullet so
        // only genuine tool-call lines become panels.
        if (c0 == '●' || c0 == '⏺' || c0 == '•') {
            String text = stripLeadingChromeGlyphs(trimmed.toLowerCase(Locale.ROOT));
            // A tool invocation is "Name(args)" — ANY tool name (Bash, Grep, Read …), not just the
            // past-tense verbs in TOOL_VERBS — or a bare past-tense verb ("• Explored …").
            return looksLikeToolInvocation(text) || startsWithToolVerb(text);
        }
        return false;
    }

    /** "name(" — a word immediately followed by '(', i.e. a tool invocation like {@code Bash(...)}. */
    private boolean looksLikeToolInvocation(String text) {
        int i = 0;
        while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) i++;
        return i > 0 && i < text.length() && text.charAt(i) == '(';
    }

    /** A tool-call HEADER ("● Name(...)", "• Explored …", "[kompile] …") — starts a NEW tool
     *  panel. Detail lines (tree branches └ ├) continue the current call's panel. */
    private boolean isToolHeader(String row) {
        String t = row.strip();
        if (t.isEmpty()) return false;
        char c0 = t.charAt(0);
        return c0 == '●' || c0 == '⏺' || c0 == '•' || t.startsWith("[kompile]");
    }

    private boolean startsWithToolVerb(String text) {
        for (String verb : TOOL_VERBS) {
            if (text.equals(verb) || text.startsWith(verb + " ") || text.startsWith(verb + "(")) {
                // "verb — description" (an em-dash list separator) is a CONTENT description, e.g.
                // Claude's MCP tools list "read — read files" — NOT a tool call. A real tool line
                // is "verb <path/args>", so a following em-dash means this is prose, not a call.
                String rest = text.length() > verb.length() ? text.substring(verb.length()).strip() : "";
                if (rest.startsWith("—") || rest.startsWith("–")) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    private static final String[] TOOL_VERBS = {
            "explored", "exploring", "ran", "running", "read", "reading", "wrote", "writing",
            "edited", "editing", "searched", "searching", "listed", "listing", "fetched",
            "fetching", "created", "creating", "updated", "updating", "deleted", "deleting",
            "applied", "patched", "patching", "grepped", "globbed", "viewed", "viewing",
            "executed", "executing", "calling", "called", "invoked", "invoking"
    };

    // ------------------------------------------------------------------
    // Shared text helpers
    // ------------------------------------------------------------------

    protected String normalizeRow(String row) {
        if (row == null) return "";
        String normalized = row
                .replace(' ', ' ')
                .replace('​', ' ')
                .replace('‌', ' ')
                .replace('‍', ' ')
                .trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized;
    }

    /**
     * Strip leading/trailing frame box-drawing characters and spaces, while
     * preserving a leading tree-branch marker (└ ├ ╰ ┗ ┣) so tool detail lines
     * like "└ List ls -1" keep their classifiable prefix.
     */
    protected String stripBoxBorders(String row) {
        if (row == null) return "";
        int start = 0;
        int end = row.length();
        while (start < end) {
            char c = row.charAt(start);
            if (isLeadingBranchMarker(c)) break;
            if (!isBoxOrSpace(c)) break;
            start++;
        }
        while (end > start && isBoxOrSpace(row.charAt(end - 1))) end--;
        return row.substring(start, end);
    }

    private boolean isBoxOrSpace(char c) {
        return Character.isWhitespace(c) || (c >= '─' && c <= '╿');
    }

    private boolean isLeadingBranchMarker(char c) {
        return c == '└' || c == '├' || c == '╰' || c == '┗' || c == '┣';
    }

    protected boolean containsContentCharacter(String row) {
        for (int i = 0; i < row.length(); i++) {
            if (Character.isLetterOrDigit(row.charAt(i))) return true;
        }
        return false;
    }

    /** Drop leading non-text glyphs (bullets, arrows, logo art) up to the first word char. */
    protected String stripLeadingChromeGlyphs(String lower) {
        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '/' || c == '?') break;
            i++;
        }
        return lower.substring(i).stripLeading();
    }

    /** True when the row begins with block/quadrant art (e.g. a CLI logo banner). */
    protected boolean startsWithBlockArt(String row) {
        if (row == null) return false;
        int seen = 0;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (Character.isWhitespace(c)) {
                if (seen > 0) break;
                continue;
            }
            if (isBlockChar(c) || (c >= '─' && c <= '╿')) {
                seen++;
                if (seen >= 2) return true;
            } else {
                break;
            }
        }
        return false;
    }

    protected boolean isSeparatorOrBorder(String row) {
        int decorative = 0;
        int total = 0;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (isBorderChar(c)) decorative++;
        }
        return total > 0 && decorative >= Math.max(4, total * 7 / 10);
    }

    protected boolean containsMostlyDecorative(String row) {
        int decorative = 0;
        int total = 0;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (isBorderChar(c) || isBlockChar(c) || c == '·' || c == '•') decorative++;
        }
        return total >= 4 && decorative >= total * 3 / 5;
    }

    protected boolean isBorderChar(char c) {
        return c == '-' || c == '_' || c == '='
                || (c >= '─' && c <= '╿');
    }

    protected boolean isBlockChar(char c) {
        return (c >= '▀' && c <= '▟')
                || (c >= '⠀' && c <= '⣿');
    }

    /** True when the row ends with a version-like token (e.g. "1.14.48", "v2.1.0"). */
    protected boolean endsWithVersionToken(String row) {
        if (row == null || row.isEmpty()) return false;
        return TRAILING_VERSION.matcher(row).matches();
    }

    /** True when the row uses a middle-dot separator (common in agent status bars). */
    protected boolean hasBulletSeparator(String row) {
        return row != null && (row.contains(" · ") || row.indexOf('·') >= 0);
    }

    protected boolean looksLikePath(String row) {
        if (row == null) return false;
        String t = row.trim();
        if (t.isEmpty() || t.indexOf(' ') >= 0) return false;
        return t.startsWith("/") || t.startsWith("~/") || t.startsWith("./");
    }

    protected boolean containsModelName(String lower) {
        return lower.contains("deepseek")
                || lower.contains("claude")
                || lower.contains("gpt")
                || lower.contains("gemini")
                || lower.contains("qwen")
                || lower.contains("kimi")
                || lower.contains("sonnet")
                || lower.contains("opus")
                || lower.contains("o3")
                || lower.contains("o4");
    }

    protected String screen(VirtualTerminal vt) {
        return vt == null ? "" : vt.getFullScreen().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Terminal query responses
    // ------------------------------------------------------------------

    /**
     * Whether to answer the Kitty keyboard protocol query (ESC[?u). Most TUIs are
     * fine receiving "not supported" (ESC[?0u); Claude Code mis-handles it and
     * launches nano, so it overrides this to false.
     */
    protected boolean answersKittyKeyboard() {
        return true;
    }

    @Override
    public String buildResponses(String rawChunk, VirtualTerminal vt) {
        if (rawChunk == null || rawChunk.isEmpty()) return "";
        StringBuilder response = new StringBuilder();

        if (rawChunk.contains("\033[6n")) {
            response.append("\033[")
                    .append(clamp(vt.getCursorRow() + 1, 1, vt.getRows()))
                    .append(';')
                    .append(clamp(vt.getCursorCol() + 1, 1, vt.getCols()))
                    .append('R');
        }
        if (rawChunk.contains("\033[5n")) {
            response.append("\033[0n");
        }
        if (rawChunk.contains("\033[c") || rawChunk.contains("\033[0c")) {
            response.append("\033[?62;22c");
        }
        if (rawChunk.contains("\033[>c") || rawChunk.contains("\033[>0c")) {
            response.append("\033[>0;0;0c");
        }
        if (answersKittyKeyboard() && rawChunk.contains("\033[?u")) {
            response.append("\033[?0u");
        }
        // XTVERSION (ESC[>q) — report a neutral terminal identity.
        if (rawChunk.contains("\033[>q")) {
            response.append("\033P>|kompile\033\\");
        }
        if (rawChunk.contains("\033[18t")) {
            response.append("\033[8;").append(vt.getRows()).append(';').append(vt.getCols()).append('t');
        }
        if (rawChunk.contains("\033[14t")) {
            response.append("\033[4;").append(vt.getRows() * 16).append(';').append(vt.getCols() * 8).append('t');
        }
        if (rawChunk.contains("\033[16t")) {
            response.append("\033[6;16;8t");
        }
        if (containsOsc(rawChunk, "10")) {
            response.append("\033]10;rgb:eeee/eeee/eeee\033\\");
        }
        if (containsOsc(rawChunk, "11")) {
            response.append("\033]11;rgb:0000/0000/0000\033\\");
        }
        if (containsOsc(rawChunk, "12")) {
            response.append("\033]12;rgb:eeee/eeee/eeee\033\\");
        }
        appendExtraResponses(response, rawChunk, vt);
        return response.toString();
    }

    /** Hook for decoder-specific query responses. Default: none. */
    protected void appendExtraResponses(StringBuilder response, String rawChunk, VirtualTerminal vt) {
    }

    protected boolean containsOsc(String data, String code) {
        String prefix = "\033]" + code + ";?";
        return data.contains(prefix + "\007") || data.contains(prefix + "\033\\");
    }

    protected int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
