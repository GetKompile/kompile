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

package ai.kompile.app.services.diffpolicy;

import ai.kompile.app.services.diffindex.DiffIndexEntry;
import ai.kompile.app.services.diffindex.DiffIndexService;
import ai.kompile.core.llm.chat.LLMChat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the diff-policy scan over captured agent diffs: the path-of-concern
 * detector and the reused {@link ai.kompile.cli.common.enforcer.DiffPatternEvaluator}
 * content detector, plus violation persistence/query and re-scan idempotency.
 */
class DiffPolicyServiceTest {

    @TempDir
    Path policyDir;

    private DiffPolicyService policy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DiffIndexService diffIndex = new DiffIndexService();
        Map<String, DiffIndexEntry> entries =
                (Map<String, DiffIndexEntry>) ReflectionTestUtils.getField(diffIndex, "entries");
        entries.clear();
        // A secret-file edit (only the path detector should fire).
        putDiff(entries, "secret1", "claude-code", "/proj/.env",
                "--- a/proj/.env\n+++ b/proj/.env\n@@ -1 +1,2 @@\n KEY=old\n+ADDED=1\n");
        // A banned content pattern inside a normal file.
        putDiff(entries, "code1", "codex", "/proj/src/Main.java",
                "--- a/proj/src/Main.java\n+++ b/proj/src/Main.java\n@@ -1,2 +1,3 @@\n class Main {\n+    System.exit(1);\n }\n");
        // A clean edit (no rule fires).
        putDiff(entries, "clean1", "claude-code", "/proj/src/Foo.java",
                "--- a/proj/src/Foo.java\n+++ b/proj/src/Foo.java\n@@ -1 +1,2 @@\n class Foo {}\n+// note\n");

