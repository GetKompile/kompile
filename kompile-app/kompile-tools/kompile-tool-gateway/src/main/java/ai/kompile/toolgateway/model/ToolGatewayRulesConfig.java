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
 * Root configuration object for the tool gateway rules file.
 * <p>
 * Persisted as JSON at the configured rules file path
 * (default: {@code ~/.kompile/config/tool-gateway-rules.json}).
 * </p>
 */
@Data
public class ToolGatewayRulesConfig {

    /**
     * Version of the rules file schema, for forward compatibility.
     */
    @JsonProperty
    private int version = 1;

    /**
     * Default action when no rules match a tool call.
     * Typically ALLOW so that unmatched calls pass through.
     */
    @JsonProperty
    private GatewayAction defaultAction = GatewayAction.ALLOW;

    /**
     * Optional system-level instructions prepended to every LLM evaluation prompt.
     * Use this to set global context, e.g., "You are a security policy enforcer.
     * Be conservative — if uncertain, prefer BLOCK."
     */
    @JsonProperty
    private String systemPrompt;

    /**
     * The ordered list of rules. Rules are sorted by priority (descending)
     * before evaluation.
     */
    @JsonProperty
    private List<ToolGatewayRule> rules = new ArrayList<>();
}
