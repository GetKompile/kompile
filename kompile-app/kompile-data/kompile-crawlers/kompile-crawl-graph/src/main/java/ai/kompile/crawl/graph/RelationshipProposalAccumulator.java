package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import java.util.*;

/** Retains structurally valid relation candidates, not admitted facts. Lexical and
 * consolidation gates still follow; conflicting connection families never pick a winner. */
final class RelationshipProposalAccumulator {
    private final GraphSchema established;
    private final Map<String,RelationshipType> retained = new LinkedHashMap<>();
    private final Map<String,RelationshipType> returned = new LinkedHashMap<>();
    private final Set<String> quarantined = new LinkedHashSet<>();
    private final List<String> rejected = new ArrayList<>();
    RelationshipProposalAccumulator(GraphSchema established) { this.established=established; }
    void beginAttempt() { returned.clear(); }
    GraphSchema returnedSchema() { return new GraphSchema(null,new ArrayList<>(returned.values()),null); }
    CorpusSchemaResponseParser.ParseResult accept(Map<String,Object> arguments) {
        beginAttempt();
        if(arguments==null || !arguments.keySet().equals(Set.of("relationshipTypes"))
                || !(arguments.get("relationshipTypes") instanceof List<?> rows) || rows.size()>32) {
            String error="[SCHEMA_TYPE_ONLY] relationshipTypes must be the only field, an array of at most 32 objects";
            rejected.add(error);
            return new CorpusSchemaResponseParser.ParseResult(null,List.of(error));
        }
        List<String> errors=new ArrayList<>();
        for(int i=0;i<rows.size();i++) {
            var parsed=CorpusSchemaUnifier.parseTypeArguments(Map.of("relationshipTypes",Collections.singletonList(rows.get(i))),
                    CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES);
            if(!parsed.valid()) { errors.add("relationshipTypes["+i+"]: "+String.join("; ",parsed.errors())); continue; }
            for(var relation:parsed.schema().getRelationshipTypes()) {
                String label=relation.getType(); returned.putIfAbsent(label,relation);
                if(established.getAllRelationshipTypes().contains(label) || established.getAllNodeLabels().contains(label)) continue;
                var validation=CorpusSchemaOverlayValidator.validateTypesOnly(established,new GraphSchema(null,List.of(relation),null));
                if(!validation.valid()) { errors.add("relationshipTypes["+i+"]: "+String.join("; ",validation.errors())); continue; }
                if(quarantined.contains(label)) { errors.add("[SCHEMA_QUARANTINED] "+label); continue; }
                var prior=retained.get(label);
                if(prior!=null && !Objects.equals(prior.getConnectionFamily(),relation.getConnectionFamily())) {
                    retained.remove(label); quarantined.add(label);
                    errors.add("[SCHEMA_FAMILY_CONFLICT] "+label+" families="+prior.getConnectionFamily()+","+relation.getConnectionFamily());
                } else retained.putIfAbsent(label,relation);
            }
        }
        rejected.addAll(errors);
        for(String label:quarantined) errors.add("[SCHEMA_QUARANTINED] "+label);
        return new CorpusSchemaResponseParser.ParseResult(new GraphSchema(null,new ArrayList<>(retained.values()),null),errors);
    }
    /** Discards every retained/quarantined candidate of a degenerate batch and records why. */
    void rejectBatch(String reason) {
        retained.clear();
        returned.clear();
        quarantined.clear();
        rejected.add(reason);
    }
    Set<CorpusSchemaUnifier.TypeProposal> proposals() {
        Set<CorpusSchemaUnifier.TypeProposal> result=new LinkedHashSet<>();
        retained.values().forEach(r -> result.add(new CorpusSchemaUnifier.TypeProposal(r.getType(),r.getConnectionFamily())));
        return Collections.unmodifiableSet(result);
    }
    List<String> rejected() { return List.copyOf(rejected); }
    Set<String> quarantined() { return Set.copyOf(quarantined); }
}
