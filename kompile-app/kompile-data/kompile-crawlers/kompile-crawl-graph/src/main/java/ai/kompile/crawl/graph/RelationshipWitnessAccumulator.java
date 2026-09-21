package ai.kompile.crawl.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects host-validated relationship witnesses for one discovery batch with per-row
 * isolation: a malformed row produces a diagnostic and never discards its valid siblings.
 * Duplicate observations of the same window/quote/roles (repeated model output) are
 * deduplicated and never inflate support.
 */
final class RelationshipWitnessAccumulator {

    /** One attempted discovery row: the parsed witness or the reason it was dropped. */
    record RowOutcome(int rowIndex, RelationshipWitness witness, String error) {
        boolean retained() {
            return witness != null;
        }
    }

    /** Explicit final outcome of one batch attempt — never inferred from row counts. */
    enum AttemptStatus {
        /** Required tool call present with witnesses=[]: valid abstention, never retried. */
        VALID_EMPTY,
        /** At least one witness retained and no structural row errors. */
        RETAINED_CLEAN,
        /** At least one witness retained but some rows failed; repair may recover them. */
        RETAINED_WITH_ERRORS,
        /** Every returned row was invalid or duplicate: repair with row diagnostics. */
        ALL_ROWS_DROPPED,
        /** Envelope malformed: repair with the shape diagnostic. */
        MALFORMED_BATCH
    }

    record BatchResult(List<RowOutcome> rows,
            List<RelationshipWitness> witnesses,
            List<String> errors,
            int observedRowCount,
            AttemptStatus status) {
        boolean anyRetained() {
            return !witnesses.isEmpty();
        }

        boolean repairable() {
            return status == AttemptStatus.ALL_ROWS_DROPPED
                    || status == AttemptStatus.MALFORMED_BATCH
                    || status == AttemptStatus.RETAINED_WITH_ERRORS;
        }
    }

    private static final int MAX_WITNESSES_PER_CALL = 32;
    private static final int MAX_FIELD_CHARS = 160;
    private static final int MAX_QUOTE_CHARS = 1_024;

    private final Map<String, String> submittedWindows;
    private final String windowScope;
    private final List<RelationshipWitness> witnesses = new ArrayList<>();
    private final Set<String> retainedIds = new LinkedHashSet<>();

    RelationshipWitnessAccumulator(Map<String, String> submittedWindows, String windowScope) {
        if (submittedWindows == null || submittedWindows.isEmpty()) {
            throw new IllegalArgumentException("submittedWindows must not be empty");
        }
        if (windowScope == null || windowScope.isBlank()) {
            throw new IllegalArgumentException("windowScope must not be blank");
        }
        this.submittedWindows = submittedWindows;
        this.windowScope = windowScope.trim();
    }

    /**
     * Parses one {@code submit_relationship_witnesses} arguments object. Row failures are
     * isolated: each row yields a {@link RowOutcome}; the batch is valid when at least one
     * row is retained and no row failed structurally (malformed batch shape still fails).
     */
    BatchResult accept(Map<String, Object> arguments) {
        List<RowOutcome> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (arguments == null || !arguments.keySet().equals(Set.of("witnesses"))
                || !(arguments.get("witnesses") instanceof List<?> rawRows)
                || rawRows.size() > MAX_WITNESSES_PER_CALL) {
            String error = "[WITNESS_SHAPE] witnesses must be the only field and an array "
                    + "of at most " + MAX_WITNESSES_PER_CALL + " objects";
            errors.add(error);
            return new BatchResult(List.of(), List.of(), errors, 0,
                    AttemptStatus.MALFORMED_BATCH);
        }
        if (rawRows.isEmpty()) {
            // Valid emptiness: the model explicitly abstains because the windows state no
            // relationships. Distinct from a missing/malformed response; never retried.
            return new BatchResult(List.of(), List.of(), List.of(), 0,
                    AttemptStatus.VALID_EMPTY);
        }
        Set<String> batchIds = new LinkedHashSet<>();
        for (int index = 0; index < rawRows.size(); index++) {
            String path = "witnesses[" + index + "]";
            try {
                RelationshipWitness parsed = parseRow(path, rawRows.get(index), batchIds);
                // Scope BEFORE dedup: identity is host-scoped, so a retry of the same local
                // row collides with its earlier retained copy, while the same local id in a
                // different batch scope stays distinct.
                String scopedWindow = windowScope + ":" + parsed.windowId();
                RelationshipWitness identified = new RelationshipWitness(
                        scopedId(parsed),
                        scopedWindow, parsed.sourceId(), parsed.subject(),
                        parsed.predicateText(), parsed.object(), parsed.quote(),
                        parsed.qualifier());
                if (witnesses.contains(identified)) {
                    // Duplicate observation of the same evidence: never independent support.
                    rows.add(new RowOutcome(index, null,
                            "[WITNESS_DUPLICATE] " + path + " repeats an already retained witness"));
                    continue;
                }
                if (!retainedIds.add(identified.witnessId())) {
                    rows.add(new RowOutcome(index, null,
                            "[WITNESS_ID_COLLISION] " + path));
                    continue;
                }
                witnesses.add(identified);
                rows.add(new RowOutcome(index, identified, null));
            } catch (IllegalArgumentException invalid) {
                rows.add(new RowOutcome(index, null, path + ": " + invalid.getMessage()));
            }
        }
        rows.forEach(row -> {
            if (!row.retained()) {
                errors.add(row.error());
            }
        });
        boolean anyDropped = rows.stream().anyMatch(row -> !row.retained());
        AttemptStatus status = anyDropped
                ? AttemptStatus.RETAINED_WITH_ERRORS
                : AttemptStatus.RETAINED_CLEAN;
        return new BatchResult(List.copyOf(rows), List.copyOf(witnesses), errors,
                rawRows.size(), status);
    }

