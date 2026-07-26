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

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.passes.ExtractionProjection.Props;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Alternative;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceRole;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.MentionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PassBundle;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExtractionProjection} — the epistemic firewall between what a model proposed
 * and what the graph is allowed to hold.
 */
class ExtractionProjectionTest {

    private static final PassContext CONTEXT = PassContext
            .forChunk("chunk-1", "doc-1", "Acme Corp acquired Initech in 2019.")
            .withGraph("graph-7", "graph-6")
            .withSchema("kompile-graph-extraction/v1", "test-model");

    private static PropositionProposal proposition(Polarity polarity, Modality modality) {
        return new PropositionProposal("p1", "Acme Corp acquired Initech in 2019", "Acme Corp",
                "acquired", "Initech", polarity, modality, "2019", null, null,
                EvidenceSpan.ofQuote("chunk-1", "Acme Corp acquired Initech"));
    }

    private static MentionProposal reuse(String entityId, String text, String role) {
        return new MentionProposal("p1", text, role, ProposalOperation.REUSE_ENTITY, entityId,
                null, null, 0.9, List.of(new Alternative("ent-other", 0.2, "different company")),
                "same organization", EvidenceSpan.ofQuote("chunk-1", text));
    }

    private static MentionProposal provisional(String name, String type, String role) {
        return new MentionProposal("p1", name, role,
                ProposalOperation.CREATE_PROVISIONAL_ENTITY, null, name, type, 0.6, List.of(),
                "no candidate matched", EvidenceSpan.ofQuote("chunk-1", name));
    }

    private static RelationProposal relation(String source, String target, double confidence,
                                             Map<String, String> qualifiers) {
        return new RelationProposal("p1", source, target, "ACQUIRED",
                ProposalOperation.CREATE_CLAIM, confidence, "2019", qualifiers,
                List.of(new Alternative("PARTNERED_WITH", 0.1, "text says acquired")),
                "explicit acquisition verb",
                EvidenceSpan.ofQuote("chunk-1", "acquired Initech"));
    }

    private static Optional<ExtractedRelation> onlyRelation(ExtractionProjection.Projection p) {
        return p.result().relations().stream().findFirst();
    }

    @Test
    void reusedMentionKeepsTheGraphIdAndProvisionalMentionGetsADeterministicSlug() {
        PassBundle bundle = new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        provisional("Initech Inc", "ORGANIZATION", "OBJECT")),
                List.of(new EpistemicProposal("p1", SpeechAct.ASSERTION, null, 0.9, "stated",
                        null)),
                List.of(), List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        List<String> ids = projection.result().entities().stream()
                .map(ExtractedEntity::id).toList();
        assertEquals(List.of("ent-acme", "initech_inc"), ids);

