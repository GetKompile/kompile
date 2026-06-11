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

package ai.kompile.core.toolgateway;

/**
 * Callback interface for tool gateway evaluation events.
 * <p>
 * Implementations can collect metrics, log decisions, or feed
 * evaluations into the judge scoring pipeline.
 * </p>
 */
public interface GatewayEvaluationListener {

    /**
     * Called after each gateway evaluation completes.
     *
     * @param toolName  the tool that was evaluated
     * @param action    the action taken (ALLOW, REWRITE, BLOCK)
     * @param reason    the reason for the decision
     * @param ruleId    the matched rule ID, or null
     * @param latencyMs how long the evaluation took
     */
    void onEvaluation(String toolName, String action, String reason, String ruleId, long latencyMs);
}
