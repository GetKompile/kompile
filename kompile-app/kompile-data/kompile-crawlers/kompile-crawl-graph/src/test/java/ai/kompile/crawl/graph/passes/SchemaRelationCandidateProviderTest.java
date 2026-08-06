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

package ai.kompile.crawl.graph.passes;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.PassContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relation vocabulary offered for an endpoint pair. The contract that matters: a type this
 * provider offers for a pair is one the validator would accept for that pair — retrieval and
 * validation read the same signatures, so the model is never set up to fail.
 */
class SchemaRelationCandidateProviderTest {

    private static final PassContext CONTEXT =
            PassContext.forChunk("chunk-1", "doc-1", "Acme acquired Initech.");

    private static GraphSchema schema(List<RelationshipType> types, List<String> patterns) {
        GraphSchema schema = new GraphSchema();
        schema.setRelationshipTypes(types);
        schema.setPatterns(patterns);
        return schema;
    }

    private static RelationshipType type(String name, String description) {
        return new RelationshipType(name, description, null);
    }

    private static RelationshipType type(String name, String description, List<String> aliases) {
        return new RelationshipType(name, description, null, aliases);
    }

    private static List<String> typesOf(List<RelationCandidate> candidates) {
        return candidates.stream().map(RelationCandidate::type).toList();
    }

    @Test
    void schemaTypesAreOfferedWithTheirDescriptions() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("ACQUIRED", "one organization bought another")), null),
                null, null);

        List<RelationCandidate> candidates =
                provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12);

        assertEquals(1, candidates.size());
        assertEquals("ACQUIRED", candidates.get(0).type());
        assertEquals("one organization bought another", candidates.get(0).description());
    }

    @Test
    void schemaOwnedLexicalAliasesReachTheBoundedCandidate() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("SUBMITTED_BY", "a person submitted the forecast",
                                List.of("submitted", "提出しました"))),
                        List.of("(PERSON)-[:SUBMITTED_BY]->(REGIONAL_FORECAST)")),
                null, null);

        RelationCandidate candidate = provider.candidatesFor(
                "PERSON", "REGIONAL_FORECAST", CONTEXT, 12).get(0);

        assertEquals(List.of("submitted", "提出しました"), candidate.aliases());
        assertEquals("SUBMITTED_BY", candidate.type(),
                "aliases are routing metadata and never replace the engine-owned canonical type");
    }

    @Test
    void configuredTypesAreMergedAndNormalizedToUpperCase() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("ACQUIRED", null)), null),
                List.of("partnered_with", " employs "), null);

        assertEquals(List.of("ACQUIRED", "PARTNERED_WITH", "EMPLOYS"),
                typesOf(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12)));
    }

    @Test
    void aSignatureConstrainedTypeIsWithheldWhenTheEndpointsDoNotMatch() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("WORKS_AT", null), type("ACQUIRED", null)),
                        List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)",
                                "(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")),
                null, null);

        assertEquals(List.of("ACQUIRED"),
                typesOf(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12)));
        assertEquals(List.of("WORKS_AT"),
                typesOf(provider.candidatesFor("PERSON", "ORGANIZATION", CONTEXT, 12)));
    }

    @Test
    void aSignatureMatchOutranksAnUnconstrainedType() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("MENTIONS", null), type("ACQUIRED", null)),
                        List.of("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")),
                null, null);

        List<RelationCandidate> candidates =
                provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12);

        assertEquals(List.of("ACQUIRED", "MENTIONS"), typesOf(candidates));
        assertTrue(candidates.get(0).score() > candidates.get(1).score());
    }

    @Test
    void aConstrainedTypeCarriesItsDomainAndRangeSoTheModelSeesWhyItFits() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(null, List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)",
                        "(PERSON)-[:WORKS_AT]->(GOVERNMENT_AGENCY)")),
                null, null);

        RelationCandidate candidate =
                provider.candidatesFor("PERSON", "ORGANIZATION", CONTEXT, 12).get(0);

        assertEquals(List.of("PERSON"), candidate.domainTypes());
        assertEquals(List.of("ORGANIZATION", "GOVERNMENT_AGENCY"), candidate.rangeTypes());
    }

    @Test
    void aTypeDeclaredOnlyByAPatternIsStillPartOfTheVocabulary() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(null, List.of("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")), null, null);

        assertFalse(provider.isEmpty());
        assertEquals(List.of("ACQUIRED"),
                typesOf(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12)));
    }

    @Test
    void unknownEndpointTypesCannotDiscriminateSoEverythingIsOffered() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("WORKS_AT", null), type("ACQUIRED", null)),
                        List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)",
                                "(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")),
                null, null);

        assertEquals(2, provider.candidatesFor(null, null, CONTEXT, 12).size());
        assertEquals(2, provider.candidatesFor("", "  ", CONTEXT, 12).size());
    }

    @Test
    void oneKnownEndpointStillNarrowsTheVocabulary() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(null, List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)",
                        "(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")),
                null, null);

        assertEquals(List.of("WORKS_AT"), typesOf(provider.candidatesFor("PERSON", null, CONTEXT, 12)));
    }

    @Test
    void aMalformedPatternIsIgnoredRatherThanPoisoningTheVocabulary() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("ACQUIRED", null)), List.of("this is not a pattern", "")),
                null, null);

        assertEquals(List.of("ACQUIRED"),
                typesOf(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12)));
    }

    @Test
    void theVocabularyIsCappedAtTheRequestedLimit() {
        SchemaRelationCandidateProvider provider = new SchemaRelationCandidateProvider(
                schema(List.of(type("A_REL", null), type("B_REL", null), type("C_REL", null)), null),
                null, null);

        assertEquals(2, provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 2).size());
        assertTrue(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 0).isEmpty());
    }

    @Test
    void anUnconfiguredProjectHasNoVocabularyAtAll() {
        SchemaRelationCandidateProvider provider =
                new SchemaRelationCandidateProvider(null, null, null);

        assertTrue(provider.isEmpty());
        assertTrue(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12).isEmpty());
    }

    @Test
    void theValidationPolicysOwnSignaturesConstrainRetrievalToo() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .relationshipTypes(new java.util.ArrayList<>(List.of("WORKS_AT")))
                .build();
        GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                .relationPatterns(List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)"))
                .build();

        SchemaRelationCandidateProvider provider =
                SchemaRelationCandidateProvider.from(config, null, policy);

        assertEquals(List.of("WORKS_AT"),
                typesOf(provider.candidatesFor("PERSON", "ORGANIZATION", CONTEXT, 12)));
        assertTrue(provider.candidatesFor("ORGANIZATION", "ORGANIZATION", CONTEXT, 12).isEmpty(),
                "the policy's signature rules this pair out, so it is never offered");
    }
}
