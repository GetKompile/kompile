package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the stall detectors: a turn whose last sentence promises action,
 * and a turn whose closing block reports an outstanding gap. Negatives carry the
 * weight here — a false positive sends an unsolicited message into a live turn.
 */
class ContinueDetectorTest {

    // ── PROMISED_ACTION ──────────────────────────────────────────────────────

    @Test
    void finalSentencePromiseIsDetected() {
        ContinueDetector.Hit hit = ContinueDetector.promisedAction(
                "The cache is not the cause.\n\nLet me do that now.");
        assertNotNull(hit, "a closing promise must fire");
        assertEquals(ContinueDetector.Kind.PROMISED_ACTION, hit.kind());
        assertEquals("Let me do that now.", hit.evidence());

        ContinueDetector.Hit contraction = ContinueDetector.promisedAction(
                "The test file is stale.\n\nI'll fix it next.");
        assertNotNull(contraction);
        assertEquals(ContinueDetector.Kind.PROMISED_ACTION, contraction.kind());

        assertNotNull(ContinueDetector.promisedAction("Two modules left.\n\nNow let's wire them up."));
    }

    @Test
    void promiseInTheMiddleOfTheTurnIsNotAStall() {
        assertNull(ContinueDetector.promisedAction(
                "Let me start with the resolver.\n\nThe resolver reads the route table "
                        + "and the cache sits in front of it, so the stale read is expected."),
                "the turn kept going after the promise");

        assertNull(ContinueDetector.promisedAction(
                "Let me recap what changed:\n\n- detector added\n- tests added\n"),
                "a list after the promise shows the work was done");

        assertNull(ContinueDetector.promisedAction(
                "Let me show the fix:\n```java\nvoid decide() { }\n```\n"),
                "fenced output substantiates the promise");
    }

    @Test
    void closingQuestionBelongsToTheKeywordLane() {
        assertNull(ContinueDetector.promisedAction("I can fix this. Shall I proceed?"),
                "asking for input is not a silent stop");
        assertNull(ContinueDetector.promisedAction("Let me know if you want the short version."),
                "\"let me know\" is a closing courtesy");
    }

    // ── REPORTED_GAP ─────────────────────────────────────────────────────────

    @Test
    void closingGapReportIsDetected() {
        ContinueDetector.Hit hit = ContinueDetector.reportedGap(
                "The typed fields propagate end to end.\n\n"
                        + "Remaining gap: no test drives the failover path.");
        assertNotNull(hit, "a closing gap report must fire");
        assertEquals(ContinueDetector.Kind.REPORTED_GAP, hit.kind());
        assertTrue(hit.evidence().toLowerCase().startsWith("remaining gap"));

        assertNotNull(ContinueDetector.reportedGap(
                "Half the lanes build.\n\n- Not yet verified: the Triton path."));
        assertNotNull(ContinueDetector.reportedGap(
                "The refactor landed.\n\nStill not verified: restart behaviour."));
        assertNotNull(ContinueDetector.reportedGap(
                "Two checkpoints done.\n\nOutstanding gap is the reset parser."));
    }

    @Test
    void gapHeadingOverItsBulletsIsDetected() {
        ContinueDetector.Hit hit = ContinueDetector.reportedGap(
                "Most of the work landed.\n\nRemaining gaps\n\n- the parser\n- the restart path");
        assertNotNull(hit, "a heading introducing the closing bullets is the closing state");
    }

    @Test
    void explicitAllClearIsNotAGap() {
        assertNull(ContinueDetector.reportedGap("All four classes pass.\n\nNo remaining gaps."),
                "an explicit all-clear is not a stall");
        assertNull(ContinueDetector.reportedGap(
                "Everything is green.\n\nThere are no gaps left in this module."));
        assertNull(ContinueDetector.reportedGap("The work is finished.\n\nNothing remaining."));
        assertNull(ContinueDetector.reportedGap("Shipped as planned.\n\nAll gaps resolved."));
    }

    @Test
    void gapNamedMidTurnIsFollowedByWorkNotAStop() {
        assertNull(ContinueDetector.reportedGap(
                "Remaining gaps: two of the checks need dtypes.\n\n"
                        + "I ran the suite against both lanes and reconciled the counts with "
                        + "the expected totals, then re-ran the failing class on its own."));
    }

    @Test
    void ordinaryProseIsNotAGap() {
        assertNull(ContinueDetector.reportedGap("I closed the gap between the two configs."));
        assertNull(ContinueDetector.reportedGap("Done. Every test passes."));
        assertNull(ContinueDetector.reportedGap("The bandwidth graph is in the report."));
    }

    // ── Combined ─────────────────────────────────────────────────────────────

    @Test
    void detectReportsEveryFiringDetectorInOrder() {
        // Both closings inside one final block: the gap is filed and the block ends on
        // a promise, so both detectors have something to report.
        List<ContinueDetector.Hit> hits = ContinueDetector.detect(
                "Here is where it stands. Remaining gap: the parser diverges. Let me unify it.");
        assertEquals(2, hits.size());
        assertEquals(ContinueDetector.Kind.PROMISED_ACTION, hits.get(0).kind());
        assertEquals(ContinueDetector.Kind.REPORTED_GAP, hits.get(1).kind());

        // A gap in an earlier block is context for the turn, not its closing state.
        List<ContinueDetector.Hit> promisedOnly = ContinueDetector.detect(
                "Remaining gaps: two of the checks need dtypes.\n\nI'll sort those out next.");
        assertEquals(List.of(ContinueDetector.Kind.PROMISED_ACTION),
                promisedOnly.stream().map(ContinueDetector.Hit::kind).toList());

        assertTrue(ContinueDetector.detect("All green. Nothing to do.").isEmpty());
        assertTrue(ContinueDetector.detect(null).isEmpty());
        assertTrue(ContinueDetector.detect("   ").isEmpty());
    }

    @Test
    void hitDescriptionNamesKindAndEvidence() {
        ContinueDetector.Hit hit = new ContinueDetector.Hit(
                ContinueDetector.Kind.REPORTED_GAP, "Remaining gap: no test yet");
        assertTrue(hit.describe().contains("reported gap"));
        assertTrue(hit.describe().contains("Remaining gap: no test yet"));
    }

    @Test
    void detectorsCanBeDisabled() {
        String stalling = "Root cause found.\n\nI'll wire the migration next.";
        assertNotNull(ContinueDetector.promisedAction(stalling));
        try {
            System.setProperty(ContinueDetector.ENABLE_PROPERTY, "false");
            assertFalse(ContinueDetector.enabled());
            assertNull(ContinueDetector.promisedAction(stalling));
            assertNull(ContinueDetector.reportedGap("Done.\n\nRemaining gap: the parser."));
            assertTrue(ContinueDetector.detect(stalling).isEmpty());
        } finally {
            System.clearProperty(ContinueDetector.ENABLE_PROPERTY);
        }
        assertTrue(ContinueDetector.enabled());
    }
}
