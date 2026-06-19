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

import java.util.Arrays;

/**
 * VT100/xterm terminal emulator that maintains a screen buffer.
 * <p>
 * Processes raw byte streams from interactive TUI subprocesses (OpenCode,
 * Claude Code, Codex, etc.) and maintains a 2D character grid representing
 * the current terminal state. Callers can diff the screen between updates
 * to extract newly-written content text in real-time.
 * <p>
 * Supports: cursor positioning (CUP), SGR (ignored), erase display/line,
 * scrolling, newlines, carriage returns, cursor movement, alternate screen
 * buffer, cursor save/restore, charset designation sequences, and private
 * mode set/reset (including alternate screen via DEC ?1049/?47/?1047).
 */
public class VirtualTerminal {

    private int rows;
    private int cols;

    // Main screen buffer
    private char[][] mainScreen;
    private char[][] mainPrevScreen;

    // Alternate screen buffer (lazy-allocated on first use)
    private char[][] altScreen;
    private char[][] altPrevScreen;

    // Active screen pointers — reference either main or alt buffers
    private char[][] screen;
    private char[][] prevScreen;

    // Per-cell SGR style, parallel to the char buffers. Encodes text attributes
    // and foreground colour so the agent's own formatting (bold, colour, …) can be
    // re-emitted as ANSI via getStyledRow(). Background is intentionally NOT tracked
    // (it would clash with kompile's own background when re-rendered).
    private long[][] mainStyle;
    private long[][] altStyle;
    private long[][] style;
    private long currentStyle = 0L;

    private static final long ST_BOLD = 1L;
    private static final long ST_DIM = 1L << 1;
    private static final long ST_ITALIC = 1L << 2;
    private static final long ST_UNDERLINE = 1L << 3;
    private static final long ST_REVERSE = 1L << 4;
    private static final long ST_STRIKE = 1L << 5;
    // fg: mode in bits 8-9 (0=default,1=indexed,2=rgb), value (24-bit) in bits 10-33

    private int cursorRow;
    private int cursorCol;

    // Saved cursor state (DECSC / DECRC — ESC 7 / ESC 8)
    private int savedCursorRow;
    private int savedCursorCol;

    // Cursor saved when switching to alternate screen (for ?1049 restore)
    private int mainSavedCursorRow;
    private int mainSavedCursorCol;

    // Alternate screen state
    private boolean inAlternateScreen;

    // Parser state for handling escape sequences across feed() calls
    private static final int STATE_NORMAL = 0;
    private static final int STATE_ESC = 1;            // Saw ESC
    private static final int STATE_CSI = 2;            // Saw ESC [
    private static final int STATE_OSC = 3;            // Saw ESC ]
    private static final int STATE_DCS = 4;            // Saw ESC P  (also APC, PM, SOS)
    private static final int STATE_ESC_INTERMEDIATE = 5; // ESC + intermediate byte, consume one more
    private int state = STATE_NORMAL;
    private final StringBuilder csiParams = new StringBuilder();
    private final StringBuilder oscPayload = new StringBuilder();

    // Scroll region (top/bottom, 0-indexed)
    private int scrollTop;
    private int scrollBottom;

    // Rows to skip at top/bottom of alternate screen for chrome detection.
    // Configurable per-agent — TUI apps have different header/footer sizes.
    private int altScreenTopChromeRows = 0;
    private int altScreenBottomChromeRows = 0;

    public VirtualTerminal(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.mainScreen = new char[rows][cols];
        this.mainPrevScreen = new char[rows][cols];
        this.mainStyle = new long[rows][cols];
        this.screen = mainScreen;
        this.prevScreen = mainPrevScreen;
        this.style = mainStyle;
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;
        clear();
        snapshotScreen();
    }

    /**
     * Configure the number of rows to skip at the top and bottom of the
     * alternate screen buffer when extracting text. TUI apps (Bubble Tea, etc.)
     * use fixed header/footer regions for status bars, hints, and input areas.
     * These rows contain chrome, not LLM response content.
     */
    public void setAltScreenChromeRows(int top, int bottom) {
        this.altScreenTopChromeRows = Math.max(0, top);
        this.altScreenBottomChromeRows = Math.max(0, bottom);
    }

    /**
     * Resize the virtual terminal. Reallocates all screen buffers and resets
     * cursor/scroll state. Existing screen content is discarded — the
     * subprocess will repaint after receiving SIGWINCH.
     */
    public void resize(int newRows, int newCols) {
        if (newRows <= 0 || newCols <= 0) return;
        this.rows = newRows;
        this.cols = newCols;
        this.mainScreen = new char[newRows][newCols];
        this.mainPrevScreen = new char[newRows][newCols];
        this.mainStyle = new long[newRows][newCols];
        boolean wasAlt = inAlternateScreen;
        if (altScreen != null) {
            altScreen = new char[newRows][newCols];
            altPrevScreen = new char[newRows][newCols];
            altStyle = new long[newRows][newCols];
        }
        screen = wasAlt && altScreen != null ? altScreen : mainScreen;
        prevScreen = wasAlt && altPrevScreen != null ? altPrevScreen : mainPrevScreen;
        style = wasAlt && altStyle != null ? altStyle : mainStyle;
        cursorRow = 0;
        cursorCol = 0;
        scrollTop = 0;
        scrollBottom = newRows - 1;
        clear();
        snapshotScreen();
    }

