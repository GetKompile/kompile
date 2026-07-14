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
package ai.kompile.graph.reasoning.local.fixture;

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.psl.Term;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standalone main class that writes a fixture {@code .kgraph} file for the C smoke test.
 *
 * <p>The fixture contains:
 * <ul>
 *   <li>Two entities (PERSON and ORG type) with weights not all 1.0</li>
 *   <li>A WORKS_AT relation between them</li>
 *   <li>An F32 embedding vector layer (2-dim) on both entities</li>
 *   <li>A putModel'd {@link PslProgram} (Serializable) so serialization is exercised</li>
 * </ul>
 *
 * <p>Invoked by {@code run-smoke.sh} before running the C smoke test:
 * <pre>
 *   java -cp ... ai.kompile.graph.reasoning.local.fixture.WriteFixtureKgraph target/fixture.kgraph
 * </pre>
 */
public final class WriteFixtureKgraph {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: WriteFixtureKgraph <output-path>");
            System.exit(1);
        }
        Path out = Paths.get(args[0]);
        writeFixture(out);
        System.out.println("Fixture written to: " + out);
    }

    /**
     * Build and save the fixture graph. Also callable from tests.
     */
    public static void writeFixture(Path out) throws IOException {
        UnifiedGraph g = new UnifiedGraph();

        // Two entities
        g.addEntity(new SimpleGraphEntity(
                "alice", "PERSON", "Alice",
                0.85, 0.85,
                Set.of("PERSON", "RESEARCHER"),
                null, null,
                Map.of("department", "Engineering")));

        g.addEntity(new SimpleGraphEntity(
                "acme", "ORG", "Acme Corp",
                0.95, 0.95,
                Set.of("ORG"),
                null, null,
                Map.of("industry", "Technology")));

        // Relation
        g.addRelation("r1", "alice", "acme", "WORKS_AT", 0.9);

        // Embedding layer (dim=2) — just enough to exercise the vector codec
        double[] aliceVec = {0.6, 0.4};
        double[] acmeVec  = {0.2, 0.8};
        g.putEntityVector("kge", "alice", aliceVec);
        g.putEntityVector("kge", "acme", acmeVec);

        // A PslProgram with one rule (serializable model — exercises putModel / serialization config)
        PslProgram prog = new PslProgram();
        // WORKS_AT(X, Y) => ASSOCIATED(X, Y)  weight=0.7
        PslAtom body = new PslAtom("WORKS_AT",
                List.of(new Term("X", true), new Term("Y", true)), false);
        PslAtom head = new PslAtom("ASSOCIATED",
                List.of(new Term("X", true), new Term("Y", true)), false);
        prog.addRule(PslRule.weighted(0.7, true, List.of(body), List.of(head)));
        g.putModel("psl_prog", prog);

        g.save(out);
    }
}