        ExtractedEntity provisionalEntity = projection.result().entities().get(1);
        assertEquals("true", provisionalEntity.properties().get(Props.PROVISIONAL));
        assertEquals("ORGANIZATION", provisionalEntity.type());
        assertEquals("false",
                projection.result().entities().get(0).properties().get(Props.PROVISIONAL));
    }

    @Test
    void unresolvedMentionsAreWithheldWithAReason() {
        MentionProposal unresolved = new MentionProposal("p1", "the company", "SUBJECT",
                ProposalOperation.UNRESOLVED, null, null, null, 0.3, List.of(),
                "two equally plausible candidates", null);
        PassBundle bundle = new PassBundle(List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(unresolved), List.of(), List.of(), List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        assertTrue(projection.result().entities().isEmpty());
        assertTrue(projection.notes().stream()
                .anyMatch(n -> n.contains("UNRESOLVED") && n.contains("equally plausible")));
    }

    @Test
    void mentionsOfTheSameEntityMergeIntoOneNodeWithAliases() {
        PassBundle bundle = new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        reuse("ent-acme", "Acme", "OTHER")),
                List.of(), List.of(), List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        assertEquals(1, projection.result().entities().size());
        ExtractedEntity entity = projection.result().entities().get(0);
        assertEquals("Acme Corp", entity.name());
        assertTrue(entity.aliases().contains("Acme"));
    }

    @Test
    void assertedRelationKeepsItsConfidenceAndIsMarkedAssertable() {
        ExtractionProjection.Projection projection = project(SpeechAct.ASSERTION, 0.9,
                Polarity.AFFIRMED, Modality.FACTUAL, 0.9, null);

        ExtractedRelation relation = onlyRelation(projection).orElseThrow();
        assertEquals(0.9d, relation.confidence(), 1e-9);
        assertEquals("true", relation.properties().get(Props.ASSERTABLE));
        assertEquals("ASSERTION", relation.properties().get(Props.EPISTEMIC));
        assertEquals("2019", relation.occurredAt());
    }

    @Test
    void opinionIsRecordedAttributedDampedAndNotAssertable() {
        ExtractionProjection.Projection projection = project(SpeechAct.OPINION, 0.6,
                Polarity.AFFIRMED, Modality.FACTUAL, 0.9, "Bob");

        ExtractedRelation relation = onlyRelation(projection).orElseThrow();
        assertEquals("false", relation.properties().get(Props.ASSERTABLE));
        assertEquals("OPINION", relation.properties().get(Props.EPISTEMIC));
        assertEquals("Bob", relation.properties().get(Props.EPISTEMIC_HOLDER));
        assertTrue(relation.confidence() <= ExtractionProjection.ATTRIBUTED_CONFIDENCE_CEILING,
                "attributed material must stay below the promotion ceiling");
    }

    @Test
    void unclassifiedPropositionsAreNotTreatedAsFacts() {
        ExtractionProjection.Projection projection = project(SpeechAct.UNKNOWN, 0.5,
                Polarity.AFFIRMED, Modality.FACTUAL, 0.95, null);

        assertEquals("false",
                onlyRelation(projection).orElseThrow().properties().get(Props.ASSERTABLE));
    }

    @Test
    void hypotheticalPropositionsAreNotAssertableEvenWhenStatedPlainly() {
        ExtractionProjection.Projection projection = project(SpeechAct.ASSERTION, 0.9,
                Polarity.AFFIRMED, Modality.HYPOTHETICAL, 0.9, null);

        ExtractedRelation relation = onlyRelation(projection).orElseThrow();
        assertEquals("false", relation.properties().get(Props.ASSERTABLE));
        assertEquals("HYPOTHETICAL", relation.properties().get(Props.MODALITY));
    }

    @Test
    void negatedPropositionsAreWithheldRatherThanAssertedPositively() {
        ExtractionProjection.Projection projection = project(SpeechAct.ASSERTION, 0.9,
                Polarity.NEGATED, Modality.FACTUAL, 0.9, null);

        assertTrue(projection.result().relations().isEmpty());
        assertTrue(projection.notes().stream().anyMatch(n -> n.contains("source negates it")));
    }

    @Test
    void relationsWithAnUnprojectedEndpointAreWithheld() {
        PassBundle bundle = new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT")),
                List.of(new EpistemicProposal("p1", SpeechAct.ASSERTION, null, 0.9, null, null)),
                List.of(relation("ent-acme", "ent-ghost", 0.9, Map.of())),
                List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        assertTrue(projection.result().relations().isEmpty());
        assertTrue(projection.notes().stream().anyMatch(n -> n.contains("endpoint unresolved")));
    }

    @Test
    void claimAbstentionWithholdsTheRelation() {
        PassBundle bundle = bundleWithClaim(new ClaimProposal("p1",
                claimKey(), ProposalOperation.ABSTAIN, null, 0.4, List.of(),
                "cannot tell whether it matches", null));

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        assertTrue(projection.result().relations().isEmpty());
        assertTrue(projection.notes().stream().anyMatch(n -> n.contains("claim matching abstained")));
    }

    @Test
    void flaggedContradictionIsHandedOnMarkedAndUnassertable() {
        PassBundle bundle = bundleWithClaim(new ClaimProposal("p1", claimKey(),
                ProposalOperation.FLAG_CONTRADICTION, "atom-1", 0.8, List.of(),
                "existing claim says otherwise", null));

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        ExtractedRelation relation = onlyRelation(projection).orElseThrow();
        assertEquals("FLAG_CONTRADICTION", relation.properties().get(Props.CLAIM_OPERATION));
        assertEquals("atom-1", relation.properties().get(Props.CLAIM_ATOM_KEY));
        assertEquals("false", relation.properties().get(Props.ASSERTABLE),
                "resolution belongs to the truth-maintenance system, not the extractor");
    }

    @Test
    void evidenceQualifiersAndRejectedAlternativesSurviveIntoProperties() {
        PassBundle bundle = new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        reuse("ent-initech", "Initech", "OBJECT")),
                List.of(new EpistemicProposal("p1", SpeechAct.ASSERTION, null, 0.9, null, null)),
                List.of(relation("ent-acme", "ent-initech", 0.9,
                        Map.of("currency", "USD", "asOf", "2019-12-31"))),
                List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        ExtractedRelation relation = onlyRelation(projection).orElseThrow();
        assertEquals("USD", relation.properties().get(Props.QUALIFIER_PREFIX + "currency"));
        assertEquals("2019-12-31", relation.properties().get(Props.QUALIFIER_PREFIX + "asOf"));
        assertEquals("acquired Initech", relation.properties().get(Props.EVIDENCE_QUOTE));
        assertEquals(EvidenceRole.DIRECT_SUPPORT.name(),
                relation.properties().get(Props.EVIDENCE_ROLE));
        assertTrue(relation.properties().get(Props.REJECTED_CANDIDATES)
                .contains("PARTNERED_WITH"));
        assertEquals("p1", relation.properties().get(Props.PROPOSITION_ID));

        ExtractedEntity entity = projection.result().entities().get(0);
        assertEquals("chunk-1", entity.properties().get(Props.SOURCE_CHUNK_ID));
        assertTrue(entity.properties().get(Props.REJECTED_CANDIDATES).contains("ent-other"));
    }

    @Test
    void metadataCarriesThePinnedGraphRevision() {
        ExtractionProjection.Projection projection = project(SpeechAct.ASSERTION, 0.9,
                Polarity.AFFIRMED, Modality.FACTUAL, 0.9, null);

        assertEquals("graph-7", projection.result().metadata().graphId());
        assertEquals("graph-6", projection.result().metadata().parentGraphId());
        assertEquals("test-model", projection.result().metadata().extractionModel());
        assertEquals("chunk-1", projection.result().metadata().sourceChunkId());
    }

    @Test
    void abstainedRelationIsWithheldWithItsReason() {
        RelationProposal abstained = new RelationProposal("p1", "ent-acme", "ent-initech", null,
                ProposalOperation.ABSTAIN, 0.2, null, Map.of(), List.of(),
                "no relation asserted between these two", null);
        PassBundle bundle = new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        reuse("ent-initech", "Initech", "OBJECT")),
                List.of(), List.of(abstained), List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        assertTrue(projection.result().relations().isEmpty());
        assertTrue(projection.notes().stream()
                .anyMatch(n -> n.contains("ABSTAIN") && n.contains("no relation asserted")));
        assertFalse(projection.result().entities().isEmpty(), "entities still project");
    }

    @Test
    void schemaGapIsCarriedButNeverAssertable() {
        RelationProposal gap = new RelationProposal("p1", "ent-acme", "ent-initech",
                "SECRETLY_CONTROLS", ProposalOperation.PROPOSE_SCHEMA_GAP, 0.95, null, Map.of(),
                List.of(), "type not in permitted set: SECRETLY_CONTROLS",
                EvidenceSpan.ofQuote("chunk-1", "acquired Initech"));
        PassBundle bundle = new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        reuse("ent-initech", "Initech", "OBJECT")),
                List.of(new EpistemicProposal("p1", SpeechAct.ASSERTION, null, 0.9, null, null)),
                List.of(gap), List.of());

        ExtractionProjection.Projection projection =
                ExtractionProjection.project(bundle, CONTEXT);

        ExtractedRelation relation = onlyRelation(projection).orElseThrow();
        assertEquals("true", relation.properties().get(Props.SCHEMA_GAP));
        assertEquals("false", relation.properties().get(Props.ASSERTABLE),
                "an unadmitted type must not promote on the model's say-so");
        assertTrue(relation.confidence() <= ExtractionProjection.ATTRIBUTED_CONFIDENCE_CEILING);
    }

    @Test
    void emptyBundleProjectsAnEmptyResultRatherThanFailing() {
        ExtractionProjection.Projection projection =
                ExtractionProjection.project(PassBundle.empty(), CONTEXT);

        assertTrue(projection.result().entities().isEmpty());
        assertTrue(projection.result().relations().isEmpty());
    }

    private static String claimKey() {
        return ExtractionProjection.relationKey(
                relation("ent-acme", "ent-initech", 0.9, Map.of()));
    }

    private static PassBundle bundleWithClaim(ClaimProposal claim) {
        return new PassBundle(
                List.of(proposition(Polarity.AFFIRMED, Modality.FACTUAL)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        reuse("ent-initech", "Initech", "OBJECT")),
                List.of(new EpistemicProposal("p1", SpeechAct.ASSERTION, null, 0.9, null, null)),
                List.of(relation("ent-acme", "ent-initech", 0.9, Map.of())),
                List.of(claim));
    }

    private static ExtractionProjection.Projection project(SpeechAct act, double certainty,
                                                           Polarity polarity, Modality modality,
                                                           double confidence, String holder) {
        PassBundle bundle = new PassBundle(
                List.of(proposition(polarity, modality)),
                List.of(reuse("ent-acme", "Acme Corp", "SUBJECT"),
                        reuse("ent-initech", "Initech", "OBJECT")),
                List.of(new EpistemicProposal("p1", act, holder, certainty, "classified", null)),
                List.of(relation("ent-acme", "ent-initech", confidence, Map.of())),
                List.of());
        return ExtractionProjection.project(bundle, CONTEXT);
    }
}
