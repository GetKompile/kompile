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
 * Agent-specific TUI decoder. Each CLI agent (OpenCode, Claude Code, Gemini CLI,
 * etc.) has a different TUI layout — different chrome rows, different content
 * areas, different terminal query needs. A decoder understands one agent's
 * rendering and can extract content text from its VT screen buffer.
 * <p>
 * Most raw PTY passthrough agents handle rendering by sending bytes directly to
 * the terminal. Some alternate-screen TUIs need decoder-owned rendering instead:
 * Kompile answers their terminal probes, shadows their screen, and renders only
 * extracted assistant text into the Kompile scroll area.
 * <p>
 * Implementations are derived from PTY dump analysis of each agent's actual
 * escape sequence output. Dumps are captured at ~/.kompile/logs/agent-pty-dump.bin
 * and analyzed with xxd/cat.
 */
public interface AgentTuiDecoder {

    /** Classification of a rendered TUI row. */
    enum TuiLineKind {
        /** Assistant prose / answer text. */
        CONTENT,
        /** A tool call header or its detail/result line. */
        TOOL,
        /** A transient progress/spinner line (e.g. "Working (4s · esc to interrupt)"). */
        PROGRESS,
        /** Banner/status/input chrome that is never part of the transcript. */
        CHROME
    }

    /**
     * One accumulated transcript entry. {@code text} is the plain visible text
     * (used for classification, merging and logging); {@code styled} is the same
     * line with the agent's foreground colour / attributes re-emitted as ANSI,
     * used for display.
     */
    record HistoryEntry(TuiLineKind kind, String text, String styled) {
        HistoryEntry(TuiLineKind kind, String text) { this(kind, text, text); }
    }

    /**
     * Returns the agent name this decoder handles (lowercase).
     */
    String agentName();

    // ------------------------------------------------------------------
    // Message history
    //
    // Because the agent renders into a fixed-size screen, long responses scroll
    // their own viewport and lines are lost between snapshots. The decoder keeps
    // a running, re-renderable transcript so kompile can repaint the full turn
    // into its fixed viewport regardless of what the agent currently shows.
    // ------------------------------------------------------------------

    /** Clear the accumulated transcript. Call at the start of each turn. */
    default void resetHistory() {}

    /**
     * Ingest the current screen snapshot, merging newly-visible content/tool lines
     * into the transcript and recording the current progress line. Lines that
     * scrolled off the agent's screen since the last call are retained.
     */
    default void observe(VirtualTerminal vt) {}

    /** The accumulated transcript entries, in order. */
    default java.util.List<HistoryEntry> history() { return java.util.List.of(); }

    /** The accumulated transcript rendered to text (content + tool lines). */
    default String renderHistory() { return ""; }

    /** The most recently observed progress/spinner line, or empty when idle. */
    default String currentProgress() { return ""; }

    /** Classify a row that has had box borders stripped and whitespace normalized. */
    default TuiLineKind classify(String normalizedRow) { return TuiLineKind.CONTENT; }

    /**
     * Extract content text from the VT screen buffer, filtering out chrome
     * (status bars, keybindings, logos, separators, input areas).
     * <p>
     * The VirtualTerminal maintains the screen state from the raw PTY feed.
     * This method reads the screen and returns only the response content.
     *
     * @param vt the virtual terminal with current screen state
     * @return extracted content text, or empty string if no content
     */
    String extractContent(VirtualTerminal vt);

    /**
     * Whether subprocess display bytes should be forwarded directly to the real
     * terminal. Agents with full-screen alternate UIs should return false and
     * let Kompile render decoded text instead.
     */
    default boolean renderRawTui() {
        return true;
    }

    /**
     * Extract the current text intended for live rendering. The default is the
     * final content extractor; decoders can override if their live screen needs
     * different filtering from their final transcript.
     */
    default String extractStreamingContent(VirtualTerminal vt) {
        return extractContent(vt);
    }

    /**
     * True while the agent's UI indicates that a model response is in progress.
     */
    default boolean isResponding(VirtualTerminal vt) {
        return false;
    }

    /**
     * True when the agent's UI has returned to its prompt/idle state.
     */
    default boolean isIdle(VirtualTerminal vt) {
        return false;
    }

    /**
     * Minimum quiet period after the decoder observes idle before a turn ends.
     */
    default long turnIdleMillis() {
        return 1200L;
    }

    /**
     * Build terminal query responses that this agent needs to proceed.
     * Different agents need different responses — some need Kitty keyboard
     * protocol, others break if they receive it.
     *
     * @param rawChunk the raw PTY output chunk that may contain queries
     * @param vt the virtual terminal (for cursor position in DSR responses)
     * @return response bytes to write back to subprocess stdin, or empty string
     */
    String buildResponses(String rawChunk, VirtualTerminal vt);

    /**
     * Build agent-specific bootstrap/input responses for known TUI prompts that
     * block startup before a user message can be processed. Rendering still uses
     * raw PTY passthrough; this only writes explicit input back to the subprocess.
     */
    default String buildInputResponses(String rawChunk, VirtualTerminal vt) {
        return "";
    }

    /**
     * Extra startup time for agent-specific bootstrap prompts to render and be
     * answered before Kompile injects the user's first message.
     */
    default long startupSettleMillis() {
        return 2000L;
    }

    /**
     * Key sequence Kompile writes after a managed prompt. Different raw TUIs
     * normalize Enter differently when stdin is attached to a PTY.
     */
    default String submitSequence() {
        return "\r";
    }

    /**
     * Optional pause between writing prompt text and the submit key.
     */
    default long submitDelayMillis() {
        return 0L;
    }

    /**
     * Returns the rows that constitute the content area for this agent,
     * given the current terminal dimensions. Content is rendered in these
     * rows; everything else is chrome.
     *
     * @param totalRows total rows in the PTY
     * @return int[2] = {firstContentRow, lastContentRow} (0-indexed)
     */
    int[] contentRowRange(int totalRows);

    /**
     * Factory: create the appropriate decoder for an agent binary name.
     */
    static AgentTuiDecoder forAgent(String agentName) {
        if (agentName == null) return new GenericDecoder();
        String lower = agentName.toLowerCase();
        if (lower.contains("opencode")) return new OpenCodeDecoder();
        if (lower.contains("claude")) return new ClaudeCodeDecoder();
        if (lower.contains("codex")) return new CodexDecoder();
        if (lower.contains("gemini")) return new GeminiCliDecoder();
        return new GenericDecoder();
    }
}
