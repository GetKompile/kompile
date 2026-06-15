/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCompactorTest {

    @Test
    void compactCollapsesOlderTurnsAndPreservesRecentWindow() {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            turns.add(new ChatHistory.Turn("user", "user request " + i));
            turns.add(new ChatHistory.Turn("assistant", "assistant progress " + i));
        }

        ConversationCompactor.CompactResult result =
                ConversationCompactor.compact(turns, 8_000, 4);

        assertTrue(result.compacted());
        assertEquals(60, result.originalTurnCount());
        assertEquals(5, result.compactedTurnCount());
        assertEquals("user", result.turns().get(0).role());
        assertTrue(result.turns().get(0).content().contains("compacted cross-agent resume context"));
        assertTrue(result.turns().get(0).content().contains("user request 0"));
        assertEquals("user request 28", result.turns().get(1).content());
        assertEquals("assistant progress 29", result.turns().get(4).content());
        assertTrue(result.charsAfter() < result.charsBefore());
    }

    @Test
    void compactLeavesSmallConversationUnchanged() {
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("user", "hello"),
                new ChatHistory.Turn("assistant", "hi"));

        ConversationCompactor.CompactResult result =
                ConversationCompactor.compact(turns, 8_000, 4);

        assertFalse(result.compacted());
        assertEquals(turns, result.turns());
        assertEquals(2, result.compactedTurnCount());
    }

    @Test
    void compactTruncatesHugeRecentTurnToBudget() {
        String huge = "x".repeat(20_000);
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("user", "older request"),
                new ChatHistory.Turn("assistant", huge));

        ConversationCompactor.CompactResult result =
                ConversationCompactor.compact(turns, 4_000, 1);

        assertTrue(result.compacted());
        assertTrue(result.charsAfter() <= 4_200);
        assertTrue(result.turns().get(1).content().contains("truncated for resume"));
    }
}
