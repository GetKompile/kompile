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

package ai.kompile.core.crawl.graph;

import org.springframework.context.ApplicationEvent;

/**
 * Spring ApplicationEvent published whenever a crawl job's progress changes.
 *
 * <p>Consumers (e.g. SSE controllers) listen for this event via {@code @EventListener}
 * and forward it to connected clients.  The {@code progressSnapshot} field holds an
 * instance of {@link UnifiedCrawlJob.ProgressSnapshot}; it is typed as {@link Object}
 * to keep this event class free of direct compile-time coupling with the mutable job
 * internals.  Cast it to {@code UnifiedCrawlJob.ProgressSnapshot} at the call site.
 */
public class CrawlProgressEvent extends ApplicationEvent {

    /**
     * Discriminates what triggered this event so consumers can decide
     * how urgently to relay it to the UI.
     */
    public enum EventType {
        /** Job was submitted and is now queued or running. */
        STARTED,
        /** Periodic or incremental progress update. */
        PROGRESS,
        /** One of the configured crawl sources has finished. */
        SOURCE_COMPLETE,
        /** The overall pipeline phase changed (e.g. LOADING → EMBEDDING). */
        PHASE_CHANGE,
        /** A recoverable or fatal error occurred. */
        ERROR,
        /** The job finished successfully. */
        COMPLETED,
        /** The job was cancelled by the user or system. */
        CANCELLED
    }

    private final String jobId;

    /**
     * An instance of {@link UnifiedCrawlJob.ProgressSnapshot} captured at event
     * publication time.  Cast to that type before accessing fields:
     * <pre>{@code
     * UnifiedCrawlJob.ProgressSnapshot snap =
     *         (UnifiedCrawlJob.ProgressSnapshot) event.getProgressSnapshot();
     * }</pre>
     */
    private final Object progressSnapshot;

    private final EventType eventType;
    private final String message;

    /**
     * @param source           the object publishing the event (typically the crawl service)
     * @param jobId            unique identifier of the crawl job
     * @param progressSnapshot a {@link UnifiedCrawlJob.ProgressSnapshot} captured now
     * @param eventType        what kind of state change triggered this event
     * @param message          optional human-readable description; may be {@code null}
     */
    public CrawlProgressEvent(Object source,
                              String jobId,
                              Object progressSnapshot,
                              EventType eventType,
                              String message) {
        super(source);
        this.jobId = jobId;
        this.progressSnapshot = progressSnapshot;
        this.eventType = eventType;
        this.message = message;
    }

    public String getJobId() {
        return jobId;
    }

    /**
     * Returns the progress snapshot as a plain {@link Object}.
     * Cast to {@link UnifiedCrawlJob.ProgressSnapshot} at the call site.
     */
    public Object getProgressSnapshot() {
        return progressSnapshot;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "CrawlProgressEvent{jobId='" + jobId + "', eventType=" + eventType
                + ", message='" + message + "'}";
    }
}
