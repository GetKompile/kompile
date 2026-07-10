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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link AgentProcess}: a {@code script(1)}-wrapped PTY subprocess (via
 * {@link ScriptPtyProvider}) with escalating-signal lifecycle (via {@link InterruptEscalation}).
 *
 * <p>This is the single consolidation of the historical spawn blocks (design finding F5b). It
 * assembles the shared environment ({@link AgentLaunchSpec#DEFAULT_INHERIT_ENV} + terminal
 * defaults + per-host {@code extraEnv}), allocates the PTY, and starts the process.</p>
 *
 * <p><b>Resize (WP2):</b> {@link #resize} sends {@code SIGWINCH} to the whole descendant tree, not
 * just the direct child — so it reaches the agent running <em>inside</em> {@code script(1)}'s child
 * shell. The historical code signalled only the child pid (design finding F7), which missed the
 * real agent and made runtime resize unreliable.</p>
 */
public final class ScriptAgentProcess implements AgentProcess {

    private final PtyProvider ptyProvider;

    private Process process;
    private OutputStream stdin;

    public ScriptAgentProcess() {
        this(ScriptPtyProvider.INSTANCE);
    }

    public ScriptAgentProcess(PtyProvider ptyProvider) {
        this.ptyProvider = ptyProvider == null ? ScriptPtyProvider.INSTANCE : ptyProvider;
    }

    @Override
    public void start(AgentLaunchSpec spec) throws IOException {
        List<String> wrapped = ptyProvider.wrap(spec.command(), spec.dims());

        ProcessBuilder pb = new ProcessBuilder(wrapped);
        if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
            pb.directory(new File(spec.workingDir()).getAbsoluteFile());
        }
        pb.redirectErrorStream(spec.redirectErrorStream());

        Map<String, String> env = pb.environment();
        for (String key : AgentLaunchSpec.DEFAULT_INHERIT_ENV) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        // Ensure the subprocess sees a real terminal environment.
        env.putIfAbsent("TERM", "xterm-256color");
        env.putIfAbsent("COLORTERM", "truecolor");
        if (spec.dims() != null) {
            env.put("COLUMNS", String.valueOf(spec.dims().cols()));
            env.put("LINES", String.valueOf(spec.dims().rows()));
        }
        env.putAll(spec.extraEnv());

        this.process = pb.start();
        this.stdin = process.getOutputStream();
    }

    @Override
    public OutputStream stdin() {
        return stdin;
    }

    @Override
    public InputStream inputStream() {
        return process == null ? null : process.getInputStream();
    }

    @Override
    public void resize(int rows, int cols) {
        // WINCH to the whole tree so it reaches the agent inside script(1)'s child shell (WP2).
        signalTree("-WINCH");
    }

    @Override
    public String interrupt(InterruptEscalation escalation) {
        if (process == null || !process.isAlive()) return "INT";
        try {
            return escalation.escalate(process);
        } catch (Exception e) {
            process.destroyForcibly();
            return "KILL";
        }
    }

    @Override
    public void signalTree(String signal) {
        if (process == null || !process.isAlive()) return;
        for (long pid : InterruptEscalation.treePids(process)) {
            try {
                new ProcessBuilder("kill", signal, String.valueOf(pid))
                        .redirectErrorStream(true).start();
            } catch (IOException ignored) {
                // best-effort; a dead pid or restricted signal is non-fatal
            }
        }
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public long pid() {
        return process == null ? -1L : process.pid();
    }

    @Override
    public CompletableFuture<Integer> exitFuture() {
        if (process == null) {
            return CompletableFuture.completedFuture(-1);
        }
        return process.onExit().thenApply(Process::exitValue);
    }

    @Override
    public Process process() {
        return process;
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            interrupt(InterruptEscalation.hardTree());
        }
    }
}
