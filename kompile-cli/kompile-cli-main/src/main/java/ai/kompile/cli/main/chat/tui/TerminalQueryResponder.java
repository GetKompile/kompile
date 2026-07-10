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

/**
 * The single implementation of terminal-query answering (design finding F5d — consolidates the
 * four historical copies: {@code AbstractTuiDecoder.buildResponses}, {@code GenericDecoder},
 * {@code GeminiCliDecoder}, and the {@code VirtualTerminal.terminalResponsesFor}/
 * {@code safeTerminalResponsesFor} pair).
 *
 * <p>Managed passthrough captures child output instead of connecting it to a real terminal, so
 * requests like cursor-position report (DSR), device attributes (DA1/DA2), window/text-area size
 * reports, XTVERSION, and OSC color queries must be answered explicitly and written back to the
 * child's stdin. Which queries are answered is parameterized by a per-agent {@link QueryPolicy}.</p>
 */
public final class TerminalQueryResponder {

    private TerminalQueryResponder() {
    }

    /**
     * Build the response bytes for any terminal queries present in {@code rawChunk}.
     *
     * @param rawChunk the raw PTY output chunk that may contain queries
     * @param vt       the shadow terminal (for cursor position and geometry)
     * @param policy   per-agent answer knobs
     * @return response bytes to write back to subprocess stdin, or empty string
     */
    public static String respond(String rawChunk, VirtualTerminal vt, QueryPolicy policy) {
        if (rawChunk == null || rawChunk.isEmpty()) return "";
        if (policy == null) policy = QueryPolicy.DEFAULT;
        StringBuilder r = new StringBuilder();

        // DSR: cursor position report. Terminal rows/cols are 1-indexed.
        if (rawChunk.contains("\033[6n")) {
            r.append("\033[")
                    .append(clamp(vt.getCursorRow() + 1, 1, vt.getRows()))
                    .append(';')
                    .append(clamp(vt.getCursorCol() + 1, 1, vt.getCols()))
                    .append('R');
        }
        // DSR: terminal status OK.
        if (rawChunk.contains("\033[5n")) {
            r.append("\033[0n");
        }
        // Primary device attributes — report VT220 with advanced features so feature detection
        // (e.g. Bubble Tea) does not fall back to a degraded rendering mode.
        if (rawChunk.contains("\033[c") || rawChunk.contains("\033[0c")) {
            r.append("\033[?62;22c");
        }
        // Secondary device attributes.
        if (rawChunk.contains("\033[>c") || rawChunk.contains("\033[>0c")) {
            r.append("\033[>0;0;0c");
        }
        // Kitty keyboard protocol query: report no enhanced keyboard flags (when enabled).
        if (policy.answersKittyKeyboard() && rawChunk.contains("\033[?u")) {
            r.append("\033[?0u");
        }
        // XTVERSION — report a neutral terminal identity (when enabled).
        if (policy.answersXtVersion() && rawChunk.contains("\033[>q")) {
            r.append("\033P>|kompile\033\\");
        }
        // Window / text-area size reports used by some terminal UI libraries.
        if (rawChunk.contains("\033[18t")) {
            r.append("\033[8;").append(vt.getRows()).append(';').append(vt.getCols()).append('t');
        }
        if (rawChunk.contains("\033[14t")) {
            r.append("\033[4;").append(vt.getRows() * 16).append(';').append(vt.getCols() * 8).append('t');
        }
        if (rawChunk.contains("\033[16t")) {
            r.append("\033[6;16;8t");
        }
        // OSC color queries. Neutral defaults; terminal libraries may block waiting for a reply.
        if (containsOsc(rawChunk, "10")) {
            r.append("\033]10;rgb:eeee/eeee/eeee\033\\");
        }
        if (containsOsc(rawChunk, "11")) {
            r.append("\033]11;rgb:0000/0000/0000\033\\");
        }
        if (containsOsc(rawChunk, "12")) {
            r.append("\033]12;rgb:eeee/eeee/eeee\033\\");
        }
        return r.toString();
    }

    static boolean containsOsc(String data, String code) {
        String prefix = "\033]" + code + ";?";
        return data.contains(prefix + "\007") || data.contains(prefix + "\033\\");
    }

    static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
