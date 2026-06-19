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

package ai.kompile.utils;

/**
 * Shared ANSI escape code constants used across all TUI rendering.
 * Every class that needs terminal colors or styles should use these
 * constants instead of declaring its own copies.
 */
public final class AnsiConstants {

    private AnsiConstants() {}

    // ── Escape prefix ─────────────────────────────────────────────────────
    public static final String ESC = "\033[";

    // ── Styles ────────────────────────────────────────────────────────────
    public static final String RESET     = ESC + "0m";
    public static final String BOLD      = ESC + "1m";
    public static final String DIM       = ESC + "2m";
    public static final String ITALIC    = ESC + "3m";
    public static final String UNDERLINE = ESC + "4m";
    public static final String INVERSE   = ESC + "7m";

    // ── Foreground colors ─────────────────────────────────────────────────
    public static final String RED     = ESC + "31m";
    public static final String GREEN   = ESC + "32m";
    public static final String YELLOW  = ESC + "33m";
    public static final String BLUE    = ESC + "34m";
    public static final String MAGENTA = ESC + "35m";
    public static final String CYAN    = ESC + "36m";
    public static final String WHITE   = ESC + "37m";
    public static final String GRAY    = ESC + "90m";

    // ── Cursor save/restore (DEC private mode — reliable in scroll regions) ──
    public static final String SAVE_CURSOR    = "\0337";
    public static final String RESTORE_CURSOR = "\0338";

    // ── Spinner frames (braille-based, 8 frames) ──────────────────────────
    public static final String[] SPINNER_FRAMES = {
            "\u28CB", "\u28D9", "\u28F9", "\u28F8", "\u28FC", "\u28F4", "\u28E6", "\u28E7"
    };

    // ── Box drawing ───────────────────────────────────────────────────────
    public static final String HORIZONTAL_LINE = "\u2500"; // ─
    public static final String VERTICAL_SEP    = "\u2502"; // │

    // ── ANSI stripping regex ──────────────────────────────────────────────
    public static final String ANSI_STRIP_REGEX =
            "\033\\[[0-9;?]*[a-zA-Z]"
                    + "|\033\\].*?(?:\033\\\\|\007)"
                    + "|\033[()][0-9A-B]"
                    + "|\033[>=<]"
                    + "|\033\\\\";

    /**
     * Strip all ANSI escape sequences from a string.
     * Useful for measuring visible character length.
     */
    public static String stripAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll(ANSI_STRIP_REGEX, "");
    }

    /**
     * Visible character count of an ANSI-decorated string.
     */
    public static int visibleLength(String text) {
        return stripAnsi(text).length();
    }
}
