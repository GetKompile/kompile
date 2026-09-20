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

    record BatchResult(List<RowOutcome> rows,
            List<RelationshipWitness> witnesses,
            List<String> errors,
            int observedRowCount) {
        boolean anyRetained() {
            return !witnesses.isEmpty();
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
            return new BatchResult(List.of(), List.of(), errors, 0);
        }
        if (rawRows.isEmpty()) {
            // Valid emptiness: the model abstains because the windows state no relationships.
            // Distinct from a malformed response; callers must not retry this.
            return new BatchResult(List.of(), List.of(), List.of(), 0);
        }
        Set<String> batchIds = new LinkedHashSet<>();
        for (int index = 0; index < rawRows.size(); index++) {
            String path = "witnesses[" + index + "]";
            try {
                RelationshipWitness witness = parseRow(path, rawRows.get(index), batchIds);
                if (witnesses.contains(witness)) {
                    // Duplicate observation of the same evidence: never independent support.
                    rows.add(new RowOutcome(index, null,
                            "[WITNESS_DUPLICATE] " + path + " repeats an already retained witness"));
                    continue;
                }
                String witnessId = scopedId(index);
                RelationshipWitness identified = new RelationshipWitness(witnessId,
                        witness.windowId(), witness.sourceId(), witness.subject(),
                        witness.predicateText(), witness.object(), witness.quote(),
                        witness.qualifier());
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
        return new BatchResult(List.copyOf(rows), List.copyOf(witnesses), errors, rawRows.size());
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
        if (subject.equalsIgnoreCase(object) && subject.length() <= MAX_FIELD_CHARS) {
            // Self-referencing observations carry no relationship information; retained as
            // an explicit row error rather than silently dropped, so the model can repair.
            throw new IllegalArgumentException("[WITNESS_SELF_REFERENCE] subject and object "
                    + "must name different mentions");
        }
        String qualifier = fields.get("qualifier") == null ? ""
                : fields.get("qualifier").toString().trim();
        if (qualifier.length() > RelationshipWitness.MAX_QUALIFIER_CHARS) {
            throw new IllegalArgumentException("[WITNESS_QUALIFIER_TOO_LONG] qualifier must "
                    + "be at most " + RelationshipWitness.MAX_QUALIFIER_CHARS + " characters");
        }
        return new RelationshipWitness("pending", sourceId, sourceId,
                subject, predicateText, object, quote, qualifier);
    }

    /** Globally scoped window id for the stored witness: prompt-local ids ("s1") collide
     * across requests, so stored identity embeds the run/batch scope. */
    private String scopedId(int rowIndex) {
        return windowScope + "-w" + (witnesses.size() + 1) + "r" + rowIndex;
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
