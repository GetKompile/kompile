/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness of the graph query paths after the scan-killing rewrite: the
 * suffix/name resolution and CALLS/IMPLEMENTS lookups must return the same
 * results the leading-wildcard LIKE queries used to, via indexed probes.
 */
class IndexDatabaseGraphQueryTest {

    @TempDir
    Path tempDir;

    private IndexDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        db = IndexDatabase.open(tempDir);
        db.insertEntities("src/A.java", List.of(
                entity("CLASS", "Alpha", "com.example.Alpha"),
                entity("METHOD", "run", "com.example.Alpha.run"),
                entity("CLASS", "Beta", "com.example.Beta"),
                entity("METHOD", "work", "com.example.Beta.work"),
                entity("INTERFACE", "Iface", "com.example.Iface")
        ));
        db.insertRelations("src/A.java", List.of(
                relation("com.example.Alpha.run", "work", "com.example.Beta.work", "CALLS"),
                relation("com.example.Beta.work", "helper", "helper", "CALLS"),
                relation("com.example.Beta", "Iface", "com.example.Iface", "IMPLEMENTS")
        ));
        db.setIndexGeneration("generation-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private Map<String, Object> entity(String type, String name, String fqn) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("entityType", type);
        e.put("name", name);
        e.put("fullyQualifiedName", fqn);
        e.put("language", "java");
        e.put("startLine", 1);
        return e;
    }

