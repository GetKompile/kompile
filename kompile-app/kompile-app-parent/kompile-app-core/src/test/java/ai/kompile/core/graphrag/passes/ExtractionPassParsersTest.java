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
    void honoursThePropositionCapAndSkipsContentlessEntries() {
        String response = """
                {"propositions":[{"text":"one","subject":"a"},{"text":"two","subject":"b"},
                                 {"predicate":"dangling"},{"text":"three","subject":"c"}]}
                """;

        assertEquals(2, ExtractionPassParsers.propositions(response, CONTEXT, 2).size());
        assertEquals(3, ExtractionPassParsers.propositions(response, CONTEXT, 10).size());
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
