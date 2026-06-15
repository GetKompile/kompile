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

package ai.kompile.app.web.controllers;

import ai.kompile.core.crawl.graph.CrawlProgressEvent;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * SSE (Server-Sent Events) controller for real-time crawl-job progress streaming.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/crawl-events/stream} — global stream (all jobs)</li>
 *   <li>{@code GET /api/crawl-events/stream/{jobId}} — per-job stream</li>
 * </ul>
 *
 * <h3>Event flow</h3>
 * <ol>
 *   <li>Client connects; the current {@link UnifiedCrawlJob.ProgressSnapshot} (if any)
 *       is sent immediately as an initial {@code progress} event.</li>
 *   <li>{@link CrawlProgressEvent}s published by the crawl service are forwarded
 *       to all registered emitters via {@link #onCrawlProgressEvent(CrawlProgressEvent)}.</li>
 *   <li>A heartbeat event is sent every 15 seconds to prevent proxy/client timeouts.</li>
 *   <li>Completed/timed-out emitters are removed automatically.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/crawl-events")
public class CrawlProgressSseController {

    /** SSE connection timeout: 30 minutes. */
    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    // -------------------------------------------------------------------------
    // Active emitter registries
    // -------------------------------------------------------------------------

    /** All currently connected emitters (global + per-job). */
    private final CopyOnWriteArrayList<SseEmitter> globalEmitters = new CopyOnWriteArrayList<>();

    /**
     * Per-job emitters.  Key = jobId; value = list of emitters interested
     * only in that job's events.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> jobEmitters =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final UnifiedCrawlService crawlService;
    private final ObjectMapper objectMapper;

    @Autowired
    public CrawlProgressSseController(
            @Autowired(required = false) UnifiedCrawlService crawlService,
            ObjectMapper objectMapper) {
        this.crawlService = crawlService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // SSE endpoints
    // -------------------------------------------------------------------------

    /**
     * Global stream: receives progress events for all crawl jobs.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAll() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        registerGlobal(emitter);
        sendInitialSnapshotForAll(emitter);
        log.debug("New global SSE subscriber connected (total={})", globalEmitters.size());
        return emitter;
    }

    /**
     * Per-job stream: receives progress events for a single crawl job only.
     *
     * @param jobId the crawl job to track
     */
    @GetMapping(value = "/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        registerForJob(jobId, emitter);
        sendInitialSnapshotForJob(jobId, emitter);
        log.debug("New per-job SSE subscriber for jobId='{}' (total={})",
                jobId, jobEmitters.getOrDefault(jobId, new CopyOnWriteArrayList<>()).size());
        return emitter;
    }

    // -------------------------------------------------------------------------
    // Event listener
    // -------------------------------------------------------------------------

    /**
     * Receives {@link CrawlProgressEvent}s published anywhere in the application
     * context and forwards them to all relevant SSE emitters.
     */
    @EventListener
    public void onCrawlProgressEvent(CrawlProgressEvent event) {
        String eventName = event.getEventType().name().toLowerCase();
        Object payload = buildPayload(event);

        // Forward to global subscribers
        sendToAll(globalEmitters, eventName, payload);

        // Forward to per-job subscribers
        CopyOnWriteArrayList<SseEmitter> jobList = jobEmitters.get(event.getJobId());
        if (jobList != null && !jobList.isEmpty()) {
            sendToAll(jobList, eventName, payload);
        }
    }

    // -------------------------------------------------------------------------
    // Heartbeat
    // -------------------------------------------------------------------------

    /**
     * Sends a heartbeat comment every 15 seconds to all connected emitters
     * so that proxies and client HTTP stacks do not close idle connections.
     */
    @Scheduled(fixedDelay = 15_000)
    public void sendHeartbeat() {
        if (globalEmitters.isEmpty() && jobEmitters.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder heartbeat = SseEmitter.event()
                .comment("heartbeat")
                .name("heartbeat")
                .data(Map.of("ts", System.currentTimeMillis()));

        sendEventToAll(globalEmitters, heartbeat);
        jobEmitters.values().forEach(list -> sendEventToAll(list, heartbeat));
    }

    // -------------------------------------------------------------------------
    // Registration helpers
    // -------------------------------------------------------------------------

    private void registerGlobal(SseEmitter emitter) {
        globalEmitters.add(emitter);
        emitter.onCompletion(() -> {
            globalEmitters.remove(emitter);
            log.debug("Global SSE emitter completed (remaining={})", globalEmitters.size());
        });
        emitter.onTimeout(() -> {
            globalEmitters.remove(emitter);
            log.debug("Global SSE emitter timed out (remaining={})", globalEmitters.size());
        });
        emitter.onError(ex -> {
            globalEmitters.remove(emitter);
            log.debug("Global SSE emitter error: {}", ex.getMessage());
        });
    }

    private void registerForJob(String jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list =
                jobEmitters.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable cleanup = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                jobEmitters.remove(jobId, list);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> {
            cleanup.run();
            log.debug("Per-job SSE emitter error for jobId='{}': {}", jobId, ex.getMessage());
        });
    }

    // -------------------------------------------------------------------------
    // Initial snapshot helpers
    // -------------------------------------------------------------------------

    private void sendInitialSnapshotForAll(SseEmitter emitter) {
        if (crawlService == null) return;
        try {
            List<UnifiedCrawlJob> activeJobs = crawlService.getActiveJobs();
            for (UnifiedCrawlJob job : activeJobs) {
                UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();
                sendEvent(emitter, "progress", snap);
            }
        } catch (Exception ex) {
            log.warn("Failed to send initial snapshots to new global SSE subscriber: {}", ex.getMessage());
        }
    }

    private void sendInitialSnapshotForJob(String jobId, SseEmitter emitter) {
        if (crawlService == null) return;
        try {
            Optional<UnifiedCrawlJob> job = crawlService.getJob(jobId);
            job.ifPresent(j -> {
                try {
                    sendEvent(emitter, "progress", j.toProgressSnapshot());
                } catch (IOException ex) {
                    log.warn("Failed to send initial snapshot for jobId='{}': {}", jobId, ex.getMessage());
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to look up job '{}' for initial SSE snapshot: {}", jobId, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Low-level send helpers
    // -------------------------------------------------------------------------

    /**
     * Serialises {@code data} as JSON and sends it as an SSE event to {@code emitter}.
     * Removes the emitter from the global list on any error.
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event().name(eventName).data(json, MediaType.APPLICATION_JSON));
    }

    /**
     * Sends a pre-built SSE event to every emitter in {@code list}, removing
     * any emitter that reports an error.
     */
    private void sendEventToAll(CopyOnWriteArrayList<SseEmitter> list,
                                SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : list) {
            try {
                emitter.send(event);
            } catch (IOException ex) {
                list.remove(emitter);
                log.debug("Removed dead SSE emitter: {}", ex.getMessage());
            }
        }
    }

    /**
     * Serialises {@code data} and sends it to every emitter in {@code list}.
     */
    private void sendToAll(CopyOnWriteArrayList<SseEmitter> list, String eventName, Object data) {
        if (list.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (IOException ex) {
            log.error("Failed to serialise SSE payload for event '{}': {}", eventName, ex.getMessage(), ex);
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventName)
                .data(json, MediaType.APPLICATION_JSON);
        sendEventToAll(list, event);
    }

    /**
     * Builds the JSON payload map for a {@link CrawlProgressEvent}.
     */
    private Map<String, Object> buildPayload(CrawlProgressEvent event) {
        return Map.of(
                "jobId", event.getJobId(),
                "eventType", event.getEventType().name(),
                "message", event.getMessage() != null ? event.getMessage() : "",
                "snapshot", event.getProgressSnapshot() != null
                        ? event.getProgressSnapshot()
                        : Map.of()
        );
    }
}