    private Map<String, Object> relation(String sourceFqn, String targetName,
                                         String targetFqn, String type) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("projectId", "test-project");
        r.put("sourceFqn", sourceFqn);
        r.put("targetName", targetName);
        r.put("targetFqn", targetFqn);
        r.put("relationType", type);
        r.put("filePath", "src/A.java");
        r.put("line", 10);
        return r;
    }

    @Test
    void readOnlyOpenServesQueriesAndRejectsWrites() throws Exception {
        try (IndexDatabase readOnly = IndexDatabase.openReadOnly(tempDir)) {
            Map<String, Object> fileGraph = readOnly.getFileGraph("src/A.java", 3, 2);
            assertEquals(5, fileGraph.get("entityCount"));
            assertEquals("generation-1", readOnly.getIndexGeneration());
            assertEquals(true, fileGraph.get("entitiesTruncated"));
            assertEquals(3, ((List<?>) fileGraph.get("entities")).size());
            assertTrue(((List<?>) fileGraph.get("outgoingRelations")).size()
                            + ((List<?>) fileGraph.get("incomingRelations")).size() <= 2);
            assertThrows(java.sql.SQLException.class,
                    () -> readOnly.insertEntities("src/B.java", List.of(
                            entity("CLASS", "Blocked", "com.example.Blocked"))));
        }
    }

    @Test
    void boundedFileGraphCapsEntitiesAndCombinedRelations() throws Exception {
        Map<String, Object> fileGraph = db.getFileGraph("src/A.java", 2, 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities =
                (List<Map<String, Object>>) fileGraph.get("entities");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outgoing =
                (List<Map<String, Object>>) fileGraph.get("outgoingRelations");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incoming =
                (List<Map<String, Object>>) fileGraph.get("incomingRelations");

        assertEquals(5, fileGraph.get("entityCount"));
        assertEquals(true, fileGraph.get("entitiesTruncated"));
        assertEquals(2, entities.size());
        assertTrue(outgoing.size() + incoming.size() <= 2,
                "maxRelations must bound both directions together");
    }

    @Test
    void suffixResolutionFindsSimpleAndDottedNames() throws Exception {
        Map<String, Object> bySimple = db.findEntityBySuffix("work");
        assertNotNull(bySimple);
        assertEquals("com.example.Beta.work", bySimple.get("fullyQualifiedName"));

        Map<String, Object> byDotted = db.findEntityBySuffix("Beta.work");
        assertNotNull(byDotted);
        assertEquals("com.example.Beta.work", byDotted.get("fullyQualifiedName"));

        assertNull(db.findEntityBySuffix("doesNotExistAnywhere"));
    }

    @Test
    void callersResolveByNameWithoutSuffixScan() throws Exception {
        List<Map<String, Object>> callers = db.getCallers("work", 10);
        assertFalse(callers.isEmpty(), "callers of 'work' should include Alpha.run");
        assertEquals("com.example.Alpha.run", callers.get(0).get("callerFqn"));

        // Also resolvable via the full FQN.
        assertFalse(db.getCallers("com.example.Beta.work", 10).isEmpty());
    }

    @Test
    void callChainTraversesBothDirections() throws Exception {
        Map<String, Object> incoming = db.getCallChain("com.example.Beta.work", 3, "incoming");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inEdges = (List<Map<String, Object>>) incoming.get("edges");
        assertFalse(inEdges.isEmpty(), "incoming chain should find Alpha.run -> Beta.work");
        assertEquals("com.example.Alpha.run", inEdges.get(0).get("sourceFqn"));

        Map<String, Object> outgoing = db.getCallChain("com.example.Alpha.run", 3, "outgoing");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outEdges = (List<Map<String, Object>>) outgoing.get("edges");
        assertFalse(outEdges.isEmpty(), "outgoing chain should find Beta.work");
    }

    @Test
    void callChainOnUnknownSymbolReturnsEmptyFast() throws Exception {
        long start = System.currentTimeMillis();
        Map<String, Object> chain = db.getCallChain("totallyUnknownSymbol", 3, "incoming");
        long elapsed = System.currentTimeMillis() - start;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) chain.get("edges");
        assertTrue(edges.isEmpty());
        // Tiny DB: generous bound just guards against reintroducing a scan path
        // that would show up as pathological on multi-GB indexes.
        assertTrue(elapsed < 2000, "unknown-symbol trace took " + elapsed + "ms");
    }

    @Test
    void implementorsResolveViaIndexedArms() throws Exception {
        List<Map<String, Object>> impls = db.getImplementors("Iface", 10);
        assertFalse(impls.isEmpty(), "Beta implements Iface");
        assertEquals("com.example.Beta", impls.get(0).get("fullyQualifiedName"));

        assertFalse(db.getImplementors("com.example.Iface", 10).isEmpty());
    }

    @Test
    void naturalSearchFallsBackToTokenOrWithoutPhraseLikeScan() throws Exception {
        List<Map<String, Object>> partial = db.search("Alpha nonexistentTerm", null, 10);
        assertFalse(partial.isEmpty(), "token-OR fallback should retain the matching term");
        assertTrue(partial.stream().anyMatch(entity -> "Alpha".equals(entity.get("name"))));

        long start = System.currentTimeMillis();
        assertTrue(db.search("none of these terms exist", null, 10).isEmpty());
        assertTrue(System.currentTimeMillis() - start < 2000,
                "multi-term miss must not fall through to a leading-wildcard table scan");
    }

    @Test
    void graphSnapshotCanBeVisitedWithoutBuildingBulkLists() throws Exception {
        List<String> entities = new java.util.ArrayList<>();
        List<String> relations = new java.util.ArrayList<>();

        db.visitGraphSnapshot(
                row -> entities.add(String.valueOf(row.get("fullyQualifiedName"))),
                row -> relations.add(row.get("sourceFqn") + "->" + row.get("targetFqn")));

        assertEquals(5, entities.size());
        assertEquals(3, relations.size());
        assertTrue(entities.contains("com.example.Alpha.run"));
        assertTrue(relations.contains("com.example.Alpha.run->com.example.Beta.work"));
    }

    private String lookupTarget(String source) throws Exception {
        try (var ps = db.getConnection().prepareStatement("SELECT target.fqn FROM relations r "
                + "JOIN fqns source ON source.id = r.source_id "
                + "LEFT JOIN fqns target ON target.id = r.target_id WHERE source.fqn = ?")) {
            ps.setString(1, source);
            try (var rows = ps.executeQuery()) {
                assertTrue(rows.next(), "relation must survive invalidation");
                return rows.getString(1);
            }
        }
    }

    @Test
    void connectivityClearsEmptyAndOverloadedLegacyTargetsInBothStrategies() throws Exception {
        var first = entity("METHOD", "overloaded", "Provider.overloaded");
        first.put("signature", "overloaded()");
        var second = entity("METHOD", "overloaded", "Provider.overloaded");
        second.put("signature", "overloaded(String value)");
        db.insertEntities("src/Provider.java", List.of(first, second));
        for (int padding : new int[]{0, 201}) {
            List<Map<String, Object>> relations = new java.util.ArrayList<>();
            for (int i = 0; i < padding; i++) {
                relations.add(relation("padding" + i, "missing" + i, null, "CALLS"));
            }
            String bare = "bare" + padding;
            String exact = "exact" + padding;
            String empty = "empty" + padding;
            relations.add(relation(bare, "overloaded", "overloaded", "CALLS"));
            relations.add(relation(exact, "Provider.overloaded", "Provider.overloaded", "CALLS"));
            relations.add(relation(empty, "", "", "CALLS"));
            db.insertRelations("src/A.java", relations);
            db.ensureConnectivity();
            assertNull(lookupTarget(bare));
            assertNull(lookupTarget(exact));
            assertNull(lookupTarget(empty));
        }
    }

    @Test
    void connectivityRejectsAmbiguousSimpleDottedAndDuplicateExactNames() throws Exception {
        db.insertEntities("src/First.java", List.of(
                entity("METHOD", "lookup", "one.Provider.lookup"),
                entity("METHOD", "same", "Exact.same")));
        db.insertEntities("src/Second.java", List.of(
                entity("METHOD", "lookup", "two.Provider.lookup"),
                entity("METHOD", "same", "Exact.same")));
        db.insertRelations("src/A.java", List.of(
                relation("simpleCaller", "lookup", null, "CALLS"),
                relation("dottedCaller", "Provider.lookup", null, "CALLS"),
                relation("exactCaller", "Exact.same", null, "CALLS")));
        db.ensureConnectivity();
        assertNull(lookupTarget("simpleCaller"));
        assertNull(lookupTarget("dottedCaller"));
        assertNull(lookupTarget("exactCaller"));
    }

    @Test
    void streamedConnectivityAlsoRejectsAmbiguityAndPrefersUniqueExactDeclaration() throws Exception {
        db.insertEntities("src/First.java", List.of(
                entity("METHOD", "lookup", "one.Provider.lookup"),
                entity("METHOD", "unique", "Provider.unique"),
                entity("METHOD", "same", "Exact.same")));
        db.insertEntities("src/Second.java", List.of(
                entity("METHOD", "lookup", "two.Provider.lookup"),
                entity("METHOD", "unique", "other.Provider.unique"),
                entity("METHOD", "same", "Exact.same")));
        List<Map<String, Object>> relations = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            relations.add(relation("missingCaller" + i, "missing" + i, null, "CALLS"));
        }
        relations.add(relation("simpleCaller", "lookup", null, "CALLS"));
        relations.add(relation("dottedCaller", "Provider.lookup", null, "CALLS"));
        relations.add(relation("exactCaller", "Exact.same", null, "CALLS"));
        relations.add(relation("uniqueCaller", "Provider.unique", null, "CALLS"));
        db.insertRelations("src/A.java", relations);
        db.ensureConnectivity();
        assertNull(lookupTarget("simpleCaller"));
        assertNull(lookupTarget("dottedCaller"));
        assertNull(lookupTarget("exactCaller"));
        assertEquals("Provider.unique", lookupTarget("uniqueCaller"));
    }

    @Test
    void importsAreNotDefinitionsAndSuffixFilteringPrecedesCandidateLimit() throws Exception {
        List<Map<String, Object>> entities = new java.util.ArrayList<>();
        entities.add(entity("IMPORT", "Remote.lookup", "Remote.lookup"));
        for (int i = 0; i < 60; i++) {
            entities.add(entity("METHOD", "other" + i, "prefix.lookup.other" + i));
        }
        entities.add(entity("METHOD", "lookup", "one.Real.lookup"));
        entities.add(entity("METHOD", "lookup", "two.Real.lookup"));
        entities.add(entity("METHOD", "lookup", "prefix.Only.lookup"));
        db.insertEntities("src/Candidates.java", entities);
        db.insertRelations("src/A.java", List.of(
                relation("importCaller", "Remote.lookup", null, "CALLS"),
                relation("suffixCaller", "lookup", null, "CALLS"),
                relation("uniqueSuffixCaller", "Only.lookup", null, "CALLS")));
        db.ensureConnectivity();
        assertNull(lookupTarget("importCaller"));
        assertNull(lookupTarget("suffixCaller"));
        assertEquals("prefix.Only.lookup", lookupTarget("uniqueSuffixCaller"));
    }

    @Test
    void newAmbiguityInvalidatesPreviouslyResolvedIncomingLookupAcrossReopen() throws Exception {
        db.insertEntities("src/First.java", List.of(entity("METHOD", "lookup", "one.Provider.lookup")));
        db.insertRelations("src/A.java", List.of(relation("lookupCaller", "lookup", null, "CALLS")));
        db.ensureConnectivity();
        assertEquals("one.Provider.lookup", lookupTarget("lookupCaller"));
        db.close();
        db = IndexDatabase.open(tempDir);
        db.insertEntities("src/Second.java", List.of(entity("METHOD", "lookup", "two.Provider.lookup")));
        assertNull(lookupTarget("lookupCaller"));
        db.ensureConnectivity(List.of("src/Second.java"));
        assertNull(lookupTarget("lookupCaller"));
        db.deleteFile("src/Second.java");
        db.ensureConnectivity(List.of("src/Second.java"));
        assertEquals("one.Provider.lookup", lookupTarget("lookupCaller"),
                "deletion-only scope must reconsider invalidated incoming hints");
    }

    @Test
    void renameAndDeletionInvalidateIncomingRelationsWithoutDeletingTheirCallSites() throws Exception {
        db.insertEntities("src/Target.java", List.of(entity("METHOD", "lookup", "Provider.lookup")));
        db.insertRelations("src/A.java", List.of(relation("lookupCaller", "lookup", null, "CALLS")));
        db.ensureConnectivity();
        assertEquals("Provider.lookup", lookupTarget("lookupCaller"));
        db.deleteFile("src/Target.java");
        db.insertEntities("src/Target.java", List.of(entity("METHOD", "renamed", "Provider.renamed")));
        db.ensureConnectivity(List.of("src/Target.java"));
        assertNull(lookupTarget("lookupCaller"));
        db.insertEntities("src/Replacement.java", List.of(entity("METHOD", "lookup", "Replacement.lookup")));
        db.ensureConnectivity(List.of("src/Replacement.java"));
        assertEquals("Replacement.lookup", lookupTarget("lookupCaller"));
        db.deleteFile("src/Replacement.java");
        db.ensureConnectivity(List.of("src/Replacement.java"));
        assertNull(lookupTarget("lookupCaller"));
    }

    @Test
    void qualifiedHintSurvivesInvalidationAndNeverFallsBackToBareName() throws Exception {
        db.insertEntities("src/Target.java", List.of(entity("METHOD", "lookup", "Provider.lookup")));
        var relation = relation("qualifiedCaller", "lookup", null, "CALLS");
        relation.put("targetHint", "Provider.lookup");
        db.insertRelations("src/A.java", List.of(relation));
        db.ensureConnectivity();
        assertEquals("Provider.lookup", lookupTarget("qualifiedCaller"));
        db.deleteFile("src/Target.java");
        db.insertEntities("src/Other.java", List.of(entity("METHOD", "lookup", "Other.lookup")));
        db.ensureConnectivity(List.of("src/Other.java"));
        assertNull(lookupTarget("qualifiedCaller"));
    }

    @Test
    void addingOverloadInvalidatesExactHintAndRollbackRestoresTarget() throws Exception {
        db.insertEntities("src/First.java", List.of(entity("METHOD", "lookup", "Provider.lookup")));
        var call = relation("overloadCaller", "lookup", null, "CALLS");
        call.put("targetHint", "Provider.lookup");
        db.insertRelations("src/A.java", List.of(call));
        db.ensureConnectivity();
        assertEquals("Provider.lookup", lookupTarget("overloadCaller"));
        db.beginTransaction();
        try {
            var overload = entity("METHOD", "lookup", "Provider.lookup");
            overload.put("signature", "lookup(String value)");
            db.insertEntities("src/Overload.java", List.of(overload));
            db.ensureConnectivity(List.of("src/Overload.java"));
            assertNull(lookupTarget("overloadCaller"));
        } finally {
            db.rollback();
        }
        assertEquals("Provider.lookup", lookupTarget("overloadCaller"));
    }

    @Test
    void connectivityAndGenerationRemainInvisibleUntilCommitAndRollBackTogether() throws Exception {
        db.beginTransaction();
        try {
            db.insertEntities("src/Target.java", List.of(entity("METHOD", "lookup", "Provider.lookup")));
            db.insertRelations("src/A.java", List.of(relation("atomicCaller", "lookup", null, "CALLS")));
            db.ensureConnectivity(List.of("src/Target.java", "src/A.java"));
            db.setIndexGeneration("generation-2");
            assertEquals("Provider.lookup", lookupTarget("atomicCaller"));
            try (IndexDatabase reader = IndexDatabase.openReadOnly(tempDir)) {
                assertEquals("generation-1", reader.getIndexGeneration());
                assertNull(reader.findEntityByFqn("Provider.lookup"));
            }
        } finally {
            db.rollback();
        }
        assertEquals("generation-1", db.getIndexGeneration());
        assertNull(db.findEntityByFqn("Provider.lookup"));
        // Repeat after rollback to cover interned-id cache invalidation and pending hints.
        db.beginTransaction();
        try {
            db.insertEntities("src/Target.java", List.of(entity("METHOD", "lookup", "Provider.lookup")));
            db.insertRelations("src/A.java", List.of(relation("atomicCaller", "lookup", null, "CALLS")));
            db.ensureConnectivity(List.of("src/Target.java", "src/A.java"));
            db.setIndexGeneration("generation-2");
            db.commit();
        } catch (Exception failure) {
            db.rollback();
            throw failure;
        }
        try (IndexDatabase reader = IndexDatabase.openReadOnly(tempDir)) {
            assertEquals("generation-2", reader.getIndexGeneration());
            assertNotNull(reader.findEntityByFqn("Provider.lookup"));
        }
        assertEquals("Provider.lookup", lookupTarget("atomicCaller"));
    }

    @Test
    void incomingInvalidationRollsBackWithDeclarationDeletion() throws Exception {
        db.insertEntities("src/Target.java", List.of(entity("METHOD", "lookup", "Provider.lookup")));
        db.insertRelations("src/A.java", List.of(relation("lookupCaller", "lookup", null, "CALLS")));
        db.ensureConnectivity();
        db.beginTransaction();
        try {
            db.deleteFile("src/Target.java");
            assertNull(lookupTarget("lookupCaller"));
        } finally {
            db.rollback();
        }
        assertEquals("Provider.lookup", lookupTarget("lookupCaller"));
        assertNotNull(db.findEntityByFqn("Provider.lookup"));
    }

    @Test
    void graphVisitorPreservesCallerOwnedTransactionAndUsesSavepointOnFailure() throws Exception {
        var connection = db.getConnection();
        connection.setAutoCommit(false);
        try {
            db.insertEntities("src/Pending.java", List.of(
                    entity("CLASS", "Pending", "com.example.Pending")));
            db.visitGraphSnapshot(row -> { }, row -> { });
            assertFalse(connection.getAutoCommit());

            assertThrows(IllegalStateException.class, () -> db.visitGraphSnapshot(row -> {
                throw new IllegalStateException("consumer failed");
            }, row -> { }));
            assertFalse(connection.getAutoCommit());
            assertNotNull(db.findEntityByFqn("com.example.Alpha"));
            assertNotNull(db.findEntityByFqn("com.example.Pending"),
                    "rollback to the visitor savepoint must preserve earlier caller work");
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }
}
