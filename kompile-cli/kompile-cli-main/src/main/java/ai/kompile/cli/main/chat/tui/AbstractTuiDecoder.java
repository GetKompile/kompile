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
        int hSize = history.size();
        int low = Math.max(0, hSize - ALIGN_LOOKBACK);
        for (int s = hSize - 1; s >= low; s--) {
            int n = hSize - s;                // history lines to match against visible head
            if (n > visible.size()) continue;
            boolean match = true;
            for (int i = 0; i < n; i++) {
                String h = history.get(s + i).text();
                String v = visible.get(i).text();
                boolean last = (i == n - 1);
                if (h.equals(v)) continue;
                if (last && !h.isBlank() && v.startsWith(h)) continue; // last line grew
                match = false;
                break;
            }
            if (match) {
                // The last matched line may have grown — adopt the visible version.
                history.set(hSize - 1, visible.get(n - 1));
                for (int i = n; i < visible.size(); i++) history.add(visible.get(i));
                return;
            }
        }
        // No alignment found. For full-repaint TUIs this almost always means the
        // visible text was re-laid-out (streaming output rewrapped as it grew),
        // NOT that a fresh screen of content scrolled in. Appending here would
        // duplicate the rewrapped lines every frame — making history grow without
        // bound and the turn never settle. Treat the current screen as
        // authoritative instead.
        history.clear();
        history.addAll(visible);
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
                while (j < history.size() && history.get(j).kind() == TuiLineKind.TOOL) {
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
        if (isSeparatorOrBorder(r) || containsMostlyDecorative(r)) return TuiLineKind.CHROME;
        if (isProgressLine(r)) return TuiLineKind.PROGRESS;
        if (isChrome(r)) return TuiLineKind.CHROME;
        if (isToolLine(r)) return TuiLineKind.TOOL;
        return TuiLineKind.CONTENT;
    }

    /** Transient progress/spinner line (excluded from the transcript). */
    protected boolean isProgressLine(String row) {
        String lower = row.toLowerCase(Locale.ROOT);
        if (lower.contains("esc to interrupt") || lower.contains("esc to cancel")
                || lower.contains("esc interrupt") || lower.contains("ctrl+c to interrupt")) {
            return true;
        }
        String text = stripLeadingChromeGlyphs(lower);
        if ((text.startsWith("working") || text.startsWith("thinking") || text.startsWith("generating")
                || text.startsWith("loading") || text.startsWith("processing") || text.startsWith("compacting")
                || text.startsWith("waiting") || text.startsWith("running") && text.contains("("))
                && (text.contains("(") || hasBrailleSpinner(row))) {
            return true;
        }
        return hasBrailleSpinner(row) && row.strip().length() <= 24;
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
        // Bullet/marker followed by a tool verb (e.g. "• Explored", "• Ran ...").
        String text = stripLeadingChromeGlyphs(trimmed.toLowerCase(Locale.ROOT));
        return startsWithToolVerb(text);
    }

    private boolean startsWithToolVerb(String text) {
        for (String verb : TOOL_VERBS) {
            if (text.equals(verb) || text.startsWith(verb + " ") || text.startsWith(verb + "(")) {
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
