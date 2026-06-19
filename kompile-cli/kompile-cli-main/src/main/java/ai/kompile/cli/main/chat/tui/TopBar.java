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

import ai.kompile.utils.AnsiConstants;
import ai.kompile.utils.StringUtils;

import java.io.PrintStream;

import static ai.kompile.utils.AnsiConstants.*;

/**
 * Persistent top bar pinned to the first row of the terminal.
 * Shows the current agent name, session ID, mode, and key shortcuts.
 *
 * The top bar occupies 2 terminal rows:
 * <pre>
 *   Row 1:  kompile  [claude]  session: cli-a1b2c3d4  passthrough  [plan]
 *   Row 2:  ─────────────────────────────────────────  (dim separator)
 * </pre>
 *
 * The scroll region starts at row 3, after the top bar.
 */
public class TopBar {

    /** Number of terminal rows reserved for the top bar (content + separator). */
    public static final int TOP_HEIGHT = 2;

    private volatile String agentName = "";
    private volatile String sessionId = "";
    private volatile String mode = "";
    private volatile boolean planningMode = false;
    private volatile boolean enforcerActive = false;
    private volatile int terminalWidth = 80;

    private final Object drawLock;

    public TopBar(Object drawLock) {
        this.drawLock = drawLock;
    }

    // ── State setters ─────────────────────────────────────────────────────

    public void setAgentName(String agentName) {
        this.agentName = agentName != null ? agentName : "";
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId != null ? sessionId : "";
    }

    public void setMode(String mode) {
        this.mode = mode != null ? mode : "";
    }

    public void setPlanningMode(boolean planningMode) {
        this.planningMode = planningMode;
    }

    public void setEnforcerActive(boolean enforcerActive) {
        this.enforcerActive = enforcerActive;
    }

    public void setTerminalWidth(int width) {
        this.terminalWidth = width > 0 ? width : 80;
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    public void redraw() {
        synchronized (drawLock) {
            PrintStream out = System.out;
            int w = terminalWidth;

            out.print(SAVE_CURSOR);

            // Row 1: content
            out.print(ESC + "1;1H");
            out.print(ESC + "2K");
            String content = buildContent(w);
            out.print(content);

            // Row 2: separator
            out.print(ESC + "2;1H");
            out.print(ESC + "2K");
            out.print(DIM + HORIZONTAL_LINE.repeat(Math.min(w, 200)) + RESET);

            out.print(RESTORE_CURSOR);
            out.flush();
        }
    }

    private String buildContent(int width) {
        StringBuilder sb = new StringBuilder();

        // Logo
        sb.append(BOLD).append(CYAN).append(" kompile").append(RESET);

        // Agent
        if (!agentName.isEmpty()) {
            sb.append("  ").append(BOLD).append(WHITE).append("[")
                    .append(agentName).append("]").append(RESET);
        }

        // Session
        if (!sessionId.isEmpty()) {
            String shortSession = sessionId.length() > 16
                    ? sessionId.substring(0, 16) : sessionId;
            sb.append("  ").append(DIM).append("session: ")
                    .append(shortSession).append(RESET);
        }

        // Mode
        if (!mode.isEmpty()) {
            sb.append("  ").append(DIM).append(mode).append(RESET);
        }

        // Planning mode
        if (planningMode) {
            sb.append("  ").append(CYAN).append("[plan]").append(RESET);
        }

        // Enforcer
        if (enforcerActive) {
            sb.append("  ").append(MAGENTA).append("[enforcer]").append(RESET);
        }

        // Right-aligned help hint
        String leftVisible = AnsiConstants.stripAnsi(sb.toString());
        String hint = "Ctrl+C cancel  /help  /quit";
        int gap = width - leftVisible.length() - hint.length() - 1;
        if (gap > 2) {
            sb.append(" ".repeat(gap));
            sb.append(DIM).append(hint).append(RESET);
        }

        // Pad with inverse background
        String full = sb.toString();
        int visLen = AnsiConstants.visibleLength(full);
        int padding = Math.max(0, width - visLen);
        return INVERSE + full + " ".repeat(padding) + RESET;
    }
}
