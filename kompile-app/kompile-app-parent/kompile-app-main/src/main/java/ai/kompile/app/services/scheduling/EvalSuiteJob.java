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

import ai.kompile.core.evaluation.EvaluationContext;
import ai.kompile.core.evaluation.EvaluationService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public class EvalSuiteJob implements Job {

    @Autowired(required = false)
    private EvaluationService evaluationService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String suiteId = context.getJobDetail().getJobDataMap().getString("suiteId");
        log.info("Running scheduled evaluation suite: {}", suiteId);

        if (evaluationService == null || !evaluationService.isEnabled()) {
            log.warn("EvaluationService not available or not enabled, skipping eval suite run");
            return;
        }

        try {
            // Evaluation suite execution — currently logs that it ran.
            // A full eval suite would load test cases from a suite definition and run them.
            log.info("Evaluation suite {} triggered successfully", suiteId);
        } catch (Exception e) {
            log.error("Evaluation suite {} failed", suiteId, e);
            throw new JobExecutionException(e);
        }
    }
}
