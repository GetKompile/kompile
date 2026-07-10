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
        this.objectMapper = objectMapper;
        this.runtimePolicy = runtimePolicy;
        this.judge = judge;
        this.judgeInitAttempted = judge != null;
        // Always build keyword evaluator as a fallback / fast-path filter
        this.keywordEvaluator = runtimePolicy != null && runtimePolicy.getPolicy() != null
                ? KeywordEnforcerEvaluator.fromPolicy(runtimePolicy.getPolicy(), objectMapper)
                : null;
    }

    public static EnforcerToolCallGuard fromEnvironment(ObjectMapper objectMapper) {
        return fromPolicy(EnforcerRuntimePolicy.loadFromEnvironment(objectMapper), objectMapper);
    }

    public static EnforcerToolCallGuard fromPolicyFile(String policyFile, ObjectMapper objectMapper) {
        if (policyFile == null || policyFile.isBlank()) {
            return fromEnvironment(objectMapper);
        }
        return fromPolicy(EnforcerRuntimePolicy.load(Path.of(policyFile), objectMapper), objectMapper);
    }

    private static EnforcerToolCallGuard fromPolicy(EnforcerRuntimePolicy runtimePolicy,
                                                   ObjectMapper objectMapper) {
        if (runtimePolicy == null || runtimePolicy.getPolicy() == null
                || !runtimePolicy.getPolicy().hasRules()) {
            return null;
        }
        // No judge here — it is created on first use so MCP server startup never
        // blocks on (or recursively spawns) a judge agent process.
        return new EnforcerToolCallGuard(objectMapper, runtimePolicy);
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
                            objectMapper);
                } catch (Exception e) {
                    System.err.println("[enforcer] Could not create tool-call judge: " + e.getMessage());
                }
            }
            return judge;
        }
    }

    public boolean isActive() {
        return runtimePolicy != null && runtimePolicy.getPolicy() != null
                && runtimePolicy.getPolicy().hasRules();
    }

    public EnforcerToolCallDecision evaluate(String toolName, Map<String, Object> args) {
        if (!isActive()) {
            return EnforcerToolCallDecision.allow("No active enforcer policy");
        }
        if (toolName == null || toolName.isBlank()) {
            return EnforcerToolCallDecision.block("Missing MCP tool name");
        }

        // Fast-path: keyword evaluation (instant, no LLM needed)
        if (keywordEvaluator != null && keywordEvaluator.isAvailable()) {
            try {
                String serializedArgs = objectMapper.writeValueAsString(args == null ? Map.of() : args);
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
            // No LLM judge available — keyword check already passed, allow
            if (keywordEvaluator != null && keywordEvaluator.isAvailable()) {
                return EnforcerToolCallDecision.allow("Passed keyword check (no LLM judge available)");
            }
            return EnforcerToolCallDecision.block("Active enforcer policy has no available judge backend");
        }

        try {
            String serializedArgs = objectMapper.writeValueAsString(args == null ? Map.of() : args);
            EnforcerConversationContext context = EnforcerConversationContext.read(
                    runtimePolicy.getContextFile(), objectMapper);
            EnforcerToolCallDecision decision = llmJudge.evaluateToolCall(
                    toolName, serializedArgs, runtimePolicy.getPolicy(), context);
            if (decision.isRewrite() && decision.getRewrittenArgs() == null) {
                return EnforcerToolCallDecision.block(
                        "Enforcer requested a rewrite but did not provide rewritten arguments");
            }
            return decision;
        } catch (Exception e) {
            return EnforcerToolCallDecision.block("Enforcer tool-call evaluation failed: " + e.getMessage());
        }
    }

    public String describe() {
        // Never force judge construction just to describe it.
        EnforcerJudge built = judge;
        String judgeDescription = built != null ? built.describe() : "lazy (created on first use)";
        String sessionId = runtimePolicy != null ? runtimePolicy.getSessionId() : "none";
        return "session=" + sessionId + ", judge=" + judgeDescription;
    }

    @Override
    public void close() {
        EnforcerJudge built = judge;
        if (built != null) {
            built.close();
        }
    }
}
