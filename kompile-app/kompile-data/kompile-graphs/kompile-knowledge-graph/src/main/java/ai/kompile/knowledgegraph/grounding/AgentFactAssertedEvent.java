/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.grounding;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Spring event published when an agent asserts a fact into a fact sheet's observed store.
 *
 * <p>Published by {@link KbGroundingService#assertFact} after the fact is durably written to
 * both the {@link ai.kompile.graph.reasoning.fol.grounding.ConcurrentFactStore} and the backing
 * {@link ai.kompile.graph.reasoning.fol.FactStore}. The
 * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeHook} listens for this event
 * (via {@code @EventListener} in the {@code kompile-graph-change-tracking} module, which
 * depends on {@code kompile-knowledge-graph}) and schedules an incremental re-ground of the
 * affected fact sheet.</p>
 *
 * <h3>Module placement</h3>
 * <p>This event lives in {@code kompile-knowledge-graph} (not {@code kompile-graph-change-tracking})
 * to avoid a circular dependency: {@code kompile-graph-change-tracking} already depends on
 * {@code kompile-knowledge-graph}, and placing the event here lets both modules compile cleanly.</p>
 *
 * <h3>L3 cascade role</h3>
 * <p>This event is the "agent assert" source in the grounding-cascade event table
 * (see {@code incremental-cascade-reasoning-design.md §1}). It carries enough information
 * for the cascade hook to classify the delta scope as {@code DELTA_ATOMS} and run a
 * predicate-scoped MAP solve over the depth-2 neighbourhood of the changed atom.</p>
 */
@Getter
public class AgentFactAssertedEvent extends ApplicationEvent {

    private final long factSheetId;
    private final String atomKey;
    private final double value;
    private final String sessionId;

    /**
     * Create a new {@code AgentFactAssertedEvent}.
     *
     * @param source      the event publisher (must not be null)
     * @param factSheetId the fact sheet id
     * @param atomKey     the canonical atom key that was asserted (e.g. {@code "trusts(Alice, Bob)"})
     * @param value       the asserted truth value in [0,1]
     * @param sessionId   the agent session id (may be null; then "unknown" is substituted)
     */
    public AgentFactAssertedEvent(Object source, long factSheetId,
                                   String atomKey, double value, String sessionId) {
        super(source);
        this.factSheetId = factSheetId;
        this.atomKey = atomKey;
        this.value = value;
        this.sessionId = (sessionId != null) ? sessionId : "unknown";
    }
}
