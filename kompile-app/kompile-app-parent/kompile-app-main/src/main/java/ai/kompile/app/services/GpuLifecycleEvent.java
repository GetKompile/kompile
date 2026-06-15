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

package ai.kompile.app.services;

import ai.kompile.app.config.GpuDevice;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * Spring ApplicationEvent for GPU lifecycle transitions.
 *
 * <p>Published by {@link ModelLifecycleManager} when GPU resources are acquired,
 * released, or when services are evicted/restored. Enables other components
 * (e.g., WebSocket broadcasters, monitoring) to react to GPU state changes
 * without tight coupling.</p>
 */
public class GpuLifecycleEvent extends ApplicationEvent {

    public enum EventType {
        /** GPU resources acquired for a job */
        GPU_ACQUIRED,
        /** GPU resources released for a job */
        GPU_RELEASED,
        /** A service was evicted to make room for a higher-priority service */
        SERVICE_EVICTED,
        /** A previously evicted service was restored */
        SERVICE_RESTORED,
        /** A stale job GPU hold was detected */
        STALE_JOB_DETECTED,
        /** A stale job GPU hold was force-released */
        STALE_JOB_RELEASED,
        /** GPU lifecycle manager started */
        LIFECYCLE_STARTED,
        /** GPU lifecycle manager stopped */
        LIFECYCLE_STOPPED
    }

    private final EventType eventType;
    private final String jobId;
    private final String serviceType;
    private final GpuDevice device;
    private final Map<String, Object> data;

    public GpuLifecycleEvent(Object source, EventType eventType, String jobId,
                              String serviceType, GpuDevice device,
                              Map<String, Object> data) {
        super(source);
        this.eventType = eventType;
        this.jobId = jobId;
        this.serviceType = serviceType;
        this.device = device;
        this.data = data != null ? data : Map.of();
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getJobId() {
        return jobId;
    }

    public String getServiceType() {
        return serviceType;
    }

    public GpuDevice getDevice() {
        return device;
    }

    public Map<String, Object> getData() {
        return data;
    }

    // ==================== Factory Methods ====================

    public static GpuLifecycleEvent gpuAcquired(Object source, String jobId,
                                                  String serviceType, GpuDevice device,
                                                  long budgetMb) {
        return new GpuLifecycleEvent(source, EventType.GPU_ACQUIRED, jobId, serviceType, device,
                Map.of("budgetMb", budgetMb));
    }

    public static GpuLifecycleEvent gpuReleased(Object source, String jobId,
                                                  String serviceType, GpuDevice device,
                                                  long heldForMs) {
        return new GpuLifecycleEvent(source, EventType.GPU_RELEASED, jobId, serviceType, device,
                Map.of("heldForMs", heldForMs));
    }

    public static GpuLifecycleEvent serviceEvicted(Object source, String serviceType,
                                                     String requester) {
        return new GpuLifecycleEvent(source, EventType.SERVICE_EVICTED, null, serviceType, null,
                Map.of("requester", requester));
    }

    public static GpuLifecycleEvent serviceRestored(Object source, String serviceType,
                                                      GpuDevice device) {
        return new GpuLifecycleEvent(source, EventType.SERVICE_RESTORED, null, serviceType, device,
                Map.of());
    }

    public static GpuLifecycleEvent staleJobDetected(Object source, String jobId,
                                                       String serviceType, GpuDevice device,
                                                       long heldForMs) {
        return new GpuLifecycleEvent(source, EventType.STALE_JOB_DETECTED, jobId, serviceType, device,
                Map.of("heldForMs", heldForMs));
    }

    public static GpuLifecycleEvent staleJobReleased(Object source, String jobId,
                                                       String serviceType, GpuDevice device,
                                                       long heldForMs) {
        return new GpuLifecycleEvent(source, EventType.STALE_JOB_RELEASED, jobId, serviceType, device,
                Map.of("heldForMs", heldForMs));
    }

    public static GpuLifecycleEvent lifecycleStarted(Object source) {
        return new GpuLifecycleEvent(source, EventType.LIFECYCLE_STARTED, null, null, null, Map.of());
    }

    public static GpuLifecycleEvent lifecycleStopped(Object source) {
        return new GpuLifecycleEvent(source, EventType.LIFECYCLE_STOPPED, null, null, null, Map.of());
    }

    @Override
    public String toString() {
        return String.format("GpuLifecycleEvent[type=%s, jobId=%s, service=%s, device=%s]",
                eventType, jobId, serviceType, device != null ? device.name() : "null");
    }
}
