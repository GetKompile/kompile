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
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The resume browser is a line-oriented pager: the mouse wheel must scroll the
 * host terminal's native scrollback, never be reported to the application.
 * Standard chat leaves the real terminal in mouse-tracking mode for its managed
 * transcript, and this browser has no mouse widget, so a wheel report would
 * otherwise be typed into the command prompt as literal characters
 * (e.g. {@code <64;20;5M}).
 */
class ResumeToolMouseTrackingTest {

    private static String renderModes(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            if (b == 0x1B) {
                out.append("ESC");
            } else if (b == '\n') {
                out.append('\n');
            } else {
                out.append((char) b);
            }
        }
        return out.toString();
    }

    private static void assertContainsMode(String sequence, String message, ByteArrayOutputStream output) {
        assertTrue(
                output.toString(StandardCharsets.UTF_8).contains(sequence),
                message + " (output was: "
                        + renderModes(output.toByteArray()) + ")");
    }

    /**
     * Every wheel-report encoding plus focus reporting and bracketed paste must be
     * disabled. Without this, a wheel event inherited from standard chat arrives at
     * JLine's self-insert and pollutes the prompt.
     */
    @Test
    void mouseTrackingDisableCoversAllWheelReportEncodings() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("resume-mouse-test", "xterm",
                output, StandardCharsets.UTF_8);
        try {
            ResumeTool.disableTerminalMouseTracking(terminal);
            terminal.writer().flush();

            // Full sweep covers legacy X10 (?9/?1000), button-motion (?1002),
            // any-motion (?1003), focus reporting (?1004), the 8/5-bit mouse
            // encodings (?1005/?1015), SGR (?1006), alternate-scroll (?1007),
            // extended coordinates (?1016), and bracketed paste (?2004).
            for (String reset : new String[] {
                    "\033[?9l", "\033[?1000l", "\033[?1001l", "\033[?1002l",
                    "\033[?1003l", "\033[?1004l", "\033[?1005l", "\033[?1006l",
                    "\033[?1007l", "\033[?1015l", "\033[?1016l", "\033[?2004l"}) {
                assertContainsMode(reset, "disable must emit " + reset, output);
            }
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("h\033[?1000"),
                    "disable must never enable tracking");
        } finally {
            terminal.close();
        }
    }

    /**
     * The browser must keep JLine's native mouse prefix unbound. A binding here
     * would make the reader consume the report instead of the terminal emulator
     * being told to stop sending them, hiding the disable round-trip.
     */
    @Test
    void sgrMousePrefixStaysUnboundInPlainBrowserReader() throws Exception {
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("resume-reader-test", "xterm",
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        try {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            LineReaderImpl impl = (LineReaderImpl) reader;
            for (KeyMap<Binding> keyMap : impl.getKeyMaps().values()) {
                if (keyMap == null) {
                    continue;
                }
                Binding bound = keyMap.getBound("\033[<");
                if (bound instanceof Reference reference) {
                    assertNotEquals("self-insert", reference.name(),
                            "SGR wheel prefix must not self-insert as literal characters");
                }
            }
        } finally {
            terminal.close();
        }
    }

    /**
     * The disable sequence must be a real DECSET-reset sweep on the terminal
     * output stream, not a no-op. Feeding chat's enable modes into a virtual
     * terminal first proves the resets actually clear them.
     */
    @Test
    void disableSequenceResetsModesEnabledByStandardChat() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("resume-reset-test", "xterm",
                output, StandardCharsets.UTF_8);
        try {
            // Simulate standard chat's capture assertion plus an agent TUI's
            // alternate-scroll mode.
            terminal.writer().print("\033[?1000h\033[?1002h\033[?1006h\033[?1007h");
            terminal.writer().flush();
            output.reset();

            ResumeTool.disableTerminalMouseTracking(terminal);
            terminal.writer().flush();

            String written = output.toString(StandardCharsets.UTF_8);
            for (String reset : new String[] {
                    "\033[?1000l", "\033[?1002l", "\033[?1006l", "\033[?1007l"}) {
                assertContainsMode(reset, "reset must disable " + reset, output);
            }
            assertFalse(written.contains("\033[?1000h"),
                    "disable must never (re)enable tracking");
        } finally {
            terminal.close();
        }
    }

    /**
     * A null terminal must be tolerated: the interactive browser upgrades lazily
     * in MCP mode, and callers run cleanup defensively during shutdown.
     */
    @Test
    void disableToleratesNullTerminal() {
        assertDoesNotThrow(() -> ResumeTool.disableTerminalMouseTracking(null));
    }

    /**
     * Terminal teardown while writing the sweep must not throw. ChatRepl's
     * equivalent cleanup swallows IOError and RuntimeException for the same reason.
     */
    @Test
    void disableSurvivesClosedTerminal() throws Exception {
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("resume-closed-test", "xterm",
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        try {
            terminal.close();
        } catch (Exception ignored) {
            // Already-closed teardown is fine here too.
        }
        assertDoesNotThrow(() -> ResumeTool.disableTerminalMouseTracking(terminal));
    }
}