    /**
     * Feed raw characters from the subprocess into the terminal emulator.
     * Updates the internal screen buffer by processing escape sequences
     * and placing characters at the correct cursor positions.
     */
    public void feed(String data) {
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            switch (state) {
                case STATE_NORMAL:
                    processNormal(c);
                    break;
                case STATE_ESC:
                    processEsc(c);
                    break;
                case STATE_CSI:
                    processCsi(c);
                    break;
                case STATE_OSC:
                    processOsc(c);
                    break;
                case STATE_DCS:
                    processDcs(c);
                    break;
                case STATE_ESC_INTERMEDIATE:
                    // Consume exactly one character after the intermediate byte
                    // (e.g., charset designator after ESC (, or DEC test after ESC #)
                    state = STATE_NORMAL;
                    break;
            }
        }
    }

    /**
     * Take a snapshot and return all NEW text that appeared since the last
     * call to this method. Only returns content from rows that changed and
     * contain real prose/code (filtering TUI chrome which is painted as
     * scattered single characters at cursor-positioned locations).
     * <p>
     * Uses content-based detection to filter chrome: recognizes status bars,
     * keybinding hints, separator lines, logo elements, and model info lines
     * by their textual patterns rather than by absolute row position (since
     * TUI layouts are dynamic and chrome positions shift with content).
     */
    public String getNewText() {
        StringBuilder result = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            if (!rowChanged(r)) continue;

            // Check the FULL row content (not just delta) — characters accumulate
            // across multiple feed() calls as the TUI paints them one at a time.
            int end = cols;
            while (end > 0 && screen[r][end - 1] == ' ') end--;
            if (end == 0) {
                // Row became blank — snapshot it so we stop checking
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }
            String fullRow = new String(screen[r], 0, end).trim();
            if (fullRow.isEmpty()) {
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }
            // Filter: need at least 1 real word (3+ chars) and 5+ total length.
            // This rejects single scattered chars from TUI painting while letting
            // real response text through.
            if (fullRow.length() < 5 || !hasEnoughWords(fullRow, 1)) {
                // Don't snapshot — row might accumulate more chars in next frame
                continue;
            }

            // Content-based chrome filtering — recognizes TUI status bars,
            // keybinding hints, separator lines, and other non-content chrome
            // regardless of which row they appear on.
            if (isChromeRow(fullRow)) {
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }

            // Check if row actually changed vs what we last emitted
            boolean changed = false;
            for (int c = 0; c < end; c++) {
                if (screen[r][c] != prevScreen[r][c]) { changed = true; break; }
            }
            if (!changed) {
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }
            // Emit the FULL row, not the delta — partial deltas from incremental
            // TUI painting produce fragments that bypass chrome filters and
            // concatenate incorrectly.
            // Snapshot this row — we're emitting it
            System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
            if (result.length() > 0) result.append('\n');
            result.append(fullRow);
        }
        return result.toString();
    }

    /**
     * Check if the string contains at least {@code minWords} proper words.
     * A "word" is a sequence of 3+ alphanumeric/common punctuation characters.
     * This filters TUI chrome which typically consists of isolated 1-2 char
     * fragments even when adjacent on the same row.
     */
    private boolean hasEnoughWords(String s, int minWords) {
        int wordCount = 0;
        int wordLen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isWordChar(c)) {
                wordLen++;
            } else {
                if (wordLen >= 3) {
                    wordCount++;
                    if (wordCount >= minWords) return true;
                }
                wordLen = 0;
            }
        }
        // Check trailing word
        if (wordLen >= 3) {
            wordCount++;
        }
        return wordCount >= minWords;
    }

    /**
     * Characters that can form part of a "word" in LLM response text.
     * Includes letters, digits, and common punctuation that appears in prose/code.
     */
    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '\''
                || c == '.' || c == ',' || c == ':' || c == ';' || c == '/'
                || c == '(' || c == ')' || c == '[' || c == ']' || c == '{'
                || c == '}' || c == '"' || c == '!' || c == '?' || c == '#'
                || c == '=' || c == '+' || c == '<' || c == '>' || c == '|'
                || c == '&' || c == '*' || c == '@' || c == '~' || c == '`';
    }

    /**
     * Content-based chrome detection. Returns true if the row text matches
     * known TUI chrome patterns from Claude Code, OpenCode, Gemini CLI, etc.
     * <p>
     * This replaces positional (top-N/bottom-N) filtering which fails when
     * the TUI layout is dynamic (chrome lines float with content, not at
     * fixed absolute row positions).
     */
    private boolean isChromeRow(String text) {
        // Separator lines: all dashes/box-drawing chars (──────)
        if (isSeparatorLine(text)) return true;

        String lower = text.toLowerCase();

        // Claude Code chrome patterns (status bar, keybinding hints, model info)
        if (lower.contains("/effort")) return true;                    // "◉ max · /effort"
        if (lower.contains("ctrl+g to edit")) return true;             // "ctrl+g to edit in nano"
        if (lower.contains("for shortcuts")) return true;              // "? for shortcuts"
        if (lower.contains("/mcp")) return true;                       // "2 MCP servers failed · /mcp"
        if (lower.contains("mcp servers")) return true;                // "2 MCP servers failed"
        if (lower.contains("claude code") && lower.contains("v")) return true; // "Claude Code v2.1.108"
        if (lower.contains("with max effort")) return true;            // "Opus 4.6 with max effort"
        if (lower.contains("with high effort")) return true;
        if (lower.contains("with low effort")) return true;
        if (lower.contains("claude max")) return true;                 // "· Claude Max"
        if (lower.contains("claude pro")) return true;
        if (lower.contains("has switched from npm")) return true;      // installer notification
        if (lower.contains("run `claude inst")) return true;
        if (lower.contains("esc to cancel")) return true;              // "esc to cancel"
        if (lower.contains("ctrl+c")) return true;                     // keybinding hints
        if (lower.contains("shift+tab")) return true;

        // Input prompt placeholder lines: "❯ Try ..." or "Try ..."
        if (text.startsWith("\u276F") || text.startsWith(">")) {       // ❯ or >
            if (lower.contains("try \"") || lower.contains("try '")) return true;
        }

        // Logo/branding: box-drawing art chars used in Claude Code banner
        if (containsMostlyBoxDrawing(text)) return true;

        // OpenCode / Gemini / Codex chrome patterns
        if (lower.contains("opencode") && lower.contains("v")) return true;
        if (lower.contains("gemini cli")) return true;
        if (lower.contains("codex")) return true;

        // Progress / spinner / status lines (Generating…, Thinking…, ⠼ spinner)
        if (isProgressOrSpinner(text)) return true;
        if (lower.contains("enter a prompt")) return true;
        if (lower.startsWith("cost:") || lower.startsWith("cost ")) return true;

        return false;
    }

    /**
     * Transient progress indicators: animated braille spinners, and progress
     * verbs ("Generating…", "Thinking…") with an ellipsis, or a "please wait"
     * hint. Deliberately conservative so real prose is never filtered.
     */
    private boolean isProgressOrSpinner(String text) {
        String t = text.strip();
        if (t.isEmpty()) return false;
        char c0 = t.charAt(0);
        if (c0 >= '⠀' && c0 <= '⣿') return true; // leading braille spinner glyph
        String lower = t.toLowerCase();
        boolean ellipsis = lower.contains("...") || lower.indexOf('…') >= 0;
        if (ellipsis && (lower.startsWith("generating") || lower.startsWith("thinking")
                || lower.startsWith("loading") || lower.startsWith("working")
                || lower.startsWith("processing") || lower.startsWith("waiting")
                || lower.startsWith("compacting") || lower.startsWith("summarizing")
                || lower.startsWith("reticulating") || lower.startsWith("esc to"))) {
            return true;
        }
        return lower.contains("please wait");
    }

    /**
     * True if the row is a separator/divider — entirely composed of
     * dashes, box-drawing characters, and whitespace.
     */
    private boolean isSeparatorLine(String text) {
        int dashLike = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '─' || c == '━' || c == '═'
                    || c == '│' || c == '┃' || c == '║'
                    || c == '┌' || c == '┐' || c == '└' || c == '┘'
                    || c == '╔' || c == '╗' || c == '╚' || c == '╝'
                    || c == '├' || c == '┤' || c == '┬' || c == '┴'
                    || c == '╭' || c == '╮' || c == '╰' || c == '╯') {
                dashLike++;
            } else if (c != ' ' && c != '\t') {
                // Non-dash, non-space character — not a pure separator.
                // But a line like "──Show me the list──" is a separator with
                // embedded text (Claude Code draws input text inside separators).
                // If >60% is dash-like, still treat as separator.
                break;
            }
        }
        // Pure separator: all dash-like chars (or whitespace), at least 10
        if (dashLike >= 10 && dashLike >= text.length() * 0.6) return true;
        // Count total dash-like in the whole string
        int totalDash = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '─' || c == '━' || c == '═') totalDash++;
        }
        // >60% dashes with at least 10 — separator with embedded text
        return totalDash >= 10 && totalDash >= text.length() * 0.6;
    }

    /**
     * True if the text is mostly box-drawing/block art (used in TUI logos).
     */
    private boolean containsMostlyBoxDrawing(String text) {
        int boxChars = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') continue;
            total++;
            // Box drawing U+2500-257F, block elements U+2580-259F,
            // braille U+2800-28FF, and specific chars used in Claude banner
            if ((c >= '\u2500' && c <= '\u259F')
                    || (c >= '\u2800' && c <= '\u28FF')
                    || c == '▐' || c == '▛' || c == '▜' || c == '▌'
                    || c == '▝' || c == '▘' || c == '▀' || c == '▄'
                    || c == '█' || c == '▍' || c == '▎' || c == '▏'
                    || c == '▊' || c == '▋' || c == '▉') {
                boxChars++;
            }
        }
        // >50% box/block characters and at least 3 of them
        return total > 0 && boxChars >= 3 && boxChars >= total * 0.5;
    }

    /**
     * Extract ALL non-empty content from the current screen.
     * Unlike {@link #getNewText()}, this does NOT use delta tracking — it
     * scrapes every row of the active screen buffer, applying only structural
     * filters (word count, length).
     * <p>
     * Use this as a fallback when delta-based extraction fails (e.g., Bubble Tea
     * TUIs that repaint the entire screen each frame, causing deltas to be tiny
     * fragments that fail word/length filters).
     * <p>
     * This method does NOT update the snapshot — calling it won't affect future
     * {@link #getNewText()} results.
     */
    public String getAllContentText() {
        StringBuilder result = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            int end = cols;
            while (end > 0 && screen[r][end - 1] == ' ') end--;
            if (end == 0) continue;
            String fullRow = new String(screen[r], 0, end).trim();
            if (fullRow.isEmpty()) continue;
            // Require at least 1 real word — same filter as getNewText()
            if (fullRow.length() < 5 || !hasEnoughWords(fullRow, 1)) continue;
            if (isChromeRow(fullRow)) continue;   // drop status/spinner/banner chrome
            if (result.length() > 0) result.append('\n');
            result.append(fullRow);
        }
        return result.toString();
    }

    /**
     * Returns a hash of the current screen content for stability detection.
     * Two consecutive calls returning the same value means the screen hasn't
     * changed between them — useful for detecting when a TUI has finished
     * rendering its response.
     */
    public long getScreenHash() {
        long hash = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                hash = hash * 31 + screen[r][c];
            }
        }
        return hash;
    }

    /**
     * Get the full text content of a specific row (trimmed).
     */
    public String getRow(int row) {
        if (row < 0 || row >= rows) return "";
        int end = cols;
        while (end > 0 && screen[row][end - 1] == ' ') end--;
        return new String(screen[row], 0, end);
    }

    /**
     * Get the full screen content as a string (for debugging).
     */
    public String getFullScreen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            String line = getRow(r);
            if (!line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }

    /** Whether the terminal is currently in the alternate screen buffer. */
    public boolean isInAlternateScreen() { return inAlternateScreen; }

    /**
     * Build terminal responses for queries emitted by full-screen TUIs.
     * <p>
     * Managed passthrough captures child output instead of connecting it to a
     * real terminal emulator, so requests like cursor-position report must be
     * answered explicitly and written back to the child stdin.
     */
    public String terminalResponsesFor(String data) {
        if (data == null || data.isEmpty()) return "";
        StringBuilder response = new StringBuilder();

        // DSR: cursor position report. Terminal rows/cols are 1-indexed.
        if (data.contains("\033[6n")) {
            response.append("\033[")
                    .append(clamp(cursorRow + 1, 1, rows))
                    .append(';')
                    .append(clamp(cursorCol + 1, 1, cols))
                    .append('R');
        }
        // DSR: terminal status OK.
        if (data.contains("\033[5n")) {
            response.append("\033[0n");
        }
        // Primary device attributes — report VT220 with advanced features.
        // A richer DA1 response satisfies Bubble Tea's feature detection so it
        // doesn't fall back to a degraded rendering mode.
        if (data.contains("\033[c") || data.contains("\033[0c")) {
            response.append("\033[?62;22c");
        }
        // Secondary device attributes.
        if (data.contains("\033[>c") || data.contains("\033[>0c")) {
            response.append("\033[>0;0;0c");
        }
        // Kitty keyboard protocol query: report no enhanced keyboard flags.
        if (data.contains("\033[?u")) {
            response.append("\033[?0u");
        }
        // Window/text-area reports used by some terminal UI libraries.
        if (data.contains("\033[18t")) {
            response.append("\033[8;").append(rows).append(';').append(cols).append('t');
        }
        if (data.contains("\033[14t")) {
            response.append("\033[4;").append(rows * 16).append(';').append(cols * 8).append('t');
        }
        if (data.contains("\033[16t")) {
            response.append("\033[6;16;8t");
        }
        // OSC color queries. Use neutral defaults; the exact color is rarely
        // important, but terminal libraries may block waiting for a reply.
        if (containsOscQuery(data, "10")) {
            response.append("\033]10;rgb:eeee/eeee/eeee\033\\");
        }
        if (containsOscQuery(data, "11")) {
            response.append("\033]11;rgb:0000/0000/0000\033\\");
        }
        if (containsOscQuery(data, "12")) {
            response.append("\033]12;rgb:eeee/eeee/eeee\033\\");
        }

        return response.toString();
    }

    /**
     * Like {@link #terminalResponsesFor(String)} but omits responses that
     * are known to cause problems when written back to subprocess stdin.
     * <p>
     * Specifically, the Kitty keyboard protocol response ({@code ESC[?0u})
     * gets misinterpreted as keystrokes by Claude Code, triggering nano.
     * This method sends DA1, DSR, and other safe responses that Claude Code
     * needs to proceed with rendering, while skipping dangerous ones.
     */
    public String safeTerminalResponsesFor(String data) {
        if (data == null || data.isEmpty()) return "";
        StringBuilder response = new StringBuilder();

        // DSR: cursor position report
        if (data.contains("\033[6n")) {
            response.append("\033[")
                    .append(clamp(cursorRow + 1, 1, rows))
                    .append(';')
                    .append(clamp(cursorCol + 1, 1, cols))
                    .append('R');
        }
        // DSR: terminal status OK
        if (data.contains("\033[5n")) {
            response.append("\033[0n");
        }
        // Primary device attributes — needed for Claude Code to proceed
        if (data.contains("\033[c") || data.contains("\033[0c")) {
            response.append("\033[?62;22c");
        }
        // Secondary device attributes
        if (data.contains("\033[>c") || data.contains("\033[>0c")) {
            response.append("\033[>0;0;0c");
        }
        // SKIP: Kitty keyboard protocol (\033[?u) — response \033[?0u
        // triggers nano when misinterpreted as keystrokes

        // Window/text-area size reports
        if (data.contains("\033[18t")) {
            response.append("\033[8;").append(rows).append(';').append(cols).append('t');
        }
        if (data.contains("\033[14t")) {
            response.append("\033[4;").append(rows * 16).append(';').append(cols * 8).append('t');
        }
        if (data.contains("\033[16t")) {
            response.append("\033[6;16;8t");
        }
        // OSC color queries
        if (containsOscQuery(data, "10")) {
            response.append("\033]10;rgb:eeee/eeee/eeee\033\\");
        }
        if (containsOscQuery(data, "11")) {
            response.append("\033]11;rgb:0000/0000/0000\033\\");
        }
        if (containsOscQuery(data, "12")) {
            response.append("\033]12;rgb:eeee/eeee/eeee\033\\");
        }

        return response.toString();
    }

    private boolean containsOscQuery(String data, String code) {
        String prefix = "\033]" + code + ";?";
        return data.contains(prefix + "\007") || data.contains(prefix + "\033\\");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal processing
    // ═══════════════════════════════════════════════════════════════════════

    private void processNormal(char c) {
        if (c == '\033') {
            state = STATE_ESC;
        } else if (c == '\u009B') {
            // 8-bit CSI
            state = STATE_CSI;
            csiParams.setLength(0);
        } else if (c == '\u009D') {
            // 8-bit OSC
            state = STATE_OSC;
            oscPayload.setLength(0);
        } else if (c == '\u0090') {
            // 8-bit DCS
            state = STATE_DCS;
        } else if (c == '\u009E') {
            // 8-bit PM
            state = STATE_DCS; // consume until ST, same as DCS
        } else if (c == '\u009F') {
            // 8-bit APC
            state = STATE_DCS;
        } else if (c == '\n') {
            linefeed();
        } else if (c == '\r') {
            cursorCol = 0;
        } else if (c == '\t') {
            cursorCol = Math.min(((cursorCol / 8) + 1) * 8, cols - 1);
        } else if (c == '\b') {
            if (cursorCol > 0) cursorCol--;
        } else if (c == '\007') {
            // BEL — ignore
        } else if (c == '\016') {
            // SO — Shift Out (switch to G1 charset) — ignore
        } else if (c == '\017') {
            // SI — Shift In (switch to G0 charset) — ignore
        } else if (c >= 0x20 && c != 0x7F) {
            // Printable character (including all of Unicode above 0x20)
            putChar(c);
        }
        // Other control chars (0x00-0x1F not handled above) — ignore
    }

    private void processEsc(char c) {
        if (c == '[') {
            state = STATE_CSI;
            csiParams.setLength(0);
        } else if (c == ']') {
            state = STATE_OSC;
            oscPayload.setLength(0);
        } else if (c == 'P') {
            // DCS — Device Control String
            state = STATE_DCS;
        } else if (c == '_' || c == '^' || c == 'X') {
            // APC (Application Program Command), PM (Privacy Message),
            // SOS (Start of String) — all consume until ST, same as DCS
            state = STATE_DCS;
        } else if (c == '(' || c == ')' || c == '*' || c == '+') {
            // Charset designation (G0-G3) — consume one more char (designator)
            // e.g., ESC ( B = set G0 to US-ASCII, ESC ( 0 = set G0 to DEC Special Graphics
            state = STATE_ESC_INTERMEDIATE;
        } else if (c == '#') {
            // DEC double-width/double-height/alignment test — consume one more char
            // e.g., ESC # 8 = DECALN (fill screen with 'E')
            state = STATE_ESC_INTERMEDIATE;
        } else if (c == ' ') {
            // Set ANSI conformance level — consume one more char
            // e.g., ESC SP F = S7C1T, ESC SP G = S8C1T
            state = STATE_ESC_INTERMEDIATE;
        } else if (c == '%') {
            // Character set switch — consume one more char
            // e.g., ESC % @ = default, ESC % G = UTF-8
            state = STATE_ESC_INTERMEDIATE;
        } else if (c == 'N' || c == 'O') {
            // SS2 / SS3 — single shift. Next character uses G2/G3 charset.
            // Consume one more char.
            state = STATE_ESC_INTERMEDIATE;
        } else if (c == 'D') {
            // IND — Index (linefeed)
            linefeed();
            state = STATE_NORMAL;
        } else if (c == 'M') {
            // RI — Reverse Index (reverse linefeed)
            if (cursorRow == scrollTop) {
                scrollDown();
            } else if (cursorRow > 0) {
                cursorRow--;
            }
            state = STATE_NORMAL;
        } else if (c == 'E') {
            // NEL — Next Line
            cursorCol = 0;
            linefeed();
            state = STATE_NORMAL;
        } else if (c == 'c') {
            // RIS — Full Reset
            fullReset();
            state = STATE_NORMAL;
        } else if (c == '7') {
            // DECSC — Save Cursor position and attributes
            savedCursorRow = cursorRow;
            savedCursorCol = cursorCol;
            state = STATE_NORMAL;
        } else if (c == '8') {
            // DECRC — Restore Cursor position and attributes
            cursorRow = clamp(savedCursorRow, 0, rows - 1);
            cursorCol = clamp(savedCursorCol, 0, cols - 1);
            state = STATE_NORMAL;
        } else if (c == '=' || c == '>') {
            // DECKPAM / DECKPNM — application/normal keypad mode — ignore
            state = STATE_NORMAL;
        } else if (c == 'H') {
            // HTS — Horizontal Tab Set — ignore (we use fixed tab stops)
            state = STATE_NORMAL;
        } else if (c == 'Z') {
            // DECID — DEC private identification (obsolete DA1) — ignore
            state = STATE_NORMAL;
        } else if (c == '\\') {
            // ST — String Terminator (can appear after ESC in some contexts)
            state = STATE_NORMAL;
        } else {
            // Unknown ESC sequence — ignore and go back to normal
            state = STATE_NORMAL;
        }
    }

    private void processCsi(char c) {
        if (c >= 0x20 && c <= 0x3F) {
            // Parameter byte (0x30-0x3F: digits, semicolon, etc.) or
            // intermediate byte (0x20-0x2F: space, !, etc.)
            csiParams.append(c);
        } else if (c >= 0x40 && c <= 0x7E) {
            // Final byte — execute the CSI command
            executeCsi(c, csiParams.toString());
            state = STATE_NORMAL;
        } else {
            // Invalid character in CSI sequence — abort
            state = STATE_NORMAL;
        }
    }

    private void processOsc(char c) {
        if (c == '\007') {
            // BEL terminates OSC
            state = STATE_NORMAL;
        } else if (c == '\033') {
            // Potential ST (ESC \) — transition to ESC state.
            // processEsc will handle '\' → back to NORMAL.
            state = STATE_ESC;
        } else if (c == '\u009C') {
            // 8-bit ST
            state = STATE_NORMAL;
        }
        // Otherwise: accumulate/ignore OSC payload
    }

    private void processDcs(char c) {
        if (c == '\033') {
            // Potential ST (ESC \) — transition to ESC state
            state = STATE_ESC;
        } else if (c == '\u009C') {
            // 8-bit ST
            state = STATE_NORMAL;
        }
        // Otherwise: accumulate/ignore DCS payload
    }

    private void executeCsi(char cmd, String params) {
        // Strip leading private-mode indicator (?, >, <, =)
        String p = params;
        boolean privateMode = false;
        char privateModeChar = 0;
        if (p.length() > 0 && (p.charAt(0) == '?' || p.charAt(0) == '>'
                || p.charAt(0) == '<' || p.charAt(0) == '=')) {
            privateMode = true;
            privateModeChar = p.charAt(0);
            p = p.substring(1);
        }
        // Strip trailing intermediate bytes (0x20-0x2F: space, !, $, etc.)
        while (p.length() > 0 && p.charAt(p.length() - 1) >= 0x20
                && p.charAt(p.length() - 1) <= 0x2F) {
            p = p.substring(0, p.length() - 1);
        }

        int[] args = parseArgs(p);

        switch (cmd) {
            case 'H': case 'f': // CUP — cursor position
                cursorRow = clamp((args.length > 0 ? args[0] : 1) - 1, 0, rows - 1);
                cursorCol = clamp((args.length > 1 ? args[1] : 1) - 1, 0, cols - 1);
                break;
            case 'A': // CUU — cursor up
                cursorRow = Math.max(0, cursorRow - Math.max(1, arg0(args)));
                break;
            case 'B': // CUD — cursor down
                cursorRow = Math.min(rows - 1, cursorRow + Math.max(1, arg0(args)));
                break;
            case 'C': // CUF — cursor forward
                cursorCol = Math.min(cols - 1, cursorCol + Math.max(1, arg0(args)));
                break;
            case 'D': // CUB — cursor back
                cursorCol = Math.max(0, cursorCol - Math.max(1, arg0(args)));
                break;
            case 'E': // CNL — cursor next line
                cursorCol = 0;
                cursorRow = Math.min(rows - 1, cursorRow + Math.max(1, arg0(args)));
                break;
            case 'F': // CPL — cursor previous line
                cursorCol = 0;
                cursorRow = Math.max(0, cursorRow - Math.max(1, arg0(args)));
                break;
            case 'G': // CHA — cursor horizontal absolute
                cursorCol = clamp((args.length > 0 ? args[0] : 1) - 1, 0, cols - 1);
                break;
            case 'd': // VPA — line position absolute
                cursorRow = clamp((args.length > 0 ? args[0] : 1) - 1, 0, rows - 1);
                break;
            case 'J': // ED — erase display
                eraseDisplay(args.length > 0 ? args[0] : 0);
                break;
            case 'K': // EL — erase line
                eraseLine(args.length > 0 ? args[0] : 0);
                break;
            case 'L': // IL — insert lines
                insertLines(Math.max(1, arg0(args)));
                break;
            case 'M': // DL — delete lines
                deleteLines(Math.max(1, arg0(args)));
                break;
            case 'S': // SU — scroll up
                for (int n = Math.max(1, arg0(args)); n > 0; n--) scrollUp();
                break;
            case 'T': // SD — scroll down
                if (!privateMode) {
                    for (int n = Math.max(1, arg0(args)); n > 0; n--) scrollDown();
                }
                break;
            case 'r': // DECSTBM — set scroll region
                if (!privateMode) {
                    scrollTop = clamp((args.length > 0 ? args[0] : 1) - 1, 0, rows - 1);
                    scrollBottom = clamp((args.length > 1 ? args[1] : rows) - 1, 0, rows - 1);
                    cursorRow = 0;
                    cursorCol = 0;
                }
                break;
            case 'm': // SGR — select graphic rendition (colors, bold, etc.)
                parseSgr(args);
                break;
            case 'h': // SM — set mode
                if (privateMode) {
                    handlePrivateMode(args, true);
                }
                break;
            case 'l': // RM — reset mode
                if (privateMode) {
                    handlePrivateMode(args, false);
                }
                break;
            case 'n': // DSR — device status report
                // Response is handled by terminalResponsesFor()
                break;
            case 's': // SCP — save cursor position (ANSI.SYS variant)
                savedCursorRow = cursorRow;
                savedCursorCol = cursorCol;
                break;
            case 'u': // RCP — restore cursor position (ANSI.SYS variant)
                if (!privateMode) {
                    cursorRow = clamp(savedCursorRow, 0, rows - 1);
                    cursorCol = clamp(savedCursorCol, 0, cols - 1);
                }
                break;
            case 'X': // ECH — erase characters
                int count = Math.max(1, arg0(args));
                for (int ci = 0; ci < count && cursorCol + ci < cols; ci++) {
                    screen[cursorRow][cursorCol + ci] = ' ';
                    style[cursorRow][cursorCol + ci] = 0L;
                }
                break;
            case 'P': // DCH — delete characters
                int delCount = Math.max(1, arg0(args));
                int row = cursorRow;
                for (int ci = cursorCol; ci < cols - delCount; ci++) {
                    screen[row][ci] = screen[row][ci + delCount];
                    style[row][ci] = style[row][ci + delCount];
                }
                for (int ci = cols - delCount; ci < cols; ci++) {
                    screen[row][ci] = ' ';
                    style[row][ci] = 0L;
                }
                break;
            case '@': // ICH — insert characters
                int insCount = Math.max(1, arg0(args));
                for (int ci = cols - 1; ci >= cursorCol + insCount; ci--) {
                    screen[cursorRow][ci] = screen[cursorRow][ci - insCount];
                    style[cursorRow][ci] = style[cursorRow][ci - insCount];
                }
                for (int ci = cursorCol; ci < cursorCol + insCount && ci < cols; ci++) {
                    screen[cursorRow][ci] = ' ';
                    style[cursorRow][ci] = 0L;
                }
                break;
            case 't': // Window manipulation — ignore
            case 'c': // DA — device attributes — handled by terminalResponsesFor()
            case 'q': // DECLL/DECSCUSR — ignore (cursor shape, LED)
            case 'p': // DECSTR and others — ignore
            case 'b': // REP — repeat preceding graphic char — ignore for now
            case 'g': // TBC — tab clear — ignore
            case 'i': // MC — media copy (print) — ignore
            case 'W': // CTC — cursor tabulation control — ignore
            case 'Z': // CBT — cursor backward tabulation — ignore for now
                break;
            default:
                // Unknown CSI command — ignore
                break;
        }
    }

    /**
     * Handle DEC private mode set (h) / reset (l) sequences.
     * These control features like cursor visibility, alternate screen, etc.
     */
    private void handlePrivateMode(int[] args, boolean set) {
        for (int arg : args) {
            switch (arg) {
                case 1: // DECCKM — cursor key mode — ignore
                case 3: // DECCOLM — 132/80 column mode — ignore
                case 4: // DECSCLM — smooth/jump scroll — ignore
                case 5: // DECSCNM — reverse/normal video — ignore
                case 6: // DECOM — origin mode — ignore
                case 7: // DECAWM — auto-wrap mode — ignore
                case 8: // DECARM — auto-repeat keys — ignore
                case 9: // X10 mouse tracking — ignore
                case 12: // Cursor blink — ignore
                case 25: // DECTCEM — cursor visibility — ignore
                    break;
                case 47: // Alternate screen buffer (old xterm)
                    if (set) {
                        enterAlternateScreen(false);
                    } else {
                        leaveAlternateScreen(false);
                    }
                    break;
                case 1047: // Alternate screen buffer (xterm)
                    if (set) {
                        enterAlternateScreen(false);
                    } else {
                        // Clear alternate screen before leaving
                        if (inAlternateScreen) {
                            clear();
                        }
                        leaveAlternateScreen(false);
                    }
                    break;
                case 1048: // Save/restore cursor (xterm)
                    if (set) {
                        savedCursorRow = cursorRow;
                        savedCursorCol = cursorCol;
                    } else {
                        cursorRow = clamp(savedCursorRow, 0, rows - 1);
                        cursorCol = clamp(savedCursorCol, 0, cols - 1);
                    }
                    break;
                case 1049: // Alternate screen buffer + save/restore cursor (xterm)
                    if (set) {
                        // Save cursor THEN enter alternate screen
                        savedCursorRow = cursorRow;
                        savedCursorCol = cursorCol;
                        enterAlternateScreen(true);
                    } else {
                        leaveAlternateScreen(true);
                        // Restore cursor AFTER leaving alternate screen
                        cursorRow = clamp(savedCursorRow, 0, rows - 1);
                        cursorCol = clamp(savedCursorCol, 0, cols - 1);
                    }
                    break;
                case 1000: case 1002: case 1003: // Mouse tracking modes — ignore
                case 1004: // Focus events — ignore
                case 1005: case 1006: case 1015: case 1016: // Mouse format modes — ignore
                case 2004: // Bracketed paste mode — ignore
                case 2026: // Synchronized output — ignore
                    break;
                default:
                    // Other private modes — ignore
                    break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Alternate screen buffer
    // ═══════════════════════════════════════════════════════════════════════

    private void enterAlternateScreen(boolean clearOnEnter) {
        if (inAlternateScreen) return;

        // Lazy-allocate alternate screen buffers
        if (altScreen == null) {
            altScreen = new char[rows][cols];
            altPrevScreen = new char[rows][cols];
            altStyle = new long[rows][cols];
        }

        // Save main screen cursor position for restoration
        mainSavedCursorRow = cursorRow;
        mainSavedCursorCol = cursorCol;

        // Switch to alternate screen
        screen = altScreen;
        prevScreen = altPrevScreen;
        style = altStyle;
        inAlternateScreen = true;

        if (clearOnEnter) {
            clear();
            // Also clear prevScreen so getNewText() doesn't see stale data
            for (int r = 0; r < rows; r++) {
                Arrays.fill(altPrevScreen[r], ' ');
            }
        }

        // Reset scroll region for alternate screen
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    private void leaveAlternateScreen(boolean restoreCursor) {
        if (!inAlternateScreen) return;

        // Switch back to main screen
        screen = mainScreen;
        prevScreen = mainPrevScreen;
        style = mainStyle;
        inAlternateScreen = false;

        if (restoreCursor) {
            cursorRow = clamp(mainSavedCursorRow, 0, rows - 1);
            cursorCol = clamp(mainSavedCursorCol, 0, cols - 1);
        }

        // Reset scroll region for main screen
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Screen operations
    // ═══════════════════════════════════════════════════════════════════════

    private void putChar(char c) {
        if (cursorRow >= 0 && cursorRow < rows && cursorCol >= 0 && cursorCol < cols) {
            screen[cursorRow][cursorCol] = c;
            style[cursorRow][cursorCol] = currentStyle;
        }
        cursorCol++;
        if (cursorCol >= cols) {
            cursorCol = 0;
            linefeed();
        }
    }

    private void linefeed() {
        if (cursorRow == scrollBottom) {
            scrollUp();
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    private void scrollUp() {
        // Scroll content in scroll region up by one line
        for (int r = scrollTop; r < scrollBottom; r++) {
            System.arraycopy(screen[r + 1], 0, screen[r], 0, cols);
            System.arraycopy(style[r + 1], 0, style[r], 0, cols);
        }
        Arrays.fill(screen[scrollBottom], ' ');
        Arrays.fill(style[scrollBottom], 0L);
    }

    private void scrollDown() {
        // Scroll content in scroll region down by one line
        for (int r = scrollBottom; r > scrollTop; r--) {
            System.arraycopy(screen[r - 1], 0, screen[r], 0, cols);
            System.arraycopy(style[r - 1], 0, style[r], 0, cols);
        }
        Arrays.fill(screen[scrollTop], ' ');
        Arrays.fill(style[scrollTop], 0L);
    }

    private void insertLines(int n) {
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > cursorRow; r--) {
                System.arraycopy(screen[r - 1], 0, screen[r], 0, cols);
                System.arraycopy(style[r - 1], 0, style[r], 0, cols);
            }
            Arrays.fill(screen[cursorRow], ' ');
            Arrays.fill(style[cursorRow], 0L);
        }
    }

    private void deleteLines(int n) {
        for (int k = 0; k < n; k++) {
            for (int r = cursorRow; r < scrollBottom; r++) {
                System.arraycopy(screen[r + 1], 0, screen[r], 0, cols);
                System.arraycopy(style[r + 1], 0, style[r], 0, cols);
            }
            Arrays.fill(screen[scrollBottom], ' ');
            Arrays.fill(style[scrollBottom], 0L);
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            // Erase from cursor to end
            for (int ci = cursorCol; ci < cols; ci++) { screen[cursorRow][ci] = ' '; style[cursorRow][ci] = 0L; }
            for (int r = cursorRow + 1; r < rows; r++) { Arrays.fill(screen[r], ' '); Arrays.fill(style[r], 0L); }
        } else if (mode == 1) {
            // Erase from start to cursor
            for (int r = 0; r < cursorRow; r++) { Arrays.fill(screen[r], ' '); Arrays.fill(style[r], 0L); }
            for (int ci = 0; ci <= cursorCol && ci < cols; ci++) { screen[cursorRow][ci] = ' '; style[cursorRow][ci] = 0L; }
        } else if (mode == 2 || mode == 3) {
            // Erase all
            clear();
        }
    }

    private void eraseLine(int mode) {
        if (mode == 0) {
            for (int ci = cursorCol; ci < cols; ci++) { screen[cursorRow][ci] = ' '; style[cursorRow][ci] = 0L; }
        } else if (mode == 1) {
            for (int ci = 0; ci <= cursorCol && ci < cols; ci++) { screen[cursorRow][ci] = ' '; style[cursorRow][ci] = 0L; }
        } else if (mode == 2) {
            Arrays.fill(screen[cursorRow], ' ');
            Arrays.fill(style[cursorRow], 0L);
        }
    }

    private void clear() {
        for (int r = 0; r < rows; r++) { Arrays.fill(screen[r], ' '); Arrays.fill(style[r], 0L); }
        cursorRow = 0;
        cursorCol = 0;
    }

    /**
     * Full terminal reset — clears both screens, resets all state.
     */
    private void fullReset() {
        // Leave alternate screen if active
        if (inAlternateScreen) {
            leaveAlternateScreen(false);
        }
        clear();
        currentStyle = 0L;
        scrollTop = 0;
        scrollBottom = rows - 1;
        savedCursorRow = 0;
        savedCursorCol = 0;
        mainSavedCursorRow = 0;
        mainSavedCursorCol = 0;
        // Clear alternate screen too if it was allocated
        if (altScreen != null) {
            for (int r = 0; r < rows; r++) Arrays.fill(altScreen[r], ' ');
            for (int r = 0; r < rows; r++) Arrays.fill(altPrevScreen[r], ' ');
            if (altStyle != null) for (int r = 0; r < rows; r++) Arrays.fill(altStyle[r], 0L);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SGR style tracking + styled row extraction
    // ═══════════════════════════════════════════════════════════════════════

    private void parseSgr(int[] args) {
        if (args.length == 0) { currentStyle = 0L; return; }
        for (int i = 0; i < args.length; i++) {
            int code = args[i];
            switch (code) {
                case 0:  currentStyle = 0L; break;
                case 1:  currentStyle |= ST_BOLD; break;
                case 2:  currentStyle |= ST_DIM; break;
                case 3:  currentStyle |= ST_ITALIC; break;
                case 4:  currentStyle |= ST_UNDERLINE; break;
                case 7:  currentStyle |= ST_REVERSE; break;
                case 9:  currentStyle |= ST_STRIKE; break;
                case 22: currentStyle &= ~(ST_BOLD | ST_DIM); break;
                case 23: currentStyle &= ~ST_ITALIC; break;
                case 24: currentStyle &= ~ST_UNDERLINE; break;
                case 27: currentStyle &= ~ST_REVERSE; break;
                case 29: currentStyle &= ~ST_STRIKE; break;
                case 39: currentStyle = withFg(currentStyle, 0, 0); break; // default fg
                case 38: // extended fg: 38;5;n  or  38;2;r;g;b
                    if (i + 2 < args.length && args[i + 1] == 5) {
                        currentStyle = withFg(currentStyle, 1, args[i + 2] & 0xFF);
                        i += 2;
                    } else if (i + 4 < args.length && args[i + 1] == 2) {
                        int rgb = ((args[i + 2] & 0xFF) << 16) | ((args[i + 3] & 0xFF) << 8) | (args[i + 4] & 0xFF);
                        currentStyle = withFg(currentStyle, 2, rgb);
                        i += 4;
                    }
                    break;
                case 48: // extended bg — not tracked, but consume its parameters
                    if (i + 2 < args.length && args[i + 1] == 5) i += 2;
                    else if (i + 4 < args.length && args[i + 1] == 2) i += 4;
                    break;
                default:
                    if (code >= 30 && code <= 37) currentStyle = withFg(currentStyle, 1, code - 30);
                    else if (code >= 90 && code <= 97) currentStyle = withFg(currentStyle, 1, code - 90 + 8);
                    // 40-47, 49, 100-107 (background) intentionally ignored
                    break;
            }
        }
    }

    private long withFg(long st, int mode, int val) {
        st &= ~(0x3L << 8);
        st &= ~(0xFFFFFFL << 10);
        st |= ((long) (mode & 0x3)) << 8;
        st |= ((long) (val & 0xFFFFFF)) << 10;
        return st;
    }

    private int fgMode(long st) { return (int) ((st >> 8) & 0x3); }
    private int fgVal(long st)  { return (int) ((st >> 10) & 0xFFFFFF); }

    /**
     * Like {@link #getRow(int)} but re-emits the agent's foreground colour and
     * text attributes as ANSI SGR escapes, so the agent's own formatting is
     * preserved. Leading default-styled spaces and trailing spaces are trimmed.
     */
    public String getStyledRow(int row) {
        if (row < 0 || row >= rows) return "";
        int end = cols;
        while (end > 0 && screen[row][end - 1] == ' ') end--;
        int start = 0;
        while (start < end && screen[row][start] == ' ' && style[row][start] == 0L) start++;
        if (start >= end) return "";
        StringBuilder sb = new StringBuilder();
        long active = 0L;
        for (int c = start; c < end; c++) {
            long st = style[row][c];
            if (st != active) {
                sb.append(sgrFor(st));
                active = st;
            }
            sb.append(screen[row][c]);
        }
        if (active != 0L) sb.append("\033[0m");
        return sb.toString();
    }

    private String sgrFor(long st) {
        if (st == 0L) return "\033[0m";
        StringBuilder s = new StringBuilder("\033[0");
        if ((st & ST_BOLD) != 0) s.append(";1");
        if ((st & ST_DIM) != 0) s.append(";2");
        if ((st & ST_ITALIC) != 0) s.append(";3");
        if ((st & ST_UNDERLINE) != 0) s.append(";4");
        if ((st & ST_REVERSE) != 0) s.append(";7");
        if ((st & ST_STRIKE) != 0) s.append(";9");
        int mode = fgMode(st), val = fgVal(st);
        if (mode == 1) {
            s.append(";38;5;").append(val & 0xFF);
        } else if (mode == 2) {
            s.append(";38;2;").append((val >> 16) & 0xFF).append(';').append((val >> 8) & 0xFF).append(';').append(val & 0xFF);
        }
        s.append('m');
        return s.toString();
    }

    private void snapshotScreen() {
        for (int r = 0; r < rows; r++) {
            System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
        }
    }

    private boolean rowChanged(int r) {
        for (int c = 0; c < cols; c++) {
            if (screen[r][c] != prevScreen[r][c]) return true;
        }
        return false;
    }

    private int[] parseArgs(String params) {
        if (params.isEmpty()) return new int[0];
        String[] parts = params.split(";");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private int arg0(int[] args) {
        return args.length > 0 ? args[0] : 1;
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
