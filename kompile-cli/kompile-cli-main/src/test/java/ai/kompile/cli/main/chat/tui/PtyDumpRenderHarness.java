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

/**
 * Local analysis harness (NOT a CI test). Reads raw PTY dumps captured from real
 * agent CLIs, feeds them through the real VirtualTerminal, and prints the rendered
 * screen plus what each decoder extracts / how it classifies state. Used to build
 * and validate chrome filters against ground truth.
 *
 * Run with: -Dpty.harness=true (dumps must exist under /tmp/kompile-pty-capture).
 */
@EnabledIfSystemProperty(named = "pty.harness", matches = "true")
public class PtyDumpRenderHarness {

    private static final int ROWS = 40;
    private static final int COLS = 120;
    private static final String DIR = "/tmp/kompile-pty-capture";

    @Test
    void renderAccumulatedHistory() throws Exception {
        for (String name : new String[]{"codex-tool", "opencode-tool"}) {
            Path p = Path.of(DIR, name + ".raw");
            if (!Files.exists(p)) { System.out.println("\n%%%%% " + name + ": NO DUMP %%%%%"); continue; }
            String stream = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            VirtualTerminal vt = new VirtualTerminal(ROWS, COLS);
            AgentTuiDecoder decoder = AgentTuiDecoder.forAgent(name.replace("-tool", ""));
            decoder.resetHistory();
            int chunk = 1200;
            for (int i = 0; i < stream.length(); i += chunk) {
                vt.feed(stream.substring(i, Math.min(stream.length(), i + chunk)));
                decoder.observe(vt);   // accumulate transcript across the fixed-viewport stream
            }
            System.out.println("\n%%%%% " + name + " ACCUMULATED HISTORY %%%%%");
            for (AgentTuiDecoder.HistoryEntry e : decoder.history()) {
                System.out.printf("[%-8s] %s%n", e.kind(), e.text());
            }
            System.out.println("progress(last): '" + decoder.currentProgress() + "'");
        }
    }

    @Test
    void renderToolEvolution() throws Exception {
        for (String name : new String[]{"codex-tool", "opencode-tool"}) {
            Path p = Path.of(DIR, name + ".raw");
            if (!Files.exists(p)) { System.out.println("\n@@@@@ " + name + ": NO DUMP @@@@@"); continue; }
            String stream = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            VirtualTerminal vt = new VirtualTerminal(ROWS, COLS);
            int chunk = 1500;
            int checkpoints = 6;
            int step = Math.max(1, (stream.length() / chunk) / checkpoints);
            int fed = 0, cp = 0;
            System.out.println("\n@@@@@ " + name + " evolution (" + stream.length() + " chars) @@@@@");
            for (int i = 0; i < stream.length(); i += chunk) {
                vt.feed(stream.substring(i, Math.min(stream.length(), i + chunk)));
                fed++;
                if (fed % step == 0 && cp < checkpoints) {
                    cp++;
                    System.out.println("----- checkpoint " + cp + " (rows with text) -----");
                    for (int r = 0; r < ROWS; r++) {
                        String row = vt.getRow(r);
                        if (row != null && !row.strip().isEmpty()) System.out.printf("%2d | %s%n", r, row);
                    }
                }
            }
            System.out.println("----- FINAL screen -----");
            for (int r = 0; r < ROWS; r++) {
                String row = vt.getRow(r);
                if (row != null && !row.strip().isEmpty()) System.out.printf("%2d | %s%n", r, row);
            }
        }
    }

    @Test
    void renderAll() throws Exception {
        for (String name : new String[]{"opencode", "claude", "codex", "gemini", "qwen"}) {
            Path p = Path.of(DIR, name + ".raw");
            if (!Files.exists(p)) {
                System.out.println("\n##### " + name + ": NO DUMP #####");
                continue;
            }
            byte[] raw = Files.readAllBytes(p);
            String stream = new String(raw, StandardCharsets.UTF_8);
            VirtualTerminal vt = new VirtualTerminal(ROWS, COLS);
            // Feed in chunks to mimic the real reader.
            for (int i = 0; i < stream.length(); i += 4096) {
                vt.feed(stream.substring(i, Math.min(stream.length(), i + 4096)));
            }
            AgentTuiDecoder decoder = AgentTuiDecoder.forAgent(name);

            System.out.println("\n##### " + name + " (" + raw.length + " bytes, decoder="
                    + decoder.getClass().getSimpleName()
                    + ", renderRawTui=" + decoder.renderRawTui() + ") #####");
            System.out.println("--- RENDERED SCREEN (row | text) ---");
            for (int r = 0; r < ROWS; r++) {
                String row = vt.getRow(r);
                if (row != null && !row.strip().isEmpty()) {
                    System.out.printf("%2d | %s%n", r, row);
                }
            }
            System.out.println("--- isResponding=" + decoder.isResponding(vt)
                    + " isIdle=" + decoder.isIdle(vt) + " ---");
            System.out.println("--- EXTRACTED CONTENT ---");
            System.out.println(decoder.extractContent(vt));
            System.out.println("--- END " + name + " ---");
        }
    }
}
