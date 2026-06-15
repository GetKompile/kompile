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
package ai.kompile.orchestrator.service.prompt;

import ai.kompile.orchestrator.model.prompt.PromptRoutingRule;
import ai.kompile.orchestrator.model.prompt.StatePromptConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Result of building a prompt with all applied configurations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptBuildResult {

    /**
     * The assembled prompt ready for LLM invocation.
     */
    private String prompt;

    /**
     * The system prompt for the LLM.
     */
    private String systemPrompt;

    /**
     * IDs of injections that were applied.
     */
    private List<String> appliedInjections;

    /**
     * The routing rule that matched (if any).
     */
    private PromptRoutingRule routingRule;

    /**
     * Variables used in template rendering.
     */
    private Map<String, Object> variables;

    /**
     * The state configuration used.
     */
    private StatePromptConfig stateConfig;

    /**
     * Check if a routing rule was matched.
     */
    public boolean hasRoutingRule() {
        return routingRule != null;
    }

    /**
     * Get the suggested action from the routing rule.
     */
    public PromptRoutingRule.RoutingAction getSuggestedAction() {
        return routingRule != null ? routingRule.getAction() : null;
    }

    /**
     * Get the target state from the routing rule.
     */
    public String getTargetState() {
        return routingRule != null ? routingRule.getTargetState() : null;
    }

    /**
     * Check if any injections were applied.
     */
    public boolean hasInjections() {
        return appliedInjections != null && !appliedInjections.isEmpty();
    }

    /**
     * Get the number of applied injections.
     */
    public int getInjectionCount() {
        return appliedInjections != null ? appliedInjections.size() : 0;
    }

    /**
     * Get a variable value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String name) {
        return variables != null ? (T) variables.get(name) : null;
    }

    /**
     * Get output format hint from state config.
     */
    public String getOutputFormat() {
        return stateConfig != null ? stateConfig.getOutputFormat() : null;
    }

    /**
     * Get max tokens from state config.
     */
    public Integer getMaxTokens() {
        return stateConfig != null ? stateConfig.getMaxTokens() : null;
    }

    /**
     * Get temperature from state config.
     */
    public Double getTemperature() {
        return stateConfig != null ? stateConfig.getTemperature() : null;
    }

    /**
     * Check if this result suggests a retry action.
     */
    public boolean suggestsRetry() {
        return routingRule != null && routingRule.getAction() == PromptRoutingRule.RoutingAction.RETRY;
    }

    /**
     * Check if this result suggests escalation.
     */
    public boolean suggestsEscalation() {
        return routingRule != null && routingRule.getAction() == PromptRoutingRule.RoutingAction.ESCALATE;
    }

    /**
     * Check if this result suggests failure.
     */
    public boolean suggestsFailure() {
        return routingRule != null && routingRule.getAction() == PromptRoutingRule.RoutingAction.FAIL;
    }

    /**
     * Check if the routing rule is terminal.
     */
    public boolean isTerminal() {
        return routingRule != null && routingRule.isTerminal();
    }

    /**
     * Get routing advice if available.
     */
    public String getRoutingAdvice() {
        return routingRule != null ? routingRule.getAdvice() : null;
    }
}
