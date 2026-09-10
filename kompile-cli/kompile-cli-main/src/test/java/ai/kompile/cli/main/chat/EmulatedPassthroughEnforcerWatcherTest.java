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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a supervised passthrough session exposes one unified judge watcher instead of
 * separate judge and enforcer rows for the same control plane.
 */
class EmulatedPassthroughEnforcerWatcherTest {

    private static EnforcerEvaluator evaluator(boolean llm, String describe) {
        return new EnforcerEvaluator() {
            @Override
            public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                             EnforcerPolicy policy, int attempt) {
                return EnforcerDecision.pass("ok");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String describe() {
                return describe;
            }

            @Override
            public boolean recordsJudgements() {
                return llm;
            }
        };
    }

    @Test
    void llmModeRegistersOneJudgeWatcher() {
        BackgroundProcessManager mgr = new BackgroundProcessManager("test-watch-llm");
        try {
            EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash\nBAN: rm -rf", 2, false);
            EmulatedPassthroughCommand.registerEnforcerWatchers(
                    mgr, evaluator(true, "remote(anthropic/haiku)"), policy);

            List<ProcessEntry> all = mgr.listAll();
            assertEquals(1, all.size(), "LLM mode exposes one unified judge watcher");
            ProcessEntry judge = all.stream()
                    .filter(e -> e.getKind() == ProcessKind.JUDGE)
                    .findFirst().orElseThrow();
            assertTrue(judge.getDescription().contains("remote(anthropic/haiku)"),
                    "judge watcher shows the backend in its description");
            assertEquals("remote(anthropic/haiku)", judge.getMetadata().get("backend"));
            assertTrue(judge.isRunning(), "the judge watcher is live for the session");
        } finally {
            mgr.close();
        }
    }

    @Test
    void keywordModeStillRegistersOneJudgePolicyWatcher() {
        BackgroundProcessManager mgr = new BackgroundProcessManager("test-watch-kw");
        try {
            EnforcerPolicy policy = new EnforcerPolicy("BAN: rm -rf", 2, false);
            EmulatedPassthroughCommand.registerEnforcerWatchers(
                    mgr, evaluator(false, "keyword(1 rule)"), policy);

            List<ProcessEntry> all = mgr.listAll();
            assertEquals(1, all.size());
            assertEquals(ProcessKind.JUDGE, all.get(0).getKind());
            assertTrue(all.get(0).getDescription().contains("keyword"));
        } finally {
            mgr.close();
        }
    }

    @Test
    void nullManagerOrEvaluatorIsSafe() {
        EmulatedPassthroughCommand.registerEnforcerWatchers(null, evaluator(true, "x"), null);
        BackgroundProcessManager mgr = new BackgroundProcessManager("test-watch-null");
        try {
            EmulatedPassthroughCommand.registerEnforcerWatchers(mgr, null, null);
            assertTrue(mgr.listAll().isEmpty());
        } finally {
            mgr.close();
        }
    }
}
