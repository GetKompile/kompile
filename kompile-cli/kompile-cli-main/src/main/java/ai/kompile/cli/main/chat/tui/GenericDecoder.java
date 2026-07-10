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
 * Fallback decoder for unknown agents.
 * <p>
 * Makes minimal assumptions: the entire screen is content, and only
 * basic terminal queries (DSR, DA1) are answered. This is safe for
 * any agent since it doesn't inject protocol responses that could
 * be misinterpreted (e.g. Kitty keyboard).
 */
public class GenericDecoder implements AgentTuiDecoder {

    @Override
    public String agentName() {
        return "generic";
    }

    @Override
    public int[] contentRowRange(int totalRows) {
        // No chrome knowledge — treat the entire screen as content.
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

    /** Conservative: DSR/DA/window/OSC only — no Kitty keyboard, no XTVERSION (safe for any agent). */
    @Override
    public QueryPolicy queryPolicy() {
        return QueryPolicy.CONSERVATIVE;
    }

    @Override
    public String buildResponses(String rawChunk, VirtualTerminal vt) {
        return TerminalQueryResponder.respond(rawChunk, vt, queryPolicy());
    }
}
