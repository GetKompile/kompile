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

import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.FocusedMentionChoice;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.FocusedMentionDecision;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceRole;
import ai.kompile.core.graphrag.passes.ExtractionProposals.MentionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExtractionPassParsers}: every malformed answer must degrade to abstention,
 * never to a committing operation.
 */
class ExtractionPassParsersTest {

    private static final PassContext CONTEXT = PassContext.forChunk("chunk-1", "doc-1",
            "Acme Corp acquired Initech in 2019.");

    // ── pass 1 ────────────────────────────────────────────────────────

    @Test
    void parsesPropositionsThroughMarkdownFencesAndProse() {
        String response = """
                Sure, here is the analysis:
                ```json
                {"propositions":[
                  {"id":"p1","text":"Acme Corp acquired Initech in 2019","subject":"Acme Corp",
                   "predicate":"acquired","object":"Initech","polarity":"affirmed",
                   "modality":"factual","timeExpression":"2019",
                   "evidence":{"quote":"Acme Corp acquired Initech","start":0,"end":26,
                               "role":"direct_support"}}
                ]}
                ```
                Let me know if you need more.
                """;

        List<PropositionProposal> parsed =
                ExtractionPassParsers.propositions(response, CONTEXT, 10);

        assertEquals(1, parsed.size());
        PropositionProposal p = parsed.get(0);
        assertEquals("p1", p.id());
        assertEquals(Polarity.AFFIRMED, p.polarity());
        assertEquals(Modality.FACTUAL, p.modality());
        assertEquals("Acme Corp", p.subject());
        assertEquals(EvidenceRole.DIRECT_SUPPORT, p.evidence().role());
        assertEquals("chunk-1", p.evidence().chunkId());
    }

    @Test
    void parsesTheProductionSingletonPropositionContractAndAssignsTheId() {
        String response = """
                {"proposition":{"text":"Acme Corp acquired Initech in 2019.",
                  "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
                  "evidence":{"quote":"Acme Corp acquired Initech in 2019.",
                              "role":"DIRECT_SUPPORT"}}}
                """;

        List<PropositionProposal> parsed =
                ExtractionPassParsers.propositions(response, CONTEXT, 10);

        assertEquals(1, parsed.size());
        assertEquals("chunk-1:p1", parsed.get(0).id());
        assertEquals("Acme Corp", parsed.get(0).subject());
        assertEquals(Polarity.AFFIRMED, parsed.get(0).polarity());
        assertEquals("Acme Corp acquired Initech in 2019.", parsed.get(0).evidence().quote());
    }

    @Test
    void aNullSingletonPropositionMeansNoAssertion() {
        assertTrue(ExtractionPassParsers.propositions(
                "{\"proposition\":null}", CONTEXT, 10).isEmpty());
    }

    @Test
    void focusedPropositionParsesTheFlatDiscriminatedContract() {
        String response = """
                {"asserted":true,"subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
                  "condition":null,"attributedTo":null}
                """;
        String focus = "Acme Corp acquired Initech in 2019.";

        PropositionProposal parsed = ExtractionPassParsers
                .proposition(response, CONTEXT, focus).orElseThrow();

        assertEquals("chunk-1:p1", parsed.id());
        assertEquals(focus, parsed.text());
        assertEquals(focus, parsed.evidence().quote());
        assertEquals("Acme Corp", parsed.subject());
        assertEquals("acquired", parsed.predicate());
        assertEquals("Initech", parsed.object());
        assertEquals(Polarity.AFFIRMED, parsed.polarity());
        assertEquals(Modality.FACTUAL, parsed.modality());
        assertEquals("2019", parsed.timeExpression());
    }

