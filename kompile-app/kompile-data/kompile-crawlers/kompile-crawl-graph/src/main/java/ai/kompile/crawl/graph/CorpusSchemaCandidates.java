package ai.kompile.crawl.graph;

import java.util.List;

final class CorpusSchemaCandidates {

    private CorpusSchemaCandidates() {
    }

    record NodeCandidate(
            String candidateKey,
            List<String> surfaceForms,
            List<String> categories,
            int supportCount,
            double maximumConfidence,
            List<String> passageIds,
            List<String> exampleContexts) {

        NodeCandidate {
            surfaceForms = surfaceForms == null ? List.of() : List.copyOf(surfaceForms);
            categories = categories == null ? List.of() : List.copyOf(categories);
            passageIds = passageIds == null ? List.of() : List.copyOf(passageIds);
            exampleContexts = exampleContexts == null ? List.of() : List.copyOf(exampleContexts);
        }
    }

    record RelationshipCandidate(
            String candidateKey,
            List<String> surfaceForms,
            List<String> sourceConcepts,
            List<String> targetConcepts,
            int supportCount,
            double maximumStrength,
            List<String> passageIds) {

        RelationshipCandidate {
            surfaceForms = surfaceForms == null ? List.of() : List.copyOf(surfaceForms);
            sourceConcepts = sourceConcepts == null ? List.of() : List.copyOf(sourceConcepts);
            targetConcepts = targetConcepts == null ? List.of() : List.copyOf(targetConcepts);
            passageIds = passageIds == null ? List.of() : List.copyOf(passageIds);
        }
    }

    record Inventory(
            List<NodeCandidate> nodeCandidates,
            List<RelationshipCandidate> relationshipCandidates) {

        Inventory {
            nodeCandidates = nodeCandidates == null
                    ? List.of()
                    : List.copyOf(nodeCandidates);

            relationshipCandidates = relationshipCandidates == null
                    ? List.of()
                    : List.copyOf(relationshipCandidates);
        }

        boolean isEmpty() {
            return nodeCandidates.isEmpty()
                    && relationshipCandidates.isEmpty();
        }
    }
}
