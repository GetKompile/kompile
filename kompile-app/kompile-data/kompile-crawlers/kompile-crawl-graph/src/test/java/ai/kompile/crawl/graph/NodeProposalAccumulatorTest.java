package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeProposalAccumulatorTest {
    private static Map<String,Object> row(String label, String parent, String quote) {
        return Map.of("label",label,"parentType",parent,"evidence",List.of(Map.of("sourceId","s1","quote",quote)));
    }
    @Test void preservesValidRowsAcrossRejectedRowsAndRetries() {
        var a = new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        var windows = Map.of("s1","An observation and a forecast were recorded.");
        var first = a.accept(Map.of("nodeTypes",List.of(row("OBSERVATION","ACTIVITY","observation"),
                row("FORECAST","DOCUMENT","invented"))),windows);
        assertFalse(first.valid());
        assertEquals(Set.of(new CorpusSchemaUnifier.TypeProposal("OBSERVATION","ACTIVITY")),a.proposals());
        var second = a.accept(Map.of("nodeTypes",List.of(row("FORECAST","DOCUMENT","forecast"))),windows);
        assertTrue(second.valid(),second.errors().toString());
        assertEquals(2,a.proposals().size());
        assertFalse(a.rejected().isEmpty(),"Rejections remain auditable after recovery");
    }
    @Test void conflictingParentsQuarantineBothRegardlessOfOrder() {
        var x=row("REPORT","DOCUMENT","report"); var y=row("REPORT","PERSON","report");
        for (var rows : List.of(List.of(x,y),List.of(y,x))) {
            var a=new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
            for (var r : rows) a.accept(Map.of("nodeTypes",List.of(r)),Map.of("s1","report"));
            assertTrue(a.proposals().isEmpty());
            assertEquals(Set.of("REPORT"),a.quarantined());
            assertFalse(a.accept(Map.of("nodeTypes",List.of(x)),Map.of("s1","report")).valid(),
                    "A repeated alternative is not explicit conflict resolution");
        }
    }
    @Test void ancestorParentsResolveToMostSpecificInEitherOrder() {
        var broad=row("REPORT","CREATIVE_WORK","report");
        var narrow=row("REPORT","DOCUMENT","document");
        for (var rows : List.of(List.of(broad,narrow),List.of(narrow,broad))) {
            var a=new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
            for (var r:rows) assertTrue(a.accept(Map.of("nodeTypes",List.of(r)),Map.of("s1","report document third")).valid());
            assertEquals(Set.of(new CorpusSchemaUnifier.TypeProposal("REPORT","DOCUMENT")),a.proposals());
            assertTrue(a.quarantined().isEmpty());
            assertEquals("DOCUMENT",a.snapshot().getNodeTypes().get(0).getParentType());
            // Evidence from both parent declarations must survive the reconciliation.
            assertFalse(a.accept(Map.of("nodeTypes",List.of(row("REPORT","CREATIVE_WORK","third"))),
                    Map.of("s1","report document third")).valid());
            assertTrue(a.proposals().isEmpty());
        }
    }
    @Test void transitiveAncestorReconciliationDoesNotHideUnrelatedParent() {
        var a=new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        for (String parent:List.of("CREATIVE_WORK","LAW","DOCUMENT"))
            assertTrue(a.accept(Map.of("nodeTypes",List.of(row("STATUTE",parent,"statute"))),Map.of("s1","statute")).valid());
        assertEquals(Set.of(new CorpusSchemaUnifier.TypeProposal("STATUTE","LAW")),a.proposals());
        assertFalse(a.accept(Map.of("nodeTypes",List.of(row("STATUTE","PERSON","statute"))),Map.of("s1","statute")).valid());
        assertEquals(Set.of("STATUTE"),a.quarantined());
    }
    @Test void unrelatedCandidateSurvivesConflictAndMalformedCallAddsNothing() {
        var a=new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        var windows=Map.of("s1","report observation");
        a.accept(Map.of("nodeTypes",List.of(row("REPORT","DOCUMENT","report"),
                row("OBSERVATION","ACTIVITY","observation"),row("REPORT","PERSON","report"))),windows);
        assertEquals(Set.of(new CorpusSchemaUnifier.TypeProposal("OBSERVATION","ACTIVITY")),a.proposals());
        assertFalse(a.accept(Map.of("wrong",List.of()),windows).valid());
        assertEquals(1,a.proposals().size());
    }
    @Test void evidenceBoundAndOverlayChecksStillApply() {
        var a=new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        var windows=Map.of("s1","one two three");
        for (String quote : List.of("one","two","three"))
            a.accept(Map.of("nodeTypes",List.of(row("REPORT","DOCUMENT",quote))),windows);
        assertTrue(a.proposals().isEmpty());
        assertEquals(Set.of("REPORT"),a.quarantined());
        var b=new NodeProposalAccumulator(SchemaHierarchyVocabulary.baselineSchema());
        assertFalse(b.accept(Map.of("nodeTypes",List.of(row("REPORT","UNKNOWN_PARENT","one"))),windows).valid());
        assertTrue(b.proposals().isEmpty());
    }
}
