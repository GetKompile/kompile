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

import java.util.function.Consumer;

/**
 * Line-buffered renderer for streamed model reasoning.
 * <p>
 * Vendors emit thinking as word-sized deltas; printing each delta on its own
 * terminal line turns one reasoning paragraph into dozens of fragments. This
 * renderer accumulates deltas in a line buffer and emits a terminal line only
 * when a newline arrives, so output stays visually coherent while still
 * streaming in real time.
 * <p>
 * A dim "{@code ✻ thinking}" header opens each reasoning burst — a burst ends
 * at {@link #flush()}, which callers invoke when ordinary text, a tool call,
 * or the turn end resumes. {@link #flush()} also emits any trailing partial
 * line so the tail of a burst is never lost. Blank runs are collapsed to a
 * single blank line (paragraph breaks).
 * <p>
 * Styling: when a {@link TerminalRenderer} is supplied, body lines are dimmed
 * and italicized and the header dimmed; with {@code null} the lines are plain
 * (safe for piped/recorded output). Not thread-safe: call from the single
 * thread that processes the agent's output events.
 */
public final class ThinkingStreamRenderer {

    private static final String HEADER_GLYPH = "\u273b"; // ✻ six-spoked asterisk
    private static final String INDENT = "  ";

    private final Consumer<String> lineSink;
    private final TerminalRenderer term;

    private final StringBuilder lineBuffer = new StringBuilder();
    private boolean headerShown;
    private boolean lastEmittedWasBlank;

    public ThinkingStreamRenderer(Consumer<String> lineSink, TerminalRenderer term) {
        this.lineSink = lineSink != null ? lineSink : line -> { };
        this.term = term;
    }

    /**
     * Feed one raw reasoning delta. Complete lines are emitted as they form;
     * the trailing partial line waits for the next delta or {@link #flush()}.
     */
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        lineBuffer.append(chunk.replace('\r', '\n'));
        int newline;
        while ((newline = lineBuffer.indexOf("\n")) >= 0) {
            String line = lineBuffer.substring(0, newline);
            lineBuffer.delete(0, newline + 1);
            emitLine(line);
        }
    }

    /**
     * End the current reasoning burst: emit any buffered partial line and
     * close the burst so the next {@link #accept(String)} opens a fresh
     * header. Safe to call repeatedly.
     */
    public void flush() {
        if (lineBuffer.length() > 0) {
            String tail = lineBuffer.toString();
            lineBuffer.setLength(0);
            emitLine(tail);
        }
        headerShown = false;
        lastEmittedWasBlank = false;
    }

    private void emitLine(String raw) {
        String line = raw.stripTrailing();
        if (line.isBlank()) {
            // Collapse blank runs into at most one paragraph break, and never
            // lead a burst with a blank line.
            if (headerShown && !lastEmittedWasBlank) {
                lineSink.accept("");
                lastEmittedWasBlank = true;
            }
            return;
        }
        if (!headerShown) {
            headerShown = true;
            lineSink.accept(term != null
                    ? term.dim(INDENT + HEADER_GLYPH + " thinking")
                    : INDENT + HEADER_GLYPH + " thinking");
            lastEmittedWasBlank = false;
        }
        lineSink.accept(term != null
                ? term.dim(term.italic(INDENT + line))
                : INDENT + line);
        lastEmittedWasBlank = false;
    }
}
