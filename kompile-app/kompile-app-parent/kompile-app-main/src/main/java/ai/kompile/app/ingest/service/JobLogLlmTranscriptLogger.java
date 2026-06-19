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

package ai.kompile.app.ingest.service;

import ai.kompile.core.crawl.graph.LlmTranscriptLogger;
import ai.kompile.app.ingest.domain.JobLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persists LLM call transcripts to the job log database via {@link JobLogService}.
 * Each transcript is stored as a {@link JobLogEntry} with source {@code LLM_TRANSCRIPT},
 * making it auditable through the existing job log viewer and archive system.
 *
 * <p>The message format is structured text with clear prompt/response sections,
 * optimized for readability in the log viewer while remaining searchable.</p>
 */
@Component
public class JobLogLlmTranscriptLogger implements LlmTranscriptLogger {

    private static final Logger log = LoggerFactory.getLogger(JobLogLlmTranscriptLogger.class);

    /** Maximum prompt/response text to persist per entry (chars). */
    private static final int MAX_TRANSCRIPT_CHARS = 8000;

    private final JobLogService jobLogService;

    public JobLogLlmTranscriptLogger(JobLogService jobLogService) {
        this.jobLogService = jobLogService;
    }

    @Override
    public void logTranscript(String jobId, String backendId, String taskType,
                              String prompt, String response,
                              long latencyMs, boolean success, String errorMessage,
                              String agentSessionId) {
        if (jobId == null || !jobLogService.isEnabled()) {
            return;
        }

        // Use crawl- prefix to match the frontend convention for crawl job log viewing
        String taskId = "crawl-" + jobId;
        JobLogEntry.LogLevel level = success ? JobLogEntry.LogLevel.INFO : JobLogEntry.LogLevel.WARN;
        String message = formatTranscript(backendId, taskType, prompt, response,
                latencyMs, success, errorMessage, agentSessionId);

        try {
            jobLogService.logEntry(
                    taskId,
                    level,
                    JobLogEntry.LogSource.LLM_TRANSCRIPT,
                    message,
                    backendId,
                    Thread.currentThread().getName());
        } catch (Exception e) {
            log.debug("Failed to persist LLM transcript for job {}: {}", jobId, e.getMessage());
        }
    }

    private String formatTranscript(String backendId, String taskType,
                                     String prompt, String response,
                                     long latencyMs, boolean success, String errorMessage,
                                     String agentSessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(taskType).append("] backend=").append(backendId);
        if (agentSessionId != null && !agentSessionId.isBlank()) {
            sb.append(" session=").append(agentSessionId);
        }
        sb.append(" latency=").append(latencyMs).append("ms")
          .append(" status=").append(success ? "OK" : "FAILED");

        if (errorMessage != null) {
            sb.append(" error=").append(errorMessage);
        }
        sb.append("\n");

        // Prompt section
        sb.append("--- PROMPT ---\n");
        if (prompt != null) {
            if (prompt.length() > MAX_TRANSCRIPT_CHARS) {
                sb.append(prompt, 0, MAX_TRANSCRIPT_CHARS);
                sb.append("\n... [truncated, ").append(prompt.length()).append(" chars total]");
            } else {
                sb.append(prompt);
            }
        } else {
            sb.append("(no prompt)");
        }
        sb.append("\n");

        // Response section
        sb.append("--- RESPONSE ---\n");
        if (response != null) {
            if (response.length() > MAX_TRANSCRIPT_CHARS) {
                sb.append(response, 0, MAX_TRANSCRIPT_CHARS);
                sb.append("\n... [truncated, ").append(response.length()).append(" chars total]");
            } else {
                sb.append(response);
            }
        } else {
            sb.append(success ? "(empty response)" : "(no response - call failed)");
        }

        return sb.toString();
    }
}
