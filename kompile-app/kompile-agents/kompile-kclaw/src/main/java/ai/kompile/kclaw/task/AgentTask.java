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
package ai.kompile.kclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A unit of agent work: run an agent on a {@link #task} and persist the {@link #output}.
 * Persisted as JSON by {@code AgentTaskStore} and returned over the REST API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

    /** Lifecycle of a task. */
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    /** Unique task id (UUID). */
    private String id;

    /** Which engine ran (or will run) the work. */
    private TaskEngine engine;

    /** For {@link TaskEngine#REACT}: the kclaw agent definition id (e.g. "jarvis"). */
    private String agentId;

    /** The work to perform — the prompt/instruction handed to the agent. */
    private String task;

    /** Optional session key for ReAct conversational continuity. */
    private String sessionKey;

    /** Optional model override (used by the kompile CLI engine). */
    private String model;

    @Builder.Default
    private Status status = Status.PENDING;

    /** Final text output produced by the agent. */
    private String output;

    /** Absolute path to the saved output artifact file, if written. */
    private String outputFile;

    /** Error message when {@link #status} is {@link Status#FAILED}. */
    private String error;

    /** Optional channel the result was delivered to (e.g. "discord"). */
    private String channel;

    /** Target id on {@link #channel} (e.g. a Discord/Slack channel id). */
    private String channelTarget;

    /** Chat-history session id when the output was persisted to the DB store. */
    private String dbSessionId;

    private long createdAt;
    private long startedAt;
    private long finishedAt;
}
