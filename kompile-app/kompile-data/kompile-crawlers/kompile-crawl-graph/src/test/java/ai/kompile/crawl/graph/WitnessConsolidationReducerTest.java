package ai.kompile.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic regression tests for the witness-consolidation reducer, driven by the
 * recorded live failure (fpna seed42 witness-on run, proc-106): three rows proposed
 * IS_MONTHLY_CLOSE (AFFILIATION, then ATTRIBUTION twice) and the whole consolidation
 * attempt exhausted with SCHEMA_DUPLICATE_TYPE + SCHEMA_CONNECTION_FAMILY_CONFLICT,
 * freezing zero relationship vocabulary.
 *
 * The live payload WAS saved in the run log, so the replay case below is byte-exact,
 * not reconstructed. No live model is called; these prove control flow only.
 */
class WitnessConsolidationReducerTest {

    private static final String LIVE_WITNESS_A = "schema-ablation-sources:rel:1-9206374b";
    private static final String LIVE_WITNESS_B = "schema-ablation-sources:rel:1-db4503c2";
    private static final String LIVE_WITNESS_C = "schema-ablation-sources:rel:1-bd0b483d";

    private static final Set<String> TRUSTED_FAMILIES = Set.of(
            "AFFILIATION", "ATTRIBUTION", "COMMUNICATION", "DEPENDENCY", "REFERENCE");

