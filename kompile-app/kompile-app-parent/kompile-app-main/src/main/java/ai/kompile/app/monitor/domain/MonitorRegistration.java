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

package ai.kompile.app.monitor.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Durable monitor registration.
 *
 * A monitor binds a background task (or a schedule) to a chat session so that
 * when the task completes (or the scheduled trigger fires), a wake-up message
 * is pushed into the chat via WebSocket + persisted to chat history.
 */
@Entity
@Table(name = "monitor_registrations", indexes = {
        @Index(name = "idx_monitor_session", columnList = "sessionId"),
        @Index(name = "idx_monitor_task", columnList = "taskId"),
        @Index(name = "idx_monitor_status", columnList = "status"),
        @Index(name = "idx_monitor_type", columnList = "type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String monitorId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private MonitorType type;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private MonitorStatus status;

    /** Chat session that gets woken up on fire. */
    @Column(nullable = false)
    private String sessionId;

    /** Subprocess/background task id being watched (TASK_COMPLETION only). */
    @Column
    private String taskId;

    /** Cron expression (SCHEDULED_CRON only). */
    @Column
    private String cronExpression;

    /** Target fire time as epoch millis (SCHEDULED_ONCE only). */
    @Column
    private Long fireAtEpochMs;

    /** Human-readable description shown in the wake-up message. */
    @Column(length = 1024)
    private String description;

    /** Optional payload forwarded as the wake-up message body (e.g. a chat prompt). */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime firedAt;

    @Column
    private LocalDateTime cancelledAt;

    @Column
    private Integer fireCount;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = MonitorStatus.ACTIVE;
        if (fireCount == null) fireCount = 0;
    }

    public enum MonitorType {
        /** Fires when the named subprocess task completes (success or failure). */
        TASK_COMPLETION,
        /** One-shot fire at a specific wall-clock time. */
        SCHEDULED_ONCE,
        /** Recurring cron-driven fire. */
        SCHEDULED_CRON
    }

    public enum MonitorStatus {
        ACTIVE,
        FIRED,
        CANCELLED,
        ERROR
    }
}
