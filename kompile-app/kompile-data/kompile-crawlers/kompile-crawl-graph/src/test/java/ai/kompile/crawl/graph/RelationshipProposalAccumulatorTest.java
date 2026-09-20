package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RelationshipProposalAccumulatorTest {
    private static Map<String,Object> row(String type,String family) { return Map.of("type",type,"connectionFamily",family); }
    @Test void genericSiblingDoesNotDiscardValidPredicateAcrossRetries() {
        var a=new RelationshipProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        assertFalse(a.accept(Map.of("relationshipTypes",List.of(row("ASSOCIATION","AFFILIATION"),row("USES_INSTRUMENT","DEPENDENCY")))).valid());
        assertEquals(Set.of(new CorpusSchemaUnifier.TypeProposal("USES_INSTRUMENT","DEPENDENCY")),a.proposals());
        assertTrue(a.accept(Map.of("relationshipTypes",List.of(row("AUTHORED_BY","ATTRIBUTION")))).valid());
        assertEquals(2,a.proposals().size());
        assertFalse(a.rejected().isEmpty());
    }
    @Test void contradictoryFamiliesRemainQuarantinedInEitherOrder() {
        var x=row("USES_INSTRUMENT","DEPENDENCY"); var y=row("USES_INSTRUMENT","ATTRIBUTION");
        for(var rows:List.of(List.of(x,y),List.of(y,x))) {
            var a=new RelationshipProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
            for(var row:rows) a.accept(Map.of("relationshipTypes",List.of(row)));
            assertTrue(a.proposals().isEmpty());
            assertEquals(Set.of("USES_INSTRUMENT"),a.quarantined());
            assertFalse(a.accept(Map.of("relationshipTypes",List.of(x))).valid());
        }
    }
    @Test void malformedAndUnknownFamiliesAreNotRetained() {
        var a=new RelationshipProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        assertFalse(a.accept(Map.of("relationshipTypes",List.of(row("USES_INSTRUMENT","MISSING_FAMILY")))).valid());
        assertFalse(a.accept(Map.of("relationshipTypes",Collections.singletonList(null))).valid());
        assertTrue(a.proposals().isEmpty());
    }
}
