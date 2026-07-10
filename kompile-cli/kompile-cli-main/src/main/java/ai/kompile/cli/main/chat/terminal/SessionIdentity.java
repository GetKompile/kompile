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

/**
 * The one identity for a managed session (design F8 / WP10). A single session historically carried
 * up to three distinct ids, and features keyed off whichever one was in scope:
 *
 * <ul>
 *   <li>{@code kompileSessionId} — Kompile's own id ({@code emulated-<uuid>}); keys chat history,
 *       metrics, the message queue and background-process registry.</li>
 *   <li>{@code enforcerSessionId} — the enforcer runtime-policy id ({@code KOMPILE_ENFORCER_SESSION_ID});
 *       keys the {@code judgements.jsonl} log and the REST control server.</li>
 *   <li>{@code agentNativeSessionId} — the underlying agent's own session id (claude/codex),
 *       discovered mid-session from its structured output; keys resume/replay.</li>
 * </ul>
 *
 * <p>Holding them in one object removes the "which id?" guessing that forced per-feature workarounds
 * (e.g. reading judgements via the judge's own log handle because the passthrough id and enforcer id
 * differed). {@link #forJudgements()} names the canonical id for judgement lookups.</p>
 */
public record SessionIdentity(String kompileSessionId,
                              String enforcerSessionId,
                              String agentNativeSessionId) {

    public static SessionIdentity of(String kompileSessionId, String enforcerSessionId) {
        return new SessionIdentity(kompileSessionId, enforcerSessionId, null);
    }

    /** Copy with the agent's native session id filled in once it is discovered mid-session. */
    public SessionIdentity withAgentNativeSessionId(String agentNativeSessionId) {
        return new SessionIdentity(kompileSessionId, enforcerSessionId, agentNativeSessionId);
    }

    /**
     * The id judgement records are keyed under: the enforcer id when enforcement is active,
     * otherwise the Kompile session id. Never returns blank-as-present.
     */
    public String forJudgements() {
        return isPresent(enforcerSessionId) ? enforcerSessionId : kompileSessionId;
    }

    public boolean hasEnforcerId() {
        return isPresent(enforcerSessionId);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
