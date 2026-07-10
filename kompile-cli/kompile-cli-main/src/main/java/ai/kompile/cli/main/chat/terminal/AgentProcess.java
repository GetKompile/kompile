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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * L0 of the Terminal Session Framework: ownership of one agent subprocess —
 * spawn, PTY allocation, signals/lifecycle and runtime resize.
 *
 * <p>Consolidates the three historical spawn blocks, three {@code wrapWithPty} copies and two
 * kill paths (design findings F5a/F5b/F5c) behind a single interface. The default implementation
 * is {@link ScriptAgentProcess} (a {@code script(1)}-wrapped PTY); a pty4j-backed implementation
 * can slot in later (design WP14) without touching callers.</p>
 *
 * <p>The output <em>pump</em> is deliberately not owned here yet: the god-class pump interleaves
 * render-policy and decoder logic that is formalized into an {@code OutputTap} chain in later work
 * packages. For now callers read {@link #inputStream()} / {@link #stdin()} and run their existing
 * pump; L0 owns everything else.</p>
 */
public interface AgentProcess extends AutoCloseable {

    /** Spawn the subprocess per {@code spec}. Idempotent guard is the caller's responsibility. */
    void start(AgentLaunchSpec spec) throws java.io.IOException;

    /** Subprocess stdin (write user messages / submit sequences here). */
    OutputStream stdin();

    /** Subprocess stdout (stderr folded in when {@code redirectErrorStream}); the pump reads this. */
    InputStream inputStream();

    /** Runtime resize: propagate the new geometry to the subprocess (WINCH to the process tree). */
    void resize(int rows, int cols);

    /** Stop the subprocess using the given escalation policy; returns the signal that stopped it. */
    String interrupt(InterruptEscalation escalation);

    /** Send an arbitrary signal (e.g. {@code -WINCH}, {@code -TSTP}) to the whole descendant tree. */
    void signalTree(String signal);

    boolean isAlive();

    /** OS process id, or -1 before {@link #start} / after teardown. */
    long pid();

    /** Completes with the process exit code when it terminates. */
    CompletableFuture<Integer> exitFuture();

    /** The underlying {@link Process}; exposed while the pump still lives in the host (see class doc). */
    Process process();

    @Override
    void close();
}
