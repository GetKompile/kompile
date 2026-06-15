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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Orchestrates a subordinate LLM turn through judge evaluation and correction retries.
 */
public class EnforcerService {

    @FunctionalInterface
    public interface AgentTurnExecutor {
        String run(String prompt) throws Exception;
    }

    private final EnforcerEvaluator evaluator;

    public EnforcerService(EnforcerEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public EnforcerResult enforce(String userPrompt, EnforcerPolicy policy,
                                  AgentTurnExecutor executor) {
        return enforce(userPrompt, policy, null, executor);
    }

    public EnforcerResult enforce(String userPrompt, EnforcerPolicy policy,
                                  Supplier<EnforcerConversationContext> contextSupplier,
                                  AgentTurnExecutor executor) {
        String backend = evaluator != null ? evaluator.describe() : "none";
        if (policy == null || !policy.hasRules()) {
            return EnforcerResult.error("Enforcer rules are required", List.of(), backend);
        }
        if (evaluator == null || !evaluator.isAvailable()) {
            return EnforcerResult.unavailable("No enforcer judge backend is available", backend);
        }

        List<EnforcerResult.Attempt> attempts = new ArrayList<>();
        String nextPrompt = buildInitialPrompt(userPrompt, policy);
        String lastOutput = "";

        int totalAttempts = policy.getMaxCorrections() + 1;
        for (int attemptNumber = 1; attemptNumber <= totalAttempts; attemptNumber++) {
            try {
                lastOutput = executor.run(nextPrompt);
                EnforcerConversationContext context = contextSupplier != null
                        ? contextSupplier.get() : EnforcerConversationContext.empty();
                EnforcerDecision decision = evaluator.evaluate(userPrompt, lastOutput, policy,
                        attemptNumber, context);
                attempts.add(new EnforcerResult.Attempt(attemptNumber, lastOutput, decision));

                if (decision.isCompliant()) {
                    return EnforcerResult.accepted(lastOutput, attempts, backend);
                }

                if (decision.isStop()) {
                    return EnforcerResult.blocked(lastOutput, attempts,
                            "Stopped by enforcer: " + summarizeDecision(decision), backend);
                }

                if (attemptNumber == totalAttempts) {
                    return EnforcerResult.blocked(lastOutput, attempts,
                            "Maximum corrections reached: " + summarizeDecision(decision), backend);
                }

                nextPrompt = buildCorrectionPrompt(userPrompt, policy, lastOutput, decision, attemptNumber + 1);
            } catch (Exception e) {
                return EnforcerResult.error("Enforcer execution failed: " + e.getMessage(), attempts, backend);
            }
        }

        return EnforcerResult.blocked(lastOutput, attempts, "Enforcer exhausted correction attempts", backend);
    }

    public static String buildInitialPrompt(String userPrompt, EnforcerPolicy policy) {
        return "# Enforcer-Controlled Task\n\n"
                + "You are the subordinate LLM in an enforcer-controlled chat. "
                + "Follow the enforcer rules exactly. If a rule conflicts with a normal preference, "
                + "the enforcer rule wins for this task.\n\n"
                + "## Enforcer Rules\n"
                + policy.getRules()
                + "\n\n## User Prompt\n"
                + userPrompt
                + "\n\nProduce the response now. Do not mention the enforcer unless the rules require it.";
    }

    public static String buildCorrectionPrompt(String userPrompt, EnforcerPolicy policy,
                                               String previousOutput, EnforcerDecision decision,
                                               int nextAttempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Enforcer Correction\n\n")
                .append("Your previous response was blocked by the enforcer. ")
                .append("Stop following the blocked response and produce a corrected answer.\n\n")
                .append("## Enforcer Rules\n")
                .append(policy.getRules())
                .append("\n\n## Original User Prompt\n")
                .append(userPrompt)
                .append("\n\n## Violations\n");

        if (decision.getViolations().isEmpty()) {
            sb.append("- Non-compliant with the enforcer rules\n");
        } else {
            for (String violation : decision.getViolations()) {
                sb.append("- ").append(violation).append("\n");
            }
        }

        if (!decision.getCorrectionPrompt().isBlank()) {
            sb.append("\n## Required Correction\n")
                    .append(decision.getCorrectionPrompt())
                    .append("\n");
        }

        sb.append("\n## Previous Blocked Response\n")
                .append(previousOutput == null ? "" : previousOutput)
                .append("\n\nThis is correction attempt ")
                .append(nextAttempt)
                .append(". Return only the corrected response.");
        return sb.toString();
    }

    private static String summarizeDecision(EnforcerDecision decision) {
        if (decision == null) {
            return "no decision";
        }
        if (!decision.getViolations().isEmpty()) {
            return String.join("; ", decision.getViolations());
        }
        if (!decision.getReasoning().isBlank()) {
            return decision.getReasoning();
        }
        return decision.getSeverity();
    }
}
