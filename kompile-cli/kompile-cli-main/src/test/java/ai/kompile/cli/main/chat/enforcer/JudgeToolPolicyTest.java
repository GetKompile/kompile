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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeToolPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesNamespacedTodoTools() {
        assertTrue(JudgeToolPolicy.isRoutineSessionTool("todowrite"));
        assertTrue(JudgeToolPolicy.isRoutineSessionTool("mcp__kompile__todoread"));
        assertFalse(JudgeToolPolicy.isRoutineSessionTool("write"));
    }

    @Test
    void naturalLanguageGuidanceCannotSpeculativelyBlockRoutineBookkeeping() {
        EnforcerPolicy policy = new EnforcerPolicy(
                "Be careful and only use tools relevant to the task.", 2, false);

        EnforcerToolCallDecision decision = JudgeToolPolicy.evaluateRoutineTool(
                "todowrite", "{\"action\":\"update\"}", policy, objectMapper);

        assertTrue(decision.isAllowed());
    }

    @Test
    void explicitToolAndCommandBansStillWin() {
        EnforcerPolicy toolBan = new EnforcerPolicy("BAN_TOOL: todowrite", 2, false);
        assertFalse(JudgeToolPolicy.evaluateRoutineTool(
                "todowrite", "{\"action\":\"update\"}", toolBan, objectMapper).isAllowed());

        EnforcerPolicy commandBan = new EnforcerPolicy("BAN_CMD: forbidden-status", 2, false);
        assertFalse(JudgeToolPolicy.evaluateRoutineTool(
                "todowrite", "{\"subject\":\"forbidden-status\"}", commandBan, objectMapper).isAllowed());
    }

    @Test
    void readOnlyGitInspectionCannotBeSpeculativelyReclassifiedAsMutation() {
        EnforcerPolicy broadGuidance = new EnforcerPolicy(
                "Plan before changes. Git operations, especially resets, are not allowed.", 2, false);

        EnforcerToolCallDecision decision = JudgeToolPolicy.evaluateReadOnlyGitTool(
                "bash", "{\"command\":\"git diff --check\"}", broadGuidance, objectMapper);

        assertTrue(decision.isAllowed());
        assertTrue(decision.getReason().contains("non-mutating"));
    }

    @Test
    void explicitReadOnlyGitBanStillWins() {
        EnforcerPolicy policy = new EnforcerPolicy("BAN_CMD: git diff", 2, false);

        EnforcerToolCallDecision decision = JudgeToolPolicy.evaluateReadOnlyGitTool(
                "mcp__kompile__bash", "{\"command\":\"git diff --check\"}", policy, objectMapper);

        assertFalse(decision.isAllowed());
    }

    @Test
    void jsonObjectCommandBanStillWins() {
        EnforcerPolicy policy = new EnforcerPolicy(
                "{\"rules\":[{\"keyword\":\"git diff\",\"scope\":\"command\"}]}",
                2, false);

        EnforcerToolCallDecision decision = JudgeToolPolicy.evaluateReadOnlyGitTool(
                "bash", "{\"command\":\"git diff --check\"}", policy, objectMapper);

        assertFalse(decision.isAllowed());
    }

    @Test
    void gitMutationsAndUnknownFormsContinueThroughNormalJudgeEvaluation() {
        EnforcerPolicy policy = new EnforcerPolicy("Use safe commands", 2, false);

        assertNull(JudgeToolPolicy.evaluateReadOnlyGitTool(
                "bash", "{\"command\":\"git commit -m test\"}", policy, objectMapper));
        assertNull(JudgeToolPolicy.evaluateReadOnlyGitTool(
                "bash", "{\"command\":\"git branch new-branch\"}", policy, objectMapper));
        assertNull(JudgeToolPolicy.evaluateReadOnlyGitTool(
                "bash", "{\"command\":\"git diff --output=review.patch\"}", policy, objectMapper));
    }

    @Test
    void nonRoutineToolsContinueThroughNormalJudgeEvaluation() {
        assertNull(JudgeToolPolicy.evaluateRoutineTool(
                "bash", "{\"command\":\"pwd\"}",
                new EnforcerPolicy("Use safe commands", 2, false), objectMapper));
    }
}
