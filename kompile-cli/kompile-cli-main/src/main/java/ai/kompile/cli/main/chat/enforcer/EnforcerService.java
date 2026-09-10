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

    private static final long JUDGE_READY_TIMEOUT_MS =
            Long.getLong("kompile.judge.readyTimeoutMs", 30_000L);

    @FunctionalInterface
    public interface AgentTurnExecutor {
        String run(String prompt) throws Exception;
    }

    private final EnforcerEvaluator evaluator;
    private JudgementLog judgementLog;
    private EnforcerFallbackPolicy fallbackPolicy = EnforcerFallbackPolicy.FAIL_OPEN;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public EnforcerService(EnforcerEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** Attach a judgement log so enforcement outcomes (and keyword decisions) are recorded. */
    public void setJudgementLog(JudgementLog judgementLog) {
        this.judgementLog = judgementLog;
    }

    /**
     * Configure how the enforcer behaves when the judge is unavailable or fails mid-turn.
     * The mapper is needed to build a keyword fallback evaluator for DEGRADE_TO_KEYWORD.
     */
    public void setFallbackPolicy(EnforcerFallbackPolicy fallbackPolicy,
                                  com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        if (fallbackPolicy != null) {
            this.fallbackPolicy = fallbackPolicy;
        }
        this.objectMapper = objectMapper;
    }

    public EnforcerResult enforce(String userPrompt, EnforcerPolicy policy,
                                  AgentTurnExecutor executor) {
        return enforce(userPrompt, policy, null, executor);
    }

    public EnforcerResult enforce(String userPrompt, EnforcerPolicy policy,
                                  Supplier<EnforcerConversationContext> contextSupplier,
                                  AgentTurnExecutor executor) {
        if (policy == null || !policy.hasRules()) {
            return finish(EnforcerResult.error("Enforcer rules are required", List.of(), "none"),
                    null, userPrompt, "", "none");
        }

        // Resolve the evaluator, applying the fallback policy if the judge is unavailable.
        EnforcerEvaluator activeEvaluator = evaluator;
        String backend = activeEvaluator != null ? activeEvaluator.describe() : "none";
        boolean ready = false;
        try {
            ready = activeEvaluator != null
                    && activeEvaluator.awaitReady(JUDGE_READY_TIMEOUT_MS)
                    && activeEvaluator.isAvailable();
        } catch (RuntimeException failure) {
            EnforcerDiagnostics.alert("[enforcer] judge readiness failed open: " + failure.getMessage());
        }
        if (!ready) {
            EnforcerEvaluator keyword = buildKeywordFallback(policy);
            if (keyword != null) {
                activeEvaluator = keyword;
                backend = keyword.describe() + " (judge unavailable → keyword)";
            } else if (fallbackPolicy == EnforcerFallbackPolicy.FAIL_CLOSED) {
                return finish(EnforcerResult.unavailable(
                                "No enforcer judge backend is available (failing closed)", backend),
                        activeEvaluator, userPrompt, "", backend);
            } else {
                // No judge and no usable keyword fallback: run the agent once, unjudged.
                try {
                    String out = executor.run(buildInitialPrompt(userPrompt, policy));
                    return finish(EnforcerResult.accepted(out, List.of(), "fail-open (no judge)"),
                            activeEvaluator, userPrompt, out, "fail-open (no judge)");
                } catch (Exception e) {
                    return finish(EnforcerResult.error("Agent run failed: " + e.getMessage(), List.of(),
                                    "fail-open (no judge)"),
                            activeEvaluator, userPrompt, "", "fail-open (no judge)");
                }
            }
        }

        List<EnforcerResult.Attempt> attempts = new ArrayList<>();
        String nextPrompt = buildInitialPrompt(userPrompt, policy);
        String lastOutput = "";

        int totalAttempts = policy.getMaxCorrections() + 1;
        for (int attemptNumber = 1; attemptNumber <= totalAttempts; attemptNumber++) {
            try {
                lastOutput = executor.run(nextPrompt);
            } catch (Exception e) {
                return finish(EnforcerResult.error(
                                "Agent run failed: " + e.getMessage(), attempts, backend),
                        activeEvaluator, userPrompt, lastOutput, backend);
            }

            EnforcerDecision decision;
            try {
                EnforcerConversationContext context = contextSupplier != null
                        ? contextSupplier.get() : EnforcerConversationContext.empty();
                decision = activeEvaluator.evaluate(userPrompt, lastOutput, policy,
                        attemptNumber, context);
                if (decision == null) {
                    throw new IllegalStateException("judge returned no decision");
                }
            } catch (Exception e) {
                // Judge failed mid-turn (timeout / exhausted swaps): apply the fallback policy.
                return finish(applyMidTurnFallback(userPrompt, policy, lastOutput, attempts, backend, e),
                        activeEvaluator, userPrompt, lastOutput, backend);
            }

            attempts.add(new EnforcerResult.Attempt(attemptNumber, lastOutput, decision));
            recordAttempt(activeEvaluator, attemptNumber, decision, userPrompt, lastOutput, backend);

            if (decision.isCompliant()) {
                return finish(EnforcerResult.accepted(lastOutput, attempts, backend),
                        activeEvaluator, userPrompt, lastOutput, backend);
            }

            if (decision.isStop()) {
                return finish(EnforcerResult.blocked(lastOutput, attempts,
                        "Stopped by enforcer: " + summarizeDecision(decision), backend),
                        activeEvaluator, userPrompt, lastOutput, backend);
            }

            if (attemptNumber == totalAttempts) {
                return finish(EnforcerResult.blocked(lastOutput, attempts,
                        "Maximum corrections reached: " + summarizeDecision(decision), backend),
                        activeEvaluator, userPrompt, lastOutput, backend);
            }

            nextPrompt = buildCorrectionPrompt(userPrompt, policy, lastOutput, decision, attemptNumber + 1);
        }

        return finish(EnforcerResult.blocked(lastOutput, attempts, "Enforcer exhausted correction attempts", backend),
                activeEvaluator, userPrompt, lastOutput, backend);
    }

    /** Record the final enforcement outcome (RESULT phase) and return the result unchanged. */
    private EnforcerResult finish(EnforcerResult result, EnforcerEvaluator activeEvaluator,
                                  String userPrompt, String lastOutput, String backend) {
        if (judgementLog != null && result != null) {
            judgementLog.record(JudgementRecord.builder()
                    .phase("RESULT")
                    .judgeMode(activeEvaluator != null && activeEvaluator.recordsJudgements() ? "llm" : "keyword")
                    .backend(backend)
                    .status(result.getStatus() != null ? result.getStatus().name() : "UNKNOWN")
                    .compliant(result.getStatus() == EnforcerResult.Status.ACCEPTED)
                    .reasoning(result.getMessage())
                    .userPromptExcerpt(userPrompt)
                    .agentOutputExcerpt(lastOutput)
                    .build());
        }
        return result;
    }

    /**
     * Record a per-attempt decision. Only fires when the evaluator does not record its own
     * judgements (i.e. the keyword path) — LLM judges already log a JUDGE_TURN record with the
     * raw response, so this avoids duplicates.
     */
    private void recordAttempt(EnforcerEvaluator activeEvaluator, int attemptNumber, EnforcerDecision decision,
                               String userPrompt, String lastOutput, String backend) {
        if (judgementLog == null || (activeEvaluator != null && activeEvaluator.recordsJudgements())) {
            return;
        }
        judgementLog.record(JudgementRecord.builder()
                .phase("ATTEMPT")
                .attempt(attemptNumber)
                .judgeMode("keyword")
                .backend(backend)
                .compliant(decision.isCompliant())
                .stop(decision.isStop())
                .severity(decision.getSeverity())
                .violations(decision.getViolations())
                .correctionPrompt(decision.getCorrectionPrompt())
                .reasoning(decision.getReasoning())
                .userPromptExcerpt(userPrompt)
                .agentOutputExcerpt(lastOutput)
                .build());
    }

    /** Build a keyword evaluator from the policy for degrade-to-keyword fallback, or null. */
    private EnforcerEvaluator buildKeywordFallback(EnforcerPolicy policy) {
        if (fallbackPolicy != EnforcerFallbackPolicy.DEGRADE_TO_KEYWORD || objectMapper == null) {
            return null;
        }
        try {
            KeywordEnforcerEvaluator keyword = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);
            return keyword.isAvailable() ? keyword : null;
        } catch (RuntimeException failure) {
            EnforcerDiagnostics.alert("[enforcer] keyword fallback failed open: " + failure.getMessage());
            return null;
        }
    }

    /** Apply the configured fallback when the judge throws mid-turn (after the agent produced output). */
    private EnforcerResult applyMidTurnFallback(String userPrompt, EnforcerPolicy policy, String lastOutput,
                                                List<EnforcerResult.Attempt> attempts, String backend,
                                                Exception cause) {
        if (fallbackPolicy == EnforcerFallbackPolicy.FAIL_CLOSED) {
            return EnforcerResult.blocked(lastOutput, attempts,
                    "Judge failed; failing closed: " + cause.getMessage(), backend);
        }
        if (fallbackPolicy == EnforcerFallbackPolicy.DEGRADE_TO_KEYWORD && objectMapper != null
                && lastOutput != null && !lastOutput.isBlank()) {
            try {
                KeywordEnforcerEvaluator keyword = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);
                if (keyword.isAvailable()) {
                    EnforcerDecision decision = keyword.evaluate(userPrompt, lastOutput, policy, attempts.size() + 1);
                    if (decision.isCompliant()) {
                        return EnforcerResult.accepted(lastOutput, attempts, backend + " (judge failed → keyword)");
                    }
                    return EnforcerResult.blocked(lastOutput, attempts,
                            "Judge failed; keyword check blocked: " + summarizeDecision(decision),
                            backend + " (judge failed → keyword)");
                }
            } catch (Exception ignored) {
                // Fall through to fail-open when the fallback itself cannot run.
            }
        }
        return EnforcerResult.accepted(lastOutput, attempts, backend + " (judge failed → fail-open)");
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
