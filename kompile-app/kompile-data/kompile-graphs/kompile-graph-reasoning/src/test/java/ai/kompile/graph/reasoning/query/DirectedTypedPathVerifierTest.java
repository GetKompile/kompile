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
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.query.DirectedTypedPathVerifier.AnswerValidation;
import ai.kompile.graph.reasoning.query.DirectedTypedPathVerifier.Edge;
import ai.kompile.graph.reasoning.query.DirectedTypedPathVerifier.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectedTypedPathVerifierTest {

    @Test
    void acceptsReachableTrueOnlyWhenEveryDirectedTypedEdgeExists() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "person", "file",
                List.of("SENT", "HAS_ATTACHMENT"), true,
                List.of("person", "email", "file"),
                List.of("sent", "has-attachment"));

        assertTrue(result.accepted());
        assertEquals(Status.REACHABLE, result.truth().status());
        assertEquals(List.of("person", "email", "file"), result.truth().entityPath());
        assertEquals(List.of("person-email", "email-file"), result.truth().relationIds());
    }

    @Test
    void rejectsReachableTrueWithEmptyEntityPath() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "person", "file",
                List.of("SENT", "HAS_ATTACHMENT"), true, List.of(),
                List.of("SENT", "HAS_ATTACHMENT"));

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("length"));
    }

    @Test
    void rejectsReachableTrueWhenOneWitnessEdgeIsMissing() {
        MutableReasoningGraph graph = graph().addEntity("other", "PERSON", "Other");

        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph, "person", "file",
                List.of("SENT", "HAS_ATTACHMENT"), true,
                List.of("person", "other", "file"),
                List.of("SENT", "HAS_ATTACHMENT"));

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("missing, reversed, or wrongly typed edge"));
    }

    @Test
    void rejectsWrongDirectionEvenWhenReverseEdgesExist() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("person", "PERSON", "Person")
                .addEntity("email", "EMAIL", "Email")
                .addRelation("reverse", "email", "person", "SENT", 1.0);

        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph, "person", "email",
                List.of("SENT"), true, List.of("person", "email"), List.of("SENT"));

        assertFalse(result.accepted());
        assertEquals(Status.UNREACHABLE, result.truth().status());
    }

    @Test
    void rejectsCorrectNodesWithWrongRelationTypeOrder() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "person", "file",
                List.of("SENT", "HAS_ATTACHMENT"), true,
                List.of("person", "email", "file"),
                List.of("HAS_ATTACHMENT", "SENT"));

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("ordered sequence"));
    }

    @Test
    void acceptsReachableFalseWhenDeterministicTraversalFindsNoPath() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "file", "person",
                List.of("HAS_ATTACHMENT", "SENT"), false, List.of(), List.of());

        assertTrue(result.accepted());
        assertEquals(Status.UNREACHABLE, result.truth().status());
    }

    @Test
    void rejectsReachableFalseWhenARequestedTypedPathExists() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "person", "file",
                List.of("SENT", "HAS_ATTACHMENT"), false, List.of(), List.of());

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("engine-proven path"));
    }

    @Test
    void reachableFalseRequiresEmptyArrays() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "file", "person",
                List.of("HAS_ATTACHMENT", "SENT"), false,
                List.of("file"), List.of());

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("requires empty"));
    }

    @Test
    void malformedOrMissingReachableFieldAbstains() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "person", "file",
                List.of("SENT", "HAS_ATTACHMENT"), null,
                List.of("person", "email", "file"),
                List.of("SENT", "HAS_ATTACHMENT"));

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("JSON boolean"));
    }

    @Test
    void rejectsAnswersWhoseRequestedEndpointsAreAbsentFromTheSuppliedGraph() {
        AnswerValidation result = DirectedTypedPathVerifier.validateAnswer(graph(), "person", "missing",
                List.of("SENT"), false, List.of(), List.of());

        assertFalse(result.accepted());
        assertEquals(Status.INVALID, result.truth().status());
        assertTrue(result.reason().contains("must both exist"));
    }

    @Test
    void proofCanBeRestrictedToTheExactCompactEdgesShownToTheModel() {
        List<Edge> compactEdges = List.of(new Edge(
                "person-email", "person", "email", "SENT"));

        AnswerValidation compact = DirectedTypedPathVerifier.validateEdgeAnswer(compactEdges,
                "person", "file", List.of("SENT", "HAS_ATTACHMENT"), true,
                List.of("person", "email", "file"),
                List.of("SENT", "HAS_ATTACHMENT"));
        AnswerValidation full = DirectedTypedPathVerifier.validateAnswer(graph(),
                "person", "file", List.of("SENT", "HAS_ATTACHMENT"), true,
                List.of("person", "email", "file"),
                List.of("SENT", "HAS_ATTACHMENT"));

        assertFalse(compact.accepted(),
                "an edge hidden from compact context cannot prove a model answer");
        assertEquals(Status.UNREACHABLE, compact.truth().status());
        assertTrue(full.accepted(), "the same witness is valid against the full graph");
    }

    private static MutableReasoningGraph graph() {
        return new MutableReasoningGraph()
                .addEntity("person", "PERSON", "Jordan Lee")
                .addEntity("email", "EMAIL", "Close package email")
                .addEntity("file", "DOCUMENT", "regional-close.xlsx")
                .addRelation("person-email", "person", "email", "SENT", 1.0)
                .addRelation("email-file", "email", "file", "HAS_ATTACHMENT", 1.0);
    }
}
