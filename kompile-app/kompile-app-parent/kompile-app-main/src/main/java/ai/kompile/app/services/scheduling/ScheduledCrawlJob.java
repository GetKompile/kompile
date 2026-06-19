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

package ai.kompile.app.services.scheduling;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

/**
 * Quartz job that executes a unified crawl request on a schedule.
 *
 * <p>The crawl request is serialized as JSON in the Quartz JobDataMap under
 * the key {@code crawlRequestJson}. On each fire, the job deserializes the
 * request and submits it to {@link UnifiedCrawlService}.</p>
 *
 * <p>Optional JobDataMap keys:</p>
 * <ul>
 *   <li>{@code waitForCompletion} — if "true", blocks until the crawl finishes (default: false)</li>
 *   <li>{@code timeoutMinutes} — max minutes to wait when waitForCompletion is true (default: 60)</li>
 * </ul>
 */
@Slf4j
public class ScheduledCrawlJob implements Job {

    @Autowired
    private UnifiedCrawlService unifiedCrawlService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String json = context.getJobDetail().getJobDataMap().getString("crawlRequestJson");
        if (json == null || json.isBlank()) {
            throw new JobExecutionException("Missing crawlRequestJson in job data");
        }

        boolean waitForCompletion = "true".equals(
                context.getJobDetail().getJobDataMap().getString("waitForCompletion"));
        int timeoutMinutes = 60;
        String timeoutStr = context.getJobDetail().getJobDataMap().getString("timeoutMinutes");
        if (timeoutStr != null) {
            try { timeoutMinutes = Integer.parseInt(timeoutStr); }
            catch (NumberFormatException e) { log.warn("Invalid timeout value '{}' in scheduled crawl config, using default {}m: {}", timeoutStr, timeoutMinutes, e.getMessage()); }
        }

        UnifiedCrawlRequest request;
        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper.copy() : JsonUtils.standardMapper();
            request = mapper.readValue(json, UnifiedCrawlRequest.class);
        } catch (Exception e) {
            throw new JobExecutionException("Failed to deserialize crawl request", e);
        }

        String scheduleName = request.getName() != null ? request.getName()
                : context.getJobDetail().getKey().getName();
        log.info("Scheduled crawl firing: name={}, sources={}", scheduleName,
                request.getSources() != null ? request.getSources().size() : 0);

        try {
            UnifiedCrawlJob job = unifiedCrawlService.startJob(request);
            log.info("Scheduled crawl started: jobId={}, name={}", job.getJobId(), scheduleName);

            if (waitForCompletion) {
                // Poll until completion or timeout
                long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes);
                while (System.currentTimeMillis() < deadline) {
                    UnifiedCrawlJob.Status status = job.getStatus().get();
                    if (status == UnifiedCrawlJob.Status.COMPLETED
                            || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                            || status == UnifiedCrawlJob.Status.FAILED
                            || status == UnifiedCrawlJob.Status.CANCELLED) {
                        log.info("Scheduled crawl completed: jobId={}, status={}", job.getJobId(), status);
                        if (status == UnifiedCrawlJob.Status.FAILED) {
                            throw new JobExecutionException("Crawl job failed: " + job.getErrorMessage());
                        }
                        return;
                    }
                    Thread.sleep(5000);
                }
                log.warn("Scheduled crawl timed out after {} minutes: jobId={}", timeoutMinutes, job.getJobId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobExecutionException("Scheduled crawl interrupted", e);
        } catch (JobExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Scheduled crawl failed: name={}", scheduleName, e);
            throw new JobExecutionException(e);
        }
    }
}
