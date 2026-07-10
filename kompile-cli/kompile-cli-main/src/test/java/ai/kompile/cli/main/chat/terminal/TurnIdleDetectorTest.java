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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the extracted VT turn-idle decision (WP8). */
class TurnIdleDetectorTest {

    private static final long IDLE = 1200L;

    @Test
    void completesWhenIdleAndQuietPastTurnWindow() {
        assertTrue(TurnIdleDetector.turnComplete(true, true, false, IDLE, IDLE, 5_000L),
                "content + idle + quiet >= turn window");
        assertFalse(TurnIdleDetector.turnComplete(true, true, false, IDLE - 1, IDLE, 5_000L),
                "not yet quiet for the turn window");
    }

    @Test
    void completesWhenNotRespondingPastLongerWindow() {
        // Not idle, but no longer responding and quiet for max(2500, idle+1000)=2500.
        assertTrue(TurnIdleDetector.turnComplete(true, false, false, 2_600L, IDLE, 3_000L),
                "content + not-responding + quiet >= max(2500, idle+1000)");
        assertFalse(TurnIdleDetector.turnComplete(true, false, false, 2_400L, IDLE, 3_000L),
                "not-responding but not quiet long enough");
    }

    @Test
    void hardBackstopEndsTurnRegardlessOfDecoderState() {
        // Decoder says still responding and not idle, but 6s of quiet ends it anyway.
        assertTrue(TurnIdleDetector.turnComplete(true, false, true, TurnIdleDetector.QUIET_BACKSTOP_MS, IDLE, 7_000L),
                "6s quiet backstop");
        assertFalse(TurnIdleDetector.turnComplete(true, false, true, 5_999L, IDLE, 7_000L),
                "just under the backstop, still responding → not done");
    }

    @Test
    void noContentGivesUpAfter45sIdle() {
        assertTrue(TurnIdleDetector.turnComplete(false, true, false, 0L, IDLE, TurnIdleDetector.NO_CONTENT_GIVEUP_MS),
                "no content but idle for 45s → give up");
        assertFalse(TurnIdleDetector.turnComplete(false, true, false, 0L, IDLE, 44_000L),
                "no content and not yet 45s → keep waiting");
        assertFalse(TurnIdleDetector.turnComplete(false, false, false, 0L, IDLE, 60_000L),
                "no content and not idle → keep waiting even past 45s");
    }

    @Test
    void neverCompletesEarlyWhenActivelyResponding() {
        assertFalse(TurnIdleDetector.turnComplete(true, false, true, 500L, IDLE, 1_000L),
                "actively responding, barely any quiet → not done");
    }
}
