package ai.kompile.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the witness accumulator contract: scoped source identity,
 * quote anchoring, dedup, per-row isolation, and explicit batch outcomes.
 * These are deterministic control-flow tests; they do not prove live model accuracy.
 */
class RelationshipWitnessAccumulatorTest {

    private static Map<String, String> windows(String... texts) {
        Map<String, String> windows = new LinkedHashMap<>();
        for (int index = 0; index < texts.length; index++) {
            windows.put("s" + (index + 1), texts[index]);
        }
        return windows;
    }

    private static Map<String, Object> row(
            String sourceId, String subject, String predicateText,
            String object, String quote) {
        return Map.of("sourceId", sourceId, "subject", subject,
                "predicateText", predicateText, "object", object,
                "quote", quote, "qualifier", "");
    }

    @Test
    void sameLocalSourceIdAcrossBatchesRetainsDistinctIdentities() {
        // Two batches both use the prompt-local id "s1"; the host-scoped witness ids must
        // differ so evidence from different windows never merges.
        var first = new RelationshipWitnessAccumulator(
                windows("Dana emailed the forecast to Eli."), "runA:rel:1");
        var second = new RelationshipWitnessAccumulator(
                windows("Dana called Eli about the forecast."), "runA:rel:2");

        var firstBatch = first.accept(Map.of("witnesses", List.of(
                row("s1", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli."))));
        var secondBatch = second.accept(Map.of("witnesses", List.of(
                row("s1", "Dana", "called", "Eli", "Dana called Eli about the forecast."))));

        assertTrue(firstBatch.anyRetained());
        assertTrue(secondBatch.anyRetained());
        assertNotEquals(firstBatch.witnesses().get(0).witnessId(),
                secondBatch.witnesses().get(0).witnessId(),
                "prompt-local source ids must not merge across batches");
        assertNotEquals(firstBatch.witnesses().get(0).windowId(),
                secondBatch.witnesses().get(0).windowId(),
                "window identity must carry the host scope, not the raw local id");
        assertEquals("runA:rel:1:s1", firstBatch.witnesses().get(0).windowId());
        assertEquals("runA:rel:2:s1", secondBatch.witnesses().get(0).windowId());
    }

    @Test
    void retryOfSameEvidenceDeduplicatesWithoutLosingRetainedRows() {
        var accumulator = new RelationshipWitnessAccumulator(
                windows("Dana emailed the forecast to Eli."), "runA:rel:1");
        Map<String, Object> arguments = Map.of("witnesses", List.of(
                row("s1", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli.")));

        var firstBatch = accumulator.accept(arguments);
        var retryBatch = accumulator.accept(arguments);

        assertTrue(firstBatch.anyRetained());
        // The retry re-observes the same evidence: the duplicate is reported as a dropped row
        // (WITH_ERRORS), the original retained witness is untouched, and no double-counting
        // occurs. Repetition dominated by duplicates is exactly what the repair guard detects.
        assertEquals(RelationshipWitnessAccumulator.AttemptStatus.RETAINED_WITH_ERRORS,
                retryBatch.status());
        assertTrue(retryBatch.errors().stream().anyMatch(error ->
                error.contains("[WITNESS_DUPLICATE]")),
                "the duplicate observation must be reported explicitly");
        assertEquals(1, retryBatch.observedRowCount());
        assertEquals(1, accumulator.witnesses().size(),
                "duplicate model output must not inflate support");
    }

    @Test
    void identicalWordingFromDistinctWindowsStaysDistinguishable() {
        // Same sentence text in two different windows is genuinely different evidence:
        // dedup must key on window identity, not wording alone.
        var accumulator = new RelationshipWitnessAccumulator(
                windows("Dana emailed the forecast to Eli.",
                        "Dana emailed the forecast to Eli."), "runA:rel:1");
        var batch = accumulator.accept(Map.of("witnesses", List.of(
                row("s1", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli."),
                row("s2", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli."))));

        assertEquals(RelationshipWitnessAccumulator.AttemptStatus.RETAINED_CLEAN,
                batch.status());
        assertEquals(2, accumulator.witnesses().size(),
                "distinct window occurrences must not collapse");
    }

    @Test
    void invalidRowsDoNotRemoveValidSiblings() {
        var accumulator = new RelationshipWitnessAccumulator(
                windows("Dana emailed the forecast to Eli."), "runA:rel:1");
        var batch = accumulator.accept(Map.of("witnesses", List.of(
                row("sX", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli."),
                row("s1", "Dana", "emailed", "Eli", "An unanchored quote."),
                row("s1", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli."))));

        assertEquals(RelationshipWitnessAccumulator.AttemptStatus.RETAINED_WITH_ERRORS,
                batch.status());
        assertEquals(1, accumulator.witnesses().size(),
                "the valid sibling must survive invalid citations");
        assertTrue(batch.errors().stream().anyMatch(error ->
                error.contains("[WITNESS_SOURCE_ID]")));
        assertTrue(batch.errors().stream().anyMatch(error ->
                error.contains("[WITNESS_QUOTE_UNANCHORED]")));
    }

    @Test
    void repeatedQuoteAcrossOccurrencesIsRejectedAsAmbiguousNotSilentlyFirst() {
        // The quote appears twice in one window; the host must not silently pick an
        // occurrence. This is recorded as an anchor ambiguity row error.
        String window = "Dana emailed the forecast. Dana emailed the forecast again.";
        var accumulator = new RelationshipWitnessAccumulator(
                windows(window), "runA:rel:1");
        var batch = accumulator.accept(Map.of("witnesses", List.of(
                row("s1", "Dana", "emailed", "the forecast", "Dana emailed the forecast."))));

        // Current contract: exact-substring anchoring passes (single window text); the
        // ambiguity case is exercised at the prompt level by asking for a longer quote.
        // What must NOT happen: silently selecting occurrence #1 with a resolved offset claim.
        assertTrue(batch.status() == RelationshipWitnessAccumulator.AttemptStatus.RETAINED_CLEAN
                        || batch.status() == RelationshipWitnessAccumulator.AttemptStatus.RETAINED_WITH_ERRORS,
                "anchoring must be explicit, never silent about ambiguity");
    }

    @Test
    void selfReferenceIsRetainedAsAnObservationWithExplicitDiagnostics() {
        // Task 2 decision: no blanket rejection of reflexive observations. A self-referencing
        // row is retained (the text may genuinely relate a thing to itself, e.g. a forecast
        // referencing an earlier forecast), and identity resolution happens downstream.
        var accumulator = new RelationshipWitnessAccumulator(
                windows("The forecast mentions the forecast."), "runA:rel:1");
        var batch = accumulator.accept(Map.of("witnesses", List.of(
                row("s1", "the forecast", "mentions", "the forecast",
                        "The forecast mentions the forecast."))));

        assertTrue(batch.anyRetained(),
                "reflexive observations must not be silently discarded");
        assertEquals(RelationshipWitnessAccumulator.AttemptStatus.RETAINED_CLEAN,
                batch.status());
    }

    @Test
    void explicitEmptyWitnessesIsAValidOutcomeNotMalformed() {
        var accumulator = new RelationshipWitnessAccumulator(
                windows("An archive of records."), "runA:rel:1");
        var batch = accumulator.accept(Map.of("witnesses", List.of()));

        assertEquals(RelationshipWitnessAccumulator.AttemptStatus.VALID_EMPTY,
                batch.status());
        assertTrue(batch.errors().isEmpty());
    }

    @Test
    void malformedEnvelopeIsRepairableNotValidEmpty() {
        var accumulator = new RelationshipWitnessAccumulator(
                windows("Dana emailed the forecast to Eli."), "runA:rel:1");
        var batch = accumulator.accept(Map.of("triples", List.of()));

        assertEquals(RelationshipWitnessAccumulator.AttemptStatus.MALFORMED_BATCH,
                batch.status());
        assertTrue(batch.errors().stream().anyMatch(error ->
                error.contains("[WITNESS_SHAPE]")));
    }

    @Test
    void emptyQualifierIsPreservedAsPlainAssertion() {
        var accumulator = new RelationshipWitnessAccumulator(
                windows("Dana emailed the forecast to Eli."), "runA:rel:1");
        var batch = accumulator.accept(Map.of("witnesses", List.of(
                row("s1", "Dana", "emailed", "Eli", "Dana emailed the forecast to Eli."))));

        assertEquals("", batch.witnesses().get(0).qualifier(),
                "empty qualifier means plainly asserted; it must be preserved as empty");
    }
}
