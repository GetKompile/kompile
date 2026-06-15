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

    private final int rows;
    private final int cols;

    // Main screen buffer
    private final char[][] mainScreen;
    private final char[][] mainPrevScreen;

    // Alternate screen buffer (lazy-allocated on first use)
    private char[][] altScreen;
    private char[][] altPrevScreen;

    // Active screen pointers — reference either main or alt buffers
    private char[][] screen;
    private char[][] prevScreen;

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
    // Set to 0 — content region varies across TUI apps, so rely on
    // isTuiChrome() and word filtering instead of positional skipping.
    private static final int ALT_SCREEN_TOP_CHROME_ROWS = 0;
    private static final int ALT_SCREEN_BOTTOM_CHROME_ROWS = 0;

    public VirtualTerminal(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.mainScreen = new char[rows][cols];
        this.mainPrevScreen = new char[rows][cols];
        this.screen = mainScreen;
        this.prevScreen = mainPrevScreen;
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;
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
     * Uses word-level detection: real LLM response text contains multiple
     * words (sequences of 3+ alphanumeric characters separated by spaces).
     * TUI chrome like "P BDOm" or "1tcAUBDOm" fails this test because it
     * consists of isolated short fragments that don't form proper words.
     */
    public String getNewText() {
        StringBuilder result = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            if (!rowChanged(r)) continue;

            // In alternate screen mode, skip edge rows that are almost
            // always TUI chrome (header bar, input area, status bar).
            if (inAlternateScreen) {
                if (r < ALT_SCREEN_TOP_CHROME_ROWS
                        || r >= rows - ALT_SCREEN_BOTTOM_CHROME_ROWS) {
                    // Snapshot so we don't re-check this row every frame
                    System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                    continue;
                }
            }

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
            // Reject known TUI status bar patterns (OpenCode, Claude, etc.)
            if (isTuiChrome(fullRow)) {
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }
            // Filter: need at least 1 real word (3+ chars) and 5+ total length.
            // This rejects single scattered chars from TUI painting while letting
            // real response text through. isTuiChrome above handles status bars.
            if (fullRow.length() < 5 || !hasEnoughWords(fullRow, 1)) {
                // Don't snapshot — row might accumulate more chars in next frame
                continue;
            }
            // Passed filter — extract only the NEW part vs what we last emitted
            int start = 0;
            while (start < end && screen[r][start] == prevScreen[r][start]) start++;
            if (start >= end) {
                // Row content didn't actually change vs last emit — snapshot and skip
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }
            String newPart = new String(screen[r], start, end - start).trim();
            if (newPart.isEmpty()) {
                System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
                continue;
            }
            // Snapshot this row — we're emitting it
            System.arraycopy(screen[r], 0, prevScreen[r], 0, cols);
            if (result.length() > 0) result.append('\n');
            result.append(newPart);
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
     * Detects TUI status bar / chrome lines that pass the word filter
     * but aren't real LLM response content.
     */
    private boolean isTuiChrome(String row) {
        String lower = row.toLowerCase();
        // OpenCode / Claude Code status bars — keybinding hints
        if (lower.contains("ctrl+c") || lower.contains("ctrl-c")
                || lower.contains("/quit") || lower.contains("/help")
                || lower.contains("/agent") || lower.contains("/compact")) {
            return true;
        }
        // Common TUI chrome: progress indicators, mode labels.
        // Match both "..." (three dots) and "…" (unicode ellipsis U+2026).
        if (lower.contains("generating") && (lower.contains("...") || lower.contains("\u2026"))) return true;
        if (lower.contains("thinking") && (lower.contains("...") || lower.contains("\u2026"))) return true;
        if (lower.contains("connecting") && (lower.contains("...") || lower.contains("\u2026"))) return true;
        if (lower.contains("loading") && (lower.contains("...") || lower.contains("\u2026"))) return true;
        // Claude Code spinner lines: "✶ Sketching…", "✻ Thinking…", "* Reasoning…" etc.
        if (lower.contains("sketching") || lower.contains("effecting")) return true;
        if ((lower.contains("thinking") || lower.contains("reasoning"))
                && (lower.contains("max effort") || lower.contains("\u2026"))) return true;
        // Status lines with keybinding hints
        if (lower.contains("esc ") && lower.contains("cancel")) return true;
        if (lower.contains("shift+tab") || lower.contains("bypass permissions")) return true;
        // Input prompt lines (opencode's own prompt area)
        if (lower.contains("enter a prompt") || lower.contains("type a message")
                || lower.contains("type your message")) return true;
        // OpenCode-specific chrome
        if (lower.contains("opencode") && (lower.contains("session")
                || lower.contains("model") || lower.contains("provider"))) return true;
        if (lower.contains("tab ") && lower.contains("switch")) return true;
        if (lower.contains("cost:") && lower.contains("token")) return true;
        return false;
    }

    /**
     * Returns true if the row is predominantly decorative unicode characters
     * (box drawing, block elements, geometric shapes) with little real text.
     * This catches horizontal rule borders and separator lines.
     */
    private boolean isDecorativeRow(String row) {
        int decorative = 0;
        int alnum = 0;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c >= '\u2500' && c <= '\u259F') decorative++;       // Box Drawing + Block Elements
            else if (c >= '\u25A0' && c <= '\u25FF') decorative++;  // Geometric Shapes
            else if (c >= '\u2800' && c <= '\u28FF') decorative++;  // Braille Patterns
            else if (c >= '\u2B00' && c <= '\u2BFF') decorative++;  // Misc Symbols & Arrows
            else if (c >= '\u2190' && c <= '\u21FF') decorative++;  // Arrows
            else if (c >= '\uE000' && c <= '\uF8FF') decorative++;  // Private Use Area (icon fonts)
            else if (Character.isLetterOrDigit(c)) alnum++;
        }
        // Row is chrome if decorative chars dominate or total alnum is tiny
        return decorative > 0 && (decorative >= alnum || alnum <= 3);
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
     * Extract ALL non-empty, non-chrome content from the current screen.
     * Unlike {@link #getNewText()}, this does NOT use delta tracking — it
     * scrapes every row of the active screen buffer, filtering only TUI chrome.
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
            if (isTuiChrome(fullRow)) continue;
            if (isDecorativeRow(fullRow)) continue;
            // Require at least 1 real word — same filter as getNewText()
            if (fullRow.length() < 5 || !hasEnoughWords(fullRow, 1)) continue;
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
                // Ignore — we don't track attributes
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
                }
                break;
            case 'P': // DCH — delete characters
                int delCount = Math.max(1, arg0(args));
                int row = cursorRow;
                for (int ci = cursorCol; ci < cols - delCount; ci++) {
                    screen[row][ci] = screen[row][ci + delCount];
                }
                for (int ci = cols - delCount; ci < cols; ci++) {
                    screen[row][ci] = ' ';
                }
                break;
            case '@': // ICH — insert characters
                int insCount = Math.max(1, arg0(args));
                for (int ci = cols - 1; ci >= cursorCol + insCount; ci--) {
                    screen[cursorRow][ci] = screen[cursorRow][ci - insCount];
                }
                for (int ci = cursorCol; ci < cursorCol + insCount && ci < cols; ci++) {
                    screen[cursorRow][ci] = ' ';
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
        }

        // Save main screen cursor position for restoration
        mainSavedCursorRow = cursorRow;
        mainSavedCursorCol = cursorCol;

        // Switch to alternate screen
        screen = altScreen;
        prevScreen = altPrevScreen;
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
        }
        Arrays.fill(screen[scrollBottom], ' ');
    }

    private void scrollDown() {
        // Scroll content in scroll region down by one line
        for (int r = scrollBottom; r > scrollTop; r--) {
            System.arraycopy(screen[r - 1], 0, screen[r], 0, cols);
        }
        Arrays.fill(screen[scrollTop], ' ');
    }

    private void insertLines(int n) {
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > cursorRow; r--) {
                System.arraycopy(screen[r - 1], 0, screen[r], 0, cols);
            }
            Arrays.fill(screen[cursorRow], ' ');
        }
    }

    private void deleteLines(int n) {
        for (int k = 0; k < n; k++) {
            for (int r = cursorRow; r < scrollBottom; r++) {
                System.arraycopy(screen[r + 1], 0, screen[r], 0, cols);
            }
            Arrays.fill(screen[scrollBottom], ' ');
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            // Erase from cursor to end
            for (int ci = cursorCol; ci < cols; ci++) screen[cursorRow][ci] = ' ';
            for (int r = cursorRow + 1; r < rows; r++) Arrays.fill(screen[r], ' ');
        } else if (mode == 1) {
            // Erase from start to cursor
            for (int r = 0; r < cursorRow; r++) Arrays.fill(screen[r], ' ');
            for (int ci = 0; ci <= cursorCol && ci < cols; ci++) screen[cursorRow][ci] = ' ';
        } else if (mode == 2 || mode == 3) {
            // Erase all
            clear();
        }
    }

    private void eraseLine(int mode) {
        if (mode == 0) {
            for (int ci = cursorCol; ci < cols; ci++) screen[cursorRow][ci] = ' ';
        } else if (mode == 1) {
            for (int ci = 0; ci <= cursorCol && ci < cols; ci++) screen[cursorRow][ci] = ' ';
        } else if (mode == 2) {
            Arrays.fill(screen[cursorRow], ' ');
        }
    }

    private void clear() {
        for (int r = 0; r < rows; r++) Arrays.fill(screen[r], ' ');
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
        }
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
