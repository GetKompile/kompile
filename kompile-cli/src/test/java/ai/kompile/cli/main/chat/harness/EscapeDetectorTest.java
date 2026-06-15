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

package ai.kompile.cli.main.chat.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EscapeDetector}.
 * <p>
 * Covers all 12 escape types, cross-turn history tracking, priority ordering,
 * and edge cases. All detection is pure heuristic — no I/O or LLM calls.
 */
class EscapeDetectorTest {

    private EscapeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new EscapeDetector();
    }

    // ── Helper to build TurnMetrics quickly ─────────────────────────────

    private static TurnMetrics.Builder baseMetrics() {
        return TurnMetrics.builder()
                .sessionId("test-session")
                .agentName("claude")
                .model("claude-sonnet")
                .provider("anthropic");
    }

    // ===================================================================
    // 1. Empty output — hard escape
    // ===================================================================

    @Nested
    class EmptyOutput {

        @Test
        void nullOutput_isEmptyEscape() {
            TurnMetrics m = baseMetrics().agentOutput(null).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EMPTY_OUTPUT, r.type());
            assertTrue(r.isHardEscape());
        }

        @Test
        void blankOutput_isEmptyEscape() {
            TurnMetrics m = baseMetrics().agentOutput("   \n\t  ").build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EMPTY_OUTPUT, r.type());
        }

        @Test
        void veryShortOutput_isEmptyEscape() {
            // Under EMPTY_THRESHOLD (30 chars)
            TurnMetrics m = baseMetrics().agentOutput("OK done.").build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EMPTY_OUTPUT, r.type());
        }

        @Test
        void thirtyCharOutput_isNotEmptyEscape() {
            // Exactly 30 chars — should NOT be empty escape
            String output = "A".repeat(30);
            TurnMetrics m = baseMetrics().agentOutput(output).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.EMPTY_OUTPUT, r.type());
        }
    }

    // ===================================================================
    // 2. Explicit refusal — hard escape
    // ===================================================================

    @Nested
    class ExplicitRefusal {

        @Test
        void iCantDoThat_isRefusal() {
            // Must be >30 chars to avoid EMPTY_OUTPUT preemption
            TurnMetrics m = baseMetrics()
                    .agentOutput("I can't do that for you — this is outside my abilities right now.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
            assertTrue(r.isHardEscape());
        }

        @Test
        void imUnableToAssist_isRefusal() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I'm unable to help with that particular request at this time.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
        }

        @Test
        void iCannotHelp_isRefusal() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I cannot perform this operation because it requires elevated privileges.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
        }

        @Test
        void iMustDecline_isRefusal() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I must decline this request for safety reasons and compliance concerns.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
        }

        @Test
        void beyondMyCapabilities_isRefusal() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("That is beyond my capabilities and I cannot assist with it.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
        }

        @Test
        void longOutputWithRefusalPhrase_isNotRefusal() {
            // Refusal check only applies to outputs < 500 chars
            String longOutput = "I can't believe how well this code works! " + "X".repeat(500);
            TurnMetrics m = baseMetrics().agentOutput(longOutput).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
        }

        @Test
        void normalResponse_isNotRefusal() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("Here is the fix for the authentication bug in LoginService.java. "
                            + "I changed the password verification to use bcrypt instead of plain text comparison.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertFalse(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.NONE, r.type());
        }
    }

    // ===================================================================
    // 3. Max steps abandoned
    // ===================================================================

    @Nested
    class MaxStepsAbandoned {

        @Test
        void hitMaxSteps_isMaxStepsEscape() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I tried several approaches but ran out of steps.")
                    .hitMaxSteps(true)
                    .agenticSteps(25)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.MAX_STEPS_ABANDONED, r.type());
            assertTrue(r.detail().contains("25"));
            assertEquals(1.5f, r.penalty());
        }

        @Test
        void notHitMaxSteps_isNotMaxSteps() {
            // Output >150 chars with code-review keywords to avoid TRIVIALLY_SHORT and OFF_TOPIC
            TurnMetrics m = baseMetrics()
                    .agentOutput("Completed the review of all files in the diff. The method signatures "
                            + "look correct and the class structure follows the existing patterns. "
                            + "I found no critical bugs in the implementation.")
                    .hitMaxSteps(false)
                    .agenticSteps(5)
                    .toolCallsTotal(3)
                    .toolCallBreakdown(Map.of("Read", 2, "Grep", 1))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertFalse(r.hasEscape());
        }
    }

    // ===================================================================
    // 4. Tool args loop
    // ===================================================================

    @Nested
    class ToolArgsLoop {

        @Test
        void sameToolSameArgs_threeTimesIsLoop() {
            List<TurnMetrics.ToolInvocation> invocations = List.of(
                    new TurnMetrics.ToolInvocation("Read", "hash_abc"),
                    new TurnMetrics.ToolInvocation("Read", "hash_abc"),
                    new TurnMetrics.ToolInvocation("Read", "hash_abc")
            );
            TurnMetrics m = baseMetrics()
                    .agentOutput("I read the file three times to make sure I understood it correctly.")
                    .toolCallsTotal(3)
                    .toolCallBreakdown(Map.of("Read", 3))
                    .toolInvocations(invocations)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "exploration");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.TOOL_ARGS_LOOP, r.type());
        }

        @Test
        void sameToolDifferentArgs_isNotLoop() {
            List<TurnMetrics.ToolInvocation> invocations = List.of(
                    new TurnMetrics.ToolInvocation("Read", "hash_1"),
                    new TurnMetrics.ToolInvocation("Read", "hash_2"),
                    new TurnMetrics.ToolInvocation("Read", "hash_3")
            );
            TurnMetrics m = baseMetrics()
                    .agentOutput("I read three different files to understand the architecture and dependencies.")
                    .toolCallsTotal(3)
                    .toolCallBreakdown(Map.of("Read", 3))
                    .toolInvocations(invocations)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "exploration");

            // Should not be TOOL_ARGS_LOOP — different args each time
            assertNotEquals(EscapeDetector.EscapeType.TOOL_ARGS_LOOP, r.type());
        }

        @Test
        void twoInvocations_isNotLoop() {
            List<TurnMetrics.ToolInvocation> invocations = List.of(
                    new TurnMetrics.ToolInvocation("Bash", "hash_same"),
                    new TurnMetrics.ToolInvocation("Bash", "hash_same")
            );
            TurnMetrics m = baseMetrics()
                    .agentOutput("I ran the command twice. First to check, then to confirm the result.")
                    .toolCallsTotal(2)
                    .toolCallBreakdown(Map.of("Bash", 2))
                    .toolInvocations(invocations)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.TOOL_ARGS_LOOP, r.type());
        }
    }

    // ===================================================================
    // 5. Stuck loop — cross-turn repetition
    // ===================================================================

    @Nested
    class StuckLoop {

        @Test
        void threeIdenticalTurns_isStuckLoop() {
            String repeatedOutput = "I analyzed the code and found that the authentication module "
                    + "needs refactoring. The main issues are in the LoginService class "
                    + "where the password validation logic is duplicated across multiple methods.";

            // Feed 2 identical turns into history
            for (int i = 0; i < 2; i++) {
                TurnMetrics m = baseMetrics().agentOutput(repeatedOutput).build();
                detector.detect(m, "general");
            }

            // Third identical turn should trigger stuck loop
            TurnMetrics m = baseMetrics().agentOutput(repeatedOutput).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.STUCK_LOOP, r.type());
        }

        @Test
        void differentOutputsEachTurn_isNotStuck() {
            String[] outputs = {
                    "First, I'll read the file to understand the current implementation details.",
                    "Now I see the bug — the null check is missing on line 42 of AuthService.",
                    "I've fixed the null check and added a unit test to verify the behavior."
            };

            for (String output : outputs) {
                TurnMetrics m = baseMetrics().agentOutput(output).build();
                EscapeDetector.EscapeResult r = detector.detect(m, "general");
                assertFalse(r.hasEscape(), "Distinct outputs should not trigger escape: " + output);
            }
        }
    }

    // ===================================================================
    // 6. Tool sequence loop — same tool calls across turns
    // ===================================================================

    @Nested
    class ToolSequenceLoop {

        @Test
        void identicalToolSequenceThreeTurns_isToolSequenceLoop() {
            List<TurnMetrics.ToolInvocation> seq = List.of(
                    new TurnMetrics.ToolInvocation("Read", "file1"),
                    new TurnMetrics.ToolInvocation("Grep", "pattern1"),
                    new TurnMetrics.ToolInvocation("Read", "file2")
            );

            // 3 turns with identical tool sequence but different-enough output
            for (int i = 0; i < 3; i++) {
                String output = "Turn " + i + " analysis: The codebase has " + (100 + i * 10)
                        + " files across " + (10 + i) + " packages with varying complexity levels "
                        + "and different architectural patterns in each module.";
                TurnMetrics m = baseMetrics()
                        .agentOutput(output)
                        .toolCallsTotal(3)
                        .toolCallBreakdown(Map.of("Read", 2, "Grep", 1))
                        .toolInvocations(seq)
                        .build();
                detector.detect(m, "general");
            }

            // The 3rd detect call should have returned TOOL_SEQUENCE_LOOP
            // Re-detect on the same pattern to verify
            String output = "Turn 3 analysis: The codebase has 130 files across 13 packages "
                    + "with varying complexity levels and different patterns in the modules.";
            TurnMetrics m = baseMetrics()
                    .agentOutput(output)
                    .toolCallsTotal(3)
                    .toolCallBreakdown(Map.of("Read", 2, "Grep", 1))
                    .toolInvocations(seq)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.TOOL_SEQUENCE_LOOP, r.type());
        }
    }

    // ===================================================================
    // 7. Thinking loop — circular reasoning
    // ===================================================================

    @Nested
    class ThinkingLoop {

        @Test
        void circularReasoningMarkers_isThinkingLoop() {
            String thinking = "Let me think about this problem. I need to find the root cause. "
                    + "As I said before, the issue might be in the database layer. "
                    + "Going back to my earlier thought, the connection pool could be exhausted. "
                    + "I keep coming back to the same conclusion about the connection pool. "
                    + "As I mentioned previously, the database connections are the bottleneck. "
                    + "Let me try again approach to solve the connection pool exhaustion issue.";

            TurnMetrics m = baseMetrics()
                    .agentOutput("The issue is likely in the database connection pool configuration.")
                    .thinkingText(thinking)
                    .thinkingTokens(500)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.THINKING_LOOP, r.type());
        }

        @Test
        void repeatedParagraphs_isThinkingLoop() {
            // countRepeatedParagraphs counts distinct paragraphs that appear 2+ times.
            // Need 3+ distinct repeated paragraphs to hit THINKING_LOOP_PARAGRAPH_THRESHOLD.
            StringBuilder thinking = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                thinking.append("The authentication system uses bcrypt for password hashing with 12 salt rounds. ")
                        .append("Users authenticate via the /api/auth/login endpoint.\n\n");
                thinking.append("The database connection pool is configured with a maximum of 20 connections. ")
                        .append("Connection timeout is set to 30 seconds by default.\n\n");
                thinking.append("The caching layer uses Redis with a TTL of 300 seconds for session tokens. ")
                        .append("Cache invalidation happens on logout or password change.\n\n");
            }

            TurnMetrics m = baseMetrics()
                    .agentOutput("The auth system uses bcrypt with 12 salt rounds for secure password hashing.")
                    .thinkingText(thinking.toString())
                    .thinkingTokens(800)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.THINKING_LOOP, r.type());
        }

        @Test
        void shortThinkingText_isNotThinkingLoop() {
            // Under 200 chars — too short for thinking loop detection
            TurnMetrics m = baseMetrics()
                    .agentOutput("I fixed the authentication bug by adding a null check.")
                    .thinkingText("Let me think about this. The fix should be simple.")
                    .thinkingTokens(20)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.THINKING_LOOP, r.type());
        }

        @Test
        void noThinkingText_isNotThinkingLoop() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("Fixed the bug. The null check was missing on line 42.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.THINKING_LOOP, r.type());
        }
    }

    // ===================================================================
    // 8. Low effort — filler without substance
    // ===================================================================

    @Nested
    class LowEffort {

        @Test
        void anthropicFiller_isLowEffort() {
            // Must be >30 chars to avoid EMPTY_OUTPUT, <300 chars for low-effort check
            TurnMetrics m = baseMetrics()
                    .provider("anthropic")
                    .agentOutput("I'll help you with that! Let me take a look.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.LOW_EFFORT, r.type());
        }

        @Test
        void openAiFiller_isLowEffort() {
            // Must be >30 chars to avoid EMPTY_OUTPUT
            TurnMetrics m = baseMetrics()
                    .provider("openai")
                    .agentOutput("Great question! Let me look into that for you.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.LOW_EFFORT, r.type());
        }

        @Test
        void genericFiller_isLowEffort() {
            // "Sure." is only 5 chars — under EMPTY_THRESHOLD, so it's EMPTY_OUTPUT
            TurnMetrics m = baseMetrics()
                    .agentOutput("Sure.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");
            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.EMPTY_OUTPUT, r.type());
        }

        @Test
        void substantiveResponse_isNotLowEffort() {
            TurnMetrics m = baseMetrics()
                    .provider("anthropic")
                    .agentOutput("I'll help you with that! Here's the fix: the null pointer exception "
                            + "occurs because the config map is not initialized before the first access. "
                            + "Add `config = new HashMap<>()` in the constructor.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertFalse(r.hasEscape());
        }

        @Test
        void longOutput_isNotLowEffort() {
            // Over LOW_EFFORT_MAX_LENGTH (300), even if starts with filler
            String output = "I'll help you with that! " + "X".repeat(300);
            TurnMetrics m = baseMetrics()
                    .provider("anthropic")
                    .agentOutput(output)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.LOW_EFFORT, r.type());
        }
    }

    // ===================================================================
    // 9. Trivially short
    // ===================================================================

    @Nested
    class TriviallyShort {

        @Test
        void shortOutputForCodeReview_isTriviallyShort() {
            // 50 chars — over EMPTY_THRESHOLD (30) but under SHORT_THRESHOLD (150)
            String output = "Looks good, no issues found in the code changes.";
            TurnMetrics m = baseMetrics().agentOutput(output).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.TRIVIALLY_SHORT, r.type());
        }

        @Test
        void shortOutputForGeneral_isNotTriviallyShort() {
            // "general" task type is exempt from TRIVIALLY_SHORT
            String output = "The answer is 42. Simple as that.";
            TurnMetrics m = baseMetrics().agentOutput(output).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.TRIVIALLY_SHORT, r.type());
        }

        @Test
        void adequateLengthOutput_isNotTriviallyShort() {
            String output = "I reviewed all the changes in the diff. The authentication "
                    + "module has been correctly updated with bcrypt password hashing. "
                    + "The salt rounds are appropriately set to 12 for production use.";
            TurnMetrics m = baseMetrics().agentOutput(output).build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertNotEquals(EscapeDetector.EscapeType.TRIVIALLY_SHORT, r.type());
        }
    }

    // ===================================================================
    // 10. No tools used
    // ===================================================================

    @Nested
    class NoToolsUsed {

        @Test
        void noToolsForCodeReview_isNoToolsEscape() {
            // Output must be >150 chars to avoid TRIVIALLY_SHORT preemption
            TurnMetrics m = baseMetrics()
                    .agentOutput("The code looks fine. I don't see any major issues with the implementation. "
                            + "The variable naming follows conventions and the logic is straightforward. "
                            + "The class structure is clean and the method signatures look correct.")
                    .toolCallsTotal(0)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.NO_TOOLS_USED, r.type());
        }

        @Test
        void noToolsForExploration_isNoToolsEscape() {
            // Output must be >150 chars to avoid TRIVIALLY_SHORT preemption
            TurnMetrics m = baseMetrics()
                    .agentOutput("The project appears to be a standard Maven project with the usual "
                            + "directory structure including src/main/java and src/test/java directories. "
                            + "I'd recommend checking the pom.xml file for dependency details and build configuration.")
                    .toolCallsTotal(0)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "exploration");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.NO_TOOLS_USED, r.type());
        }

        @Test
        void noToolsForGeneral_isNotNoToolsEscape() {
            // "general" is not a tool-requiring task type
            TurnMetrics m = baseMetrics()
                    .agentOutput("A HashMap in Java is a key-value data structure that provides O(1) "
                            + "average-case lookup using hash codes to distribute entries across buckets.")
                    .toolCallsTotal(0)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertFalse(r.hasEscape());
        }

        @Test
        void withToolsForCodeReview_isNotNoToolsEscape() {
            // Output must be >150 chars to avoid TRIVIALLY_SHORT
            TurnMetrics m = baseMetrics()
                    .agentOutput("After reviewing the diff, I found a potential null pointer exception on line 42. "
                            + "The method getUser() can return null when the database connection times out. "
                            + "I recommend adding a null check before accessing the user's name property.")
                    .toolCallsTotal(2)
                    .toolCallBreakdown(Map.of("Read", 1, "Grep", 1))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertFalse(r.hasEscape());
        }
    }

    // ===================================================================
    // 11. Tool name loop (same tool 3+ times)
    // ===================================================================

    @Nested
    class ToolLoop {

        @Test
        void sameToolThreeTimes_isToolLoop() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I searched the codebase extensively for the pattern across all files "
                            + "using multiple grep invocations to find every occurrence.")
                    .toolCallsTotal(3)
                    .toolCallBreakdown(Map.of("Grep", 3))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.TOOL_LOOP, r.type());
        }

        @Test
        void differentToolsTwoEach_isNotToolLoop() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I read two files and ran two grep searches to understand the pattern.")
                    .toolCallsTotal(4)
                    .toolCallBreakdown(Map.of("Read", 2, "Grep", 2))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertNotEquals(EscapeDetector.EscapeType.TOOL_LOOP, r.type());
        }
    }

    // ===================================================================
    // 12. Off-topic
    // ===================================================================

    @Nested
    class OffTopic {

        @Test
        void noTaskKeywords_isOffTopic() {
            // Output must be >150 chars to avoid TRIVIALLY_SHORT preemption
            TurnMetrics m = baseMetrics()
                    .agentOutput("The weather today is sunny with a high of 75 degrees Fahrenheit. "
                            + "Perfect day for a walk in the park or having a picnic outside. "
                            + "The forecast says tomorrow will also be warm and pleasant with light breezes.")
                    .toolCallsTotal(1)
                    .toolCallBreakdown(Map.of("Bash", 1))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertTrue(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.OFF_TOPIC, r.type());
        }

        @Test
        void withTaskKeywords_isNotOffTopic() {
            // Output must be >150 chars and contain code-review keywords (diff, change, function, class, method, bug, etc.)
            TurnMetrics m = baseMetrics()
                    .agentOutput("I reviewed the diff and found a bug in the method implementation. "
                            + "The class variable is not properly initialized before it is accessed. "
                            + "The fix involves adding an initializer in the constructor of the class.")
                    .toolCallsTotal(1)
                    .toolCallBreakdown(Map.of("Read", 1))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertFalse(r.hasEscape());
        }

        @Test
        void unknownTaskType_isNeverOffTopic() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("The weather is nice today for a picnic.")
                    .toolCallsTotal(1)
                    .toolCallBreakdown(Map.of("Bash", 1))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "unknown-type");

            assertNotEquals(EscapeDetector.EscapeType.OFF_TOPIC, r.type());
        }
    }

    // ===================================================================
    // EscapeResult accessors
    // ===================================================================

    @Nested
    class EscapeResultAccessors {

        @Test
        void noneResult_hasNoEscape() {
            EscapeDetector.EscapeResult r = EscapeDetector.EscapeResult.none();

            assertFalse(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.NONE, r.type());
            assertEquals("", r.detail());
            assertEquals(0f, r.penalty());
            assertFalse(r.isHardEscape());
        }

        @Test
        void emptyOutput_isHardEscape() {
            EscapeDetector.EscapeResult r = new EscapeDetector.EscapeResult(
                    true, EscapeDetector.EscapeType.EMPTY_OUTPUT, "empty", 0f);
            assertTrue(r.isHardEscape());
        }

        @Test
        void explicitRefusal_isHardEscape() {
            EscapeDetector.EscapeResult r = new EscapeDetector.EscapeResult(
                    true, EscapeDetector.EscapeType.EXPLICIT_REFUSAL, "refused", 0f);
            assertTrue(r.isHardEscape());
        }

        @Test
        void toolLoop_isNotHardEscape() {
            EscapeDetector.EscapeResult r = new EscapeDetector.EscapeResult(
                    true, EscapeDetector.EscapeType.TOOL_LOOP, "loop", 0.8f);
            assertFalse(r.isHardEscape());
        }
    }

    // ===================================================================
    // History management
    // ===================================================================

    @Nested
    class HistoryManagement {

        @Test
        void initialHistorySize_isZero() {
            assertEquals(0, detector.getHistorySize());
        }

        @Test
        void detectIncrementsHistory() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("A normal response with enough content to pass all checks.")
                    .build();
            detector.detect(m, "general");

            assertEquals(1, detector.getHistorySize());
        }

        @Test
        void resetHistory_clearsBuffer() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("A normal response with enough content to pass all checks.")
                    .build();
            detector.detect(m, "general");
            detector.detect(m, "general");
            assertEquals(2, detector.getHistorySize());

            detector.resetHistory();
            assertEquals(0, detector.getHistorySize());
        }

        @Test
        void historyCapAt8() {
            for (int i = 0; i < 12; i++) {
                TurnMetrics m = baseMetrics()
                        .agentOutput("Unique response number " + i
                                + " with enough content to pass all the escape detection checks.")
                        .build();
                detector.detect(m, "general");
            }

            assertEquals(8, detector.getHistorySize(), "History should cap at HISTORY_SIZE=8");
        }
    }

    // ===================================================================
    // Priority ordering — higher-priority escapes preempt lower ones
    // ===================================================================

    @Nested
    class PriorityOrdering {

        @Test
        void emptyOutput_preemptsRefusal() {
            // Empty output (check 1) should fire before refusal (check 2) can match
            TurnMetrics m = baseMetrics().agentOutput("").build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertEquals(EscapeDetector.EscapeType.EMPTY_OUTPUT, r.type());
        }

        @Test
        void refusal_preemptsTriviallyShort() {
            // Refusal (check 2) should fire before trivially short (check 9)
            // Must be >30 chars to avoid EMPTY_OUTPUT, <500 chars for refusal check
            TurnMetrics m = baseMetrics()
                    .agentOutput("I'm sorry, but I cannot help with this particular task at this time.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertEquals(EscapeDetector.EscapeType.EXPLICIT_REFUSAL, r.type());
        }

        @Test
        void maxSteps_preemptsToolLoop() {
            // Max steps (check 3) should fire before tool loop (check 11)
            TurnMetrics m = baseMetrics()
                    .agentOutput("I kept trying but the build keeps failing after each attempt at fixing.")
                    .hitMaxSteps(true)
                    .agenticSteps(25)
                    .toolCallsTotal(5)
                    .toolCallBreakdown(Map.of("Bash", 5))
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertEquals(EscapeDetector.EscapeType.MAX_STEPS_ABANDONED, r.type());
        }
    }

    // ===================================================================
    // Successful detection — no escape
    // ===================================================================

    @Nested
    class NoEscape {

        @Test
        void fullResponse_withTools_noEscape() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("I've analyzed the codebase and found the root cause of the bug. "
                            + "The issue is in the ConnectionPool class where the max connections "
                            + "parameter is being set to 0 instead of the configured value. "
                            + "I've fixed the bug by reading the config value correctly.")
                    .toolCallsTotal(3)
                    .toolCallBreakdown(Map.of("Read", 2, "Edit", 1))
                    .agenticSteps(3)
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "code-review");

            assertFalse(r.hasEscape());
            assertEquals(EscapeDetector.EscapeType.NONE, r.type());
            assertEquals(0f, r.penalty());
        }

        @Test
        void generalTask_shortButOk() {
            TurnMetrics m = baseMetrics()
                    .agentOutput("A HashMap uses hash codes to store key-value pairs.")
                    .build();
            EscapeDetector.EscapeResult r = detector.detect(m, "general");

            assertFalse(r.hasEscape());
        }
    }
}