        policy = new DiffPolicyService(diffIndex);
        ReflectionTestUtils.setField(policy, "policyDir", policyDir);
        policy.saveRules(
                List.of(PathRule.builder().glob("**/*.env").severity("critical").description("Edit to a secret file").build()),
                "BAN_DIFF: System.exit(");
    }

    @Test
    void scanFlagsPathAndContentViolationsAndSkipsCleanEdits() {
        Map<String, Object> summary = policy.scan(null, null, null, null, null, null);
        assertThat(summary.get("scannedDiffs")).isEqualTo(3);
        assertThat(summary.get("violations")).isEqualTo(2);

        List<DiffPolicyViolation> all = policy.listViolations(null, null, null, null, null, null, null, null);
        assertThat(all).hasSize(2);
        assertThat(all).extracting(DiffPolicyViolation::getDiffEntryId)
                .containsExactlyInAnyOrder("secret1", "code1");
    }

    @Test
    void pathDetectorFlagsTheSecretFileAsCritical() {
        policy.scan(null, null, null, null, null, null);
        List<DiffPolicyViolation> env = policy.listViolations("**/*.env", null, null, null, null, null, null, null);
        assertThat(env).hasSize(1);
        DiffPolicyViolation v = env.get(0);
        assertThat(v.getDetector()).isEqualTo("path");
        assertThat(v.getSeverity()).isEqualTo("critical");
        assertThat(v.getRiskScore()).isEqualTo(1.0);
        assertThat(v.getAgent()).isEqualTo("claude-code");
    }

    @Test
    void contentDetectorFlagsBannedPattern() {
        policy.scan(null, null, null, null, null, null);
        List<DiffPolicyViolation> rules = policy.listViolations(null, null, null, "rule", null, null, null, null);
        assertThat(rules).hasSize(1);
        DiffPolicyViolation v = rules.get(0);
        assertThat(v.getDiffEntryId()).isEqualTo("code1");
        assertThat(v.getMatchedLine()).contains("System.exit");
        assertThat(v.getLineNumber()).isGreaterThan(0);
    }

    @Test
    void filtersViolationsByAgentAndSeverity() {
        policy.scan(null, null, null, null, null, null);
        assertThat(policy.listViolations(null, "codex", null, null, null, null, null, null))
                .extracting(DiffPolicyViolation::getDiffEntryId).containsExactly("code1");
        assertThat(policy.listViolations(null, null, null, null, "critical", null, null, null))
                .extracting(DiffPolicyViolation::getDiffEntryId).containsExactly("secret1");
    }

    @Test
    void reScanIsIdempotent() {
        policy.scan(null, null, null, null, null, null);
        policy.scan(null, null, null, null, null, null);
        assertThat(policy.listViolations(null, null, null, null, null, null, null, null)).hasSize(2);
    }

    @Test
    void statsAggregateBySeverityAndDetector() {
        policy.scan(null, null, null, null, null, null);
        Map<String, Object> stats = policy.getStats();
        assertThat(stats.get("total")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Long> bySeverity = (Map<String, Long>) stats.get("bySeverity");
        assertThat(bySeverity).containsEntry("critical", 1L).containsEntry("error", 1L);
    }

    @Test
    void llmDetectorMapsViolationsFromJson() {
        DiffIndexEntry e = DiffIndexEntry.builder()
                .id("d9").agent("claude-code").filePath("/p/F.java").sessionId("s")
                .timestamp("2026-01-01T00:00:00Z").build();
        String resp = "{\"violations\":["
                + "{\"severity\":\"critical\",\"message\":\"Hardcoded API key\"},"
                + "{\"severity\":\"warning\",\"message\":\"Overly broad catch\"}]}";
        List<DiffPolicyViolation> v = policy.parseLlmViolations(e, resp, "2026-01-02T00:00:00Z");
        assertThat(v).hasSize(2);
        assertThat(v).extracting(DiffPolicyViolation::getDetector).containsOnly("llm");
        assertThat(v.get(0).getSeverity()).isEqualTo("critical");
        assertThat(v.get(0).getRiskScore()).isEqualTo(1.0);
        assertThat(v.get(0).getMessage()).isEqualTo("Hardcoded API key");
    }

    @Test
    void llmDetectorExtractsJsonFromProseWrappedResponse() {
        DiffIndexEntry e = DiffIndexEntry.builder().id("d9").filePath("/p/F.java").timestamp("t").build();
        String resp = "Sure, here you go:\n```json\n{\"violations\":[{\"severity\":\"error\",\"message\":\"Bug\"}]}\n```";
        List<DiffPolicyViolation> v = policy.parseLlmViolations(e, resp, "n");
        assertThat(v).hasSize(1);
        assertThat(v.get(0).getMessage()).isEqualTo("Bug");
        assertThat(v.get(0).getSeverity()).isEqualTo("error");
    }

    @Test
    void llmDetectorReturnsNothingForEmptyOrMalformed() {
        DiffIndexEntry e = DiffIndexEntry.builder().id("d9").filePath("/p/F.java").timestamp("t").build();
        assertThat(policy.parseLlmViolations(e, "{\"violations\":[]}", "n")).isEmpty();
        assertThat(policy.parseLlmViolations(e, "no json here", "n")).isEmpty();
        assertThat(policy.parseLlmViolations(e, "", "n")).isEmpty();
    }

    @Test
    void scanWithLlmAddsLlmViolationsPerDiff() {
        LLMChat llm = mock(LLMChat.class, RETURNS_DEEP_STUBS);
        when(llm.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("{\"violations\":[{\"severity\":\"error\",\"message\":\"LLM concern\"}]}");
        policy.setLlmChat(llm);

        Map<String, Object> summary = policy.scan(null, null, null, null, null, null, true);
        assertThat(summary.get("llmAvailable")).isEqualTo(true);
        assertThat(summary.get("llmEvaluated")).isEqualTo(3);

        List<DiffPolicyViolation> llmV = policy.listViolations(null, null, null, "llm", null, null, null, null);
        assertThat(llmV).hasSize(3);
        assertThat(llmV).extracting(DiffPolicyViolation::getMessage).containsOnly("LLM concern");
    }

    private void putDiff(Map<String, DiffIndexEntry> entries, String id, String agent,
                         String filePath, String unifiedDiff) {
        entries.put(id, DiffIndexEntry.builder()
                .id(id).agent(agent).source("transcript").sessionId("sess-1")
                .filePath(filePath).toolName("Edit").diffType("edit")
                .unifiedDiff(unifiedDiff).timestamp("2026-01-01T00:00:00Z")
                .linesAdded(1).linesRemoved(0).build());
    }
}
