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
package ai.kompile.react.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The final result of a ReAct agent execution.
 */
@Data
@Builder
public class ReActResult {

    /**
     * Execution status.
     */
    public enum Status {
        /**
         * Agent completed successfully with a final answer.
         */
        COMPLETED,

        /**
         * Agent reached max steps without completing.
         */
        MAX_STEPS_EXCEEDED,

        /**
         * Agent was cancelled.
         */
        CANCELLED,

        /**
         * Agent encountered an error.
         */
        ERROR
    }

    /**
     * The execution status.
     */
    private Status status;

    /**
     * The final answer content.
     */
    private String answer;

    /**
     * Structured output if the answer schema was used.
     */
    private Map<String, Object> structuredOutput;

    /**
     * All messages from the execution.
     */
    private List<ReActMessage> messages;

    /**
     * Number of reasoning steps executed.
     */
    private int stepsExecuted;

    /**
     * Total token usage across all steps.
     */
    private TokenUsage totalUsage;

    /**
     * Time when execution started.
     */
    private Instant startTime;

    /**
     * Time when execution ended.
     */
    private Instant endTime;

    /**
     * Error message if status is ERROR.
     */
    private String errorMessage;

    /**
     * Get the execution duration.
     */
    public Duration getDuration() {
        if (startTime != null && endTime != null) {
            return Duration.between(startTime, endTime);
        }
        return Duration.ZERO;
    }

    /**
     * Check if execution was successful.
     */
    public boolean isSuccess() {
        return status == Status.COMPLETED;
    }

    /**
     * Create a successful result.
     */
    public static ReActResult success(String answer, List<ReActMessage> messages,
                                       int steps, TokenUsage usage,
                                       Instant startTime, Instant endTime) {
        return ReActResult.builder()
                .status(Status.COMPLETED)
                .answer(answer)
                .messages(messages)
                .stepsExecuted(steps)
                .totalUsage(usage)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }

    /**
     * Create a max steps exceeded result.
     */
    public static ReActResult maxStepsExceeded(String summary, List<ReActMessage> messages,
                                                int steps, TokenUsage usage,
                                                Instant startTime, Instant endTime) {
        return ReActResult.builder()
                .status(Status.MAX_STEPS_EXCEEDED)
                .answer(summary)
                .messages(messages)
                .stepsExecuted(steps)
                .totalUsage(usage)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }

    /**
     * Create an error result.
     */
    public static ReActResult error(String errorMessage, List<ReActMessage> messages,
                                     int steps, TokenUsage usage,
                                     Instant startTime, Instant endTime) {
        return ReActResult.builder()
                .status(Status.ERROR)
                .errorMessage(errorMessage)
                .messages(messages)
                .stepsExecuted(steps)
                .totalUsage(usage)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }
}
