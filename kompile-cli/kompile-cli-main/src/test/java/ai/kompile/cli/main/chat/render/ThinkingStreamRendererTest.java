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

package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ThinkingStreamRenderer}.
 * <p>
 * Covers the core formatting contract: word-sized deltas coalesce into
 * complete lines, the header appears once per burst, flush emits the
 * trailing partial line, blank runs collapse, and a null terminal renderer
 * yields plain (ANSI-free) output.
 */
class ThinkingStreamRendererTest {

    private static List<String> render(TerminalRenderer term, String... chunks) {
        List<String> lines = new ArrayList<>();
        ThinkingStreamRenderer renderer = new ThinkingStreamRenderer(lines::add, term);
        for (String chunk : chunks) {
            renderer.accept(chunk);
        }
        renderer.flush();
        return lines;
    }

    @Test
    void wordSizedDeltas_coalesceIntoWholeLines() {
        List<String> lines = render(null, "I should ", "check the ", "config fi",
                "rst beca", "use auth ", "matters\n");
        assertEquals(2, lines.size(), "header + one complete line, got " + lines);
        assertEquals("  ✻ thinking", lines.get(0));
        assertEquals("  I should check the config first because auth matters", lines.get(1));
    }

    @Test
    void trailingPartialLine_flushEmitsIt() {
        List<String> lines = render(null, "first line\n", "partial tail that never saw newline");
        assertEquals(3, lines.size());
        assertEquals("  first line", lines.get(1));
        assertEquals("  partial tail that never saw newline", lines.get(2));
    }

    @Test
    void flush_endsBurst_nextAcceptStartsFreshHeader() {
        List<String> lines = new ArrayList<>();
        ThinkingStreamRenderer renderer = new ThinkingStreamRenderer(lines::add, null);
        renderer.accept("burst one\n");
        renderer.flush();
        renderer.accept("burst two\n");
        renderer.flush();

        assertEquals("  ✻ thinking", lines.get(0));
        assertEquals("  burst one", lines.get(1));
        assertEquals("  ✻ thinking", lines.get(2), "second burst must re-open the header");
        assertEquals("  burst two", lines.get(3));
    }

    @Test
    void blankRuns_collapseToOneParagraphBreak() {
        List<String> lines = render(null, "para one\n", "\n", "\n\n", "para two\n");
        assertEquals(4, lines.size(), "got " + lines);
        assertEquals("  ✻ thinking", lines.get(0));
        assertEquals("  para one", lines.get(1));
        assertEquals("", lines.get(2));
        assertEquals("  para two", lines.get(3));
    }

    @Test
    void leadingBlanks_neverOpenABurst() {
        List<String> lines = render(null, "\n\n", "actual content\n");
        assertEquals(2, lines.size(), "got " + lines);
        assertEquals("  ✻ thinking", lines.get(0));
        assertEquals("  actual content", lines.get(1));
    }

    @Test
    void blankOnlyBurst_emitsNothing() {
        List<String> lines = render(null, "\n", "\n");
        assertTrue(lines.isEmpty(), "a whitespace-only burst must not emit even a header");
    }

    @Test
    void carriageReturns_treatedAsLineBreaks() {
        List<String> lines = render(null, "chunked\r", "progress\r", "final\n");
        assertEquals(4, lines.size());
        assertEquals("  chunked", lines.get(1));
        assertEquals("  progress", lines.get(2));
        assertEquals("  final", lines.get(3));
    }

    @Test
    void nullTerminalRenderer_plainOutput_noAnsi() {
        List<String> lines = render(null, "plain\n");
        for (String line : lines) {
            assertFalse(line.contains("\u001b"), "no ANSI escapes expected: " + line);
        }
    }

    @Test
    void terminalRenderer_stylesHeaderAndBody() {
        TerminalRenderer term = new TerminalRenderer(true);
        List<String> lines = render(term, "styled\n");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("\u001b[2m"), "header must be dimmed: " + lines.get(0));
        assertTrue(lines.get(1).startsWith("\u001b[2m"), "body must be dimmed: " + lines.get(1));
        assertTrue(lines.get(1).contains("\u001b[3m"), "body must be italic: " + lines.get(1));
    }

    @Test
    void nullAndEmptyChunks_ignored() {
        List<String> lines = new ArrayList<>();
        ThinkingStreamRenderer renderer = new ThinkingStreamRenderer(lines::add, null);
        renderer.accept(null);
        renderer.accept("");
        renderer.flush();
        assertTrue(lines.isEmpty());
    }
}