    @Test
    void focusedPropositionMakesTextEvidenceAndIdEngineOwned() {
        String response = """
                {"proposition":{"subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
                  "text":"model paraphrase that must be ignored",
                  "evidence":{"quote":"fabricated quote"}}}
                """;
        String focus = "Acme Corp acquired Initech in 2019.";

        PropositionProposal parsed = ExtractionPassParsers
                .proposition(response, CONTEXT, focus).orElseThrow();

        assertEquals("chunk-1:p1", parsed.id());
        assertEquals(focus, parsed.text());
        assertEquals(focus, parsed.evidence().quote());
        assertEquals("Acme Corp", parsed.subject());
        assertEquals("Initech", parsed.object());
        assertEquals(Modality.FACTUAL, parsed.modality());
    }

    @Test
    void focusedPropositionAcceptsLegacyArrayButOnlyOneSibling() {
        String response = """
                {"propositions":[
                  {"subject":"Acme Corp","predicate":"acquired","object":"Initech"},
                  {"subject":"unrelated","predicate":"must not escape"}
                ]}
                """;

        PropositionProposal parsed = ExtractionPassParsers
                .proposition(response, CONTEXT, CONTEXT.sourceText()).orElseThrow();

        assertEquals("Acme Corp", parsed.subject());
        assertEquals("acquired", parsed.predicate());
    }

    @Test
    void focusedNullOrUnparseableOutputAbstains() {
        String flatAbstention = "{\"asserted\":false,\"subject\":null,\"predicate\":null,"
                + "\"object\":null,\"polarity\":null,\"modality\":null,"
                + "\"timeExpression\":null,\"condition\":null,\"attributedTo\":null}";
        assertTrue(ExtractionPassParsers
                .proposition(flatAbstention, CONTEXT, CONTEXT.sourceText()).isEmpty());
        assertTrue(ExtractionPassParsers.explicitNullProposition(flatAbstention));

        assertTrue(ExtractionPassParsers
                .proposition("{\"proposition\":null}", CONTEXT, CONTEXT.sourceText()).isEmpty());
        assertTrue(ExtractionPassParsers.explicitNullProposition("{\"proposition\":null}"));
        assertFalse(ExtractionPassParsers.explicitNullProposition(
                "{\"asserted\":\"false\",\"subject\":null}"));
        assertFalse(ExtractionPassParsers.explicitNullProposition(
                "{\"proposition\":\"wrong shape\"}"));
        assertTrue(ExtractionPassParsers
                .proposition("not json", CONTEXT, CONTEXT.sourceText()).isEmpty());
    }

    @Test
    void assignsDeterministicIdsWhenTheModelOmitsThem() {
        String response = """
                {"propositions":[{"text":"Acme acquired Initech","subject":"Acme"},
                                 {"text":"Initech was overvalued","subject":"Initech"}]}
                """;

        List<PropositionProposal> parsed =
                ExtractionPassParsers.propositions(response, CONTEXT, 10);

        assertEquals(List.of("chunk-1:p1", "chunk-1:p2"),
                parsed.stream().map(PropositionProposal::id).toList());
    }

    @Test
    void honoursOnlyExplicitPositiveCapsAndSkipsContentlessEntries() {
        String response = """
                {"propositions":[{"text":"one","subject":"a"},{"text":"two","subject":"b"},
                                 {"predicate":"dangling"},{"text":"three","subject":"c"}]}
                """;

        assertEquals(2, ExtractionPassParsers.propositions(response, CONTEXT, 2).size());
        assertEquals(3, ExtractionPassParsers.propositions(response, CONTEXT, 10).size());
        assertEquals(3, ExtractionPassParsers.propositions(response, CONTEXT, 0).size(),
                "zero means the engine must retain every model proposal");
        assertEquals(3, ExtractionPassParsers.propositions(response, CONTEXT, -1).size(),
                "legacy negative values are also treated as unbounded");
    }

    @Test
    void returnsNothingForUnparseableOrEmptyResponses() {
        assertTrue(ExtractionPassParsers.propositions(null, CONTEXT, 5).isEmpty());
        assertTrue(ExtractionPassParsers.propositions("I cannot help with that.", CONTEXT, 5)
                .isEmpty());
        assertTrue(ExtractionPassParsers.propositions("{\"propositions\":[]}", CONTEXT, 5)
                .isEmpty());
    }

