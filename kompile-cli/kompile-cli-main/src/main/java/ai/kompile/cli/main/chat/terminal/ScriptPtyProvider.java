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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Default {@link PtyProvider}: wraps the command with {@code script -q /dev/null ...} to
 * allocate a PTY, forcing line-buffered output and preserving ANSI formatting.
 *
 * <p>This is the single consolidation of the three historical {@code wrapWithPty} copies
 * (design finding F5a): {@code EmulatedPassthroughCommand.wrapWithPty(cmd,rows,cols)} (with
 * {@code stty} dimensions), {@code SubprocessAgentRunner.wrapWithPty(cmd)} and
 * {@code AgentCommandForwarder.wrapWithPty(cmd)} (no dimensions). The three differed only in
 * whether they set dimensions and in a minor quoting detail; this class uses the safe
 * superset quoting (space, quotes, and parentheses) for every caller.</p>
 *
 * <ul>
 *   <li>With {@link PtyDims}: prepends {@code stty rows R cols C} so the subprocess renders at
 *       the exact size the shadow {@code VirtualTerminal} expects.</li>
 *   <li>On macOS the wrapped form is {@code script -q /dev/null [/bin/sh -c] ...}; on Linux it
 *       is {@code script -q /dev/null -c ...}.</li>
 *   <li>On Windows, or when {@code script} is not on PATH, returns the command unchanged. The
 *       optional {@code onFallback} hook lets a caller surface that no-PTY fallback loudly
 *       (design WP2) instead of silently degrading to fully-buffered output.</li>
 * </ul>
 */
public final class ScriptPtyProvider implements PtyProvider {

    /**
     * Shared default instance. Warns ONCE to stderr when it cannot allocate a PTY (design WP2:
     * loud failure rather than the historical silent degradation to fully-buffered output).
     */
    public static final ScriptPtyProvider INSTANCE = new ScriptPtyProvider(loudOnceWarner());

    private static Consumer<String> loudOnceWarner() {
        AtomicBoolean warned = new AtomicBoolean(false);
        return reason -> {
            if (warned.compareAndSet(false, true)) {
                System.err.println("[kompile] WARNING: no PTY allocated — " + reason
                        + ". Agent output may be fully buffered (no live streaming).");
            }
        };
    }

    /** Invoked with a human-readable reason when PTY allocation is unavailable; may be null. */
    private final Consumer<String> onFallback;

    public ScriptPtyProvider(Consumer<String> onFallback) {
        this.onFallback = onFallback;
    }

    @Override
    public List<String> wrap(List<String> command, PtyDims dims) {
        if (isWindows()) {
            fallback("running on Windows — script(1) PTY wrapper unavailable");
            return new ArrayList<>(command);
        }
        if (!scriptOnPath()) {
            fallback("`script` not found on PATH — output may be fully buffered (no PTY)");
            return new ArrayList<>(command);
        }

        boolean isMac = isMac();
        List<String> wrapped = new ArrayList<>();
        wrapped.add("script");
        wrapped.add("-q");
        wrapped.add("/dev/null");

        if (dims != null) {
            // A shell command string is required to run stty before the agent, so both platforms
            // go through a shell here.
            String sizeCmd = "stty rows " + dims.rows() + " cols " + dims.cols()
                    + " 2>/dev/null; " + quoteCommand(command);
            if (isMac) {
                wrapped.add("/bin/sh");
                wrapped.add("-c");
                wrapped.add(sizeCmd);
            } else {
                wrapped.add("-c");
                wrapped.add(sizeCmd);
            }
        } else if (isMac) {
            // macOS `script` takes the command as trailing argv directly.
            wrapped.addAll(command);
        } else {
            // Linux: script -q /dev/null -c "cmd arg1 arg2"
            wrapped.add("-c");
            wrapped.add(quoteCommand(command));
        }
        return wrapped;
    }

    @Override
    public boolean available() {
        return !isWindows() && scriptOnPath();
    }

    private void fallback(String reason) {
        if (onFallback != null) onFallback.accept(reason);
    }

    // ── Command quoting (single-quote shell escaping, superset of all three legacy copies) ──

    static String quoteCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(' ');
            String arg = cmd.get(i);
            if (needsQuoting(arg)) {
                sb.append('\'').append(arg.replace("'", "'\\''")).append('\'');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private static boolean needsQuoting(String arg) {
        return arg.indexOf(' ') >= 0 || arg.indexOf('\'') >= 0 || arg.indexOf('"') >= 0
                || arg.indexOf('(') >= 0 || arg.indexOf(')') >= 0;
    }

    // ── OS / tooling detection ──────────────────────────────────────────────

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean scriptOnPath() {
        try {
            Process check = new ProcessBuilder("which", "script")
                    .redirectErrorStream(true).start();
            return check.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
