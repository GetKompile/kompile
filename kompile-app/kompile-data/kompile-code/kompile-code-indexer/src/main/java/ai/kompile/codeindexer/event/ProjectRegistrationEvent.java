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

package ai.kompile.codeindexer.event;

import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * Spring ApplicationEvent published when a code project is registered or
 * re-registered via the CLI auto-register endpoint. Consumed by
 * {@link ProjectRegistrationBroadcaster} to push real-time notifications
 * to the web UI over WebSocket.
 */
public class ProjectRegistrationEvent extends ApplicationEvent {

    public enum EventType {
        /** A new project was registered for the first time */
        PROJECT_REGISTERED,
        /** An existing project was re-registered (metadata updated) */
        PROJECT_UPDATED,
        /** A project was activated (set as the current active project) */
        PROJECT_ACTIVATED
    }

    private final EventType eventType;
    private final String projectId;
    private final String projectName;
    private final String directory;
    private final boolean active;
    private final Map<String, Object> indexMetadata;

    public ProjectRegistrationEvent(Object source, EventType eventType,
                                     String projectId, String projectName,
                                     String directory, boolean active,
                                     Map<String, Object> indexMetadata) {
        super(source);
        this.eventType = eventType;
        this.projectId = projectId;
        this.projectName = projectName;
        this.directory = directory;
        this.active = active;
        this.indexMetadata = indexMetadata != null ? indexMetadata : Map.of();
    }

    public EventType getEventType() { return eventType; }
    public String getProjectId() { return projectId; }
    public String getProjectName() { return projectName; }
    public String getDirectory() { return directory; }
    public boolean isActive() { return active; }
    public Map<String, Object> getIndexMetadata() { return indexMetadata; }

    // ── Factory methods ──────────────────────────────────────────────

    public static ProjectRegistrationEvent registered(Object source, String projectId,
                                                       String name, String directory,
                                                       Map<String, Object> indexMetadata) {
        return new ProjectRegistrationEvent(source, EventType.PROJECT_REGISTERED,
                projectId, name, directory, false, indexMetadata);
    }

    public static ProjectRegistrationEvent updated(Object source, String projectId,
                                                    String name, String directory,
                                                    Map<String, Object> indexMetadata) {
        return new ProjectRegistrationEvent(source, EventType.PROJECT_UPDATED,
                projectId, name, directory, false, indexMetadata);
    }

    public static ProjectRegistrationEvent activated(Object source, String projectId,
                                                      String name) {
        return new ProjectRegistrationEvent(source, EventType.PROJECT_ACTIVATED,
                projectId, name, null, true, null);
    }

    @Override
    public String toString() {
        return String.format("ProjectRegistrationEvent[type=%s, projectId=%s, directory=%s]",
                eventType, projectId, directory);
    }
}
