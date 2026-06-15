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

package ai.kompile.app.monitor.service;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job that fires a scheduled monitor.
 * Dispatches back to {@link MonitorService#fireScheduled(String)} so all
 * wake-up logic lives in one place.
 */
@Slf4j
public class MonitorFireJob implements Job {

    @Autowired
    private MonitorService monitorService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String monitorId = context.getJobDetail().getJobDataMap().getString(MonitorService.JOB_DATA_MONITOR_ID);
        if (monitorId == null) {
            log.warn("MonitorFireJob fired with no monitorId in JobDataMap");
            return;
        }
        try {
            monitorService.fireScheduled(monitorId);
        } catch (Exception e) {
            log.error("Error firing monitor {}: {}", monitorId, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
