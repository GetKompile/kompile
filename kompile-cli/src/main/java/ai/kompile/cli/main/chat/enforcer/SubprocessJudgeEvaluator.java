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

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;

/**
 * An {@link EnforcerEvaluator} backed by a full {@link SubprocessAgentRunner}.
 * Unlike {@link EnforcerJudge} which uses simplified backends (PersistentAgentProcess,
 * direct HTTP API), this evaluator gives the judge agent the same first-class treatment
 * as the worker agent: MCP tools, skill injection, streaming output, and the full
 * managed subprocess lifecycle.
 *
 * <p>This enables agent-to-agent enforcement where both the worker and judge are
 * full CLI agents (Claude, Codex, Gemini, etc.) that can read files, run commands,
 * and use tools to make informed compliance decisions.</p>
 *
 * <p>The judge agent runs as a separate subprocess. Its output is captured (not displayed
 * to the user) and parsed as an {@link EnforcerDecision} JSON response.</p>
 */
public class SubprocessJudgeEvaluator implements EnforcerEvaluator, AutoCloseable {

    private final SubprocessAgentRunner judgeRunner;
    private final ChatHistory judgeHistory;
    private final ChatSessionMetrics judgeMetrics;
    private final ObjectMapper objectMapper;
    private final String judgeAgentName;
    private boolean available;

    /**
     * Create a subprocess judge evaluator.
     *
     * @param judgeAgent     the CLI agent to use as judge (e.g. "claude", "codex")
     * @param workingDir     working directory for the judge subprocess
     * @param kompileUrl     kompile-app URL for MCP tools (empty string if none)
     * @param mcpPort        MCP port (0 = auto-detect)
     * @param injectTools    whether to inject MCP tools into the judge agent
     * @param injectSkills   whether to inject skills into the judge agent
     * @param objectMapper   shared ObjectMapper for JSON parsing
     */
    public SubprocessJudgeEvaluator(String judgeAgent, String workingDir,
                                     String kompileUrl, int mcpPort,
                                     boolean injectTools, boolean injectSkills,
                                     ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.judgeAgentName = judgeAgent != null ? judgeAgent : "claude";

        String binary = SubprocessAgentRunner.resolveAgentBinary(judgeAgentName);
        if (binary == null) {
            this.judgeRunner = null;
            this.judgeHistory = null;
            this.judgeMetrics = null;
            this.available = false;
            return;
        }

        TerminalRenderer renderer = new TerminalRenderer();
        AsciiRenderer ascii = new AsciiRenderer(renderer, 120);

        this.judgeRunner = new SubprocessAgentRunner(
                judgeAgentName, workingDir, true, injectTools,
                kompileUrl != null ? kompileUrl : "", mcpPort,
                null, renderer, ascii);

        // Suppress judge output — we only want the text for parsing
        this.judgeRunner.setOutputConsumer(text -> { /* silent */ });

        String sessionId = "judge-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        this.judgeHistory = new ChatHistory(sessionId);
        this.judgeMetrics = new ChatSessionMetrics(sessionId);
        this.judgeMetrics.setAgentName(judgeAgentName + " (judge)");

        try {
            this.judgeHistory.open("judge-evaluator", judgeAgentName + " (judge)", false);
        } catch (java.io.IOException e) {
            // Non-fatal — judge can still work without transcript
        }

        // Inject MCP tools and skills into the judge agent
        if (injectTools) {
            this.judgeRunner.injectMcpTools();
        }
        if (injectSkills) {
            this.judgeRunner.injectSkills();
        }

        this.available = true;
    }

    /**
     * Create with extra environment variables for the judge subprocess.
     */
    public void setExtraEnvironment(Map<String, String> env) {
        if (judgeRunner != null && env != null) {
            judgeRunner.setExtraEnvironment(env);
        }
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt) throws Exception {
        return evaluate(userPrompt, agentOutput, policy, attempt, EnforcerConversationContext.empty());
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt,
                                     EnforcerConversationContext context) throws Exception {
        if (!available || judgeRunner == null) {
            return EnforcerDecision.stop(
                    java.util.List.of("Judge subprocess agent '" + judgeAgentName + "' not available"),
                    "Install the judge agent CLI or use a different judge mode.");
        }

        String judgePrompt = buildJudgePrompt(userPrompt, agentOutput, policy, attempt, context);
        String response = judgeRunner.runMessage(judgePrompt, judgeHistory, judgeMetrics);

        if (response == null || response.isBlank()) {
            return EnforcerDecision.stop(
                    java.util.List.of("Judge agent returned empty response"),
                    "The judge subprocess may have crashed or timed out.");
        }

        return EnforcerDecision.parse(objectMapper, response);
    }

    @Override
    public boolean isAvailable() {
        return available && judgeRunner != null;
    }

    @Override
    public String describe() {
        return "subprocess-judge(" + judgeAgentName + ")";
    }

    @Override
    public void close() {
        if (judgeRunner != null) {
            judgeRunner.cleanup();
        }
        if (judgeHistory != null) {
            judgeHistory.close();
        }
    }

    /**
     * Build the evaluation prompt sent to the judge agent.
     * The judge has full tool access so it can verify claims by reading files,
     * checking git state, etc. — not just evaluate text.
     */
    private String buildJudgePrompt(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt,
                                     EnforcerConversationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(EnforcerJudge.SYSTEM_PROMPT).append("\n\n");

        sb.append("[ENFORCER RULES]\n")
                .append(policy.getRules())
                .append("\n[END ENFORCER RULES]\n\n");

        sb.append("[USER PROMPT]\n")
                .append(truncate(userPrompt, 4000))
                .append("\n[END USER PROMPT]\n\n");

        if (context != null && !context.isEmpty()) {
            String formatted = context.formatForPrompt(8000);
            if (!formatted.isBlank()) {
                sb.append("[RECENT CHAT MESSAGES]\n")
                        .append(formatted)
                        .append("\n[END RECENT CHAT MESSAGES]\n\n");
            }
        }

        sb.append("[SUBORDINATE LLM RESPONSE, ATTEMPT ").append(attempt).append("]\n")
                .append(truncate(agentOutput, 8000))
                .append("\n[END SUBORDINATE LLM RESPONSE]\n\n");

        sb.append("You have full tool access. If the subordinate claims to have modified files, ")
                .append("you may verify by reading them. If it claims test results, you may run tests.\n\n")
                .append("Evaluate compliance with the enforcer rules and respond with ONLY the JSON verdict.");

        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max - 64)
                + "\n... (truncated, " + text.length() + " chars total)";
    }
}
