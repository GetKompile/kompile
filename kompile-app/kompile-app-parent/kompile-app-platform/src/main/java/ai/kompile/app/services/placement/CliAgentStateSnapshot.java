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

import java.util.List;

/** Immutable snapshot of all CLI agents at decision time. Produced by a {@link CliAgentStateProvider}. */
public record CliAgentStateSnapshot(List<CliAgentState> agents) {

    public CliAgentStateSnapshot {
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    /** Viable agents (available + authed + quota), best throughput first. */
    public List<CliAgentState> viable() {
        return agents.stream()
                .filter(CliAgentState::isViable)
                .sorted((a, b) -> Double.compare(b.tokensPerSec(), a.tokensPerSec()))
                .toList();
    }

    public boolean anyViable() {
        return agents.stream().anyMatch(CliAgentState::isViable);
    }

    public static CliAgentStateSnapshot none() {
        return new CliAgentStateSnapshot(List.of());
    }
}
