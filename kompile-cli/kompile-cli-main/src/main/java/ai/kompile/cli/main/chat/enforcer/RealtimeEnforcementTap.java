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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The single reusable wiring for realtime, judge-based enforcement of an agent that owns its own
 * screen — the shared core of WP9/WP12/F3. It tails the agent's native session JSONL and has the
 * judge evaluate text + tool calls as they are written, independent of who renders the terminal.
 *
 * <p>Both hosts use this instead of hand-rolling the tailer (design finding F5i — one construction,
 * not a copy per host):</p>
 * <ul>
 *   <li>the managed emulated passthrough, via {@link #fromComponents} (its judge/policy are already
 *       built by {@code EnforcerCommand});</li>
 *   <li>the raw {@code PassthroughCommand} (inheritIO), via {@link #fromConfig} (constructs the judge
 *       from the harness config and OWNS it).</li>
 * </ul>
 *
 * <p>Two invariants baked in from rooting out the WP9 concerns:</p>
 * <ol>
 *   <li>the tap always gets its OWN fresh {@link EnforcerConversationWindow} — it self-populates from
 *       the JSONL ground truth and must never share a turn-gate's window ({@code addUserMessage}
 *       does not dedup, so sharing double-inserts every user turn);</li>
 *   <li>it OBSERVES → judges → logs and hands violations to the host's callback; it never signals the
 *       agent process (the L0 escalation is a process-killer, and a persistent interactive TUI does
 *       not exit on SIGINT). Actuation is the host's turn-gate.</li>
 * </ol>
 */
public final class RealtimeEnforcementTap implements AutoCloseable {

    private final EnforcerJsonlTailer tailer;   // null when inactive
    private final EnforcerJudge ownedJudge;      // non-null only when this tap created the judge

    private RealtimeEnforcementTap(EnforcerJsonlTailer tailer, EnforcerJudge ownedJudge) {
        this.tailer = tailer;
        this.ownedJudge = ownedJudge;
    }

    /** A tap that does nothing (no judge / no rules / keyword mode). Safe to start()/close(). */
    public static RealtimeEnforcementTap inactive() {
        return new RealtimeEnforcementTap(null, null);
    }

    /**
     * Build from already-constructed components (the judge/policy are owned by the caller and will
     * NOT be closed by this tap). Returns an inactive tap when enforcement is not judge-based.
     */
    public static RealtimeEnforcementTap fromComponents(String agent, Path workingDir, ObjectMapper mapper,
                                                        EnforcerJudge judge, EnforcerPolicy policy,
                                                        EnforcerJsonlTailer.ViolationHandler handler) {
        if (judge == null || policy == null || !policy.hasRules() || handler == null) {
            return inactive();
        }
        return new RealtimeEnforcementTap(newTailer(agent, workingDir, mapper, judge, policy, handler), null);
    }

    /**
     * Build from an {@link EnforcerConfig}, constructing the judge from the harness config (this tap
     * then OWNS the judge and closes it). Returns an inactive tap for keyword mode (which uses
     * prompt-injection, not the judge tap), missing rules, or an unavailable judge backend.
     */
    public static RealtimeEnforcementTap fromConfig(String agent, Path workingDir, EnforcerConfig config,
                                                    ObjectMapper mapper,
                                                    EnforcerJsonlTailer.ViolationHandler handler) {
        if (config == null || config.isKeywordMode() || handler == null) {
            return inactive();
        }
        String rules;
        try {
            rules = config.buildRulesText(workingDir);
        } catch (IOException e) {
            return inactive();
        }
        if (rules == null || rules.isBlank()) {
            return inactive();
        }
        EnforcerJudge judge = new EnforcerJudge(HarnessConfig.load(mapper), mapper);
        if (!judge.isAvailable()) {
            judge.close();
            return inactive();
        }
        EnforcerPolicy policy = new EnforcerPolicy(rules, config.getMaxCorrections(), false);
        return new RealtimeEnforcementTap(
                newTailer(agent, workingDir, mapper, judge, policy, handler), judge);
    }

    private static EnforcerJsonlTailer newTailer(String agent, Path workingDir, ObjectMapper mapper,
                                                 EnforcerJudge judge, EnforcerPolicy policy,
                                                 EnforcerJsonlTailer.ViolationHandler handler) {
        // Fresh, in-memory (null context file) window — invariant (1) above.
        EnforcerConversationWindow window = new EnforcerConversationWindow(null, mapper);
        return new EnforcerJsonlTailer(agent, workingDir, Instant.now(), mapper, window, judge, policy, handler);
    }

    public boolean isActive() {
        return tailer != null;
    }

    public void start() {
        if (tailer != null) {
            tailer.start();
        }
    }

    @Override
    public void close() {
        if (tailer != null) {
            try {
                tailer.close();
            } catch (Exception ignored) {
                // teardown is best-effort
            }
        }
        if (ownedJudge != null) {
            try {
                ownedJudge.close();
            } catch (Exception ignored) {
                // teardown is best-effort
            }
        }
    }
}
