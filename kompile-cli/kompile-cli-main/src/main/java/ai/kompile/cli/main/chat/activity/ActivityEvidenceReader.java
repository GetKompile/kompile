package ai.kompile.cli.main.chat.activity;

import java.util.List;

/** Adapter contract for one existing activity producer/store. */
@FunctionalInterface
public interface ActivityEvidenceReader {
    ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget);

    default String id() {
        return getClass().getSimpleName();
    }

    /** On-demand detail payloads; list views must not call this method. */
    default List<String> details(ActivityIdentity identity, ActivityReadBudget budget) {
        return List.of();
    }
}
