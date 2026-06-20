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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for the real-time interrupt DECISION the enforcer feeds to the subprocess, plus
 * judge-call latency capture. Uses a stub {@link JudgeBackend} (no real LLM/agent), so it is fast
 * and deterministic.
 *
 * <p>Covers: (1) the tool-call gate returning an interrupt when the judge blocks; (2) the
 * configurable fail-open / fail-closed behavior on judge error (added for robustness); (3) that the
 * judge records a real {@code latencyMs} per call.</p>
 */
class EnforcerInterruptLatencyTest {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    /** A judge backend with canned behavior: a fixed response, optional pre-delay, or an error. */
    static final class StubJudge implements JudgeBackend {
        private final String response; // null = throw
        private final long sleepMs;

        StubJudge(String response, long sleepMs) {
            this.response = response;
            this.sleepMs = sleepMs;
        }

        @Override
        public String generate(String userPrompt, String systemPrompt) throws Exception {
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
            if (response == null) {
                throw new RuntimeException("judge backend down");
            }
            return response;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "stub-judge";
        }
    }

    private static EnforcerRealtimeMonitor monitor(JudgeBackend backend) {
        EnforcerJudge judge = new EnforcerJudge(backend, MAPPER);
        EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash", 2, false);
        return new EnforcerRealtimeMonitor(judge, policy, "delete the build artifacts");
    }

    @Test
    void monitorInterruptsDisallowedToolCall() {
        EnforcerRealtimeMonitor mon = monitor(new StubJudge("{\"action\":\"BLOCK\",\"reason\":\"banned tool\"}", 0));
        SubprocessAgentRunner.MonitorDecision d = mon.onToolUse("bash", "{\"command\":\"rm -rf /\"}");
        assertTrue(d.interrupt(), "a BLOCK judgement must interrupt the subprocess tool call");
    }

    @Test
    void monitorAllowsCompliantToolCall() {
        EnforcerRealtimeMonitor mon = monitor(new StubJudge("{\"action\":\"ALLOW\",\"reason\":\"ok\"}", 0));
        SubprocessAgentRunner.MonitorDecision d = mon.onToolUse("read", "{\"file\":\"README.md\"}");
        assertFalse(d.interrupt(), "an ALLOW judgement must let the tool call continue");
    }

    @Test
    void monitorFailsClosedOnJudgeErrorByDefault() {
        EnforcerRealtimeMonitor mon = monitor(new StubJudge(null, 0)); // judge throws
        SubprocessAgentRunner.MonitorDecision d = mon.onToolUse("bash", "{\"command\":\"echo hi\"}");
        assertTrue(d.interrupt(), "default policy fails closed: a judge error blocks the tool call");
    }

    @Test
    void monitorFailsOpenOnJudgeErrorWhenConfigured() {
        EnforcerJudge judge = new EnforcerJudge(new StubJudge(null, 0), MAPPER);
        EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash", 2, false);
        EnforcerRealtimeMonitor mon = new EnforcerRealtimeMonitor(judge, policy, "do the task");
        mon.setFailOpenOnError(true);

        SubprocessAgentRunner.MonitorDecision d = mon.onToolUse("bash", "{\"command\":\"echo hi\"}");
        assertFalse(d.interrupt(), "FAIL_OPEN policy lets the tool call continue when the judge errors");
    }

    @Test
    void judgeCallRecordsLatency(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("judgements.jsonl");
        JudgementLog log = new JudgementLog("sess-latency", file);

        EnforcerJudge judge = new EnforcerJudge(
                new StubJudge("{\"compliant\":true,\"stop\":false,\"severity\":\"info\",\"reasoning\":\"ok\"}", 60),
                MAPPER);
        judge.setJudgementLog(log);

        EnforcerPolicy policy = new EnforcerPolicy("Be concise", 2, false);
        EnforcerDecision decision = judge.evaluate("user prompt", "agent output", policy, 1,
                EnforcerConversationContext.empty());
        assertTrue(decision.isCompliant());

        List<JudgementRecord> records = JudgementLog.readFile(file);
        JudgementRecord judgeTurn = records.stream()
                .filter(r -> "JUDGE_TURN".equals(r.getPhase()))
                .findFirst().orElse(null);
        assertNotNull(judgeTurn, "a JUDGE_TURN record must be written");
        System.out.println("[latency] judge call recorded latencyMs=" + judgeTurn.getLatencyMs());
        assertTrue(judgeTurn.getLatencyMs() >= 40,
                "recorded judge latency must reflect the ~60ms call, was " + judgeTurn.getLatencyMs() + "ms");
    }
}
