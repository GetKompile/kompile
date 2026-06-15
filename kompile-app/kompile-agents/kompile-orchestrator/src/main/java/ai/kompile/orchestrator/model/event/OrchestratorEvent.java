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
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base class for all orchestrator events.
 */
@Getter
public abstract class OrchestratorEvent extends ApplicationEvent {

    private final String eventId;
    private final LocalDateTime eventTimestamp;
    private final String orchestratorInstanceId;
    private final OrchestratorEventType eventType;
    private final String message;

    protected OrchestratorEvent(Object source, String orchestratorInstanceId,
                                OrchestratorEventType eventType, String message) {
        super(source);
        this.eventId = UUID.randomUUID().toString();
        this.eventTimestamp = LocalDateTime.now();
        this.orchestratorInstanceId = orchestratorInstanceId;
        this.eventType = eventType;
        this.message = message;
    }

    protected OrchestratorEvent(Object source, String orchestratorInstanceId,
                                OrchestratorEventType eventType) {
        this(source, orchestratorInstanceId, eventType, null);
    }

    @Override
    public String toString() {
        return String.format("%s[eventId=%s, type=%s, orchestrator=%s, time=%s, message=%s]",
                getClass().getSimpleName(), eventId, eventType, orchestratorInstanceId, eventTimestamp, message);
    }
}
