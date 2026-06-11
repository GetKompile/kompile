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

package ai.kompile.process.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Defines what causes a {@link ProcessStep} to start executing.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StepTrigger {

    private TriggerType type;
    /** Source step ID for {@link TriggerType#ON_STEP_COMPLETE} triggers. */
    private String sourceStepId;
    /** Cron expression for {@link TriggerType#ON_SCHEDULE} triggers. */
    private String cronExpression;
    /** Event pattern string for {@link TriggerType#ON_EVENT} triggers. */
    private String eventPattern;
    /** File system path to watch for {@link TriggerType#ON_FILE_ARRIVAL} triggers. */
    private String watchPath;
}
