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

package ai.kompile.app.tools;

import ai.kompile.app.web.controllers.IndexingJobHistoryController;
import ai.kompile.app.web.controllers.IngestEventController;
import ai.kompile.app.web.controllers.JobLogController;
import ai.kompile.app.web.controllers.SubprocessEventHistoryController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for job history and event tracking.
 * Exposes indexing job history, ingest events, job logs, and subprocess event history.
 */
@Component
public class JobHistoryTool {

    private static final Logger logger = LoggerFactory.getLogger(JobHistoryTool.class);

    private final IndexingJobHistoryController jobHistoryController;
    private final IngestEventController ingestEventController;
    private final JobLogController jobLogController;
    private final SubprocessEventHistoryController subprocessEventController;

    @Autowired
    public JobHistoryTool(
            @Autowired(required = false) IndexingJobHistoryController jobHistoryController,
            @Autowired(required = false) IngestEventController ingestEventController,
            @Autowired(required = false) JobLogController jobLogController,
            @Autowired(required = false) SubprocessEventHistoryController subprocessEventController) {
        this.jobHistoryController = jobHistoryController;
        this.ingestEventController = ingestEventController;
        this.jobLogController = jobLogController;
        this.subprocessEventController = subprocessEventController;
    }

    // Input records
    public record GetAllJobsInput(Integer page, Integer size) {}
    public record GetJobInput(String taskId) {}
    public record GetRecentJobsInput(Integer hours) {}
    public record GetActiveJobsInput() {}
    public record GetLatestJobsInput(Integer limit) {}
    public record GetFailedJobsInput() {}
    public record GetJobStatisticsInput(Integer lastHours) {}
    public record GetJobSummaryInput(String taskId) {}
    public record SearchJobsByFileNameInput(String fileName) {}
    public record DeleteJobInput(String taskId) {}

    public record GetIngestEventStatusInput() {}
    public record GetIngestEventsForTaskInput(String taskId) {}
    public record GetRecentIngestEventsInput(Integer hours) {}
    public record GetIngestErrorEventsInput(Integer hours) {}
    public record GetIngestEventSummaryInput(Integer hours) {}

    public record GetJobLogsInput(String taskId, String level, Integer page, Integer size) {}
    public record TailJobLogsInput(String taskId, Integer lines) {}
    public record GetJobLogErrorsInput(String taskId) {}
    public record GetJobLogStatisticsInput() {}

    public record GetSubprocessEventsInput(Integer page, Integer size) {}
    public record GetRecentSubprocessEventsInput(Integer hours) {}
    public record GetSubprocessRestartEventsInput() {}
    public record GetSubprocessEventsForTaskInput(String taskId) {}
    public record GetSubprocessStatisticsInput() {}

    // === Indexing Job History ===

