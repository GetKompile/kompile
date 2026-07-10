/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.psl.PslProgram;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the full reasoning MODELS — not just their weights — round-trip inside a single {@code
 * .kgraph}: a PSL program's rules/atoms/values, an MTheory's entity types + MFrag structure, and a
 * TypeRegistry's declarations. (Lambda-valued fields — PSL external functions, MEBN CPT/constraint
 * predicates — are transient by JVM necessity and re-attached after load.)
 */
class UnifiedGraphModelBundleTest {

    private UnifiedGraph roundTrip(UnifiedGraph g) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        return UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));
    }

    @Test
    void bundlesAndRestoresAPslProgramStructure() throws IOException {
        PslProgram program = new PslProgram()
                .observe("State", 0.8, "alice")
                .observe("Link", 1.0, "alice", "bob")
                .target("State", "bob")
                .addRule("2.0: State(X) & Link(X,Y) -> State(Y) ^2")
                .addRule("1.0: State(Y) & Link(X,Y) -> State(X) ^2");
        int rules = program.rules().size();
        int ground = program.ground().size();

        UnifiedGraph g = new UnifiedGraph();
        g.putModel("psl.program", program);

        PslProgram restored = roundTrip(g).model("psl.program");
        assertNotNull(restored);
        assertEquals(rules, restored.rules().size());
        assertEquals(ground, restored.ground().size()); // grounding needs rules+atoms+values intact
    }

    @Test
    void bundlesAndRestoresAnMTheoryStructure() throws IOException {
        MTheory theory = RelationalMTheoryBuilder.build("t", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "SHARED_ENTITY", "ENTITY", "ENTITY", 0.7,
                        List.of("a", "b"), List.of("a", "b"))));

        UnifiedGraph g = new UnifiedGraph();
        g.putModel("mebn.mtheory", theory);

        MTheory restored = roundTrip(g).model("mebn.mtheory");
        assertNotNull(restored);
        assertEquals("t", restored.getName());
        assertEquals(theory.getMFrags().size(), restored.getMFrags().size());
        assertEquals(theory.getEntityTypes().size(), restored.getEntityTypes().size());
    }

    @Test
    void bundlesAndRestoresATypeRegistryStructure() throws IOException {
        TypeRegistry registry = new TypeRegistry()
                .declare("Entity")
                .declare("Person")
                .declare("Org")
                .subtype("Person", "Entity")
                .subtype("Org", "Entity");

        UnifiedGraph g = new UnifiedGraph();
        g.putModel("mebn.typeRegistry", registry);

        TypeRegistry restored = roundTrip(g).model("mebn.typeRegistry");
        assertNotNull(restored);
        assertTrue(restored.toString().contains("3 declared types"), restored.toString());
    }

    @Test
    void everythingInOneFile() throws IOException {
        // Graph topology + a PSL program + a TypeRegistry, all inside the one .kgraph.
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("n1", "PERSON", "Alice").addEntity("n2", "ORG", "Acme");
        g.addRelation("r1", "n1", "n2", "WORKS_AT", 0.8);
        g.putModel("psl.program", new PslProgram().addRule("2.0: State(X) & Link(X,Y) -> State(Y) ^2"));
        g.putModel("mebn.typeRegistry", new TypeRegistry().declare("PERSON").declare("ORG"));

        UnifiedGraph back = roundTrip(g);
        assertEquals(2, back.entityCount());
        assertEquals(1, back.relationCount());
        PslProgram psl = back.model("psl.program");
        TypeRegistry types = back.model("mebn.typeRegistry");
        assertEquals(1, psl.rules().size());
        assertTrue(types.toString().contains("2 declared types"));
    }
}
