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

package ai.kompile.app.services.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges {@link JobSchedulerEvent} events to WebSocket topics for real-time UI updates.
 *
 * <p>Topics:
 * <ul>
 *   <li>{@code /topic/scheduler/events} — individual events</li>
 *   <li>{@code /topic/scheduler/status} — full scheduler status on every state change</li>
 * </ul>
 */
@Service
public class JobSchedulerBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(JobSchedulerBroadcaster.class);

    private static final String TOPIC_EVENTS = "/topic/scheduler/events";
    private static final String TOPIC_STATUS = "/topic/scheduler/status";
    private static final int MAX_RECENT_EVENTS = 200;

    private final SimpMessagingTemplate messagingTemplate;
    private final ResourceAwareJobScheduler scheduler;
    private final ConcurrentLinkedDeque<Map<String, Object>> recentEvents = new ConcurrentLinkedDeque<>();
    private final AtomicInteger eventCount = new AtomicInteger(0);

    @Autowired
    public JobSchedulerBroadcaster(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            ResourceAwareJobScheduler scheduler) {
        this.messagingTemplate = messagingTemplate;
        this.scheduler = scheduler;
    }

    @EventListener
    public void onJobSchedulerEvent(JobSchedulerEvent event) {
        // Always store in ring buffer — available via REST even without WebSocket
        Map<String, Object> eventPayload = buildEventPayload(event);
        recentEvents.addFirst(eventPayload);
        eventCount.incrementAndGet();
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.pollLast();
        }

        if (messagingTemplate == null) return;

        try {
            // Send individual event via WebSocket
            messagingTemplate.convertAndSend(TOPIC_EVENTS, eventPayload);

            // Send full status update for state-changing events
            if (isStateChangingEvent(event.getEventType())) {
                Map<String, Object> status = scheduler.getStatus();
                status.put("queue", scheduler.getQueueSnapshot());
                status.put("running", scheduler.getRunningSnapshot());
                status.put("timestamp", Instant.now().toString());
                messagingTemplate.convertAndSend(TOPIC_STATUS, status);
            }
        } catch (Exception e) {
            log.debug("Failed to broadcast scheduler event: {}", e.getMessage());
        }
    }

    /**
     * Get recent events from the ring buffer for REST cold-load.
     */
    public List<Map<String, Object>> getRecentEvents(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> ev : recentEvents) {
            if (count >= limit) break;
            result.add(ev);
            count++;
        }
        return result;
    }

    /**
     * Total events broadcast since startup.
     */
    public int getTotalEventCount() {
        return eventCount.get();
    }

    private Map<String, Object> buildEventPayload(JobSchedulerEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.getEventType().name());
        payload.put("timestamp", Instant.now().toString());

        if (event.getJobId() != null) {
            payload.put("jobId", event.getJobId());
        }
        if (event.getJobType() != null) {
            payload.put("jobType", event.getJobType());
        }
        if (event.getCurrentPhase() != null) {
            payload.put("currentPhase", event.getCurrentPhase());
        }

        payload.put("queueDepth", event.getQueueDepth());
        payload.put("runningCount", event.getRunningCount());

        if (event.getData() != null && !event.getData().isEmpty()) {
            payload.putAll(event.getData());
        }

        return payload;
    }

    private boolean isStateChangingEvent(JobSchedulerEvent.EventType type) {
        return switch (type) {
            case JOB_QUEUED, JOB_DISPATCHED, JOB_COMPLETED, JOB_FAILED,
                 JOB_CANCELLED, JOB_PROMOTED, JOB_PHASE_TRANSITION,
                 JOB_BLOCKED, JOB_SKIPPED_AHEAD, JOB_REORDERED,
                 QUEUE_FULL, SCHEDULER_STARTED, SCHEDULER_STOPPED -> true;
        };
    }
}
