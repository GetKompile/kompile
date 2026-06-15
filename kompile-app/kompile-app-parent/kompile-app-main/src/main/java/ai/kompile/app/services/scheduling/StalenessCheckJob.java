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

import ai.kompile.app.services.DocumentFreshnessService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public class StalenessCheckJob implements Job {

    @Autowired(required = false)
    private DocumentFreshnessService freshnessService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long factSheetId = context.getJobDetail().getJobDataMap().getLong("factSheetId");
        log.info("Running scheduled staleness check for fact sheet {}", factSheetId);

        if (freshnessService == null) {
            log.warn("DocumentFreshnessService not available, skipping staleness check");
            return;
        }

        try {
            int checksumStale = freshnessService.scanForStaleDocuments(factSheetId);
            int ttlStale = freshnessService.markStaleByTtl(factSheetId);
            log.info("Staleness check complete: {} checksum-stale, {} ttl-stale", checksumStale, ttlStale);
        } catch (Exception e) {
            log.error("Staleness check failed for fact sheet {}", factSheetId, e);
            throw new JobExecutionException(e);
        }
    }
}
