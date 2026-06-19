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
package ai.kompile.core.events;

import java.util.List;

/**
 * Write-side SPI for recording observed events.
 *
 * <p>Implemented by the {@code kompile-event-observation} module and consumed by producers of
 * observations (e.g. the process engine's step/run completion callback) without those producers
 * depending on the observation module. Lives in {@code kompile-app-core} for the same
 * cycle-avoidance reason as {@link EmpiricalPriorSource}.</p>
 *
 * <p>Each observation contributes to a per-event Beta-Binomial counter: {@code occurrences} is the
 * number of times the event happened and {@code opportunities} the number of chances it had to
 * happen (the Binomial denominator). Implementations must be non-blocking / safe to call from
 * execution paths — failures must not disrupt the caller.</p>
 */
public interface EventObserver {

    /** Whether observation recording is enabled. */
    boolean isEnabled();

    /**
     * Record that an entity (KG node) was observed — realises
     * "entities can also be events in the number of times they occur".
     */
    void observeEntity(String nodeId, long occurrences, long opportunities);

    /**
     * Record that a connection (an edge of {@code edgeType} from source to target) was observed —
     * realises "connections produce observed events".
     */
    void observeConnection(String sourceNodeId, String edgeType, String targetNodeId,
                           long occurrences, long opportunities);

    /**
     * Record a process step execution as an event keyed by {@code (processDefinitionId, stepId)}.
     * {@code success} contributes an occurrence; every call contributes an opportunity, so the
     * learned probability is the empirical success rate of the step.
     */
    void observeProcessStep(String processDefinitionId, String stepId, List<String> graphNodeIds, boolean success);

    /** A disabled, no-op observer — useful as a non-null default for consumers. */
    EventObserver NO_OP = new EventObserver() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void observeEntity(String nodeId, long occurrences, long opportunities) {
            // no-op
        }

        @Override
        public void observeConnection(String sourceNodeId, String edgeType, String targetNodeId,
                                      long occurrences, long opportunities) {
            // no-op
        }

        @Override
        public void observeProcessStep(String processDefinitionId, String stepId,
                                       List<String> graphNodeIds, boolean success) {
            // no-op
        }
    };
}