    List<RelationshipWitness> witnesses() {
        return List.copyOf(witnesses);
    }

    private RelationshipWitness parseRow(String path, Object raw, Set<String> batchIds) {
        if (!(raw instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("row must be an object");
        }
        Set<String> expected = Set.of(
                "sourceId", "subject", "predicateText", "object", "quote", "qualifier");
        if (!fields.keySet().equals(expected)) {
            throw new IllegalArgumentException("[WITNESS_FIELDS] row must contain exactly "
                    + sorted(expected));
        }
        String sourceId = text(fields.get("sourceId"));
        if (!submittedWindows.containsKey(sourceId)) {
            throw new IllegalArgumentException("[WITNESS_SOURCE_ID] unknown sourceId "
                    + quoted(sourceId) + "; expected one of " + sorted(submittedWindows.keySet()));
        }
        String quote = text(fields.get("quote"));
        if (quote.length() > MAX_QUOTE_CHARS) {
            throw new IllegalArgumentException("[WITNESS_QUOTE_TOO_LONG] quote must be at most "
                    + MAX_QUOTE_CHARS + " characters");
        }
        String window = submittedWindows.get(sourceId);
        if (!window.contains(quote)) {
            throw new IllegalArgumentException("[WITNESS_QUOTE_UNANCHORED] quote is not an "
                    + "exact substring of the cited window");
        }
        String subject = bounded(fields.get("subject"));
        String predicateText = bounded(fields.get("predicateText"));
        String object = bounded(fields.get("object"));
        // Reflexive observations (subject == object) are retained: a document can genuinely
        // relate a thing to a previous version of itself. Endpoint identity resolution and
        // self-edge policy belong to downstream stages, not a blanket discovery-time ban.
        String qualifier = fields.get("qualifier") == null ? ""
                : fields.get("qualifier").toString().trim();
        if (qualifier.length() > RelationshipWitness.MAX_QUALIFIER_CHARS) {
            throw new IllegalArgumentException("[WITNESS_QUALIFIER_TOO_LONG] qualifier must "
                    + "be at most " + RelationshipWitness.MAX_QUALIFIER_CHARS + " characters");
        }
        return new RelationshipWitness("pending", sourceId, sourceId,
                subject, predicateText, object, quote, qualifier);
    }

    /**
     * Content-stable globally scoped id: same evidence in a retry maps to the same id (dedup),
     * different evidence never collides regardless of row position.
     */
    private String scopedId(RelationshipWitness parsed) {
        String canonical = (parsed.windowId() + "|" + parsed.quote() + "|"
                + parsed.subject() + "|" + parsed.predicateText() + "|" + parsed.object())
                .toLowerCase(java.util.Locale.ROOT);
        String hash = Integer.toHexString(canonical.hashCode());
        return windowScope + "-" + hash;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("must be a nonblank string");
        }
        return text.trim();
    }

    private static String bounded(Object value) {
        String text = text(value);
        if (text.length() > MAX_FIELD_CHARS) {
            throw new IllegalArgumentException("must be at most " + MAX_FIELD_CHARS
                    + " characters");
        }
        return text;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().map(RelationshipWitnessAccumulator::quoted).toList();
    }

    private static String quoted(String value) {
        return '"' + value + '"';
    }

}
