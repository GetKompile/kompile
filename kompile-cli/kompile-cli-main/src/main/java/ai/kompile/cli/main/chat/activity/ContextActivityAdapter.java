package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.main.chat.context.ConversationLedger;

import java.util.List;

/** Read-only adapter for retained context events and all compaction occurrences. */
public final class ContextActivityAdapter implements ActivityEvidenceReader {
    private final ConversationLedger ledger;

    public ContextActivityAdapter(ConversationLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() {
        return "context-ledger";
    }

    @Override
    public ActivityEvidence read(ActivityIdentity identity, ActivityReadBudget budget) {
        if (identity == null) return ActivityEvidence.unavailable(id(), "Context identity is unavailable");
        if (ledger == null || !ledger.hasDurableState()) {
            return ActivityEvidence.unavailable(id(), "Context ledger is unavailable");
        }
        ConversationLedger.Snapshot snapshot = ledger.snapshot();
        long compactions = snapshot.checkpoint() == null ? 0L : 1L;
        ActivitySourceRef source = ActivitySourceRef.logical("kompile", identity.conversationId(), "context-ledger");
        return new ActivityEvidence(id(), ActivityCoverage.COMPLETE, null, null,
                ActivityMetric.unknown(id()), null, ActivityMetric.observed(compactions, id()),
                null, null, List.of(source), List.of(),
                java.util.Map.of("events", Integer.toString(snapshot.allEvents().size())), List.of());
    }
}
