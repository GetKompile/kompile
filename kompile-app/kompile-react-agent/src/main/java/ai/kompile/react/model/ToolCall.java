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

import java.util.Map;
import java.util.UUID;

/**
 * Represents a tool call requested by the Reasoner.
 */
@Data
@Builder
public class ToolCall {

    /**
     * Unique identifier for this tool call.
     */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /**
     * The name of the tool to call.
     */
    private String name;

    /**
     * The arguments to pass to the tool.
     */
    private Map<String, Object> arguments;

    /**
     * Raw JSON string of arguments (for tools that need raw JSON).
     */
    private String rawArguments;

    /**
     * Create a tool call with parsed arguments.
     */
    public static ToolCall of(String name, Map<String, Object> arguments) {
        return ToolCall.builder()
                .name(name)
                .arguments(arguments)
                .build();
    }

    /**
     * Create a tool call with raw JSON arguments.
     */
    public static ToolCall ofRaw(String name, String rawArguments) {
        return ToolCall.builder()
                .name(name)
                .rawArguments(rawArguments)
                .build();
    }

    /**
     * Check if this is a final answer tool call.
     */
    public boolean isFinalAnswer() {
        return "final_answer".equals(name);
    }
}
