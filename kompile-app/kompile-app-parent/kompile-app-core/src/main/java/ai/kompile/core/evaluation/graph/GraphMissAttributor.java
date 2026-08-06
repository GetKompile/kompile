package ai.kompile.core.evaluation.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Attributes missing gold atoms from decision trace evidence. */
public final class GraphMissAttributor {

    private static final Comparator<GraphDecisionTraceEvent> CAUSAL_ORDER =
            Comparator.comparing(GraphDecisionTraceEvent::stage,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Attributes each requested gold atom, retaining the supplied atom order.
     *
     * <p>Only events with a concrete, stage-aligned miss reason are causal evidence. When several
     * sources support the same atom, attribution follows the source path that progressed furthest
     * before failing; the earliest failure on that selected path is primary. The complete trail is
     * retained in causal order so evidence from every source remains inspectable.</p>
     */
    public List<GraphMissAttribution> attribute(
            Collection<String> goldAtoms,
            Collection<GraphDecisionTraceEvent> events) {
        Map<String, List<GraphDecisionTraceEvent>> byAtom = new LinkedHashMap<>();
        if (events != null) {
            for (GraphDecisionTraceEvent event : events) {
                if (isCausal(event)) {
                    byAtom.computeIfAbsent(event.atom(), ignored -> new ArrayList<>()).add(event);
                }
            }
        }

        List<GraphMissAttribution> result = new ArrayList<>();
        if (goldAtoms == null) {
            return List.of();
        }
        for (String atom : goldAtoms) {
            List<GraphDecisionTraceEvent> trail =
                    new ArrayList<>(byAtom.getOrDefault(atom, List.of()));
            trail.sort(CAUSAL_ORDER);
            if (trail.isEmpty()) {
                result.add(new GraphMissAttribution(
                        atom, null, GraphMissReason.UNATTRIBUTED, List.of()));
            } else {
                GraphDecisionTraceEvent primary = deepestSupportingPathPrimary(trail);
                result.add(new GraphMissAttribution(
                        atom, primary.stage(), primary.reason(), trail));
            }
        }
        return List.copyOf(result);
    }

    private static GraphDecisionTraceEvent deepestSupportingPathPrimary(
            List<GraphDecisionTraceEvent> trail) {
        Map<SourcePath, List<GraphDecisionTraceEvent>> bySource = new LinkedHashMap<>();
        for (GraphDecisionTraceEvent event : trail) {
            bySource.computeIfAbsent(SourcePath.of(event), ignored -> new ArrayList<>()).add(event);
        }
        GraphDecisionTraceEvent selected = null;
        for (List<GraphDecisionTraceEvent> sourceTrail : bySource.values()) {
            sourceTrail.sort(CAUSAL_ORDER);
            GraphDecisionTraceEvent sourceFailure = sourceTrail.get(0);
            if (selected == null || sourceFailure.stage().compareTo(selected.stage()) > 0) {
                selected = sourceFailure;
            }
        }
        return selected;
    }

    private record SourcePath(String sourceId, String documentId, String chunkId, String shardId) {
        private static SourcePath of(GraphDecisionTraceEvent event) {
            return new SourcePath(event.sourceId(), event.documentId(), event.chunkId(), event.shardId());
        }
    }

    private static boolean isCausal(GraphDecisionTraceEvent event) {
        return event != null
                && event.atom() != null
                && event.stage() != null
                && event.reason() != null
                && event.reason() != GraphMissReason.UNATTRIBUTED
                && event.reason().stage() == event.stage();
    }
}
