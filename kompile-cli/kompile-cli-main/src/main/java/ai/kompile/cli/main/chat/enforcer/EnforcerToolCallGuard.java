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

import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Pre-execution guard for MCP tools while enforcer mode is active.
 *
 * <p>The LLM judge is created LAZILY, on the first tool call that keyword rules cannot
 * decide. Constructing it eagerly spawned a persistent judge subprocess inside every
 * {@code kompile mcp-stdio} launch that inherited the enforcer environment — blocking
 * the MCP initialize handshake for the whole agent-CLI boot (the "slow tool injection"
 * stall) and re-entering MCP injection recursively via the judge's own agent process.</p>
 */
public class EnforcerToolCallGuard implements AutoCloseable {

    private final ObjectMapper objectMapper;
    private final EnforcerRuntimePolicy runtimePolicy;
    private final Path workingDirectory;
    private final KeywordEnforcerEvaluator keywordEvaluator;

    /** Lazily-created LLM judge; guarded by {@link #judgeLock}. */
    private volatile EnforcerJudge judge;
    private volatile boolean judgeInitAttempted;
    private final Object judgeLock = new Object();

    public EnforcerToolCallGuard(ObjectMapper objectMapper,
                                 EnforcerRuntimePolicy runtimePolicy) {
        this(objectMapper, runtimePolicy, null);
    }

    public EnforcerToolCallGuard(ObjectMapper objectMapper,
                                 EnforcerRuntimePolicy runtimePolicy,
                                 EnforcerJudge judge) {
        this(objectMapper, runtimePolicy, judge, null);
    }

    public EnforcerToolCallGuard(ObjectMapper objectMapper,
                                 EnforcerRuntimePolicy runtimePolicy,
                                 EnforcerJudge judge, Path workingDirectory) {
        this.objectMapper = objectMapper;
        this.runtimePolicy = runtimePolicy;
        this.workingDirectory = workingDirectory;
        this.judge = judge;
        this.judgeInitAttempted = judge != null;
        bindReminderConstraints(judge);
        // Always build keyword evaluator as a fallback / fast-path filter
        this.keywordEvaluator = runtimePolicy != null && runtimePolicy.getPolicy() != null
                ? KeywordEnforcerEvaluator.fromPolicy(runtimePolicy.getPolicy(), objectMapper)
                : null;
    }

    public static EnforcerToolCallGuard fromEnvironment(ObjectMapper objectMapper) {
        return fromEnvironment(objectMapper, null);
    }

    public static EnforcerToolCallGuard fromEnvironment(ObjectMapper objectMapper, Path workingDirectory) {
        return fromPolicy(EnforcerRuntimePolicy.loadFromEnvironment(objectMapper), objectMapper, workingDirectory);
    }

    public static EnforcerToolCallGuard fromPolicyFile(String policyFile, ObjectMapper objectMapper) {
        if (policyFile == null || policyFile.isBlank()) {
            return fromEnvironment(objectMapper);
        }
        return fromPolicy(EnforcerRuntimePolicy.load(Path.of(policyFile), objectMapper), objectMapper, null);
    }

    private static EnforcerToolCallGuard fromPolicy(EnforcerRuntimePolicy runtimePolicy,
                                                   ObjectMapper objectMapper, Path workingDirectory) {
        if (runtimePolicy == null || runtimePolicy.getPolicy() == null
                || !runtimePolicy.getPolicy().hasRules()
                || (runtimePolicy.getHarnessConfig() != null
                    && !runtimePolicy.getHarnessConfig().isJudgeGlobalEnabled())) {
            return null;
        }
        // No judge here — it is created on first use so MCP server startup never
        // blocks on (or recursively spawns) a judge agent process.
        return new EnforcerToolCallGuard(objectMapper, runtimePolicy, null, workingDirectory);
    }

    /**
     * Create (once) and return the LLM judge, or {@code null} when construction failed.
     * Deliberately off the constructor path — see the class javadoc.
     */
    private EnforcerJudge lazyJudge() {
        EnforcerJudge existing = judge;
        if (existing != null || judgeInitAttempted) {
            return existing;
        }
        synchronized (judgeLock) {
            if (!judgeInitAttempted) {
                judgeInitAttempted = true;
                try {
                    HarnessConfig config = runtimePolicy != null ? runtimePolicy.getHarnessConfig() : null;
                    judge = new EnforcerJudge(config != null ? config : HarnessConfig.load(objectMapper),
                            objectMapper, workingDirectory);
                    bindReminderConstraints(judge);
                } catch (Exception e) {
                    EnforcerDiagnostics.alert("[enforcer] Could not create tool-call judge: " + e.getMessage());
                }
            }
            return judge;
        }
    }

    public boolean isActive() {
        return runtimePolicy != null && runtimePolicy.getPolicy() != null
                && runtimePolicy.getPolicy().hasRules()
                && runtimePolicy.isEnabled(objectMapper)
                && (runtimePolicy.getHarnessConfig() == null
                    || runtimePolicy.getHarnessConfig().isJudgeGlobalEnabled());
    }