    @Tool(name = "get_all_indexing_jobs",
            description = "Gets all indexing jobs with pagination. Returns job IDs, statuses, timing, and results.")
    public Map<String, Object> getAllJobs(GetAllJobsInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            int page = input.page() != null ? input.page() : 0;
            int size = input.size() != null ? input.size() : 20;
            ResponseEntity<?> response = jobHistoryController.getAllJobs(page, size);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting all jobs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_indexing_job",
            description = "Gets a specific indexing job by its task ID.")
    public Map<String, Object> getJob(GetJobInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = jobHistoryController.getJob(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting job: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_recent_indexing_jobs",
            description = "Gets indexing jobs from the last N hours (default 24).")
    public Map<String, Object> getRecentJobs(GetRecentJobsInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            int hours = input.hours() != null ? input.hours() : 24;
            ResponseEntity<?> response = jobHistoryController.getRecentJobs(hours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting recent jobs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_active_indexing_jobs",
            description = "Gets currently active indexing jobs.")
    public Map<String, Object> getActiveJobs(GetActiveJobsInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            ResponseEntity<?> response = jobHistoryController.getActiveJobs();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting active jobs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_latest_indexing_jobs",
            description = "Gets the most recent N indexing jobs (default 10).")
    public Map<String, Object> getLatestJobs(GetLatestJobsInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            int limit = input.limit() != null ? input.limit() : 10;
            ResponseEntity<?> response = jobHistoryController.getLatestJobs(limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting latest jobs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_failed_indexing_jobs",
            description = "Gets all failed indexing jobs.")
    public Map<String, Object> getFailedJobs(GetFailedJobsInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            ResponseEntity<?> response = jobHistoryController.getFailedJobs();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting failed jobs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_indexing_job_statistics",
            description = "Gets job statistics summary for the last N hours (default 24). Includes failure rate and timing stats.")
    public Map<String, Object> getJobStatistics(GetJobStatisticsInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            int lastHours = input.lastHours() != null ? input.lastHours() : 24;
            ResponseEntity<?> response = jobHistoryController.getStatistics(lastHours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting job statistics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_indexing_job_summary",
            description = "Gets a simplified summary view of an indexing job with key timing, phase, and result information.")
    public Map<String, Object> getJobSummary(GetJobSummaryInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = jobHistoryController.getJobSummary(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting job summary: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "search_indexing_jobs_by_filename",
            description = "Searches indexing jobs by file name.")
    public Map<String, Object> searchJobsByFileName(SearchJobsByFileNameInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            if (input.fileName() == null) return Map.of("status", "error", "error", "File name is required");
            ResponseEntity<?> response = jobHistoryController.searchByFileName(input.fileName());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error searching jobs by filename: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_indexing_job",
            description = "Deletes a specific indexing job history entry.")
    public Map<String, Object> deleteJob(DeleteJobInput input) {
        try {
            if (jobHistoryController == null) return Map.of("status", "error", "error", "Job history not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = jobHistoryController.deleteJob(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting job: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Ingest Events ===

    @Tool(name = "get_ingest_event_status",
            description = "Checks if ingest event logging is enabled.")
    public Map<String, Object> getIngestEventStatus(GetIngestEventStatusInput input) {
        try {
            if (ingestEventController == null) return Map.of("status", "error", "error", "Ingest event service not available");
            ResponseEntity<?> response = ingestEventController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting ingest event status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_ingest_events_for_task",
            description = "Gets all ingest events for a specific task ID.")
    public Map<String, Object> getIngestEventsForTask(GetIngestEventsForTaskInput input) {
        try {
            if (ingestEventController == null) return Map.of("status", "error", "error", "Ingest event service not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = ingestEventController.getEventsForTask(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting ingest events for task: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_recent_ingest_events",
            description = "Gets recent terminal ingest events from the last N hours.")
    public Map<String, Object> getRecentIngestEvents(GetRecentIngestEventsInput input) {
        try {
            if (ingestEventController == null) return Map.of("status", "error", "error", "Ingest event service not available");
            int hours = input.hours() != null ? input.hours() : 24;
            ResponseEntity<?> response = ingestEventController.getRecentEvents(hours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting recent ingest events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_ingest_error_events",
            description = "Gets ingest error events from the last N hours.")
    public Map<String, Object> getIngestErrorEvents(GetIngestErrorEventsInput input) {
        try {
            if (ingestEventController == null) return Map.of("status", "error", "error", "Ingest event service not available");
            int hours = input.hours() != null ? input.hours() : 24;
            ResponseEntity<?> response = ingestEventController.getErrorEvents(hours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting ingest error events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_ingest_event_summary",
            description = "Gets summary statistics of ingest events for the last N hours.")
    public Map<String, Object> getIngestEventSummary(GetIngestEventSummaryInput input) {
        try {
            if (ingestEventController == null) return Map.of("status", "error", "error", "Ingest event service not available");
            int hours = input.hours() != null ? input.hours() : 24;
            ResponseEntity<?> response = ingestEventController.getSummary(hours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting ingest event summary: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Job Logs ===

    @Tool(name = "get_job_logs",
            description = "Gets logs for a specific job with optional level filtering and pagination.")
    public Map<String, Object> getJobLogs(GetJobLogsInput input) {
        try {
            if (jobLogController == null) return Map.of("status", "error", "error", "Job log service not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            int page = input.page() != null ? input.page() : 0;
            int size = input.size() != null ? input.size() : 100;
            ResponseEntity<?> response = jobLogController.getLogsForJob(input.taskId(), input.level(), null, null, null, page, size);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting job logs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "tail_job_logs",
            description = "Gets the last N log entries for a specific job (default 50).")
    public Map<String, Object> tailJobLogs(TailJobLogsInput input) {
        try {
            if (jobLogController == null) return Map.of("status", "error", "error", "Job log service not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            int lines = input.lines() != null ? input.lines() : 50;
            ResponseEntity<?> response = jobLogController.tailLogs(input.taskId(), lines);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error tailing job logs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_job_log_errors",
            description = "Gets error logs with stack traces for a specific job.")
    public Map<String, Object> getJobLogErrors(GetJobLogErrorsInput input) {
        try {
            if (jobLogController == null) return Map.of("status", "error", "error", "Job log service not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = jobLogController.getErrorsWithStackTrace(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting job log errors: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_job_log_statistics",
            description = "Gets log statistics across all jobs.")
    public Map<String, Object> getJobLogStatistics(GetJobLogStatisticsInput input) {
        try {
            if (jobLogController == null) return Map.of("status", "error", "error", "Job log service not available");
            ResponseEntity<?> response = jobLogController.getLogStatistics();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting job log statistics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Subprocess Events ===

    @Tool(name = "get_subprocess_events",
            description = "Gets paginated subprocess events.")
    public Map<String, Object> getSubprocessEvents(GetSubprocessEventsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            int page = input.page() != null ? input.page() : 0;
            int size = input.size() != null ? input.size() : 20;
            ResponseEntity<?> response = subprocessEventController.getEvents(page, size);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_recent_subprocess_events",
            description = "Gets recent subprocess events from the last N hours.")
    public Map<String, Object> getRecentSubprocessEvents(GetRecentSubprocessEventsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            int hours = input.hours() != null ? input.hours() : 24;
            ResponseEntity<?> response = subprocessEventController.getRecentEvents(hours);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting recent subprocess events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_restart_events",
            description = "Gets subprocess restart events only.")
    public Map<String, Object> getSubprocessRestartEvents(GetSubprocessRestartEventsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            ResponseEntity<?> response = subprocessEventController.getRestartEvents();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess restart events: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_events_for_task",
            description = "Gets subprocess events for a specific task ID.")
    public Map<String, Object> getSubprocessEventsForTask(GetSubprocessEventsForTaskInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = subprocessEventController.getEventsForTask(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess events for task: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_statistics",
            description = "Gets subprocess event statistics.")
    public Map<String, Object> getSubprocessStatistics(GetSubprocessStatisticsInput input) {
        try {
            if (subprocessEventController == null) return Map.of("status", "error", "error", "Subprocess event service not available");
            ResponseEntity<?> response = subprocessEventController.getStatistics();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting subprocess statistics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
