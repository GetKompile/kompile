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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;

/**
 * Real-time monitor that uses keyword matching to block tool calls and interrupt
 * text output. Unlike {@link EnforcerRealtimeMonitor} which requires an LLM judge,
 * this monitor is instant and requires no backend.
 *
 * <p>Tool call evaluation is synchronous (blocks before side effects).
 * Text evaluation checks the full accumulated output on each chunk — since keyword
 * matching is instant, there's no need for async background evaluation.</p>
 */
public class KeywordRealtimeMonitor implements SubprocessAgentRunner.RealtimeMonitor {

    private static final int MIN_PARTIAL_CHARS = 20;

    private final KeywordEnforcerEvaluator evaluator;
    private final EnforcerPolicy policy;
    private final EnforcerConversationWindow conversationWindow;

    public KeywordRealtimeMonitor(KeywordEnforcerEvaluator evaluator, EnforcerPolicy policy) {
        this(evaluator, policy, null);
    }

    public KeywordRealtimeMonitor(KeywordEnforcerEvaluator evaluator, EnforcerPolicy policy,
                                  EnforcerConversationWindow conversationWindow) {
        this.evaluator = evaluator;
        this.policy = policy;
        this.conversationWindow = conversationWindow;
    }

    @Override
    public SubprocessAgentRunner.MonitorDecision onTextChunk(String chunk, String fullText) {
        if (conversationWindow != null) {
            conversationWindow.updateAssistantMessage(fullText);
        }

        if (evaluator == null || policy == null || !policy.hasRules()
                || fullText == null || fullText.length() < MIN_PARTIAL_CHARS) {
            return SubprocessAgentRunner.MonitorDecision.continueRun();
        }

        // Keyword check is instant — evaluate every chunk after minimum threshold
        EnforcerDecision decision = evaluator.evaluate(null, fullText, policy, 1);

        if (decision.isStop()) {
            return SubprocessAgentRunner.MonitorDecision.interrupt(
                    summarize(decision), decision.getCorrectionPrompt());
        }

        // For non-stop violations on partial text, only interrupt if critical
        if (!decision.isCompliant() && "critical".equals(decision.getSeverity())) {
            return SubprocessAgentRunner.MonitorDecision.interrupt(
                    summarize(decision), decision.getCorrectionPrompt());
        }

        return SubprocessAgentRunner.MonitorDecision.continueRun();
    }

    @Override
    public SubprocessAgentRunner.MonitorDecision onToolUse(String toolName, String input) {
        if (conversationWindow != null) {
            conversationWindow.addToolCall(toolName, input);
        }

        if (evaluator == null || policy == null || !policy.hasRules()) {
            return SubprocessAgentRunner.MonitorDecision.continueRun();
        }

        EnforcerToolCallDecision decision = evaluator.evaluateToolCall(toolName, input, policy);

        if (!decision.isAllowed()) {
            return SubprocessAgentRunner.MonitorDecision.interrupt(
                    decision.blockMessage(), decision.getCorrectionPrompt());
        }

        return SubprocessAgentRunner.MonitorDecision.continueRun();
    }

    private static String summarize(EnforcerDecision decision) {
        if (decision == null) {
            return "rule violation";
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
