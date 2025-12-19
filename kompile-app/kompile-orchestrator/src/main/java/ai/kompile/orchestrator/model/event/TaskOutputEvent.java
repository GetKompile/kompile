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
package ai.kompile.orchestrator.model.event;

import lombok.Getter;

/**
 * Event fired for real-time task output.
 */
@Getter
public class TaskOutputEvent extends OrchestratorEvent {

    private final Long taskInstanceId;
    private final String outputLine;
    private final boolean isError;

    public TaskOutputEvent(Object source, String orchestratorInstanceId,
                           Long taskInstanceId, String outputLine, boolean isError) {
        super(source, orchestratorInstanceId, OrchestratorEventType.TASK_OUTPUT, outputLine);
        this.taskInstanceId = taskInstanceId;
        this.outputLine = outputLine;
        this.isError = isError;
    }

    public TaskOutputEvent(Object source, String orchestratorInstanceId,
                           Long taskInstanceId, String outputLine) {
        this(source, orchestratorInstanceId, taskInstanceId, outputLine, false);
    }
}
