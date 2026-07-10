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

package ai.kompile.cli.main.chat.terminal;

/**
 * Pure turn-completion decision for decoder-owned (VT-scraped) agents — the semantic core of L4's
 * VT decoder tap (design WP8), extracted from the god-class {@code decodedTuiTurnComplete} so it can
 * be unit-tested in isolation.
 *
 * <p>An agent's turn is "done" when, since the message was sent, it has produced content and then
 * gone quiet; the exact thresholds absorb the different ways an agent signals idle:</p>
 * <ol>
 *   <li>saw content, decoder says idle, and quiet for at least the decoder's turn-idle window;</li>
 *   <li>saw content, decoder no longer responding, quiet for the longer {@code max(2500, idle+1000)} window;</li>
 *   <li>saw content and quiet for a hard 6 s backstop (decoder idle/responding both unreliable);</li>
 *   <li>never saw content but idle for 45 s — the agent produced nothing and we give up.</li>
 * </ol>
 */
public final class TurnIdleDetector {

    /** Hard backstop: content produced then quiet this long ends the turn regardless of decoder state. */
    public static final long QUIET_BACKSTOP_MS = 6_000L;

    /** No-content give-up: idle with nothing produced this long after send ends the turn. */
    public static final long NO_CONTENT_GIVEUP_MS = 45_000L;

    private TurnIdleDetector() {
    }

    /**
     * @param sawContent      whether any assistant content has been observed since the message was sent
     * @param idle            decoder's idle verdict for the current screen
     * @param responding      decoder's responding verdict for the current screen
     * @param quietForMs      milliseconds since the later of (message sent, last output)
     * @param turnIdleMillis  the decoder's configured turn-idle window
     * @param sinceSentMs     milliseconds since the message was sent
     * @return whether the turn should be considered complete
     */
    public static boolean turnComplete(boolean sawContent, boolean idle, boolean responding,
                                       long quietForMs, long turnIdleMillis, long sinceSentMs) {
        if (sawContent && idle && quietForMs >= turnIdleMillis) {
            return true;
        }
        if (sawContent && !responding && quietForMs >= Math.max(2500L, turnIdleMillis + 1000L)) {
            return true;
        }
        if (sawContent && quietForMs >= QUIET_BACKSTOP_MS) {
            return true;
        }
        return !sawContent && idle && sinceSentMs >= NO_CONTENT_GIVEUP_MS;
    }
}
