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

import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.task.TaskStatus;
import lombok.Getter;

/**
 * Event fired for task lifecycle changes.
 */
@Getter
public class TaskEvent extends OrchestratorEvent {

    private final Long taskInstanceId;
    private final String taskDefinitionId;
    private final TaskStatus status;
    private final Integer exitCode;
    private final String errorMessage;

    public TaskEvent(Object source, String orchestratorInstanceId,
                     OrchestratorEventType eventType, Long taskInstanceId,
                     String taskDefinitionId, TaskStatus status,
                     Integer exitCode, String errorMessage) {
        super(source, orchestratorInstanceId, eventType,
                String.format("Task %d: %s", taskInstanceId, status));
        this.taskInstanceId = taskInstanceId;
        this.taskDefinitionId = taskDefinitionId;
        this.status = status;
        this.exitCode = exitCode;
        this.errorMessage = errorMessage;
    }

    /**
     * Create from a TaskInstance.
     */
    public static TaskEvent from(Object source, TaskInstance task, OrchestratorEventType eventType) {
        return new TaskEvent(source, task.getOrchestratorInstanceId(),
                eventType, task.getId(), task.getTaskDefinitionId(),
                task.getStatus(), task.getExitCode(), task.getErrorMessage());
    }

    /**
     * Create a task started event.
     */
    public static TaskEvent started(Object source, TaskInstance task) {
        return from(source, task, OrchestratorEventType.TASK_STARTED);
    }

    /**
     * Create a task completed event.
     */
    public static TaskEvent completed(Object source, TaskInstance task) {
        return from(source, task, OrchestratorEventType.TASK_COMPLETED);
    }

    /**
     * Create a task failed event.
     */
    public static TaskEvent failed(Object source, TaskInstance task) {
        return from(source, task, OrchestratorEventType.TASK_FAILED);
    }

    /**
     * Create a task cancelled event.
     */
    public static TaskEvent cancelled(Object source, TaskInstance task) {
        return from(source, task, OrchestratorEventType.TASK_CANCELLED);
    }

    /**
     * Create a task timeout event.
     */
    public static TaskEvent timeout(Object source, TaskInstance task) {
        return from(source, task, OrchestratorEventType.TASK_TIMEOUT);
    }
}