    // ── pass 2 ────────────────────────────────────────────────────────

    @Test
    void parsesMentionsWithLowerCaseOperationsAndPercentageConfidence() {
        String response = """
                {"mentions":[{"mentionText":"Acme Corp","mentionRole":"subject",
                  "operation":"reuse_entity","selectedEntityId":"ent-acme","confidence":87,
                  "alternatives":[{"candidateId":"ent-acme-holdings","score":0.2,
                                   "reason":"different legal entity"}],
                  "reason":"same company","evidence":{"quote":"Acme Corp"}}]}
                """;

        List<MentionProposal> parsed =
                ExtractionPassParsers.mentions(response, CONTEXT, "p1");

        assertEquals(1, parsed.size());
        MentionProposal mention = parsed.get(0);
        assertEquals(ProposalOperation.REUSE_ENTITY, mention.operation());
        assertEquals("ent-acme", mention.selectedEntityId());
        assertEquals(0.87d, mention.confidence(), 1e-9);
        assertEquals(1, mention.alternatives().size());
        assertEquals("ent-acme-holdings", mention.alternatives().get(0).candidateId());
        assertEquals("p1", mention.propositionId());
    }

    @Test
    void downgradesReuseWithoutAnIdToUnresolved() {
        String response = """
                {"mentions":[{"mentionText":"Acme","operation":"REUSE_ENTITY",
                              "selectedEntityId":null,"confidence":0.9}]}
                """;

        MentionProposal mention = ExtractionPassParsers.mentions(response, CONTEXT, "p1").get(0);

        assertEquals(ProposalOperation.UNRESOLVED, mention.operation());
        assertNull(mention.selectedEntityId());
    }

    @Test
    void fallsBackToTheSurfaceFormWhenANewEntityIsUnnamed() {
        String response = """
                {"mentions":[{"mentionText":"Initech","operation":"CREATE_PROVISIONAL_ENTITY",
                              "provisionalType":"ORGANIZATION"}]}
                """;

        MentionProposal mention = ExtractionPassParsers.mentions(response, CONTEXT, "p1").get(0);

        assertEquals(ProposalOperation.CREATE_PROVISIONAL_ENTITY, mention.operation());
        assertEquals("Initech", mention.provisionalName());
    }

    @Test
    void unknownOperationLabelsBecomeAbstentions() {
        String response = """
                {"mentions":[{"mentionText":"Acme","operation":"do_whatever_you_think",
                              "selectedEntityId":"ent-acme"}]}
                """;

        MentionProposal mention = ExtractionPassParsers.mentions(response, CONTEXT, "p1").get(0);

        assertTrue(mention.operation().isAbstention());
    }

    @Test
    void acceptsASingleMentionObjectInsteadOfAnArray() {
        String response = """
                {"mentions":{"mentionText":"Acme","operation":"CREATE_PROVISIONAL_ENTITY",
                             "provisionalName":"Acme Corp"}}
                """;

        assertEquals(1, ExtractionPassParsers.mentions(response, CONTEXT, "p1").size());
    }

    @Test
    void parsesTheFocusedSingletonMentionContractWithoutRedundantEngineFields() {
        String response = """
                {"mention":{"selectedEntityId":"ent-acme",
                            "confidence":0.96,"reason":"exact candidate"}}
                """;

        MentionProposal mention =
                ExtractionPassParsers.mention(response, CONTEXT, "p1").orElseThrow();

        assertEquals(ProposalOperation.REUSE_ENTITY, mention.operation());
        assertEquals("ent-acme", mention.selectedEntityId());
        assertNull(mention.mentionText());
        assertNull(mention.mentionRole());
    }

