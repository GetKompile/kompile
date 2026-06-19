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
 * Decoder for Gemini CLI (github.com/google-gemini/gemini-cli).
 * <p>
 * TODO: needs PTY dump capture and analysis to determine exact layout.
 * This is a stub based on the generic decoder with Gemini-specific
 * terminal query handling added as we learn more.
 */
public class GeminiCliDecoder implements AgentTuiDecoder {

    @Override
    public String agentName() {
        return "gemini";
    }

    @Override
    public int[] contentRowRange(int totalRows) {
        // Stub — treat entire screen as content until PTY dump analysis.
        return new int[]{0, Math.max(0, totalRows - 1)};
    }

    @Override
    public String extractContent(VirtualTerminal vt) {
        int[] range = contentRowRange(vt.getRows());
        StringBuilder sb = new StringBuilder();
        for (int r = range[0]; r <= range[1]; r++) {
            String row = vt.getRow(r).trim();
            if (row.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(row);
        }
        return sb.toString();
    }

    @Override
    public String buildResponses(String rawChunk, VirtualTerminal vt) {
        if (rawChunk == null || rawChunk.isEmpty()) return "";
        StringBuilder response = new StringBuilder();

        // DSR: cursor position report
        if (rawChunk.contains("\033[6n")) {
            response.append("\033[")
                    .append(clamp(vt.getCursorRow() + 1, 1, vt.getRows()))
                    .append(';')
                    .append(clamp(vt.getCursorCol() + 1, 1, vt.getCols()))
                    .append('R');
        }
        // DSR: terminal status OK
        if (rawChunk.contains("\033[5n")) {
            response.append("\033[0n");
        }
        // Primary device attributes
        if (rawChunk.contains("\033[c") || rawChunk.contains("\033[0c")) {
            response.append("\033[?62;22c");
        }
        // Secondary device attributes
        if (rawChunk.contains("\033[>c") || rawChunk.contains("\033[>0c")) {
            response.append("\033[>0;0;0c");
        }
        // Window size reports
        if (rawChunk.contains("\033[18t")) {
            response.append("\033[8;").append(vt.getRows()).append(';').append(vt.getCols()).append('t');
        }
        if (rawChunk.contains("\033[14t")) {
            response.append("\033[4;").append(vt.getRows() * 16).append(';').append(vt.getCols() * 8).append('t');
        }
        if (rawChunk.contains("\033[16t")) {
            response.append("\033[6;16;8t");
        }
        // OSC color queries
        if (containsOsc(rawChunk, "10")) {
            response.append("\033]10;rgb:eeee/eeee/eeee\033\\");
        }
        if (containsOsc(rawChunk, "11")) {
            response.append("\033]11;rgb:0000/0000/0000\033\\");
        }
        if (containsOsc(rawChunk, "12")) {
            response.append("\033]12;rgb:eeee/eeee/eeee\033\\");
        }

        return response.toString();
    }

    private boolean containsOsc(String data, String code) {
        String prefix = "\033]" + code + ";?";
        return data.contains(prefix + "\007") || data.contains(prefix + "\033\\");
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
