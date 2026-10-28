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
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer.deflection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-corpus tests for the deterministic deflection detector, mirroring
 * docs/architecture/enforcer-deflection-design.md §8: deflecting outputs without
 * receipts must be flagged; the same phrasing WITH receipts (this repo's recurring
 * "pre-existing suite failure + git-history evidence" case) must pass; guards must
 * suppress quoted/echoed and user-echoed phrasing.
 */
class DeflectionDetectorTest {

    private static final DeflectionDetector DETECTOR = new DeflectionDetector(
            DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());

    // ── The core contract: signal ∧ ¬evidence ────────────────────────────────

    @Test
    void bareDeflectionIsCorrected() {
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "fix the failing WebWorkflowApprovalTest",
                "That error is not my error. Nothing I can do here.",
                null, 0, 2);
        assertEquals(DeflectionVerdict.Action.CORRECT, verdict.action());
        assertTrue(verdict.isActionable());
    }

    @Test
    void deflectionWithGitHistoryReceiptsPasses() {
        // This repo's own recurring real case: pre-existing failure + receipts.
        String output = "The failure is pre-existing and not from this change. "
                + "git log shows the file was last touched (commit 3f2a91bc), and "
                + "INC-faba862d4c72 already records this as another session's working-tree "
                + "modification (ChatRepl.java:1409).";
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "is this failure yours?",
                output,
                "git log --oneline -5; read tool-results",
                0, 2);
        assertEquals(DeflectionVerdict.Action.PASS_NOTED, verdict.action());
        assertFalse(verdict.isActionable());
    }

    @Test
    void deflectionWithHandoffActionPasses() {
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "fix the kclaw bearer contract",
                "This is upstream, outside my area. I filed an issue and escalated to the "
                        + "KClaw owner; assigned to @owner.",
                null, 0, 2);
        assertEquals(DeflectionVerdict.Action.PASS_NOTED, verdict.action());
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    void ownershipLanguageSuppressesVerdict() {
        // Both a deflection signal AND ownership language present: the ownership
        // acknowledgment suppresses the verdict (agent owns part of the work and is
        // actively fixing it — prose triage, not lazy deflection).
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "the suite is failing",
                "I broke this in my last edit — my mistake — and I'm fixing the parser now. "
                        + "The remaining kclaw failures are not my error.",
                null, 0, 2);
        assertEquals(DeflectionVerdict.Action.PASS_NOTED, verdict.action());
    }

    @Test
    void quotedCompilerOutputIsNotDeflection() {
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "build is red",
                "The log says:\n```\nerror: not my error\nerror: this failure is out of scope\n```\n"
                        + "I'm investigating the real cause now.",
                null, 0, 2);
        assertEquals(DeflectionVerdict.Action.NONE, verdict.action());
    }

    @Test
    void echoedUserLanguageIsExempt() {
        // The USER said the phrase; the agent discussing it is not deflecting.
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "CI says 'not my error' — what does that mean?",
                "That phrase usually appears when a script denies the blame assignment. "
                        + "Here is how to interpret it and what to check next.",
                null, 0, 2);
        assertEquals(DeflectionVerdict.Action.NONE, verdict.action());
    }

    @Test
    void clarifyingQuestionIsNotDeflection() {
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "something fails",
                "Could you share the stack trace so I can tell whether it is a regression "
                        + "from this branch?",
                null, 0, 2);
        assertEquals(DeflectionVerdict.Action.NONE, verdict.action());
    }

    // ── Escalation state machine ─────────────────────────────────────────────

    @Test
    void exhaustedBudgetStops() {
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "fix it",
                "Not mine, won't fix.",
                null, 2, 2);
        assertEquals(DeflectionVerdict.Action.STOP, verdict.action());
    }

    @Test
    void receiptsBeatThreshold() {
        DeflectionDetector strict = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), new DeflectionDetector.Options(0.95, true));
        DeflectionVerdict verdict = strict.evaluate(
                "fix it",
                "I can't reproduce that here.",
                "mvn test -Dtest=FooTest; git status", 0, 2);
        // Receipts present (mvn test in trace) → PASS_NOTED regardless of threshold.
        assertEquals(DeflectionVerdict.Action.PASS_NOTED, verdict.action());
    }

    @Test
    void emptyOutputIsNone() {
        assertEquals(DeflectionVerdict.Action.NONE,
                DETECTOR.evaluate("fix it", "", null, 0, 2).action());
        assertEquals(DeflectionVerdict.Action.NONE,
                DETECTOR.evaluate("fix it", null, null, 0, 2).action());
    }

    @Test
    void cleanAnswerIsNone() {
        DeflectionVerdict verdict = DETECTOR.evaluate(
                "fix the failing test",
                "I read AgenticChatLoop.java:2908 and the correction loop resets the counter "
                        + "per turn. Patching the reset now.",
                "read AgenticChatLoop.java",
                0, 2);
        assertEquals(DeflectionVerdict.Action.NONE, verdict.action());
    }

    // ── Evidence scanning ────────────────────────────────────────────────────

    @Test
    void evidenceScanFindsDiagnosticsAndArtifacts() {
        DeflectionEvidence evidence = DeflectionEvidence.scan(
                "The failure is pre-existing; see AgenticChatLoop.java:2908 and INC-abc123.",
                "git log --oneline -5; mvn test -Dtest=FooBarTest");
        assertFalse(evidence.isEmpty());
        assertTrue(evidence.diagnostics().size() >= 2, "git + mvn should both count");
        assertTrue(evidence.artifacts().stream().anyMatch(a -> a.contains("AgenticChatLoop.java")));
        assertTrue(evidence.artifacts().stream().anyMatch(a -> a.startsWith("INC-")));
        assertEquals(0, evidence.handoffs().size());
    }

    // ── Mandate builders ─────────────────────────────────────────────────────

    @Test
    void mandatesMentionReceipts() {
        String ownership = DeflectionMandate.ownershipMandate("fix it", "tier-0 signals");
        assertTrue(ownership.contains("diagnostic command NOW"));
        assertTrue(ownership.contains("out of scope"));
        String evidenceFirst = DeflectionMandate.evidenceFirstMandate("fix it", "tier-0 signals");
        assertTrue(evidenceFirst.contains("tool trace is checked"));
        String stop = DeflectionMandate.stopReason("tier-0 signals", 2, 2);
        assertTrue(stop.contains("2/2"));
    }

    // ── Monitor mapping ──────────────────────────────────────────────────────

    @Test
    void monitorMapsVerdictsToDecisions() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor.Options options = new DeflectionMonitor.Options(
                "pattern", false, 0.7, 2, 0, true);
        DeflectionMonitor monitor = new DeflectionMonitor(detector, options, null);
        monitor.beginTurn();

        DeflectionMonitor.Outcome first = monitor.check(
                "fix it", "That's not my error, nothing I can do.", null);
        assertTrue(first.shouldCorrect());
        assertTrue(first.correctionPrompt().contains("ownership mandate"));

        DeflectionMonitor.Outcome second = monitor.check(
                "fix it", "Still not my error.", null);
        // Same phrase repeated → escalated evidence-first mandate.
        assertTrue(second.shouldCorrect());
        assertTrue(second.correctionPrompt().contains("evidence-first"));

        // Third identical deflection exceeds the budget → stop.
        DeflectionMonitor.Outcome third = monitor.check(
                "fix it", "not my error again", null);
        assertTrue(third.shouldStop());
    }

    @Test
    void reportOnlyOverrideSuppressesActions() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor.Options options = new DeflectionMonitor.Options(
                "pattern", true, 0.7, 2, 0, true);
        DeflectionMonitor monitor = new DeflectionMonitor(detector, options, null);
        monitor.beginTurn();
        DeflectionMonitor.Outcome outcome = monitor.check(
                "fix it", "Not my error. Nothing I can do.", null);
        assertFalse(outcome.shouldCorrect());
        assertFalse(outcome.shouldStop());
        assertTrue(outcome.shouldLog());
        assertTrue(outcome.reportOnlyOverride());
    }

    // ── Cross-turn streak ────────────────────────────────────────────────────

    @Test
    void crossTurnStreakEscalatesToStop() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor.Options options = new DeflectionMonitor.Options(
                "pattern", false, 0.7, 2, 2, true);
        DeflectionMonitor monitor = new DeflectionMonitor(detector, options, null);

        // Turn 1: deflects, budget spent, completes deflecting → streak 1.
        monitor.beginTurn();
        monitor.check("fix it", "Not my error at all.", null);
        monitor.completeTurn(true);
        assertEquals(1, monitor.getConsecutiveDeflectingTurns());

        // Turn 2: streak limit 2 reached → the CORRECT verdict escalates to STOP.
        monitor.beginTurn();
        DeflectionMonitor.Outcome outcome = monitor.check("fix it", "Not my error again.", null);
        assertTrue(outcome.shouldStop());
    }

    @Test
    void cleanTurnResetsStreak() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor.Options options = new DeflectionMonitor.Options(
                "pattern", false, 0.7, 2, 2, true);
        DeflectionMonitor monitor = new DeflectionMonitor(detector, options, null);

        monitor.beginTurn();
        monitor.check("fix it", "Not my error at all.", null);
        monitor.completeTurn(true);
        assertEquals(1, monitor.getConsecutiveDeflectingTurns());

        monitor.beginTurn();
        monitor.check("fix it", "I own this — I broke this in the last commit. Fixing now.", null);
        monitor.completeTurn(true);
        assertEquals(0, monitor.getConsecutiveDeflectingTurns());
    }

    // ── Subagent child review ────────────────────────────────────────────────

    @Test
    void reportOnlyChildReviewNeverCorrectsOrExhaustsTheBudget() {
        DeflectionMonitor monitor = new DeflectionMonitor(DETECTOR,
                new DeflectionMonitor.Options("pattern", true, 0.7, 1, 0, true), null);
        var child = monitor.childReview();
        for (int i = 0; i < 4; i++) {
            assertEquals(null, child.review("fix it", "Not my error. Nothing I can do.", null));
        }
        assertEquals(0, child.getCorrections(), "report-only observations consume no correction budget");
    }

    @Test
    void offModeBypassesParentAndChildDetection() {
        DeflectionDetector unavailable = new DeflectionDetector(DeflectionPatternLibrary.defaults(),
                output -> { throw new AssertionError("disabled monitor must not evaluate"); },
                DeflectionDetector.Options.defaults());
        DeflectionMonitor monitor = new DeflectionMonitor(unavailable,
                new DeflectionMonitor.Options("off", false, 0.7, 1, 0, true), null);
        assertEquals(DeflectionVerdict.Action.NONE, monitor.check("fix it", "Not my error.", null).verdict().action());
        assertEquals(null, monitor.childReview().review("fix it", "Not my error.", null));
    }

    @Test
    void childReviewAcceptsCleanCompletions() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor monitor = new DeflectionMonitor(detector,
                new DeflectionMonitor.Options("pattern", false, 0.7, 2, 3, true), null);
        DeflectionMonitor.ChildReview child = monitor.childReview();
        String feedback = child.review("fix the test",
                "I read Foo.java:42; the null check is missing. Patching it now.",
                "read Foo.java");
        assertEquals(null, feedback);
    }

    @Test
    void childReviewCorrectsThenEscalatesIdenticalDeflection() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor monitor = new DeflectionMonitor(detector,
                new DeflectionMonitor.Options("pattern", false, 0.7, 2, 0, true), null);
        DeflectionMonitor.ChildReview child = monitor.childReview();

        String first = child.review("fix it", "Not my error, nothing I can do.", null);
        assertTrue(first.contains("ownership mandate"));

        String second = child.review("fix it", "Still not my error.", null);
        assertTrue(second.contains("evidence-first"));

        // Third identical deflection exceeds the budget (maxCorrections=2) → block.
        try {
            child.review("fix it", "not my error again", null);
            assertTrue(false, "expected escalation to block the child");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("blocked") || expected.getMessage().contains("Deflection persisted"));
        }
    }

    @Test
    void childReviewFailsOpenOnDetectorCrash() {
        // Semantic scorer that explodes; the monitor must catch and fail open.
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), (out) -> {
                    throw new RuntimeException("semantic backend exploded");
                }, new DeflectionDetector.Options(0.7, true));
        DeflectionMonitor monitor = new DeflectionMonitor(detector,
                new DeflectionMonitor.Options("pattern", false, 0.7, 2, 0, true), null);
        DeflectionMonitor.ChildReview child = monitor.childReview();
        // Semantic scorer crash is caught inside the monitor → fail open, no feedback.
        String feedback = child.review("fix it", "I read the file and fixed the null check.", "read X.java");
        assertEquals(null, feedback);
    }

    @Test
    void childCorrectionsAreIsolatedPerChild() {
        DeflectionDetector detector = new DeflectionDetector(
                DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults());
        DeflectionMonitor monitor = new DeflectionMonitor(detector,
                new DeflectionMonitor.Options("pattern", false, 0.7, 2, 0, true), null);
        DeflectionMonitor.ChildReview childA = monitor.childReview();
        DeflectionMonitor.ChildReview childB = monitor.childReview();

        childA.review("fix it", "Not my error.", null);
        childA.review("fix it", "Not my error.", null);
        assertEquals(2, childA.getCorrections());
        assertEquals(0, childB.getCorrections(), "sibling child starts with its own budget");
    }

    // ── Pattern library ──────────────────────────────────────────────────────

    @Test
    void extrasAndWhitelistMerge() {
        DeflectionPatternLibrary library = DeflectionPatternLibrary.create(
                List.of("per our chart that is the platform team's lane"),
                List.of("platform team's lane"));
        List<DeflectionSignal> signals = library.findSignals(
                "Per our chart that is the platform team's lane.");
        assertTrue(signals.isEmpty(), "whitelisted extra must not signal");

        DeflectionPatternLibrary strict = DeflectionPatternLibrary.create(
                List.of("per our chart that is the platform team's lane"), List.of());
        assertFalse(strict.findSignals("Per our chart that is the platform team's lane.")
                .isEmpty(), "extra pattern must signal without whitelist");
    }

    @Test
    void optionsClamp() {
        DeflectionMonitor.Options o = new DeflectionMonitor.Options(
                "SEMANTIC", true, 5.0, 99, -3, true);
        assertEquals("semantic", o.mode());
        assertEquals(1.0, o.confidenceThreshold());
        assertEquals(4, o.maxCorrections());
        assertEquals(0, o.crossTurnLimit());
    }
}
