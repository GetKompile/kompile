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

package ai.kompile.app.monitor.dto;

/**
 * Request body for creating monitor registrations.
 */
public class MonitorRequest {

    /** Watch a subprocess task id; fires on completion (success or failure). */
    public record WatchTask(
            String sessionId,
            String taskId,
            String description,
            String payload
    ) {}

    /** One-shot schedule: fires once at the given epoch milliseconds. */
    public record ScheduleOnce(
            String sessionId,
            long fireAtEpochMs,
            String description,
            String payload
    ) {}

    /** Recurring cron schedule. */
    public record ScheduleCron(
            String sessionId,
            String cronExpression,
            String description,
            String payload
    ) {}
}