    @Test
    void focusedMentionOperationIsDerivedFromMutuallyExclusiveChoiceFields() {
        MentionProposal reuseDespiteContradictoryLegacyLabel = ExtractionPassParsers.mention("""
                {"mention":{"operation":"CREATE_PROVISIONAL_ENTITY",
                            "selectedEntityId":"ent-acme","provisionalName":"Acme"}}
                """, CONTEXT, "p1").orElseThrow();
        MentionProposal create = ExtractionPassParsers.mention("""
                {"mention":{"selectedEntityId":null,"provisionalName":"Newco",
                            "provisionalType":"ORGANIZATION"}}
                """, CONTEXT, "p1").orElseThrow();
        MentionProposal unresolved = ExtractionPassParsers.mention("""
                {"mention":{"selectedEntityId":null,"provisionalName":null,
                            "provisionalType":null,"reason":"ambiguous"}}
                """, CONTEXT, "p1").orElseThrow();

        assertEquals(ProposalOperation.REUSE_ENTITY,
                reuseDespiteContradictoryLegacyLabel.operation());
        assertEquals(ProposalOperation.CREATE_PROVISIONAL_ENTITY, create.operation());
        assertEquals(ProposalOperation.UNRESOLVED, unresolved.operation());
        assertFalse(ExtractionPassParsers.mentionInternallyConsistent(
                reuseDespiteContradictoryLegacyLabel, false));
        assertTrue(ExtractionPassParsers.mentionInternallyConsistent(create, false));
        assertTrue(ExtractionPassParsers.mentionInternallyConsistent(unresolved, false));
    }

