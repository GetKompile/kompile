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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.ChatCompleter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the live compaction progress UI: animated
 * transcript-block frames, append-only fallback behavior, terminal states,
 * and the {@link CompactionProgress} callback contract consumed by
 * {@code AgenticChatLoop#forceCompact}.
 */
class CompactionProgressIndicatorTest {

    private TerminalRenderer plainRenderer;

    @BeforeEach
    void setUp() {
        plainRenderer = new TerminalRenderer(false);
    }

    @AfterEach
    void tearDown() {
        ChatCompleter.setTranscriptBlockOutput(null);
    }

    @Test
    void activeFrameDescribesPhaseAndCounts() {
        String frame = CompactionProgressIndicator.renderActiveFrame(
                plainRenderer, "Summarizing 40 older entries", 3, 57, 120_000L, "1.2s");

        assertTrue(frame.contains("Compacting context"), frame);
        assertTrue(frame.contains("Summarizing 40 older entries"), frame);
        assertTrue(frame.contains("57 entries"), frame);
        assertTrue(frame.contains("portable history estimate: ~120,000 tokens"), frame);
        assertTrue(frame.contains("1.2s"), frame);
        // Exactly two lines: spinner+phase, bar+counts.
        assertEquals(2, frame.split("\\R", -1).length, frame);
    }

    @Test
    void barIsTwentyCells() {
        String bar = CompactionProgressIndicator.renderBar(plainRenderer, 7);
        assertEquals(20, bar.length(), bar);
    }

    @Test
    void completeNoticeLabelsPortableEstimatesRatherThanProviderContextSavings() {
        List<String> emitted = new ArrayList<>();
        ChatCompleter.setTranscriptBlockOutput((key, content) -> false);
        CompactionProgressIndicator indicator = CompactionProgressIndicator.start(
                "compact:test-complete", plainRenderer, 10, 50_000L,
                emitted::add, null);

        indicator.complete(50_000, 12_000, 4);

        assertTrue(indicator.isFinished());
        assertEquals(2, emitted.size(), "one start and one completion, not duplicate notices");
        String notice = emitted.get(1);
        assertTrue(notice.contains("portable history estimate: ~50,000 → ~12,000 tokens"), notice);
        assertTrue(notice.contains("76% history reduction"), notice);
        assertTrue(notice.contains("provider context not measured"), notice);
        assertTrue(notice.contains("4 recent turns preserved"), notice);
        assertFalse(notice.contains("% saved"), notice);
    }

    @Test
    void inlineNativeNoticeDoesNotTreatThePortableDigestAsProviderContext() {
        String notice = plainRenderer.renderCompactionNotice(42_000, 200);
        assertTrue(notice.contains("portable history estimate: ~42000 → ~200 tokens"), notice);
        assertTrue(notice.contains("provider context not measured"), notice);
    }

    @Test
    void unmanagedSurfaceEmitsOneStartLineAndOneTerminalLine() {
        List<String> emitted = new ArrayList<>();
        ChatCompleter.setTranscriptBlockOutput((key, content) -> false);
        AtomicInteger activitySets = new AtomicInteger();

        CompactionProgressIndicator indicator = CompactionProgressIndicator.start(
                "compact:test-fallback", plainRenderer, 3, 900L,
                emitted::add, label -> activitySets.incrementAndGet());
        indicator.phase("Summarizing conversation");
        indicator.failed("summarizer offline");

        // One fallback start line + one terminal failure line; animated frames
        // must never leak onto append-only surfaces.
        assertEquals(2, emitted.size(), emitted.toString());
        assertTrue(emitted.get(0).contains("Compacting context"), emitted.toString());
        assertTrue(emitted.get(1).contains("summarizer offline"), emitted.toString());
        assertTrue(indicator.isFinished());
    }

    @Test
    void managedSurfaceNeverEmitsAppendOnlyLines() {
        List<String> emitted = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        ChatCompleter.setTranscriptBlockOutput((key, content) -> {
            blocks.add(content);
            return true;
        });

        CompactionProgressIndicator indicator = CompactionProgressIndicator.start(
                "compact:test-managed", plainRenderer, 2, 500L,
                emitted::add, null);
        indicator.phase("Inspecting 2 conversation entries");
        indicator.complete(500, 300, 1);

        assertTrue(blocks.size() >= 3, blocks.toString());
        assertTrue(emitted.isEmpty(),
                "managed surfaces must not also print fallback lines: " + emitted);
        assertTrue(blocks.get(blocks.size() - 1).contains("Context compacted"),
                blocks.toString());
    }

    @Test
    void abandonIfActiveOnlyFiresWhenNotFinished() {
        List<String> emitted = new ArrayList<>();
        ChatCompleter.setTranscriptBlockOutput((key, content) -> false);

        CompactionProgressIndicator indicator = CompactionProgressIndicator.start(
                "compact:test-abandon", plainRenderer, 1, 100L,
                emitted::add, null);
        indicator.complete(100, 40, 0);
        indicator.abandonIfActive("must not override completion");
        assertEquals(2, emitted.size());
        assertTrue(emitted.get(1).contains("Context compacted"));

        CompactionProgressIndicator stuck = CompactionProgressIndicator.start(
                "compact:test-abandon-2", plainRenderer, 1, 100L,
                emitted::add, null);
        stuck.abandonIfActive(null);
        // Second indicator: one fallback start line + the abandon noop line.
        assertEquals(4, emitted.size());
        assertTrue(emitted.get(3).contains("Compaction ended"));
    }

    @Test
    void noOpProgressAcceptsEveryPhase() {
        CompactionProgress.NO_OP.phase("anything");
        CompactionProgress.NO_OP.phase(null);
        CompactionProgress.NO_OP.phase("");
    }

    @Test
    void indicatorIsACompactionProgress() {
        CompactionProgressIndicator indicator = CompactionProgressIndicator.start(
                "compact:test-assign", plainRenderer, 0, 0L, line -> { }, null);
        CompactionProgress progress = indicator;
        progress.phase("interface surface works");
        indicator.noop("done");
        assertTrue(indicator.isFinished());
    }

    @Test
    void savedPercentAndTokenFormattingAreSane() {
        assertEquals(76, CompactionProgressIndicator.savedPercent(50_000, 12_000));
        assertEquals(0, CompactionProgressIndicator.savedPercent(0, 0));
        assertEquals(100, CompactionProgressIndicator.savedPercent(10, 0));
        assertEquals("120,000", CompactionProgressIndicator.formatTokens(120_000L));
        assertEquals("0", CompactionProgressIndicator.formatTokens(0L));
    }

    @Test
    void phaseUpdateAfterFinishIsIgnored() {
        ChatCompleter.setTranscriptBlockOutput((key, content) -> false);
        List<String> emitted = new ArrayList<>();
        CompactionProgressIndicator indicator = CompactionProgressIndicator.start(
                "compact:test-late-phase", plainRenderer, 1, 10L,
                emitted::add, null);
        indicator.noop("nothing to do");
        int before = emitted.size();
        indicator.phase("late phase must not resurrect the block");
        assertEquals(before, emitted.size());
    }
}
