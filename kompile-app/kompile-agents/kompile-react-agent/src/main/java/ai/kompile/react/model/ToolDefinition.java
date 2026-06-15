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
import java.util.function.Function;

/**
 * Defines a tool that can be called by the ReAct agent.
 */
@Data
@Builder
public class ToolDefinition {

    /**
     * The unique name of the tool.
     */
    private String name;

    /**
     * A description of what the tool does.
     */
    private String description;

    /**
     * JSON Schema for the tool's parameters.
     */
    private Map<String, Object> parameters;

    /**
     * The function to execute when this tool is called.
     * Takes a map of arguments and returns the result as a string.
     */
    private Function<Map<String, Object>, String> executor;

    /**
     * Whether this tool requires approval before execution.
     */
    @Builder.Default
    private boolean requiresApproval = false;

    /**
     * Whether this tool can be executed in parallel with others.
     */
    @Builder.Default
    private boolean parallelizable = true;

    /**
     * Execute the tool with the given arguments.
     */
    public String execute(Map<String, Object> arguments) {
        if (executor == null) {
            throw new IllegalStateException("Tool " + name + " has no executor");
        }
        return executor.apply(arguments);
    }
}
