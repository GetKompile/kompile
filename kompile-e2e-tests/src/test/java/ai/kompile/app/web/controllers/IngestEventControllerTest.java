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
package ai.kompile.app.web.controllers;

import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IngestEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for IngestEventController.
 * Uses standalone MockMvc setup to avoid Spring context loading issues.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IngestEventController Tests")
class IngestEventControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private IngestEventService ingestEventService;
    private IngestEventController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        ingestEventService = mock(IngestEventService.class);
        controller = new IngestEventController(ingestEventService, objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("GET /api/ingest/events/status")
    class GetStatusTests {

        @Test
        @DisplayName("Should return status with service available and enabled")
        void shouldReturnStatusWithServiceAvailableAndEnabled() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);
            when(ingestEventService.getTotalEventCount()).thenReturn(42L);
            when(ingestEventService.getTaskIds(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of("task-1", "task-2"));

            mockMvc.perform(get("/api/ingest/events/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.serviceAvailable", is(true)))
                    .andExpect(jsonPath("$.enabled", is(true)))
                    .andExpect(jsonPath("$.totalEvents", is(42)))
                    .andExpect(jsonPath("$.recentTaskCount", is(2)))
                    .andExpect(jsonPath("$.recentTaskIds", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("Service Absent Tests")
    class ServiceAbsentTests {

        @BeforeEach
        void setUp() {
            controller = new IngestEventController(null, objectMapper);

            MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
            converter.setObjectMapper(objectMapper);

            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(converter)
                    .build();
        }

        @Test
        @DisplayName("GET /status should return enabled=false when service is null")
        void shouldReturnEnabledFalseWhenServiceIsNull() throws Exception {
            mockMvc.perform(get("/api/ingest/events/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)))
                    .andExpect(jsonPath("$.serviceAvailable", is(false)));
        }

        @Test
        @DisplayName("GET /task/{taskId}/environment should return not available when service is null")
        void shouldReturnNotAvailableForEnvironmentWhenServiceNull() throws Exception {
            mockMvc.perform(get("/api/ingest/events/task/task-123/environment"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.environmentCaptured", is(false)))
                    .andExpect(jsonPath("$.taskId", is("task-123")));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/task/{taskId}")
    class GetEventsForTaskTests {

        @Test
        @DisplayName("Should return events for a task")
        void shouldReturnEventsForTask() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent event1 = mock(IngestEvent.class);
            when(event1.getEventType()).thenReturn(IngestEvent.EventType.COMPLETED);
            IngestEvent event2 = mock(IngestEvent.class);
            when(event2.getEventType()).thenReturn(IngestEvent.EventType.QUEUED);

            when(ingestEventService.getEventsForTask("task-abc"))
                    .thenReturn(List.of(event1, event2));

            mockMvc.perform(get("/api/ingest/events/task/task-abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is("task-abc")))
                    .andExpect(jsonPath("$.eventCount", is(2)));
        }

        @Test
        @DisplayName("Should return enabled=false when service is disabled")
        void shouldReturnEnabledFalseWhenDisabled() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(false);

            mockMvc.perform(get("/api/ingest/events/task/task-abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/task/{taskId}/latest")
    class GetLatestEventTests {

        @Test
        @DisplayName("Should return latest event for a task")
        void shouldReturnLatestEvent() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent event = mock(IngestEvent.class);
            when(event.getEventType()).thenReturn(IngestEvent.EventType.COMPLETED);
            when(event.getFileName()).thenReturn("test.pdf");

            when(ingestEventService.getLatestEvent("task-xyz")).thenReturn(event);

            mockMvc.perform(get("/api/ingest/events/task/task-xyz/latest"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 when no event found")
        void shouldReturn404WhenNoEvent() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);
            when(ingestEventService.getLatestEvent("task-xyz")).thenReturn(null);

            mockMvc.perform(get("/api/ingest/events/task/task-xyz/latest"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return enabled=false when disabled")
        void shouldReturnEnabledFalseWhenDisabled() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(false);

            mockMvc.perform(get("/api/ingest/events/task/task-xyz/latest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/recent")
    class GetRecentEventsTests {

        @Test
        @DisplayName("Should return recent terminal events")
        void shouldReturnRecentTerminalEvents() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent event = mock(IngestEvent.class);
            when(event.getEventType()).thenReturn(IngestEvent.EventType.COMPLETED);

            when(ingestEventService.getRecentTerminalEvents(any(Duration.class)))
                    .thenReturn(List.of(event));

            mockMvc.perform(get("/api/ingest/events/recent").param("hours", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lookbackHours", is(12)))
                    .andExpect(jsonPath("$.eventCount", is(1)));
        }

        @Test
        @DisplayName("Should return enabled=false when disabled")
        void shouldReturnEnabledFalseWhenDisabled() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(false);

            mockMvc.perform(get("/api/ingest/events/recent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/errors")
    class GetErrorEventsTests {

        @Test
        @DisplayName("Should return error events")
        void shouldReturnErrorEvents() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent errorEvent = mock(IngestEvent.class);
            when(errorEvent.getEventType()).thenReturn(IngestEvent.EventType.FAILED);

            when(ingestEventService.getErrorEvents(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(errorEvent));

            mockMvc.perform(get("/api/ingest/events/errors").param("hours", "48"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lookbackHours", is(48)))
                    .andExpect(jsonPath("$.errorCount", is(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/tasks")
    class GetTaskIdsTests {

        @Test
        @DisplayName("Should return distinct task IDs")
        void shouldReturnDistinctTaskIds() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);
            when(ingestEventService.getTaskIds(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of("task-1", "task-2", "task-3"));

            mockMvc.perform(get("/api/ingest/events/tasks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskCount", is(3)))
                    .andExpect(jsonPath("$.tasks", hasSize(3)));
        }
    }

    @Nested
    @DisplayName("DELETE /api/ingest/events/task/{taskId}")
    class DeleteTaskEventsTests {

        @Test
        @DisplayName("Should delete events for a task")
        void shouldDeleteEvents() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);
            doNothing().when(ingestEventService).deleteTaskEvents("task-del");

            mockMvc.perform(delete("/api/ingest/events/task/task-del"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is("task-del")))
                    .andExpect(jsonPath("$.deleted", is(true)));

            verify(ingestEventService).deleteTaskEvents("task-del");
        }

        @Test
        @DisplayName("Should return enabled=false when disabled")
        void shouldReturnEnabledFalseWhenDisabled() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(false);

            mockMvc.perform(delete("/api/ingest/events/task/task-del"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)));
        }
    }

    @Nested
    @DisplayName("POST /api/ingest/events/cleanup")
    class ForceCleanupTests {

        @Test
        @DisplayName("Should return deleted count after cleanup")
        void shouldReturnDeletedCount() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);
            when(ingestEventService.forceCleanup(7)).thenReturn(15);

            mockMvc.perform(post("/api/ingest/events/cleanup").param("days", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.olderThanDays", is(7)))
                    .andExpect(jsonPath("$.deletedCount", is(15)));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/summary")
    class GetSummaryTests {

        @Test
        @DisplayName("Should return statistics summary")
        void shouldReturnStatisticsSummary() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent completedEvent = mock(IngestEvent.class);
            when(completedEvent.getEventType()).thenReturn(IngestEvent.EventType.COMPLETED);
            when(completedEvent.getDurationMs()).thenReturn(5000L);

            IngestEvent failedEvent = mock(IngestEvent.class);
            when(failedEvent.getEventType()).thenReturn(IngestEvent.EventType.FAILED);

            when(ingestEventService.getRecentTerminalEvents(any(Duration.class)))
                    .thenReturn(List.of(completedEvent, failedEvent));
            when(ingestEventService.getErrorEvents(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(failedEvent));
            when(ingestEventService.getTotalEventCount()).thenReturn(100L);

            mockMvc.perform(get("/api/ingest/events/summary").param("hours", "24"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lookbackHours", is(24)))
                    .andExpect(jsonPath("$.totalTasks", is(2)))
                    .andExpect(jsonPath("$.completed", is(1)))
                    .andExpect(jsonPath("$.failed", is(1)))
                    .andExpect(jsonPath("$.errorCount", is(1)))
                    .andExpect(jsonPath("$.totalEventsInDb", is(100)));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/events/task/{taskId}/environment")
    class GetTaskEnvironmentTests {

        @Test
        @DisplayName("Should return environment snapshot when QUEUED event has snapshot")
        void shouldReturnEnvironmentSnapshotWithQueuedEvent() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent queuedEvent = mock(IngestEvent.class);
            when(queuedEvent.getEventType()).thenReturn(IngestEvent.EventType.QUEUED);
            when(queuedEvent.getFileName()).thenReturn("doc.pdf");
            when(queuedEvent.getTimestamp()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
            when(queuedEvent.getNd4jEnvironmentSnapshot()).thenReturn("{\"backend\":\"CPU\"}");

            when(ingestEventService.getEventsForTask("task-env"))
                    .thenReturn(List.of(queuedEvent));

            mockMvc.perform(get("/api/ingest/events/task/task-env/environment"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is("task-env")))
                    .andExpect(jsonPath("$.environmentCaptured", is(true)))
                    .andExpect(jsonPath("$.nd4jEnvironment.backend", is("CPU")));
        }

        @Test
        @DisplayName("Should return environmentCaptured=false when no QUEUED event")
        void shouldReturnFalseWhenNoQueuedEvent() throws Exception {
            when(ingestEventService.isEnabled()).thenReturn(true);

            IngestEvent completedEvent = mock(IngestEvent.class);
            when(completedEvent.getEventType()).thenReturn(IngestEvent.EventType.COMPLETED);

            when(ingestEventService.getEventsForTask("task-noenv"))
                    .thenReturn(List.of(completedEvent));

            mockMvc.perform(get("/api/ingest/events/task/task-noenv/environment"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.environmentCaptured", is(false)));
        }
    }
}
