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

package ai.kompile.app.ingest.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing a single log entry for an indexing job.
 * Logs are captured from both regular document ingest jobs ("add source based")
 * and vector population jobs ("populate from vector").
 *
 * This provides detailed debugging information for job execution,
 * complementing the high-level events stored in IngestEvent.
 */
@Entity
@Table(name = "job_log_entries", indexes = {
    @Index(name = "idx_job_log_task_id", columnList = "taskId"),
    @Index(name = "idx_job_log_timestamp", columnList = "timestamp"),
    @Index(name = "idx_job_log_level", columnList = "level"),
    @Index(name = "idx_job_log_task_seq", columnList = "taskId, sequenceNumber")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Task ID that groups all log entries for a single job.
     * This matches the taskId in IndexingJobHistory and IngestEvent.
     */
    @Column(nullable = false, length = 64)
    private String taskId;

    /**
     * When this log entry was created.
     */
    @Column(nullable = false)
    private Instant timestamp;

    /**
     * Log level (TRACE, DEBUG, INFO, WARN, ERROR).
     */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private LogLevel level;

    /**
     * Source of the log entry (STDOUT, STDERR, SYSTEM, APPLICATION, EMBEDDING).
     * Uses VARCHAR column definition to avoid H2 enum constraint issues when adding new values.
     */
    @Column(nullable = false, columnDefinition = "VARCHAR(32)")
    @Enumerated(EnumType.STRING)
    private LogSource source;

    /**
     * The log message content.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /**
     * The logger name that produced this log (e.g., class name).
     * May be null for STDOUT/STDERR sources.
     */
    @Column(length = 512)
    private String loggerName;

    /**
     * The thread name that produced this log.
     */
    @Column(length = 128)
    private String threadName;

    /**
     * Sequence number for ordering logs within a task.
     * Ensures proper ordering even when timestamps are identical.
     */
    @Column(nullable = false)
    private Long sequenceNumber;

    /**
     * Optional exception class name if this log is associated with an error.
     */
    @Column(length = 256)
    private String exceptionClass;

    /**
     * Optional stack trace if this log is associated with an error.
     * Truncated to 4000 characters to prevent storage issues.
     */
    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /**
     * Log levels supported for job logging.
     * All levels are captured by default per user preference.
     */
    public enum LogLevel {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * Source of log entries.
     */
    public enum LogSource {
        /** Standard output from subprocess */
        STDOUT,
        /** Standard error from subprocess */
        STDERR,
        /** System/framework logs */
        SYSTEM,
        /** Application-level logs from the indexing code */
        APPLICATION,
        /** Embedding subprocess logs */
        EMBEDDING
    }

    /**
     * Create a log entry from standard output.
     */
    public static JobLogEntry stdout(String taskId, String message, long sequenceNumber) {
        return JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(LogLevel.INFO)
                .source(LogSource.STDOUT)
                .message(message)
                .sequenceNumber(sequenceNumber)
                .build();
    }

    /**
     * Create a log entry from standard error.
     */
    public static JobLogEntry stderr(String taskId, String message, long sequenceNumber) {
        return JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(LogLevel.ERROR)
                .source(LogSource.STDERR)
                .message(message)
                .sequenceNumber(sequenceNumber)
                .build();
    }

    /**
     * Create a system log entry.
     */
    public static JobLogEntry system(String taskId, LogLevel level, String message, long sequenceNumber) {
        return JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(level)
                .source(LogSource.SYSTEM)
                .message(message)
                .sequenceNumber(sequenceNumber)
                .build();
    }

    /**
     * Create an application log entry.
     */
    public static JobLogEntry application(String taskId, LogLevel level, String message,
                                           String loggerName, String threadName, long sequenceNumber) {
        return JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(level)
                .source(LogSource.APPLICATION)
                .message(message)
                .loggerName(loggerName)
                .threadName(threadName)
                .sequenceNumber(sequenceNumber)
                .build();
    }

    /**
     * Create an embedding subprocess log entry.
     */
    public static JobLogEntry embedding(String taskId, LogLevel level, String message, long sequenceNumber) {
        return JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(level)
                .source(LogSource.EMBEDDING)
                .message(message)
                .sequenceNumber(sequenceNumber)
                .build();
    }

    /**
     * Create an error log entry with exception details.
     */
    public static JobLogEntry error(String taskId, String message, Throwable exception,
                                     String loggerName, String threadName, long sequenceNumber) {
        JobLogEntryBuilder builder = JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(LogLevel.ERROR)
                .source(LogSource.APPLICATION)
                .message(message)
                .loggerName(loggerName)
                .threadName(threadName)
                .sequenceNumber(sequenceNumber);

        if (exception != null) {
            builder.exceptionClass(exception.getClass().getName());
            StringBuilder sb = new StringBuilder();
            sb.append(exception.getClass().getName()).append(": ").append(exception.getMessage()).append("\n");
            for (StackTraceElement element : exception.getStackTrace()) {
                sb.append("\tat ").append(element.toString()).append("\n");
                if (sb.length() > 4000) {
                    sb.append("... (truncated)\n");
                    break;
                }
            }
            builder.stackTrace(sb.toString());
        }

        return builder.build();
    }

    /**
     * Format log entry for display/download.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp.toString())
          .append(" [").append(level.name()).append("]")
          .append(" [").append(source.name()).append("]");

        if (threadName != null) {
            sb.append(" [").append(threadName).append("]");
        }
        if (loggerName != null) {
            sb.append(" ").append(loggerName);
        }
        sb.append(" - ").append(message);

        if (stackTrace != null) {
            sb.append("\n").append(stackTrace);
        }

        return sb.toString();
    }
}