    @Test
    void parsesAndValidatesTheProductionOrdinalMentionBranches() {
        FocusedMentionDecision reuse = ExtractionPassParsers.focusedMentionDecision("""
                {"mention":{"decision":"REUSE","candidateOrdinal":2,
                            "confidence":0.96,"reason":"identifier match"}}
                """).orElseThrow();
        FocusedMentionDecision create = ExtractionPassParsers.focusedMentionDecision("""
                {"mention":{"decision":"CREATE_PROVISIONAL","provisionalType":"PRODUCT",
                            "confidence":0.8}}
                """).orElseThrow();
        FocusedMentionDecision contradictory = ExtractionPassParsers.focusedMentionDecision("""
                {"mention":{"decision":"REUSE","candidateOrdinal":1,
                            "selectedEntityId":"must-not-be-model-owned"}}
                """).orElseThrow();
        FocusedMentionDecision extraRootField = ExtractionPassParsers.focusedMentionDecision("""
                {"mention":{"decision":"CREATE_PROVISIONAL","confidence":0.8},
                 "mentionText":"Acme"}
                """).orElseThrow();
        FocusedMentionDecision extraNestedField = ExtractionPassParsers.focusedMentionDecision("""
                {"mention":{"decision":"REUSE","candidateOrdinal":1,
                            "proposalId":"model-owned-id"}}
                """).orElseThrow();

        assertEquals(FocusedMentionChoice.REUSE, reuse.choice());
        assertEquals(2, reuse.candidateOrdinal());
        assertTrue(ExtractionPassParsers.focusedMentionInternallyConsistent(reuse, true, true));
        assertTrue(ExtractionPassParsers.focusedMentionInternallyConsistent(create, false, false));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                contradictory, true, true));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                extraRootField, false, true));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                extraNestedField, true, true));
    }

    @Test
    void nonMergingMentionBranchesIgnoreStaleOrdinalsButRejectPlaceholderTypes() {
        FocusedMentionDecision typedCreateWithStaleOrdinal =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"CREATE_PROVISIONAL","candidateOrdinal":1,
                                    "confidence":0.8}}
                        """).orElseThrow();
        FocusedMentionDecision unresolvedWithStaleOrdinal =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"UNRESOLVED","candidateOrdinal":1,
                                    "confidence":0.4}}
                        """).orElseThrow();
        FocusedMentionDecision placeholderType =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"CREATE_PROVISIONAL",
                                    "provisionalType":"TYPE_FROM_SOURCE","confidence":0.7}}
                        """).orElseThrow();
        FocusedMentionDecision echoedTemplateType =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"CREATE_PROVISIONAL",
                                    "provisionalType":"__SOURCE_OR_SCHEMA_TYPE__","confidence":0.7}}
                        """).orElseThrow();
        FocusedMentionDecision invalidFormatType =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"CREATE_PROVISIONAL",
                                    "provisionalType":"sheet type","confidence":0.7}}
                        """).orElseThrow();
        FocusedMentionDecision concreteType =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"CREATE_PROVISIONAL",
                                    "provisionalType":"SHEET","confidence":0.7}}
                        """).orElseThrow();
        FocusedMentionDecision ballotType =
                ExtractionPassParsers.focusedMentionDecision("""
                        {"mention":{"decision":"CREATE_PROVISIONAL",
                                    "provisionalTypeOrdinal":2,"confidence":0.7}}
                        """).orElseThrow();

        assertTrue(ExtractionPassParsers.focusedMentionInternallyConsistent(
                typedCreateWithStaleOrdinal, true, true));
        assertTrue(ExtractionPassParsers.focusedMentionInternallyConsistent(
                unresolvedWithStaleOrdinal, true, false));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                placeholderType, false, false));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                echoedTemplateType, false, false));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                invalidFormatType, false, false));
        assertTrue(ExtractionPassParsers.focusedMentionInternallyConsistent(
                concreteType, false, false));
        assertTrue(ExtractionPassParsers.focusedMentionInternallyConsistent(
                ballotType, false, false, true));
        assertFalse(ExtractionPassParsers.focusedMentionInternallyConsistent(
                ballotType, false, false, false));
    }

    @Test
    void focusedMentionLegacyOperationCanBePreservedForControlledAblation() {
        MentionProposal mention = ExtractionPassParsers.mention("""
                {"mention":{"operation":"CREATE_PROVISIONAL_ENTITY",
                            "selectedEntityId":"ent-acme","provisionalName":"Acme"}}
                """, CONTEXT, "p1", false).orElseThrow();

        assertEquals(ProposalOperation.CREATE_PROVISIONAL_ENTITY, mention.operation());
    }

    // ── pass 3 ────────────────────────────────────────────────────────

    @Test
    void parsesEpistemicClassificationWithSynonyms() {
        String response = """
                {"classification":{"speechAct":"belief","holder":"Bob","certainty":0.6,
                  "reason":"'said' marks attribution",
                  "evidence":{"quote":"Bob said","role":"attribution"}}}
                """;

        EpistemicProposal epistemic =
                ExtractionPassParsers.epistemic(response, CONTEXT, "p1").orElseThrow();

        assertEquals(SpeechAct.OPINION, epistemic.speechAct());
        assertEquals("Bob", epistemic.holder());
        assertEquals(0.6d, epistemic.certainty(), 1e-9);
        assertEquals(EvidenceRole.ATTRIBUTION, epistemic.evidence().role());
    }

    @Test
    void unknownSpeechActLabelsAreNotPromotable() {
        String response = "{\"classification\":{\"speechAct\":\"vibes\"}}";

        EpistemicProposal epistemic =
                ExtractionPassParsers.epistemic(response, CONTEXT, "p1").orElseThrow();

        assertEquals(SpeechAct.UNKNOWN, epistemic.speechAct());
        assertTrue(!epistemic.speechAct().isDirectlyPromotable());
    }

    @Test
    void missingClassificationYieldsNoProposal() {
        assertEquals(Optional.empty(), ExtractionPassParsers.epistemic("no json", CONTEXT, "p1"));
    }

    // ── pass 4 ────────────────────────────────────────────────────────

    @Test
    void parsesRelationSelectionIncludingQualifiersAndAlternatives() {
        String response = """
                {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.82,
                  "occurredAt":"2019","qualifiers":{"currency":"USD","asOf":"2019-12-31"},
                  "alternatives":[{"candidateId":"PARTNERED_WITH","score":0.1,
                                   "reason":"text says acquired"}],
                  "reason":"explicit acquisition verb",
                  "evidence":{"quote":"acquired Initech"}}}
                """;

        RelationProposal relation = ExtractionPassParsers
                .relation(response, CONTEXT, "p1", "ent-acme", "ent-initech").orElseThrow();

        assertEquals(ProposalOperation.CREATE_CLAIM, relation.operation());
        assertEquals("ACQUIRED", relation.type());
        assertEquals("ent-acme", relation.sourceEntityId());
        assertEquals("ent-initech", relation.targetEntityId());
        assertEquals("2019", relation.occurredAt());
        assertEquals("USD", relation.qualifiers().get("currency"));
        assertEquals(1, relation.alternatives().size());
        assertTrue(relation.committing());
    }

    @Test
    void relationWithoutATypeCannotCommit() {
        String response = "{\"relation\":{\"operation\":\"CREATE_CLAIM\",\"type\":null}}";

        RelationProposal relation = ExtractionPassParsers
                .relation(response, CONTEXT, "p1", "a", "b").orElseThrow();

        assertEquals(ProposalOperation.ABSTAIN, relation.operation());
        assertTrue(!relation.committing());
    }

    @Test
    void parsesNeutralVerdictRelationExistenceContract() {
        ExtractionPassParsers.RelationExistenceDecision asserted =
                ExtractionPassParsers.relationExistence(
                        "{\"decision\":{\"verdict\":\"ASSERTED\",\"confidence\":0.9}}")
                        .orElseThrow();
        ExtractionPassParsers.RelationExistenceDecision absent =
                ExtractionPassParsers.relationExistence(
                        "{\"decision\":{\"verdict\":\"NOT_ASSERTED\",\"confidence\":0.8}}")
                        .orElseThrow();

        assertEquals(ExtractionPassParsers.RelationExistence.ASSERTED, asserted.decision());
        assertEquals(ExtractionPassParsers.RelationExistence.NOT_ASSERTED, absent.decision());
    }

    @Test
    void normalizesSafeNumericRelationTypeShorthandToTheEngineOwnedOrdinal() {
        ExtractionPassParsers.RelationTypeDecision decision = ExtractionPassParsers.relationType("""
                {"selection":2,"schemaGap":false,"confidence":1.0,"qualifiers":{},
                 "reason":"exact predicate match"}
                """).orElseThrow();

        assertEquals(2, decision.candidateOrdinal());
        assertFalse(decision.schemaGap());
        assertEquals("exact predicate match", decision.reason());
    }

    @Test
    void retainsACompleteRelationProposalMissingOnlyTheFinalOuterBrace() {
        ExtractionPassParsers.RelationTypeDecision decision = ExtractionPassParsers.relationType("""
                {"selection":{"decision":"SELECT","candidateOrdinal":1,"schemaGap":false,
                 "confidence":0.5,"qualifiers":{},
                 "reason":"candidate [1] matches the source predicate and direction"}
                """).orElseThrow();

        assertEquals(ExtractionPassParsers.RelationTypeDisposition.SELECT,
                decision.disposition());
        assertEquals(1, decision.candidateOrdinal());
        assertTrue(ExtractionPassParsers.relationTypeInternallyConsistent(decision));
    }

    @Test
    void relationTypeReasonIsExplanatoryAndNeverAHiddenLanguageSpecificControlField() {
        ExtractionPassParsers.RelationTypeDecision selected =
                ExtractionPassParsers.relationType("""
                        {"selection":{"decision":"SELECT","candidateOrdinal":3,
                         "schemaGap":false,"confidence":1.0,
                         "reason":"任意の自由形式の説明"}}
                        """).orElseThrow();
        ExtractionPassParsers.RelationTypeDecision schemaGap =
                ExtractionPassParsers.relationType("""
                        {"selection":{"decision":"SCHEMA_GAP","candidateOrdinal":null,
                         "schemaGap":true,"confidence":1.0,
                         "reason":"another free-form explanation"}}
                        """).orElseThrow();

        assertTrue(ExtractionPassParsers.relationTypeInternallyConsistent(selected));
        assertTrue(ExtractionPassParsers.relationTypeInternallyConsistent(schemaGap));
        assertEquals("任意の自由形式の説明", selected.reason());
    }

    @Test
    void schemaGapSelectionMayOmitTheInapplicableNullableOrdinal() {
        ExtractionPassParsers.RelationTypeDecision gap =
                ExtractionPassParsers.relationType("""
                        {"selection":{"schemaGap":true,"confidence":1.0,"qualifiers":{},
                         "reason":"no permitted candidate expresses the predicate"}}
                        """).orElseThrow();

        assertNull(gap.candidateOrdinal());
        assertTrue(gap.schemaGap());
        assertTrue(ExtractionPassParsers.relationTypeInternallyConsistent(gap));
    }

    @Test
    void relationContinuationDoneIsExplicitAndOnlyValidAfterASelection() {
        ExtractionPassParsers.RelationTypeDecision done =
                ExtractionPassParsers.relationType("""
                        {"selection":{"decision":"DONE","candidateOrdinal":null,
                         "schemaGap":false,"confidence":0.9,"qualifiers":{},
                         "reason":"no additional distinct relation is asserted"}}
                        """).orElseThrow();

        assertEquals(ExtractionPassParsers.RelationTypeDisposition.DONE, done.disposition());
        assertTrue(ExtractionPassParsers.relationTypeInternallyConsistent(done));
        assertFalse(ExtractionPassParsers.relationTypeAllowedForRound(done, false));
        assertTrue(ExtractionPassParsers.relationTypeAllowedForRound(done, true));
    }

    @Test
    void recognizesMalformedLfmContinuationIntentWithoutAcceptingItsShape() {
        String observedLfmResponse = """
                {"selection":null,"schemaGap":false,"confidence":0.5,"qualifiers":{},
                 "reason":"No additional semantically distinct relation type was identified."}
                """;

        assertTrue(ExtractionPassParsers.relationType(observedLfmResponse).isEmpty());
        assertTrue(ExtractionPassParsers
                .relationTypeContinuationCompletionIntent(observedLfmResponse));
    }

    @Test
    void doneCannotCarryACandidateOrdinal() {
        ExtractionPassParsers.RelationTypeDecision contradictory =
                ExtractionPassParsers.relationType("""
                        {"selection":{"decision":"DONE","candidateOrdinal":1,
                         "schemaGap":false,"confidence":0.9,"qualifiers":{},
                         "reason":"no additional relation"}}
                        """).orElseThrow();

        assertFalse(ExtractionPassParsers.relationTypeInternallyConsistent(contradictory));
    }

    @Test
    void schemaGapIsPreservedRatherThanForcedIntoAType() {
        String response = """
                {"relation":{"operation":"PROPOSE_SCHEMA_GAP","type":null,
                  "reason":"no permitted type expresses 'spun off'"}}
                """;

        RelationProposal relation = ExtractionPassParsers
                .relation(response, CONTEXT, "p1", "a", "b").orElseThrow();

        assertEquals(ProposalOperation.PROPOSE_SCHEMA_GAP, relation.operation());
        assertTrue(relation.reason().contains("spun off"));
    }

    // ── pass 5 ────────────────────────────────────────────────────────

    @Test
    void parsesClaimDecisionAndKeepsTheRelationKey() {
        String response = """
                {"decision":{"operation":"ADD_EVIDENCE","matchedAtomKey":"atom-1",
                  "confidence":0.75,"reason":"same acquisition",
                  "evidence":{"quote":"acquired Initech"}}}
                """;

        ClaimProposal claim = ExtractionPassParsers
                .claim(response, CONTEXT, "p1", "p1|a|ACQUIRED|b").orElseThrow();

        assertEquals(ProposalOperation.ADD_EVIDENCE, claim.operation());
        assertEquals("atom-1", claim.matchedAtomKey());
        assertEquals("p1|a|ACQUIRED|b", claim.relationKey());
    }

    @Test
    void mapsCandidateOrdinalToTheExactEngineOwnedAtomKey() {
        String response = """
                {"decision":{"operation":"ADD_EVIDENCE","matchedCandidateOrdinal":"2",
                  "confidence":0.75,"reason":"same acquisition"}}
                """;
        List<ClaimCandidate> ballot = List.of(
                ClaimCandidate.of("opaque-atom-A", "unrelated claim", 0.4),
                ClaimCandidate.of("opaque-atom-B", "same acquisition", 0.8));

        ClaimProposal claim = ExtractionPassParsers
                .claim(response, CONTEXT, "p1", "relation-key", ballot).orElseThrow();

        assertEquals(ProposalOperation.ADD_EVIDENCE, claim.operation());
        assertEquals("opaque-atom-B", claim.matchedAtomKey());
        assertEquals("relation-key", claim.relationKey());
    }

    @Test
    void sameClaimComparisonCannotOverrideAKnownAtomFieldMismatch() {
        RelationProposal relation = new RelationProposal("p1", "ent-acme", "ent-initech",
                "ACQUIRED", ProposalOperation.CREATE_CLAIM, 0.9, null, java.util.Map.of(),
                List.of(), null, null);
        ClaimCandidate differentSubject = new ClaimCandidate("atom-1", "ent-globex", "ACQUIRED",
                "ent-initech", 0.8, 1, 0, "Globex acquired Initech");
        var same = ExtractionPassParsers.claimComparison(
                "{\"comparison\":{\"relationship\":\"SAME\"}}").orElseThrow();
        var different = ExtractionPassParsers.claimComparison(
                "{\"comparison\":{\"relationship\":\"DIFFERENT\"}}").orElseThrow();

        assertFalse(ExtractionPassParsers.claimComparisonInternallyConsistent(
                same, relation, differentSubject));
        assertTrue(ExtractionPassParsers.claimComparisonInternallyConsistent(
                different, relation, differentSubject));
    }

    @Test
    void evidenceAttachmentWithoutAnAtomKeyBecomesAbstention() {
        String response = "{\"decision\":{\"operation\":\"ADD_EVIDENCE\"}}";

        ClaimProposal claim = ExtractionPassParsers
                .claim(response, CONTEXT, "p1", "key").orElseThrow();

        assertEquals(ProposalOperation.ABSTAIN, claim.operation());
    }

    @Test
    void contradictionWithoutAnAtomKeyBecomesAbstention() {
        String response = "{\"decision\":{\"operation\":\"FLAG_CONTRADICTION\"}}";

        ClaimProposal claim = ExtractionPassParsers
                .claim(response, CONTEXT, "p1", "key").orElseThrow();

        assertEquals(ProposalOperation.ABSTAIN, claim.operation());
    }

    @Test
    void toleratesAFlatDecisionWithoutTheWrapperKey() {
        String response = "{\"operation\":\"CREATE_CLAIM\",\"confidence\":0.4}";

        ClaimProposal claim = ExtractionPassParsers
                .claim(response, CONTEXT, "p1", "key").orElseThrow();

        assertEquals(ProposalOperation.CREATE_CLAIM, claim.operation());
        assertEquals(0.4d, claim.confidence(), 1e-9);
    }

    // ── shared ────────────────────────────────────────────────────────

    @Test
    void treatsPlaceholderStringsAsAbsentValues() {
        String response = """
                {"mentions":[{"mentionText":"Acme","operation":"CREATE_PROVISIONAL_ENTITY",
                  "provisionalName":"Acme","provisionalType":"null","reason":"N/A"}]}
                """;

        MentionProposal mention = ExtractionPassParsers.mentions(response, CONTEXT, "p1").get(0);

        assertNull(mention.provisionalType());
        assertNull(mention.reason());
    }

    @Test
    void acceptsAnInlineQuoteWhenTheEvidenceObjectIsOmitted() {
        String response = """
                {"propositions":[{"text":"Acme acquired Initech","subject":"Acme",
                                  "quote":"Acme Corp acquired Initech"}]}
                """;

        PropositionProposal p = ExtractionPassParsers.propositions(response, CONTEXT, 5).get(0);

        assertEquals("Acme Corp acquired Initech", p.evidence().quote());
        assertEquals(EvidenceRole.DIRECT_SUPPORT, p.evidence().role());
    }
}
