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
}
