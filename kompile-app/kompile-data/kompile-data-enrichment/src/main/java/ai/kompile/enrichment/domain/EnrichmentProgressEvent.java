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
package ai.kompile.enrichment.domain;

import org.springframework.context.ApplicationEvent;

/**
 * Published during enrichment pipeline execution to track progress.
 * Listeners in kompile-app-main forward these to the frontend via WebSocket.
 */
public class EnrichmentProgressEvent extends ApplicationEvent {

    public enum EventType {
        STARTED,
        PHASE_STARTED,
        PHASE_COMPLETED,
        STEP_COMPLETED,
        COMPLETED,
        FAILED
    }

    private final String jobId;
    private final Long factSheetId;
    private final EventType eventType;
    private final String phase;
    private final String step;
    private final int progressPercent;
    private final String message;
    private final EnrichmentResult result;

    public EnrichmentProgressEvent(Object source, String jobId, Long factSheetId,
                                    EventType eventType, String phase, String step,
                                    int progressPercent, String message,
                                    EnrichmentResult result) {
        super(source);
        this.jobId = jobId;
        this.factSheetId = factSheetId;
        this.eventType = eventType;
        this.phase = phase;
        this.step = step;
        this.progressPercent = progressPercent;
        this.message = message;
        this.result = result;
    }

    public String getJobId() { return jobId; }
    public Long getFactSheetId() { return factSheetId; }
    public EventType getEventType() { return eventType; }
    public String getPhase() { return phase; }
    public String getStep() { return step; }
    public int getProgressPercent() { return progressPercent; }
    public String getMessage() { return message; }
    public EnrichmentResult getResult() { return result; }
}