    /** Standalone MCP has tool-call boundaries, not chat turns. Control calls never consume a one-shot. */
    public static EnforcerToolCallDecision evaluateSession(EnforcerToolCallGuard guard,
            String toolName, Map<String, Object> args, JudgeControl control, ObjectMapper mapper) {
        if ("judge_control".equals(JudgeToolPolicy.canonicalToolName(toolName))) {
            return EnforcerToolCallDecision.allow("Operator control; confirmation and permission are checked by the tool");
        }
        JudgeControl.TurnSnapshot snapshot = control.beginTurn();
        String serialized = mapper.valueToTree(args == null ? Map.of() : args).toString();
        EnforcerToolCallDecision mandate = ShellMandatePolicy.evaluateFromSerializedArgs(toolName, serialized);
        if (mandate != null) return mandate;
        if (!snapshot.enabled()) return EnforcerToolCallDecision.allow("Session judge disabled");
        if (snapshot.approvesCommand(toolName, serialized)) {
            return EnforcerToolCallDecision.allow("Explicit session command approval consumed for this tool call");
        }
        if (guard == null) return EnforcerToolCallDecision.allow("No configured judge policy");
        return guard.evaluate(toolName, args, snapshot.guidance(), snapshot.reportOnly());
    }

    public EnforcerToolCallDecision evaluate(String toolName, Map<String, Object> args) {
        return evaluate(toolName, args, "", false);
    }

    private EnforcerToolCallDecision evaluate(String toolName, Map<String, Object> args,
                                               String guidance, boolean reportOnly) {
        if (!isActive()) {
            return EnforcerToolCallDecision.allow("No active enforcer policy");
        }
        if (toolName == null || toolName.isBlank()) {
            return EnforcerToolCallDecision.block("Missing MCP tool name");
        }

        // Deterministic shell-mandate layer: bash must not smuggle sed/grep/cat/find over
        // files when dedicated kompile tools exist. Hard block, no LLM, no fail-open.
        if (args != null) {
            Object command = args.get("command");
            if (command instanceof String commandText) {
                EnforcerToolCallDecision mandate = ShellMandatePolicy.evaluateCommand(toolName, commandText);
                if (mandate != null) {
                    return mandate;
                }
            }
        }

        String serializedArgs = args == null ? "{}" : objectMapper.valueToTree(args).toString();
        EnforcerToolCallDecision readOnlyGit = JudgeToolPolicy.evaluateReadOnlyGitTool(
                toolName, serializedArgs, runtimePolicy.getPolicy(), objectMapper);
        if (readOnlyGit != null) {
            return readOnlyGit;
        }

        if (runtimePolicy.getReminderConstraints().isBlank()) {
            EnforcerToolCallDecision routine = JudgeToolPolicy.evaluateRoutineTool(
                    toolName, serializedArgs,
                    runtimePolicy.getPolicy(), objectMapper);
            if (routine != null) {
                return routine;
            }
        }

        // Fast-path: keyword evaluation (instant, no LLM needed)
        if (keywordEvaluator != null && keywordEvaluator.isAvailable()) {
            try {
                EnforcerToolCallDecision kwDecision = keywordEvaluator.evaluateToolCall(
                        toolName, serializedArgs, runtimePolicy.getPolicy());
                if (!kwDecision.isAllowed()) {
                    // Keyword match is definitive — block immediately without LLM
                    return kwDecision;
                }
            } catch (Exception ignored) {
                // Fall through to LLM judge
            }
        }

        // Full LLM evaluation for nuanced rules (judge created on first need)
        EnforcerJudge llmJudge = lazyJudge();
        if (llmJudge == null || !llmJudge.isAvailable()) {
            return EnforcerToolCallDecision.allow(
                    "No LLM judge available after keyword check; failing open");
        }

        try {
            EnforcerConversationContext context = EnforcerConversationContext.read(
                    runtimePolicy.getContextFile(), objectMapper);
            if (guidance != null && !guidance.isBlank()) {
                var messages = new java.util.ArrayList<>(context.getMessages());
                messages.add(new EnforcerConversationContext.Message("user", "Judge guidance: " + guidance));
                context = EnforcerConversationContext.of(messages);
            }
            EnforcerToolCallDecision decision = llmJudge.evaluateToolCall(
                    toolName, serializedArgs, runtimePolicy.getPolicy(), context);
            if (reportOnly) {
                return EnforcerToolCallDecision.allow("Report-only judge verdict: "
                        + decision.getAction() + " — " + decision.getReason());
            }
            if (decision.isRewrite() && decision.getRewrittenArgs() == null) {
                return EnforcerToolCallDecision.allow(
                        "Enforcer returned an invalid rewrite; failing open");
            }
            return decision;
        } catch (Exception e) {
            return EnforcerToolCallDecision.allow(
                    "Enforcer tool-call evaluation failed; failing open: " + e.getMessage());
        }
    }

    public String describe() {
        // Never force judge construction just to describe it.
        EnforcerJudge built = judge;
        String judgeDescription = built != null ? built.describe() : "lazy (created on first use)";
        String sessionId = runtimePolicy != null ? runtimePolicy.getSessionId() : "none";
        return "session=" + sessionId + ", judge=" + judgeDescription;
    }

    private void bindReminderConstraints(EnforcerJudge target) {
        if (target != null && runtimePolicy != null) {
            target.setReminderSupplier(runtimePolicy::getReminderConstraints);
        }
    }

    @Override
    public void close() {
        EnforcerJudge built = judge;
        if (built != null) {
            built.close();
        }
    }
}
