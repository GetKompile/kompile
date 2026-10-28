package ai.kompile.cli.main.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoopDetectorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode args = mapper.createObjectNode().put("file_path", "a.java");

    @Test
    void warnsBeforeStoppingIdenticalCalls() {
        LoopDetector detector = new LoopDetector();
        assertEquals(LoopDetector.Action.PROCEED, result(detector, "unchanged").action());
        assertEquals(LoopDetector.Action.PROCEED, result(detector, "unchanged").action());
        assertEquals(LoopDetector.Action.WARN, result(detector, "unchanged").action());
        assertTrue(detector.drainWarning().contains("Change approach"));
        assertNull(detector.drainWarning());
        assertFalse(result(detector, "unchanged").shouldStopTurn());
        assertTrue(result(detector, "unchanged").shouldStopTurn());
        assertTrue(detector.isTerminated());
        assertNotNull(detector.stopNotice());
    }

    @Test
    void changingResultsAreProgressEvenAfterWarning() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 3; i++) result(detector, "old");
        for (int i = 0; i < 1000; i++) assertFalse(result(detector, "new " + i).shouldStopTurn());
        assertFalse(detector.isTerminated());
    }

    @Test
    void changingArgumentsDoNotCountAsLoop() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 500; i++) {
            assertEquals(LoopDetector.Action.PROCEED, detector.afterToolResult("read",
                    mapper.createObjectNode().put("offset", i), "same", false).action());
        }
    }

    @Test
    void detectsAlternatingCycles() {
        LoopDetector detector = new LoopDetector();
        for (int i = 1; i <= 5; i++) {
            detector.afterToolResult("read", args, "same", false);
            var verdict = detector.afterToolResult("grep", args, "same", true);
            if (i == 3) assertEquals(LoopDetector.Action.WARN, verdict.action());
            assertEquals(i == 5, verdict.shouldStopTurn());
        }
    }

    @Test
    void progressBreaksRecentCycleRatherThanCountingTurnWide() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 100; i++) {
            result(detector, "unchanged");
            detector.afterToolResult("edit", mapper.createObjectNode().put("replacement", i), "ok", false);
        }
        assertFalse(detector.isTerminated());
    }

    @Test
    void canonicalizesObjectKeysButPreservesArrayOrder() throws Exception {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 5; i++) {
            JsonNode input = mapper.readTree(i % 2 == 0 ? "{\"a\":1,\"b\":2}" : "{\"b\":2,\"a\":1}");
            detector.afterToolResult("read", input, "same", false);
        }
        assertTrue(detector.isTerminated());
        detector.reset();
        for (int i = 0; i < 5; i++) {
            detector.afterToolResult("read", mapper.readTree("[" + i + ",1]"), "same", false);
        }
        assertFalse(detector.isTerminated());
    }

    @Test
    void preservesMeaningfulIdsTimestampsAndWhitespace() {
        for (String key : new String[]{"session_id", "timestamp", "text"}) {
            LoopDetector detector = new LoopDetector();
            for (int i = 0; i < 20; i++) {
                detector.afterToolResult("read", mapper.createObjectNode().put(key,
                        "call_123456" + " ".repeat(i)), "same", false);
            }
            assertFalse(detector.isTerminated(), key);
        }
    }

    @Test
    void namespaceDoesNotEvadeRepetition() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 5; i++) {
            detector.afterToolResult(i % 2 == 0 ? "read" : "mcp__kompile__read", args, "same", false);
        }
        assertTrue(detector.isTerminated());
    }

    @Test
    void errorStateIsPartOfObservation() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 4; i++) detector.afterToolResult("read", args, "same", true);
        assertFalse(detector.afterToolResult("read", args, "same", false).shouldStopTurn());
    }

    @Test
    void pollingHasLongerButFiniteRunway() {
        LoopDetector detector = new LoopDetector();
        JsonNode polling = mapper.createObjectNode().put("action", "status").put("process_id", "proc-1");
        for (int i = 1; i <= 20; i++) {
            var verdict = detector.afterToolResult("process", polling, "RUNNING", false);
            if (i == 5) assertEquals(LoopDetector.Action.PROCEED, verdict.action());
            if (i == 12) assertEquals(LoopDetector.Action.WARN, verdict.action());
            assertEquals(i == 20, verdict.shouldStopTurn());
        }
    }

    @Test
    void exactTextOnlyAndEmptyCorrectionLoopsAreStopped() {
        for (String text : new String[]{"same short response", "", "long response ".repeat(100)}) {
            LoopDetector detector = new LoopDetector();
            for (int i = 1; i <= 5; i++) {
                var verdict = detector.afterAssistantText(text);
                if (i == 3) assertEquals(LoopDetector.Action.WARN, verdict.action());
                assertEquals(i == 5, verdict.shouldStopTurn());
            }
        }
    }

    @Test
    void resetStartsFreshScopeAfterStop() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 5; i++) result(detector, "same");
        detector.reset();
        assertFalse(detector.isTerminated());
        assertNull(detector.stopNotice());
        assertNull(detector.drainWarning());
        assertEquals(LoopDetector.Action.PROCEED, result(detector, "same").action());
    }

    @Test
    void nativeArgumentsAreCanonicalizedAndMissingInputsAreNotEvidence() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 20; i++) {
            assertEquals(LoopDetector.Action.PROCEED,
                    detector.afterProviderToolResult("Read", null, "same", false).action());
        }
        for (int i = 0; i < 5; i++) {
            detector.afterProviderToolResult("Read", i % 2 == 0 ? "{\"a\":1,\"b\":2}"
                    : "{\"b\":2,\"a\":1}", "same", false);
        }
        assertTrue(detector.isTerminated());
        detector.reset();
        for (int i = 0; i < 5; i++) {
            detector.afterProviderToolResult("Bash", "plain text command", "same", false);
        }
        assertTrue(detector.isTerminated());
    }

    @Test
    void varyingHarnessReceiptDoesNotHideIdenticalToolOutput() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 5; i++) result(detector, "same contents\n[saved to: /results/" + i + ".txt]");
        assertTrue(detector.isTerminated());
    }

    @Test
    void detectsEightStepCycleWithinBoundedHistory() {
        LoopDetector detector = new LoopDetector();
        for (int repeat = 0; repeat < 5; repeat++) {
            for (int step = 0; step < 8; step++) {
                var verdict = detector.afterToolResult("read",
                        mapper.createObjectNode().put("offset", step), "same", false);
                assertEquals(repeat == 4 && step == 7, verdict.shouldStopTurn());
            }
        }
    }

    @Test
    void progressClearsUndeliveredWarningButUnchangedRepeatKeepsIt() {
        LoopDetector detector = new LoopDetector();
        for (int i = 0; i < 3; i++) result(detector, "old");
        result(detector, "new result");
        assertNull(detector.drainWarning(), "do not send stale corrections after actual progress");
        detector.reset();
        for (int i = 0; i < 4; i++) result(detector, "old");
        assertNotNull(detector.drainWarning(), "a fourth identical result is not progress");
    }

    @Test
    void statusClockDoesNotDisguisePollingButLogProgressStillCounts() {
        JsonNode input = mapper.createObjectNode().put("action", "status").put("process_id", "proc-1");
        LoopDetector detector = new LoopDetector();
        for (int i = 1; i <= 20; i++) {
            var verdict = detector.afterToolResult("process", input,
                    "Process: proc-1\n  State: RUNNING\n  Duration:    " + i
                            + "s\nRecent Output:\n  waiting\n", false);
            assertEquals(i == 20, verdict.shouldStopTurn());
        }
        detector.reset();
        for (int i = 0; i < 100; i++) {
            assertFalse(detector.afterToolResult("process", input,
                    "Process: proc-1\n  Duration:    " + i + "s\nRecent Output:\n  Duration: " + i,
                    false).shouldStopTurn(), "duration inside actual logs must not be erased");
        }
    }

    private LoopDetector.Verdict result(LoopDetector detector, String output) {
        return detector.afterToolResult("read", args, output, false);
    }
}
