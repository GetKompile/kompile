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

package ai.kompile.app.services.placement;

/**
 * Routable state of one CLI agent (opencode/claude/codex/…) at decision time. Produced by a
 * {@link CliAgentStateProvider} (real impl wraps {@code CliAgentAvailabilityAdapter} +
 * {@code CliAgentQuotaLedger}). A CLI candidate is viable iff available && authed && quotaRemaining>0.
 *
 * @param agentName        e.g. {@code opencode-cli}
 * @param available        binary reachable + not circuit-broken
 * @param authed           credentials present/valid
 * @param quotaRemaining   remaining calls/tokens for this crawl (0 = exhausted); &lt;0 = unmetered
 * @param costPerKTok      $ per 1k tokens (0 = free tier)
 * @param tokensPerSec     measured throughput; used by the engine's throughput factor
 * @param modelId          the model this agent would use (metadata for logging/routing)
 */
public record CliAgentState(
        String agentName,
        boolean available,
        boolean authed,
        long quotaRemaining,
        double costPerKTok,
        double tokensPerSec,
        String modelId
) {
    public boolean isViable() {
        return available && authed && quotaRemaining != 0;
    }

    public static CliAgentState of(String agentName, boolean available, long quotaRemaining, double tokensPerSec) {
        return new CliAgentState(agentName, available, true, quotaRemaining, 0.0, tokensPerSec, null);
    }
}
