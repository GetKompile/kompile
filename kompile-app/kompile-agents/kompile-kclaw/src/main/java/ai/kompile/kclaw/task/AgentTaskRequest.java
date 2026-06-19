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

import lombok.Data;

/**
 * Request body for {@code POST /api/kclaw/tasks} — run an agent on a task and save its output.
 */
@Data
public class AgentTaskRequest {

    /** Engine: "react" (default) or "kompile-cli". */
    private String engine;

    /** For the ReAct engine: agent id (defaults to the configured default agent). */
    private String agentId;

    /** The work to perform (required). */
    private String task;

    /** Optional ReAct session key for conversational continuity. */
    private String sessionKey;

    /** Optional model override (kompile-cli engine). */
    private String model;

    /** Run in the background (default true). When false, the call blocks until the task finishes. */
    private Boolean async;

    /** Optional channel to deliver the result to on completion (e.g. "discord", "slack"). */
    private String channel;

    /** Target id on the channel (e.g. a Discord/Slack channel id). Required when {@link #channel} is set. */
    private String channelTarget;
}
