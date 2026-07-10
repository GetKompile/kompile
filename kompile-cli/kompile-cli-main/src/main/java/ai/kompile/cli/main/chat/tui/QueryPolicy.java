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
 * Per-agent knobs for {@link TerminalQueryResponder}. Everything else about terminal-query
 * answering (DSR, DA1/DA2, window/text-area reports, OSC 10-12) is identical across agents; only
 * these two vary:
 *
 * <ul>
 *   <li>{@code answersKittyKeyboard} — reply {@code ESC[?0u} to the Kitty keyboard query
 *       ({@code ESC[?u}). Most TUIs are fine with it; Claude Code mis-handles the reply and
 *       launches nano, so it disables this.</li>
 *   <li>{@code answersXtVersion} — reply to XTVERSION ({@code ESC[>q}) with a neutral identity.
 *       Decoders derived from {@link AbstractTuiDecoder} answer it; the conservative fallback
 *       decoders (generic, gemini) do not.</li>
 * </ul>
 */
public record QueryPolicy(boolean answersKittyKeyboard, boolean answersXtVersion) {

    /** Answers both Kitty keyboard and XTVERSION — the {@link AbstractTuiDecoder} baseline. */
    public static final QueryPolicy DEFAULT = new QueryPolicy(true, true);

    /** Answers neither — the safe fallback for unknown/stub agents (generic, gemini). */
    public static final QueryPolicy CONSERVATIVE = new QueryPolicy(false, false);

    public QueryPolicy withKittyKeyboard(boolean v) {
        return new QueryPolicy(v, answersXtVersion);
    }
}
