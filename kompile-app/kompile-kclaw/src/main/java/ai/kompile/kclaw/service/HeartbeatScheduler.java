/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.service;

import java.time.Instant;
import java.util.List;

/**
 * Scheduler for proactive agent heartbeats.
 * Enables agents to initiate actions on schedules.
 */
public interface HeartbeatScheduler {

    void scheduleHeartbeat(String id, String cronExpression, String agentId, String message);

    void scheduleHeartbeat(String id, String cronExpression, String agentId, String sessionKey, String message);

    boolean cancelHeartbeat(String id);

    boolean isScheduled(String id);

    void pauseHeartbeat(String id);

    void resumeHeartbeat(String id);

    List<HeartbeatInfo> listHeartbeats();

    record HeartbeatInfo(
            String id,
            String cronExpression,
            String agentId,
            String sessionKey,
            String message,
            HeartbeatStatus status,
            Instant nextFireTime,
            Instant lastFireTime
    ) {}

    enum HeartbeatStatus {
        SCHEDULED,
        PAUSED,
        RUNNING,
        ERROR
    }
}
