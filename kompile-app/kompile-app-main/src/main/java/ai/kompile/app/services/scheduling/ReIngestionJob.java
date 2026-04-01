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

import ai.kompile.app.services.IndexSyncService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public class ReIngestionJob implements Job {

    @Autowired
    private IndexSyncService syncService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long factSheetId = context.getJobDetail().getJobDataMap().getLong("factSheetId");
        log.info("Running scheduled re-ingestion for fact sheet {}", factSheetId);

        try {
            var result = syncService.syncAll(factSheetId).get();
            log.info("Re-ingestion complete: {} documents, {} passages processed",
                    result.documentsProcessed(), result.passagesProcessed());
        } catch (Exception e) {
            log.error("Re-ingestion failed for fact sheet {}", factSheetId, e);
            throw new JobExecutionException(e);
        }
    }
}
