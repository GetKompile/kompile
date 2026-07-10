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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Escalating Unix interrupt policy: {@code SIGINT → (grace) → SIGTERM → (grace) → SIGKILL}.
 *
 * <p>Single consolidation of the historical interrupt/kill paths (design finding F5c):
 * {@code SubprocessAgentRunner.escalatingUnixInterrupt} (single-pid, 300/200 ms — the tested
 * "soft interrupt"), {@code SubprocessAgentRunner.killProcess} (single-pid, 500/300 ms), and
 * {@code EmulatedPassthroughCommand.killProcess} (whole-process-tree, 500/300 ms).</p>
 *
 * <p>Escalation is mandatory (constraint §3.5): agents differ — claude honors SIGINT, opencode
 * ignores it and must be escalated to SIGTERM. {@link #escalate(Process)} returns the signal
 * that actually stopped the process so callers/tests can assert which stage fired.</p>
 *
 * <p>{@code targetTree} controls whether each signal is delivered to just the root process or to
 * the whole descendant tree — the latter is required when the agent runs <em>inside</em> a
 * {@code script(1)} child shell, so signalling only the direct child misses the real agent.</p>
 */
public final class InterruptEscalation {

    private final int sigintGraceMs;
    private final int sigtermGraceMs;
    private final int killWaitMs;
    private final boolean targetTree;

    public InterruptEscalation(int sigintGraceMs, int sigtermGraceMs, int killWaitMs, boolean targetTree) {
        this.sigintGraceMs = sigintGraceMs;
        this.sigtermGraceMs = sigtermGraceMs;
        this.killWaitMs = killWaitMs;
        this.targetTree = targetTree;
    }

    /** The tested "soft interrupt" default: single-pid, 300 ms SIGINT grace, 200 ms SIGTERM grace. */
    public static InterruptEscalation soft() {
        return new InterruptEscalation(300, 200, 1000, false);
    }

    /** Hard single-process kill: 500 ms / 300 ms grace (legacy {@code SubprocessAgentRunner.killProcess}). */
    public static InterruptEscalation hardSingle() {
        return new InterruptEscalation(500, 300, 1000, false);
    }

    /** Hard whole-tree kill: 500 ms / 300 ms grace (legacy {@code EmulatedPassthroughCommand.killProcess}). */
    public static InterruptEscalation hardTree() {
        return new InterruptEscalation(500, 300, 1000, true);
    }

    /**
     * Escalate until the process stops.
     *
     * @return {@code "INT"}, {@code "TERM"} or {@code "KILL"} for the signal that stopped it, or
     *         {@code "ALIVE"} if it somehow survived SIGKILL.
     */
    public String escalate(Process process) throws Exception {
        if (process == null || !process.isAlive()) return "INT";
        signal(process, "-INT");
        if (process.waitFor(sigintGraceMs, TimeUnit.MILLISECONDS)) return "INT";
        signal(process, "-TERM");
        if (process.waitFor(sigtermGraceMs, TimeUnit.MILLISECONDS)) return "TERM";
        signal(process, "-9");
        return process.waitFor(killWaitMs, TimeUnit.MILLISECONDS) ? "KILL" : "ALIVE";
    }

    private void signal(Process process, String sig) throws IOException, InterruptedException {
        if (targetTree) {
            for (long pid : treePids(process)) {
                sendSignal(sig, pid);
            }
        } else {
            sendSignal(sig, process.pid());
        }
    }

    private static void sendSignal(String sig, long pid) throws IOException, InterruptedException {
        new ProcessBuilder("kill", sig, String.valueOf(pid))
                .redirectErrorStream(true).start().waitFor();
    }

    /** Live descendant pids (deepest first) plus the root pid, for whole-tree signalling. */
    static List<Long> treePids(Process process) {
        if (process == null) return List.of();
        List<Long> pids = new ArrayList<>();
        process.toHandle().descendants()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .sorted(Comparator.reverseOrder())
                .forEach(pids::add);
        if (process.isAlive()) {
            pids.add(process.pid());
        }
        return pids;
    }
}
