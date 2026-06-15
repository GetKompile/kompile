/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.model.event;

import lombok.Getter;

import java.util.Map;

/**
 * Event fired when the orchestrator state changes.
 */
@Getter
public class StateChangeEvent extends OrchestratorEvent {

    private final String previousStateId;
    private final String newStateId;
    private final Map<String, Object> context;

    public StateChangeEvent(Object source, String orchestratorInstanceId,
                            String previousStateId, String newStateId,
                            Map<String, Object> context) {
        super(source, orchestratorInstanceId, OrchestratorEventType.STATE_CHANGED,
                String.format("State changed: %s -> %s", previousStateId, newStateId));
        this.previousStateId = previousStateId;
        this.newStateId = newStateId;
        this.context = context;
    }

    /**
     * Create an event for entering a state.
     */
    public static StateChangeEvent entering(Object source, String orchestratorInstanceId,
                                            String stateId, Map<String, Object> context) {
        return new StateChangeEvent(source, orchestratorInstanceId, null, stateId, context) {
            @Override
            public OrchestratorEventType getEventType() {
                return OrchestratorEventType.STATE_ENTERING;
            }
        };
    }

    /**
     * Create an event for exiting a state.
     */
    public static StateChangeEvent exiting(Object source, String orchestratorInstanceId,
                                           String stateId, Map<String, Object> context) {
        return new StateChangeEvent(source, orchestratorInstanceId, stateId, null, context) {
            @Override
            public OrchestratorEventType getEventType() {
                return OrchestratorEventType.STATE_EXITING;
            }
        };
    }
}
