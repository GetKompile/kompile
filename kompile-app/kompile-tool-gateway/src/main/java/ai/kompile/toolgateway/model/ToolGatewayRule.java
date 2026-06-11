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

package ai.kompile.toolgateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A single gateway rule that describes when and how to intercept a tool call.
 * <p>
 * Rules are evaluated by an LLM against the incoming tool call. The LLM uses
 * the {@code description} and {@code condition} fields as natural-language
 * instructions to decide whether the rule applies.
 * </p>
 *
 * <p>Example rule (JSON):
 * <pre>{@code
 * {
 *   "id": "block-destructive-writes",
 *   "description": "Prevent file deletion or overwriting of protected paths",
 *   "toolPatterns": ["write_file", "delete_file"],
 *   "condition": "The file path targets /etc, /usr, or any system directory outside the configured workspace roots",
 *   "action": "BLOCK",
 *   "blockMessage": "Destructive write to a protected system path is not allowed.",
 *   "priority": 100,
 *   "enabled": true
 * }
 * }</pre>
 * </p>
 */
@Data
public class ToolGatewayRule {

    /**
     * Unique identifier for this rule.
     */
    @JsonProperty
    private String id;

    /**
     * Human-readable description of what this rule does.
     * Also fed to the LLM as context when evaluating tool calls.
     */
    @JsonProperty
    private String description;

    /**
     * Glob patterns for tool names this rule applies to.
     * An empty list means the rule applies to all tools.
     * Supports '*' wildcards (e.g., "write_*", "fs_*", "*").
     */
    @JsonProperty
    private List<String> toolPatterns = new ArrayList<>();

    /**
     * Natural-language condition that the LLM evaluates against the tool call arguments.
     * If the LLM determines this condition is met, the rule's action is triggered.
     * <p>
     * Examples:
     * <ul>
     *   <li>"The query contains SQL injection patterns"</li>
     *   <li>"The file path is outside the allowed workspace directories"</li>
     *   <li>"The content includes personally identifiable information (PII)"</li>
     * </ul>
     */
    @JsonProperty
    private String condition;

    /**
     * The action to take when this rule matches: ALLOW, REWRITE, or BLOCK.
     */
    @JsonProperty
    private GatewayAction action = GatewayAction.BLOCK;

    /**
     * For BLOCK actions: the message returned to the caller.
     */
    @JsonProperty
    private String blockMessage;

    /**
     * For REWRITE actions: natural-language instructions telling the LLM
     * how to rewrite the arguments. The LLM will produce a new argument map
     * based on these instructions.
     * <p>
     * Example: "Remove any PII fields from the content argument and replace them with [REDACTED]"
     * </p>
     */
    @JsonProperty
    private String rewriteInstructions;

    /**
     * Priority for rule ordering. Higher values are evaluated first.
     * If multiple rules match, the highest-priority one wins.
     */
    @JsonProperty
    private int priority = 0;

    /**
     * Whether this rule is active. Disabled rules are skipped during evaluation.
     */
    @JsonProperty
    private boolean enabled = true;
}