    /** Byte-exact row set from the recorded live response (order preserved). */
    private static List<Map<String, Object>> liveLiveRows() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(Map.of("connectionFamily", "AFFILIATION", "type", "IS_MONTHLY_CLOSE",
                "witnessIds", List.of(LIVE_WITNESS_A)));
        rows.add(Map.of("connectionFamily", "ATTRIBUTION", "type", "IS_MONTHLY_CLOSE",
                "witnessIds", List.of(LIVE_WITNESS_B)));
        rows.add(Map.of("connectionFamily", "ATTRIBUTION", "type", "IS_MONTHLY_CLOSE",
                "witnessIds", List.of(LIVE_WITNESS_C)));
        return rows;
    }

    private static Set<String> liveWitnessInventory() {
        return Set.of(LIVE_WITNESS_A, LIVE_WITNESS_B, LIVE_WITNESS_C);
    }

    private static Map<String, Object> arguments(List<Map<String, Object>> rows) {
        return Map.of("relationshipTypes", rows);
    }

    private static CorpusSchemaResponseParser.WitnessConsolidationResult parse(
            List<Map<String, Object>> rows) {
        return CorpusSchemaResponseParser.parseWitnessConsolidation(
                arguments(rows), liveWitnessInventory(), TRUSTED_FAMILIES, Set.of());
    }

    private static String sortedIds(List<String> ids) {
        return String.join(",", new TreeSet<>(ids));
    }

    /** Case A: same label + same family => one retained predicate, union of citations. */
    @Test
    void sameLabelSameFamilyMergesCitations() {
        var result = parse(List.of(
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "AFFILIATION",
                        "witnessIds", List.of(LIVE_WITNESS_A, LIVE_WITNESS_B)),
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "AFFILIATION",
                        "witnessIds", List.of(LIVE_WITNESS_B, LIVE_WITNESS_C))));

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(1, result.supported().size());
        assertEquals("IS_MONTHLY_CLOSE", result.supported().get(0).type().getType());
        assertEquals("AFFILIATION", result.supported().get(0).type().getConnectionFamily());
        // Union of unique citations; duplicating a row must not double-count support.
        assertEquals(sortedIds(List.of(LIVE_WITNESS_A, LIVE_WITNESS_B, LIVE_WITNESS_C)),
                sortedIds(result.supported().get(0).witnessIds()));
    }

    /** Case B (live failure): same label, conflicting valid families => quarantine. */
    @Test
    void sameLabelConflictingFamiliesQuarantinesLabel() {
        var result = parse(liveLiveRows());

        assertTrue(result.supported().isEmpty(),
                "the conflicted label must not be admitted");
        assertTrue(result.errors().stream().anyMatch(error ->
                        error.contains("IS_MONTHLY_CLOSE")
                                && error.contains("AFFILIATION")
                                && error.contains("ATTRIBUTION")),
                "diagnostics must name the label and both conflicting families: "
                        + result.errors());
    }

    /** Case C: conflict + unrelated valid sibling => sibling survives, label does not. */
    @Test
    void conflictedLabelDoesNotPoisonValidSibling() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>(liveLiveRows());
        rows.add(Map.of("type", "SENT_BY", "connectionFamily", "ATTRIBUTION",
                "witnessIds", List.of(LIVE_WITNESS_A)));

        var result = parse(rows);

        assertEquals(List.of("SENT_BY"), result.supported().stream()
                .map(supported -> supported.type().getType()).sorted().toList());
        assertEquals(sortedIds(List.of(LIVE_WITNESS_A)),
                sortedIds(result.supported().get(0).witnessIds()));
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("IS_MONTHLY_CLOSE")));
    }

    /** Case D: family A, B, then A again => the third row cannot resurrect the label. */
    @Test
    void lateReappearanceCannotResurrectQuarantinedLabel() {
        var result = parse(List.of(
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "AFFILIATION",
                        "witnessIds", List.of(LIVE_WITNESS_A)),
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "ATTRIBUTION",
                        "witnessIds", List.of(LIVE_WITNESS_B)),
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "AFFILIATION",
                        "witnessIds", List.of(LIVE_WITNESS_C))));

        assertTrue(result.supported().isEmpty(),
                "the quarantined label must stay quarantined regardless of later rows");
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("IS_MONTHLY_CLOSE")));
    }

    /** Case E: row order must not change the retained set, support, or diagnostics. */
    @Test
    void rowOrderIndependence() {
        var forward = parse(liveLiveRows());
        var reversed = parse(liveLiveRows().stream().sorted(
                java.util.Comparator.comparingInt(value -> -((List<?>) ((Map<?, ?>) value)
                        .get("witnessIds")).size())).toList());

        assertEquals(retainedFingerprint(forward), retainedFingerprint(reversed),
                "permutation must not change the outcome");
    }

    private static String retainedFingerprint(CorpusSchemaResponseParser.WitnessConsolidationResult result) {
        List<String> labels = result.supported().stream()
                .map(supported -> supported.type().getType() + ":"
                        + supported.type().getConnectionFamily() + ":"
                        + sortedIds(supported.witnessIds()))
                .sorted().toList();
        return "retained=" + labels + "; errors=" + new TreeSet<>(result.errors());
    }

    /** Case F: malformed row + valid row => invalid rejected, sibling retained. */
    @Test
    void malformedRowDoesNotPoisonValidSibling() {
        var result = parse(List.of(
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "AFFILIATION",
                        "witnessIds", List.of("nonexistent-witness")),
                Map.of("type", "SENT_BY", "connectionFamily", "ATTRIBUTION",
                        "witnessIds", List.of(LIVE_WITNESS_A))));

        assertEquals(List.of("SENT_BY"), result.supported().stream()
                .map(supported -> supported.type().getType()).toList());
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("nonexistent-witness")));
    }

    /** Case G: substring labels — rejecting the longer must not remove the shorter. */
    @Test
    void substringLabelsAreIndependent() {
        var result = parse(List.of(
                Map.of("type", "IS_MONTHLY_CLOSE", "connectionFamily", "AFFILIATION",
                        "witnessIds", List.of(LIVE_WITNESS_A)),
                Map.of("type", "MONTHLY_CLOSE", "connectionFamily", "ATTRIBUTION",
                        "witnessIds", List.of(LIVE_WITNESS_B))));

        // IS_MONTHLY_CLOSE conflicts with MONTHLY_CLOSE only in the substring sense; the
        // reducer keys by exact label, so both must be evaluated independently.
        assertEquals(Set.of("IS_MONTHLY_CLOSE", "MONTHLY_CLOSE"),
                result.supported().stream()
                        .map(supported -> supported.type().getType())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    /** Case H: a model row cannot redefine a configured authoritative type. */
    @Test
    void authoritativeTypesAreAccountedNotRedefined() {
        var result = CorpusSchemaResponseParser.parseWitnessConsolidation(
                Map.of("relationshipTypes", List.of(
                        Map.of("type", "CONFIGURED_REL", "connectionFamily", "ATTRIBUTION",
                                "witnessIds", List.of(LIVE_WITNESS_A)))),
                liveWitnessInventory(), TRUSTED_FAMILIES, Set.of("CONFIGURED_REL"));

        assertTrue(result.supported().isEmpty(),
                "discovery must not re-emit an authoritative type");
        assertTrue(result.errors().isEmpty(),
                "authoritative redefinition is accounting, not a row failure");
    }

    /**
     * Byte-exact live replay of the recorded proc-106 consolidation payload (both attempts):
     * the reducer must produce a deterministic outcome without the SCHEMA_DUPLICATE_TYPE /
     * SCHEMA_CONNECTION_FAMILY_CONFLICT whole-batch exhaustion.
     */
    @Test
    void livePayloadReplayIsDeterministic() {
        var attempt1 = parse(liveLiveRows());
        // Attempt 2 from the log: same label/family conflict with a duplicated citation row.
        var attempt2 = parse(List.of(
                Map.of("connectionFamily", "AFFILIATION", "type", "IS_MONTHLY_CLOSE",
                        "witnessIds", List.of(LIVE_WITNESS_A)),
                Map.of("connectionFamily", "ATTRIBUTION", "type", "IS_MONTHLY_CLOSE",
                        "witnessIds", List.of(LIVE_WITNESS_B)),
                Map.of("connectionFamily", "ATTRIBUTION", "type", "IS_MONTHLY_CLOSE",
                        "witnessIds", List.of(LIVE_WITNESS_B, LIVE_WITNESS_C))));

        assertEquals(retainedFingerprint(attempt1), retainedFingerprint(attempt2));
        assertTrue(attempt1.supported().isEmpty(),
                "the conflicted live label stays quarantined in both attempts");
    }

    /** Deterministic serialization for equivalent consolidated results. */
    @Test
    void equivalentResultsSerializeDeterministically() {
        var left = parse(liveLiveRows());
        var right = parse(liveLiveRows());
        assertEquals(new LinkedHashMap<>(Map.of(
                        "supported", left.supported().stream()
                                .map(supported -> supported.type().getType()).sorted().toList(),
                        "errors", new TreeSet<>(left.errors()))),
                new LinkedHashMap<>(Map.of(
                        "supported", right.supported().stream()
                                .map(supported -> supported.type().getType()).sorted().toList(),
                        "errors", new TreeSet<>(right.errors()))));
    }
}
