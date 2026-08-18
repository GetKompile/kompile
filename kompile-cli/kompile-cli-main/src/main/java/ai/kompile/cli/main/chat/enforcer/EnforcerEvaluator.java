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

package ai.kompile.cli.main.chat.enforcer;

/**
 * Evaluates whether a subordinate agent response follows the active enforcer rules.
 */
public interface EnforcerEvaluator {

    EnforcerDecision evaluate(String userPrompt, String agentOutput,
                              EnforcerPolicy policy, int attempt) throws Exception;

    default EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                      EnforcerPolicy policy, int attempt,
                                      EnforcerConversationContext context) throws Exception {
        return evaluate(userPrompt, agentOutput, policy, attempt);
    }

    boolean isAvailable();

    /**
     * Wait for an evaluator that has asynchronous startup (for example a CLI judge
     * process) to finish its readiness check. Keyword evaluators are ready immediately.
     */
    default boolean awaitReady(long timeoutMs) {
        return isAvailable();
    }

    String describe();

    /**
     * Whether this evaluator records its own judgements to the {@link JudgementLog}
     * (e.g. an LLM judge that captures the raw judge response per call). When true,
     * {@link EnforcerService} skips its own per-attempt logging to avoid duplicates.
     */
    default boolean recordsJudgements() {
        return false;
    }
}
