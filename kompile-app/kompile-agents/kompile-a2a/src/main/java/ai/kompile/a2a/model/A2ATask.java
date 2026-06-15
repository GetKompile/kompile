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

package ai.kompile.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task - represents a unit of work exchanged between agents.
 * Maps to the A2A protocol Task object with lifecycle state tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class A2ATask {

    private String id;
    private String contextId;
    private TaskStatus status;
    private List<A2AArtifact> artifacts;
    private A2AMessage history;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TaskStatus {
        private TaskState state;
        private String message;
        private Instant timestamp;
    }

    public enum TaskState {
        SUBMITTED,
        WORKING,
        INPUT_REQUIRED,
        COMPLETED,
        CANCELED,
        FAILED,
        UNKNOWN
    }
}
