package ai.kompile.cli.main.chat.activity;

import ai.kompile.app.services.diffindex.DiffIndexEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Adapter boundary for the existing diff index; it never refreshes the index during a read. */
public final class DiffIndexActivityAdapter implements ActivityEvidenceReader {
    private final Supplier<List<DiffObservation>> source;
    private final Supplier<List<String>> detailSource;

    public DiffIndexActivityAdapter(Supplier<List<DiffObservation>> source) {
        this(source, List::of);
    }

    private DiffIndexActivityAdapter(Supplier<List<DiffObservation>> source,
                                     Supplier<List<String>> detailSource) {
        this.source = source;
        this.detailSource = detailSource == null ? List::of : detailSource;
    }

    /** Bridge the existing diff-index model without copying old/new/unified payloads. */
    public static DiffIndexActivityAdapter fromEntries(Supplier<List<DiffIndexEntry>> source) {
        return new DiffIndexActivityAdapter(() -> {
            if (source == null) return List.of();
            List<DiffIndexEntry> entries = source.get();
            if (entries == null) return List.of();
            return entries.stream().filter(java.util.Objects::nonNull).map(entry -> {
                ActivitySourceRef ref = ActivitySourceRef.logical("diff-index",
                        entry.getId() == null ? "unknown" : entry.getId(), "diff");
                // DiffIndexEntry has no result/provenance field. A stored diff is not proof that
                // the edit applied, so leave the result explicitly unknown.
                return new DiffObservation(entry.getId(), entry.getSessionId(), false, false, false,
                        (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.getLinesAdded())),
                        (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.getLinesRemoved())), ref);
            }).toList();
        }, () -> {
            if (source == null) return List.of();
            List<DiffIndexEntry> entries = source.get();
            if (entries == null) return List.of();
            return entries.stream().filter(java.util.Objects::nonNull).map(entry ->
                    "diff [" + (entry.getId() == null ? "unknown" : entry.getId()) + "] session="
                            + (entry.getSessionId() == null ? "unknown" : entry.getSessionId()) + " "
                            + (entry.getFilePath() == null ? "" : entry.getFilePath()) + "\n"
                            + (entry.getUnifiedDiff() == null ? "(unified diff unavailable)" : entry.getUnifiedDiff()))
                    .toList();
        });
    }

    @Override
    public String id() {
        return "diff-index";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Diff index identity is unavailable");
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        if (source == null) return ActivityEvidence.unavailable(id(), "Diff index is unavailable");
        List<DiffObservation> observations;
        try {
            observations = source.get();
        } catch (RuntimeException failure) {
            return ActivityEvidence.unavailable(id(), "Diff index read failed: " + failure.getMessage());
        }
        int edits = 0;
        int failed = 0;
        int unknown = 0;
        int examined = 0;
        boolean bounded = false;
        long indexedAdded = 0L;
        long indexedRemoved = 0L;
        long appliedAdded = 0L;
        long appliedRemoved = 0L;
        List<ActivitySourceRef> refs = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<DiffObservation> available = observations == null ? List.of() : observations;
        for (DiffObservation observation : available) {
            if (examined++ >= budget.maxItems()) {
                bounded = true;
                break;
            }
            if (observation == null || !identity.conversationId().equals(observation.sessionId())) continue;
            if (observation.id() != null && !seen.add(observation.id())) continue;
            indexedAdded += Math.max(0L, observation.linesAdded());
            indexedRemoved += Math.max(0L, observation.linesRemoved());
            if (observation.successful() && observation.resultKnown() && !observation.dryRun()) {
                edits++;
                appliedAdded += Math.max(0L, observation.linesAdded());
                appliedRemoved += Math.max(0L, observation.linesRemoved());
                if (observation.source() != null) refs.add(observation.source());
            } else if (observation.resultKnown()) {
                failed++;
            } else {
                unknown++;
            }
        }
        ActivityCoverage coverage = bounded || unknown > 0 ? ActivityCoverage.PARTIAL : ActivityCoverage.COMPLETE;
        List<String> warnings = unknown > 0
                ? List.of("Diff index entries do not include result provenance; applied edits are unverified")
                : bounded ? List.of("Diff index read stopped at the configured item bound") : List.of();
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("indexedLinesAdded", Long.toString(indexedAdded));
        attributes.put("indexedLinesRemoved", Long.toString(indexedRemoved));
        attributes.put("appliedLinesAdded", Long.toString(appliedAdded));
        attributes.put("appliedLinesRemoved", Long.toString(appliedRemoved));
        attributes.put("unknownResults", Integer.toString(unknown));
        return new ActivityEvidence(id(), coverage, null, null,
                ActivityMetric.unknown(id()), ActivityMetric.observed(edits, id()),
                ActivityMetric.observed(failed, id()), null, null, refs, List.of(),
                attributes, warnings);
    }

    @Override
    public List<String> details(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null || detailSource == null) return List.of();
        budget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        try {
            String sessionMarker = "session=" + identity.conversationId();
            return detailSource.get().stream().filter(detail -> detail != null
                            && detail.contains(sessionMarker))
                    .limit(budget.maxItems()).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    public record DiffObservation(String id, String sessionId, boolean successful, boolean resultKnown,
                                  boolean dryRun, int linesAdded, int linesRemoved, ActivitySourceRef source) {
        public DiffObservation(String id, String sessionId, boolean successful, boolean dryRun,
                               int linesAdded, int linesRemoved, ActivitySourceRef source) {
            this(id, sessionId, successful, true, dryRun, linesAdded, linesRemoved, source);
        }
    }
}
