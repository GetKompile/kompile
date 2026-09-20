package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import java.util.*;

/** Batch-local validated candidates across bounded retries. Not a semantic truth evaluator.
 * Related parents resolve to the most specific established parent; unrelated conflicts
 * remain quarantined for this batch. Row order never chooses the surviving declaration.
 * The existing lexical/consolidation stages still decide final admission.
 */
final class NodeProposalAccumulator {
    private final GraphSchema established;
    private final Map<String, NodeType> nodes = new LinkedHashMap<>();
    private final Map<CorpusSchemaUnifier.TypeProposal, List<CorpusSchemaResponseParser.NodeEvidence>> evidence = new LinkedHashMap<>();
    private final Set<String> quarantined = new LinkedHashSet<>();
    private final List<String> rejected = new ArrayList<>();
    private final Map<String, NodeType> returned = new LinkedHashMap<>();

    void beginAttempt() { returned.clear(); }
    GraphSchema returnedSchema() { return new GraphSchema(new ArrayList<>(returned.values()), null, null); }

    NodeProposalAccumulator(GraphSchema established) { this.established = established; }

    CorpusSchemaResponseParser.ParseResult accept(Map<String,Object> arguments, Map<String,String> windows) {
        beginAttempt();
        if (arguments == null || !arguments.keySet().equals(Set.of("nodeTypes"))
                || !(arguments.get("nodeTypes") instanceof List<?> rows) || rows.size() > 32) {
            String error = "[SCHEMA_TYPE_ONLY] nodeTypes must be the only field, an array of at most 32 objects";
            rejected.add(error);
            return new CorpusSchemaResponseParser.ParseResult(null, List.of(error));
        }
        List<String> errors = new ArrayList<>();
        for (int i=0; i<rows.size(); i++) {
            // singletonList deliberately permits null so the strict parser reports malformed rows.
            var result = CorpusSchemaResponseParser.parseNodeDiscovery(
                    Map.of("nodeTypes", Collections.singletonList(rows.get(i))), windows, established.getAllNodeLabels());
            if (!result.parsed().valid()) {
                for (String error : result.parsed().errors())
                    errors.add("row["+i+"]: " + error.replace("nodeTypes[0]", "nodeTypes["+i+"]"));
                continue;
            }
            for (NodeType node : result.parsed().schema().getNodeTypes()) {
                returned.putIfAbsent(node.getLabel(), node);
                if (established.getAllNodeLabels().contains(node.getLabel())) continue;
                var validation = CorpusSchemaOverlayValidator.validateTypesOnly(established,
                        new GraphSchema(List.of(node), null, null));
                if (!validation.valid()) {
                    errors.add("nodeTypes["+i+"]: " + String.join("; ",validation.errors()));
                    continue;
                }
                var proposal = new CorpusSchemaUnifier.TypeProposal(node.getLabel(),node.getParentType());
                var spans = result.evidence().get(proposal);
                if (spans == null || spans.isEmpty()) {
                    errors.add("nodeTypes["+i+"]: [SCHEMA_NODE_EVIDENCE] no validated evidence for " + proposal.label());
                    continue;
                }
                String label = proposal.label();
                if (quarantined.contains(label)) {
                    errors.add("[SCHEMA_QUARANTINED] " + label);
                    continue;
                }
                NodeType prior = nodes.get(label);
                if (prior != null && !Objects.equals(prior.getParentType(),node.getParentType())) {
                    if (established.isNodeTypeAssignableTo(prior.getParentType(), node.getParentType())) {
                        node = prior;
                    } else if (!established.isNodeTypeAssignableTo(node.getParentType(), prior.getParentType())) {
                        quarantine(label);
                        errors.add("[SCHEMA_PARENT_CONFLICT] " + label + " parents=" + prior.getParentType()+","+node.getParentType());
                        continue;
                    }
                    proposal = new CorpusSchemaUnifier.TypeProposal(label, node.getParentType());
                }
                var merged = new LinkedHashSet<CorpusSchemaResponseParser.NodeEvidence>();
                evidence.forEach((key, retained) -> { if (key.label().equals(label)) merged.addAll(retained); });
                merged.addAll(spans);
                if (merged.size()>2) {
                    quarantine(label);
                    errors.add("[SCHEMA_NODE_EVIDENCE] " + label + " has more than 2 distinct evidence spans across rows/retries");
                    continue;
                }
                nodes.put(label,node);
                evidence.keySet().removeIf(key -> key.label().equals(label));
                evidence.put(proposal,List.copyOf(merged));
            }
        }
        rejected.addAll(errors);
        // A later clean response cannot silently resolve a previously contradictory declaration.
        for (String label : quarantined) errors.add("[SCHEMA_QUARANTINED] " + label);
        return new CorpusSchemaResponseParser.ParseResult(snapshot(), errors);
    }

    private void quarantine(String label) {
        quarantined.add(label);
        nodes.remove(label);
        evidence.keySet().removeIf(p -> p.label().equals(label));
    }
    GraphSchema snapshot() { return new GraphSchema(new ArrayList<>(nodes.values()), null, null); }
    Set<CorpusSchemaUnifier.TypeProposal> proposals() { return Set.copyOf(evidence.keySet()); }
    Map<CorpusSchemaUnifier.TypeProposal, List<CorpusSchemaResponseParser.NodeEvidence>> evidence() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
    List<String> rejected() { return List.copyOf(rejected); }
    Set<String> quarantined() { return Set.copyOf(quarantined); }
}
