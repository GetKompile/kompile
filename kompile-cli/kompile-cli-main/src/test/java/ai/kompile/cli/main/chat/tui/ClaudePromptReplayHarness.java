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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Local analysis harness (NOT a CI test). Replays a captured claude-code PTY dump
 * (raw agent output from a real managed-lane session) through the REAL pipeline
 * pieces — VirtualTerminal + ClaudeCodeDecoder + the reader loop's awaiting-input
 * detection and the decoded-delta render gating — and prints, frame by frame,
 * what the user's transcript actually received. Used to diagnose prompt/dialog
 * rendering, driven by the frame boundaries ink itself emits (ESC[?2026h/l).
 *
 * Run with: -Dpty.harness=true and -Dpty.dump=<path to raw dump>
 * (defaults to /tmp/claude-prompt-repro.bin).
 */
@EnabledIfSystemProperty(named = "pty.harness", matches = "true")
public class ClaudePromptReplayHarness {

    private static final int ROWS = 33;   // dump parks the cursor at ESC[33;1H
    private static final int COLS = 160;  // widest addressed col in the dump is ~137

    @Test
    void replayDecodedPipeline() throws Exception {
        Path dump = Path.of(System.getProperty("pty.dump", "/tmp/claude-prompt-repro.bin"));
        String stream = new String(Files.readAllBytes(dump), StandardCharsets.UTF_8);

        // Split on ink's synchronized-update terminator so each fed chunk is one
        // coherent repaint — the finest cadence the real settle-gated loop can see.
        List<String> frames = new ArrayList<>();
        int start = 0;
        String marker = "[?2026l";
        int idx;
        while ((idx = stream.indexOf(marker, start)) >= 0) {
            frames.add(stream.substring(start, idx + marker.length()));
            start = idx + marker.length();
        }
        if (start < stream.length()) frames.add(stream.substring(start));

        VirtualTerminal vt = new VirtualTerminal(ROWS, COLS);
        AgentTuiDecoder decoder = AgentTuiDecoder.forAgent("claude");
        decoder.resetHistory();

        // Per-turn faithful mode: replay everything into the VT, but start the
        // decoded turn (resetHistory + blank rendered-content, like sendMessage does)
        // at the frame where the given message text first reaches claude's input box.
        String turnMarker = System.getProperty("pty.turnMarker", "Prompt me with a yes or no");

        boolean turnStarted = false;
        boolean awaiting = false;
        String emitted = "";        // what the user's transcript accumulated
        String lastRendered = "";   // tuiLastRenderedContent equivalent
        int frameNo = 0;
        int blankedDeltas = 0;

        for (String frame : frames) {
            frameNo++;
            vt.feed(frame);

            if (!turnStarted && frame.contains(turnMarker)) {
                decoder.resetHistory();
                // FIX 4: seed baseline from the current VT so still-visible previous-turn rows
                // don't get re-emitted as new content on the first observe() call.
                decoder.observe(vt);
                lastRendered = decoder.renderHistory();
                emitted = "";
                turnStarted = true;
                System.out.println("== frame " + frameNo + ": TURN START (resetHistory, rendered='"
                        + (lastRendered.length() > 60 ? lastRendered.substring(0, 60) + "..." : lastRendered)
                        + "') ==");
            }
            if (!turnStarted) continue;   // decoded pipeline inactive between turns (tuiFullText==null)

            boolean awaitingNow = decoder.isAwaitingUserInput(vt);
            if (awaitingNow != awaiting) {
                awaiting = awaitingNow;
                System.out.println("== frame " + frameNo + ": isAwaitingUserInput -> " + awaitingNow
                        + " | isResponding=" + decoder.isResponding(vt)
                        + " isIdle=" + decoder.isIdle(vt)
                        + " altScreen=" + vt.isInAlternateScreen() + " ==");
                if (awaitingNow) {
                    String prompt = decoder.extractPromptText(vt);
                    System.out.println("   BANNER extractPromptText='" + prompt + "'");
                    System.out.println("   selectedOptionDigit=" + decoder.selectedOptionDigit(vt));
                }
            }

            // processDecodedTuiScreen equivalent.
            decoder.observe(vt);
            String extracted = decoder.renderHistory();
            if (extracted == null || extracted.isBlank()) extracted = decoder.extractStreamingContent(vt);
            if (extracted == null || extracted.isBlank()) continue;
            String renderable = renderableDecodedDelta(lastRendered, extracted, decoder.isIdle(vt));
            if (renderable == null || renderable.isBlank()) { blankedDeltas++; continue; }
            emitted = emitted + renderable;
            lastRendered = updateRenderedDecodedContent(lastRendered, extracted, renderable);
        }

        // flushDecodedTuiRemainder equivalent at turn end.
        decoder.observe(vt);
        String extracted = decoder.renderHistory();
        if (extracted != null && !extracted.isBlank()) {
            String remainder = renderableDecodedDelta(lastRendered, extracted, true);
            if (remainder != null && !remainder.isBlank()) emitted = emitted + remainder;
            else System.out.println("== turn-end flush: remainder BLANK (delta diverged) ==");
        }

        System.out.println("\n######## EMITTED TO USER'S TRANSCRIPT (" + frames.size()
                + " frames, " + blankedDeltas + " frames produced no renderable delta) ########");
        System.out.println(emitted);

        System.out.println("\n######## FINAL DECODER HISTORY ########");
        for (AgentTuiDecoder.HistoryEntry e : decoder.history()) {
            System.out.printf("[%-8s] %s%n", e.kind(), e.text());
        }

        System.out.println("\n######## FINAL VT SCREEN ########");
        for (int r = 0; r < ROWS; r++) {
            String row = vt.getRow(r);
            if (row != null && !row.strip().isEmpty()) System.out.printf("%2d | %s%n", r, row);
        }
        System.out.println("\nfinal: isResponding=" + decoder.isResponding(vt)
                + " isIdle=" + decoder.isIdle(vt)
                + " isAwaitingUserInput=" + decoder.isAwaitingUserInput(vt)
                + " extractPromptText='" + decoder.extractPromptText(vt) + "'");
    }

    // ---- exact copies of EmulatedPassthroughCommand's private delta gating ----

    private String renderableDecodedDelta(String rendered, String current, boolean finalChunk) {
        if (current == null || current.isBlank()) return "";
        String previous = rendered == null ? "" : rendered;
        if (current.equals(previous)) return "";
        String delta;
        if (previous.isBlank()) {
            delta = current;
        } else if (current.startsWith(previous)) {
            delta = current.substring(previous.length());
        } else if (previous.contains(current)) {
            return "";
        } else {
            return "";
        }
        if (finalChunk) return delta;
        int lastNewline = delta.lastIndexOf('\n');
        if (lastNewline < 0) return "";
        return delta.substring(0, lastNewline + 1);
    }

    private String updateRenderedDecodedContent(String rendered, String current, String emitted) {
        if (current == null) return rendered == null ? "" : rendered;
        String previous = rendered == null ? "" : rendered;
        if (emitted == null || emitted.isBlank()) return previous;
        if (previous.isBlank() && current.startsWith(emitted)) return emitted;
        if (current.startsWith(previous + emitted)) return previous + emitted;
        return current;
    }
}
