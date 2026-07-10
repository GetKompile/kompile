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

package ai.kompile.app.services.placement;

import ai.kompile.app.services.scheduler.JobResourceProfile;

/**
 * One unit of model-using work presented to the placement engine.
 *
 * @param serviceType  the subprocess/service key (embedding, serving, vlm, …) — matches
 *                    {@link JobResourceProfile#serviceType()} and the ND4J service routes
 * @param profile      the job-type resource declaration
 * @param taskKind     what kind of compute this is (decides CLI-routability)
 * @param modelId      catalog id of the model this workload runs, or null (e.g. a CPU-only step)
 */
public record WorkloadRequest(
        String serviceType,
        JobResourceProfile profile,
        TaskKind taskKind,
        String modelId
) {

    /** Only LLM-shaped work can be served by a CLI agent; embedding/VLM/KGE are local-only. */
    public enum TaskKind {
        LLM(true), EMBEDDING(false), VLM(false), KGE(false), OTHER(false);

        private final boolean cliRoutable;
        TaskKind(boolean cliRoutable) { this.cliRoutable = cliRoutable; }
        public boolean isCliRoutable() { return cliRoutable; }
    }

    public boolean isCliRoutable() {
        return taskKind != null && taskKind.isCliRoutable();
    }
}
