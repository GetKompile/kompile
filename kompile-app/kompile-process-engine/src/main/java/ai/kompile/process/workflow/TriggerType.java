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

/**
 * Describes what event activates a {@link StepTrigger}.
 */
public enum TriggerType {
    /** Step starts when a predecessor step completes. */
    ON_STEP_COMPLETE,
    /** Step starts on a recurring cron schedule. */
    ON_SCHEDULE,
    /** Step starts when a domain event matches a pattern. */
    ON_EVENT,
    /** Step starts when a file arrives at a watched path. */
    ON_FILE_ARRIVAL,
    /** Step is started explicitly by a human or orchestrator. */
    MANUAL
}
