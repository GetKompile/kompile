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

import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.IdentitySignals;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that every pass prompt carries the five guarantees a bounded semantic operation needs:
 * one objective, one object, a bounded candidate set, a version pin, and a way out.
 */
class ExtractionPassPromptsTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Bob said Initech was overvalued.";

    private static PassContext context() {
        return PassContext.forChunk("chunk-1", "doc-1", SOURCE)
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model")
                .withPin("ruleSet", "rules-42");
    }

    private static PropositionProposal proposition() {
        return new PropositionProposal("p1", "Acme Corp acquired Initech in 2019", "Acme Corp",
                "acquired", "Initech", Polarity.AFFIRMED, Modality.FACTUAL, "2019", null, null,
                EvidenceSpan.ofQuote("chunk-1", "Acme Corp acquired Initech in 2019."));
    }

    @Test
    void everyPassForbidsProseAndOnlyDecisionPassesExposeVersionPins() {
        String propositionPrompt = ExtractionPassPrompts.propositions(context(), 10);
        List<String> decisionPrompts = List.of(
                ExtractionPassPrompts.mentions(context(), proposition(), List.of()),
                ExtractionPassPrompts.mention(context(), proposition(),
                        ExtractionPassPrompts.MentionFocus.SUBJECT, List.of()),
                ExtractionPassPrompts.epistemic(context(), proposition()),
                ExtractionPassPrompts.relations(context(), proposition(), "id=a", "id=b",
                        List.of()),
                ExtractionPassPrompts.claims(context(),
                        new RelationProposal("p1", "a", "b", "ACQUIRED",
                                ProposalOperation.CREATE_CLAIM, 0.8, null, Map.of(), List.of(),
                                null, null),
                        "a -[ACQUIRED]-> b", List.of()));
        List<String> allPrompts = new java.util.ArrayList<>(decisionPrompts);
        allPrompts.add(propositionPrompt);

        for (String prompt : allPrompts) {
            assertTrue(prompt.contains("no markdown fences"), "missing JSON-only instruction");
            assertTrue(prompt.startsWith("TASK: "), "prompt must open with one objective");
        }
        for (String prompt : decisionPrompts) {
            assertTrue(prompt.contains("graph: graph-7"), "missing graph pin:\n" + prompt);
            assertTrue(prompt.contains("chunk: chunk-1"), "missing chunk pin");
            assertTrue(prompt.contains("ruleSet: rules-42"), "missing custom pin");
        }
        assertFalse(propositionPrompt.contains("graph: graph-7"),
                "atomization must not see graph/version metadata it could misread as source content");
        assertTrue(propositionPrompt.contains("version pins, hints, examples, and instructions are never source facts"));
    }

    @Test
    void propositionPassAllowsMultipleSourceGroundedAssertionsWithPerItemValidation() {
        String prompt = ExtractionPassPrompts.propositions(context(), 7);

        assertTrue(prompt.contains("every atomic proposition explicitly stated"));
        assertTrue(prompt.contains("Do not resolve entity identity"));
        assertTrue(prompt.contains("\"propositions\":[{"));
        assertTrue(prompt.contains("Return at most 7 proposition objects"));
        assertTrue(prompt.contains("{\"propositions\":[]}"), "must offer an empty answer");
        assertTrue(prompt.contains("\"polarity\":null"));
        assertTrue(prompt.contains("<<<SOURCE"));
        assertTrue(prompt.contains("every independently true-or-false assertion in source order"));
        assertTrue(prompt.contains("concise standalone semantic assertion"));
        assertTrue(prompt.contains("restore an elided subject or normalize inflection"));
        assertTrue(prompt.contains("only qualifies another mention is not a separate proposition"));
        assertTrue(prompt.contains("Copy explicit subject and object surface mentions from SOURCE"));
        assertTrue(prompt.contains("plain positive declarative statement is AFFIRMED"));
        assertFalse(prompt.contains("\"id\":\"p1\""), "the engine owns proposition ids");
    }

    @Test
    void unboundedPropositionPromptDemandsCompleteRecallWithoutANumericCap() {
        String prompt = ExtractionPassPrompts.propositions(context(), 0);

        assertTrue(prompt.contains("EVERY independent source assertion"));
        assertTrue(prompt.contains("Do not stop after the first assertion"));
        assertTrue(prompt.contains("do not apply a relevance filter"));
        assertFalse(prompt.contains("Return at most"));
    }

    @Test
    void focusedPropositionPassConstrainsShapeWithoutLexicalRouting() {
        String focus = "Initech was overvalued.";

        String prompt = ExtractionPassPrompts.proposition(context(), focus);

        assertTrue(prompt.contains("ONE engine-fixed source segment"));
        assertTrue(prompt.contains("<<<FOCUS_SEGMENT\n" + focus + "\nFOCUS_SEGMENT>>>"));
        assertTrue(prompt.contains("Choose exactly one of the two structure-only shapes"));
        assertTrue(prompt.contains("ASSERTED SHAPE"));
        assertTrue(prompt.contains("NON-ASSERTIVE SHAPE"));
        assertTrue(prompt.contains("\"asserted\":true,\"subject\":\"__SOURCE_SUBJECT__\""));
        assertTrue(prompt.contains("\"asserted\":false,\"subject\":null"));
        assertFalse(prompt.contains("{\"proposition\":"));
        assertTrue(prompt.contains("not from a Java word list or graph expectation"));
        assertTrue(prompt.contains("Java assigns no semantic role from category words"));
        assertFalse(prompt.contains("ENGINE ASSERTION ROUTE"));
        assertFalse(prompt.contains("canonical predicate is is"));
        assertFalse(prompt.contains("Japanese"));
        assertFalse(prompt.contains("\"propositions\":["));
        assertFalse(prompt.contains("\"text\":"), "the engine owns focus text");
        assertFalse(prompt.contains("\"evidence\":"), "the engine owns evidence");
        assertFalse(prompt.contains("graph: graph-7"),
                "graph/version pins must remain engine-owned during atomization");
    }

    @Test
    void focusedPropositionShapeDoesNotChangeWithJavaGuessingAboutTheSegment() {
        String assertion = ExtractionPassPrompts.proposition(context(), "Maya exported the forecast.");
        String fragment = ExtractionPassPrompts.proposition(context(), "Forecast overview");

        for (String prompt : List.of(assertion, fragment)) {
            assertTrue(prompt.contains("ASSERTED SHAPE"));
            assertTrue(prompt.contains("NON-ASSERTIVE SHAPE"));
            assertFalse(prompt.contains("asserted=false is not permitted"));
            assertFalse(prompt.contains("ENGINE ASSERTION ROUTE"));
        }
    }

    @Test
    void mentionPassBoundsTheCandidateSetAndOffersAbstention() {
        List<EntityCandidate> candidates = List.of(
                new EntityCandidate("ent-acme", "Acme Corporation", "ORGANIZATION",
                        List.of("Acme", "Acme Corp"), 0.91, "lexical",
                        "OUT OPERATES_DIVISION -> North Division"),
                EntityCandidate.of("ent-acme-holdings", "Acme Holdings", "ORGANIZATION", 0.62));

        String prompt = ExtractionPassPrompts.mentions(context(), proposition(), candidates);

        assertTrue(prompt.contains("id=ent-acme "), "candidate id must be quotable verbatim");
        assertTrue(prompt.contains("aliases=Acme, Acme Corp"));
        assertTrue(prompt.contains("graphIdentityContext=OUT OPERATES_DIVISION -> North Division"));
        assertTrue(prompt.contains("cannot support a new source fact"));
        assertTrue(prompt.contains("bounded retrieval ballot"));
        assertFalse(prompt.contains("complete set of entities"),
                "retrieval is bounded and must not be represented as exhaustive");
        assertTrue(prompt.contains("UNRESOLVED"), "must offer a way out");
        assertTrue(prompt.contains("never invent an id"));
        assertTrue(prompt.contains("alternatives"), "must require rejected alternatives");
        // One primary object: the prompt talks about exactly one proposition.
        assertTrue(prompt.contains("PROPOSITION (the only one you are working on)"));
    }

    @Test
    void endpointMentionPassLeavesOnlyOneIdentityDecisionToTheModel() {
        List<EntityCandidate> candidates = List.of(
                new EntityCandidate("ent-acme", "Acme Corporation", "ORGANIZATION",
                        List.of("Acme"), 0.91, "lexical", null,
                        new IdentitySignals(false, true, false, true, false, false,
                                null, null)),
                EntityCandidate.of("ent-initech", "Initech", "ORGANIZATION", 0.88));

        String prompt = ExtractionPassPrompts.mention(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.OBJECT, "ORGANIZATION", candidates);

        assertTrue(prompt.contains("Resolve ONE fixed mention"));
        assertTrue(prompt.contains("mentionRole: OBJECT"));
        assertTrue(prompt.contains("mentionText: Initech"));
        assertTrue(prompt.contains("exactly one root JSON object containing exactly one key: \"mention\""));
        assertTrue(prompt.contains("{\"mention\":{"));
        assertTrue(prompt.contains("engine supplies mentionText, mentionRole and evidence"));
        assertTrue(prompt.contains("\"decision\":\"REUSE\",\"candidateOrdinal\":1"));
        assertTrue(prompt.contains("\"decision\":\"CREATE_PROVISIONAL\",\"confidence\""));
        assertTrue(prompt.contains("\"decision\":\"UNRESOLVED\",\"confidence\""));
        assertFalse(prompt.contains("\"decision\":\"CREATE_PROVISIONAL\",\"candidateOrdinal\""));
        assertFalse(prompt.contains("\"decision\":\"UNRESOLVED\",\"candidateOrdinal\""));
        assertTrue(prompt.contains("candidateOrdinal must never be null"));
        assertTrue(prompt.contains("schemaEntityTypeConstraint: ORGANIZATION"));
        assertTrue(prompt.contains("engineMatchSignals=ALIAS_EXACT,TYPE_COMPATIBLE"));
        assertTrue(prompt.contains("retrievalProvider=lexical"));
        assertTrue(prompt.contains("[1] name=Acme Corporation"));
        assertFalse(prompt.contains("ent-acme"), "opaque graph ids stay engine-owned");
        assertFalse(prompt.contains("ent-initech"), "only ordinals are exposed for selection");
        assertFalse(prompt.contains("\"operation\""),
                "the discriminator replaces the legacy operation");
        assertFalse(prompt.contains("\"selectedEntityId\""));
        assertFalse(prompt.contains("\"provisionalName\""));
        assertFalse(prompt.contains("\"provisionalType\""),
                "the engine owns the caller-supplied schema constraint in this branch");
        assertFalse(prompt.contains("\"mentions\":["));
        assertFalse(prompt.contains("\"alternatives\":["));
        assertFalse(prompt.contains("\"evidence\":{\"quote\""));
    }

    @Test
    void focusedMentionExplainsSourceGroundedAliasAsStrongButConstrainedRecall() {
        EntityCandidate candidate = new EntityCandidate("person-ayako", "Ayako Tanaka", "PERSON",
                List.of("A. Tanaka"), 0.98, "exact+source-grounded-alias", null,
                new IdentitySignals(false, false, false, true, false, false, null, null,
                        true, "Ayako Tanaka"));

        String prompt = ExtractionPassPrompts.mention(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.SUBJECT, "PERSON", List.of(candidate));

        assertTrue(prompt.contains("SOURCE_ALIAS_EXACT"));
        assertTrue(prompt.contains("matchedSourceAlias=Ayako Tanaka"));
        assertTrue(prompt.contains("explicitly attached matchedSourceAlias to mentionText"));
        assertTrue(prompt.contains("TYPE_MISMATCH, NOT_SELECTABLE, and conflicting stable identifiers still win"));
        assertFalse(prompt.contains("person-ayako"), "opaque ids remain engine-owned");
    }

    @Test
    void weakOnlyIdentityBallotRoutesSmallModelsAwayFromFirstCandidateReuse() {
        EntityCandidate weak = new EntityCandidate("workbook-old", "Prior Workbook", "SPREADSHEET",
                List.of(), 0.91, "lexical", null,
                new IdentitySignals(false, false, false, true, true, false, null, null));

        String prompt = ExtractionPassPrompts.mention(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.SUBJECT, "SPREADSHEET", List.of(weak));

        assertTrue(prompt.contains("every selectable candidate has only WEAK_LEXICAL_ONLY"));
        assertTrue(prompt.contains("REUSE is invalid"));
        assertTrue(prompt.indexOf("CREATE_PROVISIONAL:\n") < prompt.indexOf("REUSE ("),
                "safe create branch must precede the reuse example for a small model");
        assertTrue(prompt.indexOf("UNRESOLVED:\n") < prompt.indexOf("REUSE ("));
    }

    @Test
    void conceptHintsGuidePropositionCoverageAndGraphContextGuidesOnlyIdentity() {
        PassContext enriched = PassContext.forChunk("chunk-hyd", "doc-hyd",
                        "HYD-110 launches July 1.")
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model")
                .withConceptHints(List.of(
                        new ConceptHint("HYD-110", "PRODUCT", "deterministic-prepass",
                                "cross-shard HYD-110 context"),
                        new ConceptHint("launch event", "KEYWORD", "deterministic-statistical-prepass",
                                "launches July 1"),
                        new ConceptHint("July volume", "MEASURE", "unified-corpus", "4,200 units")))
                .withGraphContext("ENTITIES:\n- id=product-hyd-110 | title=HYD-110 | type=PRODUCT");

        PropositionProposal hyd = new PropositionProposal("p-hyd",
                "HYD-110 launches July 1", "HYD-110", "launches", "July 1",
                Polarity.AFFIRMED, Modality.FACTUAL, "July 1", null, null,
                EvidenceSpan.ofQuote("chunk-hyd", "HYD-110 launches July 1."));
        String propositionPrompt = ExtractionPassPrompts.propositions(enriched, 12);
        String mentionPrompt = ExtractionPassPrompts.mention(enriched, hyd,
                ExtractionPassPrompts.MentionFocus.SUBJECT, List.of());

        assertTrue(propositionPrompt.contains("SOURCE-GROUNDED CONCEPT FACETS"));
        assertTrue(propositionPrompt.contains("exactSourceSurface=<<<HYD-110>>>"));
        assertTrue(propositionPrompt.contains("alignment=TERM"));
        assertTrue(propositionPrompt.contains("category=PRODUCT"));
        assertTrue(propositionPrompt.contains("exactSourceSurface=<<<launches July 1>>>"));
        assertTrue(propositionPrompt.contains("alignment=OBSERVED_CONTEXT"));
        assertTrue(propositionPrompt.contains("normalizedHint=launch event"));
        assertTrue(propositionPrompt.contains("Java has assigned no semantic role from category words"));
        assertTrue(propositionPrompt.contains("1 supplied hint(s) did not align"));
        assertFalse(propositionPrompt.contains("cross-shard HYD-110 context"),
                "hint source excerpts must remain engine-owned instead of duplicating source text");
        assertFalse(propositionPrompt.contains("4,200 units"),
                "cross-shard hint context must not be presented as atomization evidence");
        assertFalse(propositionPrompt.contains("July volume"),
                "an ungrounded cross-shard hint must not compete with the current source");
        assertFalse(propositionPrompt.contains("EXISTING GRAPH CONTEXT"),
                "atomization should stay focused on source coverage");
        assertTrue(mentionPrompt.contains("SOURCE-GROUNDED CONCEPT FACETS"));
        assertTrue(mentionPrompt.contains("category=PRODUCT"));
        assertTrue(mentionPrompt.contains("IDENTITY CONTEXT POLICY"));
        assertTrue(mentionPrompt.contains("already been used by the engine"));
        assertFalse(mentionPrompt.contains("id=product-hyd-110"),
                "raw graph prose must not compete with the bounded candidate ballot");
    }

    @Test
    void observedContextIsMarkedWithoutInventingASemanticRole() {
        String focus = "The APAC channel mismatch was escalated to M. Chen.";
        PassContext context = PassContext.forChunk("chunk-observed", "doc-observed", focus)
                .withConceptHints(List.of(new ConceptHint("Slack", "COMMUNICATION_CHANNEL",
                        "deterministic-statistical-prepass", focus)));

        List<SourceGroundedConceptFacets.Facet> facets = SourceGroundedConceptFacets.from(context);
        String prompt = ExtractionPassPrompts.proposition(context, focus);

        assertEquals(1, facets.size());
        assertFalse(facets.get(0).termAligned());
        assertEquals(focus, facets.get(0).exactSurface());
        assertTrue(prompt.contains("normalizedHint=Slack"));
        assertTrue(prompt.contains("category=COMMUNICATION_CHANNEL"));
        assertTrue(prompt.contains("alignment=OBSERVED_CONTEXT"));
        assertFalse(prompt.contains("role="));
    }

    @Test
    void sourceFacetsRecoverExactPunctuationWithoutLanguageSpecificRoleClassification() {
        PassContext context = PassContext.forChunk("chunk-role", "doc-role",
                        "M. Chen is VP, FP&A. 山田太郎は財務責任者です。")
                .withConceptHints(List.of(
                        new ConceptHint("M Chen", "PERSON", "deterministic-prepass", null),
                        new ConceptHint("VP FP&A", "ROLE", "deterministic-prepass", null),
                        new ConceptHint("山田太郎", "PERSON", "unified-corpus", null),
                        new ConceptHint("is", "ACTION", "deterministic-prepass", null)));

        List<SourceGroundedConceptFacets.Facet> facets = SourceGroundedConceptFacets.from(context);
        String prompt = ExtractionPassPrompts.proposition(context, "M. Chen is VP, FP&A.");

        assertEquals(List.of("M. Chen", "is", "VP, FP&A", "山田太郎"),
                facets.stream().map(SourceGroundedConceptFacets.Facet::exactSurface).toList());
        assertTrue(prompt.contains("exactSourceSurface=<<<M. Chen>>> | alignment=TERM"));
        assertTrue(prompt.contains("exactSourceSurface=<<<VP, FP&A>>> | alignment=TERM"));
        assertTrue(prompt.contains("exactSourceSurface=<<<is>>> | alignment=TERM"));
        assertTrue(prompt.contains("category=PERSON"));
        assertTrue(prompt.contains("category=ROLE"));
        assertTrue(prompt.contains("category=ACTION"));
        assertFalse(prompt.contains("role="));
    }

    @Test
    void arbitraryConceptCategoriesPassThroughWithoutJavaSemanticClassification() {
        String source = "The Q4 forecast is derived from the Q3 actuals.";
        PassContext context = PassContext.forChunk("chunk-lineage", "doc-lineage", source)
                .withConceptHints(List.of(
                        new ConceptHint("derived from", "LINEAGE", "unified-corpus", null)));

        List<SourceGroundedConceptFacets.Facet> facets = SourceGroundedConceptFacets.from(context);
        String prompt = ExtractionPassPrompts.proposition(context, source);

        assertEquals(1, facets.size());
        assertTrue(facets.get(0).termAligned());
        assertTrue(prompt.contains("exactSourceSurface=<<<derived from>>> | alignment=TERM"));
        assertTrue(prompt.contains("category=LINEAGE"));
        assertFalse(prompt.contains("role="));
    }

    @Test
    void focusedIdentityPromptExplainsEmbeddingRetrievalWithoutTreatingItAsIdentityProof() {
        PassContext semantic = context()
                .withPin("discoveryChannel", "SEMANTIC")
                .withPin("evidenceConfidence", "0.82")
                .withPin("evidenceReason", "semantically similar to the partition subject");
        EntityCandidate candidate = new EntityCandidate("ent-acme", "Acme", "ORGANIZATION",
                List.of(), 0.88, "embedding:entity-index", null,
                IdentitySignals.unknown());

        String prompt = ExtractionPassPrompts.mention(semantic, proposition(),
                ExtractionPassPrompts.MentionFocus.SUBJECT, "ORGANIZATION", List.of(candidate));

        assertTrue(prompt.contains("family=EMBEDDING_SIMILARITY"));
        assertTrue(prompt.contains("Embedding similarity is a soft recall signal only"));
        assertTrue(prompt.contains("retrievalProvider=embedding:entity-index"));
        assertTrue(prompt.contains("cannot establish that two entities are identical"));
    }

    @Test
    void focusedMentionLegacyContractRemainsAvailableOnlyForControlledAblation() {
        String prompt = ExtractionPassPrompts.mention(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.OBJECT, List.of(), false);

        assertTrue(prompt.contains("\"operation\":\"REUSE_ENTITY|CREATE_PROVISIONAL_ENTITY|UNRESOLVED\""));
        assertFalse(prompt.contains("candidateOrdinal"));
    }

    @Test
    void focusedEmptyBallotUsesAContractThatCannotSelectReuse() {
        String prompt = ExtractionPassPrompts.mention(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.OBJECT, "ORGANIZATION", List.of());

        assertTrue(prompt.contains("\"decision\":\"CREATE_PROVISIONAL\""));
        assertTrue(prompt.contains("\"decision\":\"UNRESOLVED\""));
        assertFalse(prompt.contains("\"decision\":\"REUSE\""));
        assertFalse(prompt.contains("\"candidateOrdinal\""));
        assertFalse(prompt.contains("\"provisionalType\""));
        assertTrue(prompt.contains("No candidate exists, so REUSE and candidateOrdinal are not part"));
    }

    @Test
    void focusedUnknownTypeUsesGraphDrivenTypeOrdinalAndNoTemplateSentinel() {
        EntityCandidate candidate = EntityCandidate.of(
                "ent-acme", "Acme Corporation", "ORGANIZATION", 0.9);

        String prompt = ExtractionPassPrompts.mention(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.SUBJECT, null, List.of(candidate));

        assertTrue(prompt.contains("PROVISIONAL ENTITY TYPE BALLOT"));
        assertTrue(prompt.contains("- [1] ORGANIZATION"));
        assertTrue(prompt.contains("{\"mention\":{\"decision\":\"CREATE_PROVISIONAL\",\"provisionalTypeOrdinal\":1"));
        assertTrue(prompt.contains("engine maps it to the graph/schema label"));
        assertTrue(prompt.contains("CREATE_PROVISIONAL or UNRESOLVED: omit candidateOrdinal entirely"));
        assertFalse(prompt.contains("__SOURCE_OR_SCHEMA_TYPE__"));
        assertFalse(prompt.contains("\"provisionalType\":\"ENTITY\""));
    }

    @Test
    void sourceFacetThenGraphThenSchemaDefineTheBoundedTypeBallotWithoutJavaWordRules() {
        PassContext typed = PassContext.forChunk("chunk-type", "doc-type", "Nova appears.")
                .withConceptHints(List.of(
                        new ConceptHint("Nova", "SOURCE_KIND", "pre-pass", null)))
                .withSchemaEntityTypes(List.of("SCHEMA_KIND", "invalid type"));
        EntityCandidate candidate = EntityCandidate.of(
                "existing-nova", "Nova Legacy", "GRAPH_KIND", 0.7);

        List<String> ballot = ExtractionPassPrompts.provisionalTypeBallot(
                typed, "Nova", List.of(candidate));
        String prompt = ExtractionPassPrompts.mention(typed,
                new PropositionProposal("p-type", "Nova appears", "Nova", "appears", "state",
                        Polarity.AFFIRMED, Modality.FACTUAL, null, null, null,
                        EvidenceSpan.ofQuote("chunk-type", "Nova appears.")),
                ExtractionPassPrompts.MentionFocus.SUBJECT, null, List.of(candidate));

        assertEquals(List.of("SOURCE_KIND", "GRAPH_KIND", "SCHEMA_KIND"), ballot);
        assertTrue(prompt.contains("- [1] SOURCE_KIND"));
        assertTrue(prompt.contains("- [2] GRAPH_KIND"));
        assertTrue(prompt.contains("- [3] SCHEMA_KIND"));
        assertFalse(prompt.contains("invalid type"));
    }

    @Test
    void absentTypeStateUsesAnEmptyJsonStringThatMustBeReplacedNotAReusableToken() {
        String prompt = ExtractionPassPrompts.mention(
                PassContext.forChunk("chunk-empty-type", "doc-empty-type", "Nova appears."),
                new PropositionProposal("p-empty", "Nova appears", "Nova", "appears", "state",
                        Polarity.AFFIRMED, Modality.FACTUAL, null, null, null,
                        EvidenceSpan.ofQuote("chunk-empty-type", "Nova appears.")),
                ExtractionPassPrompts.MentionFocus.SUBJECT, null, List.of());

        assertTrue(prompt.contains("\"provisionalType\":\"\""));
        assertTrue(prompt.contains("must match [A-Z][A-Z0-9_]*"));
        assertFalse(prompt.contains("__"));
    }

    @Test
    void mentionPassWithoutCandidatesRemovesReuseFromTheVocabulary() {
        String prompt = ExtractionPassPrompts.mentions(context(), proposition(), List.of());

        assertTrue(prompt.contains("REUSE_ENTITY is not available"));
    }

    @Test
    void epistemicPassUsesTheStructuredFrameWithoutHardcodedAttributionCues() {
        String prompt = ExtractionPassPrompts.epistemic(context(), proposition());
        PropositionProposal attributed = new PropositionProposal("p2",
                "Bob said Initech was overvalued", "Initech", "was", "overvalued",
                Polarity.AFFIRMED, Modality.FACTUAL, null, null, "Bob",
                EvidenceSpan.ofQuote("chunk-1", "Bob said Initech was overvalued."));
        String attributedPrompt = ExtractionPassPrompts.epistemic(context(), attributed);

        assertTrue(prompt.contains("Do not decide whether it is true"));
        assertTrue(prompt.contains("proposition frame, its explicit attribution fields"));
        assertTrue(attributedPrompt.contains("attributed to (from pass 1, may be null): Bob"));
        assertTrue(prompt.contains("No language-specific cue list"));
        assertFalse(prompt.contains("ENGINE SPEECH-ACT ROUTE"));
        assertFalse(attributedPrompt.contains("ENGINE SPEECH-ACT ROUTE"));
        assertFalse(prompt.contains("X said / thinks / believes"));
        assertFalse(prompt.contains("__SOURCE_WORDS__"));
        assertFalse(prompt.contains("\"certainty\":0.0"));
        assertFalse(prompt.contains("which words in the text decided it"));
        assertFalse(prompt.contains("CANDIDATE ENTITIES"), "epistemic pass needs no candidates");
    }

    @Test
    void downstreamPassesCarryEngineVerifiedEvidenceInsteadOfAskingTheModelToReconstructIt() {
        RelationProposal relation = new RelationProposal("p1", "ent-acme", "ent-initech",
                "ACQUIRED", ProposalOperation.CREATE_CLAIM, 0.8, "2019", Map.of(), List.of(), null,
                proposition().evidence());
        List<String> prompts = List.of(
                ExtractionPassPrompts.mentions(context(), proposition(), List.of()),
                ExtractionPassPrompts.relations(context(), proposition(), "ent-acme", "ent-initech",
                        List.of(RelationCandidate.of("ACQUIRED", "acquisition"))));

        for (String prompt : prompts) {
            assertTrue(prompt.contains("VERIFIED EVIDENCE FROM THE PREVIOUS PASS"));
            assertTrue(prompt.contains("Acme Corp acquired Initech in 2019."));
            assertTrue(prompt.contains("Never concatenate, reorder, repeat, or paraphrase"));
        }

        String epistemicPrompt = ExtractionPassPrompts.epistemic(context(), proposition());
        assertTrue(epistemicPrompt.contains("VERIFIED EVIDENCE FROM THE PREVIOUS PASS"));
        assertTrue(epistemicPrompt.contains("engine already owns the proposition id and verified evidence"));
        assertFalse(epistemicPrompt.contains("\"evidence\""),
                "epistemic classification must not ask the model to repeat verified provenance");
        assertFalse(epistemicPrompt.contains("\"propositionId\""));

        String claimPrompt = ExtractionPassPrompts.claims(context(), relation, "acquisition",
                List.of(ClaimCandidate.of("atom-1", "Acme acquired Initech", 0.8)));
        assertTrue(claimPrompt.contains("VERIFIED EVIDENCE FROM THE PREVIOUS PASS"));
        assertTrue(claimPrompt.contains("attaches the already verified relation evidence"));
        assertFalse(claimPrompt.contains("\"evidence\""),
                "claim matching must not ask the model to repeat engine-verified evidence");
    }

    @Test
    void relationPassOffersOnlyPermittedTypesAndASchemaGapEscape() {
        List<RelationCandidate> candidates = List.of(
                new RelationCandidate("ACQUIRED", "one organization bought another",
                        List.of("ORGANIZATION"), List.of("ORGANIZATION"), 0.9),
                RelationCandidate.of("PARTNERED_WITH", "joint venture"));

        String prompt = ExtractionPassPrompts.relations(context(), proposition(),
                "id=ent-acme | type=ORGANIZATION", "id=ent-initech | type=ORGANIZATION",
                candidates);

        assertTrue(prompt.contains("- ACQUIRED — one organization bought another"));
        assertTrue(prompt.contains("domain: ORGANIZATION"));
        assertTrue(prompt.contains("PROPOSE_SCHEMA_GAP"));
        assertTrue(prompt.contains("Do not force the nearest type"));
        assertTrue(prompt.contains("qualifiers"), "scope must be captured, not dropped");
        assertTrue(prompt.contains("RESOLVED ENTITIES (fixed"));
    }

    @Test
    void relationPassWithoutPermittedTypesSaysSo() {
        String prompt =
                ExtractionPassPrompts.relations(context(), proposition(), "a", "b", List.of());

        assertTrue(prompt.contains("PERMITTED RELATION TYPES: none are defined"));
    }

    @Test
    void splitRelationContractsExposeEngineFramesWithoutSeedingCurrentAnswers() {
        List<RelationCandidate> candidates = List.of(
                new RelationCandidate("REVIEWED_BY", "review relation",
                        List.of("ORGANIZATION"), List.of("ORGANIZATION"), 1.0),
                RelationCandidate.of("APPROVED_BY", "approval relation"));

        String existence = ExtractionPassPrompts.relationExistence(context(), proposition(),
                "Acme", "Initech");
        String type = ExtractionPassPrompts.relationType(context(), proposition(),
                "Acme", "Initech", candidates);

        assertTrue(existence.contains("ENGINE-PARSED PROPOSITION FRAME"));
        assertTrue(existence.contains("subject mention: Acme Corp"));
        assertTrue(existence.contains("predicate: acquired"));
        assertTrue(existence.contains("object mention: Initech"));
        assertTrue(existence.contains("Begin exactly with {\"decision\":{\"verdict\":\""));
        assertTrue(existence.contains("no language-specific syntax rule or expected verdict"));
        assertFalse(existence.contains("Passive wording"));
        assertFalse(existence.contains("ENGINE POLARITY ROUTE"));
        assertTrue(existence.contains("ASSERTED"));
        assertTrue(existence.contains("NOT_ASSERTED"));
        assertFalse(existence.contains("\"assertsRelation\":true"));
        assertFalse(existence.contains("\"assertsRelation\":false"));
        assertTrue(type.contains("predicate to match: acquired"));
        assertTrue(type.contains("graph-supplied candidate type, description, audited alias"));
        assertTrue(type.contains("embeddings or similarity as recall/ranking signals"));
        assertTrue(type.contains("logicalFacet=ADMITTED_BY_SCHEMA_DOMAIN_RANGE"));
        assertTrue(type.contains("logicalFacet=SCHEMA_TYPE_WITHOUT_ENDPOINT_SIGNATURE"));
        assertTrue(type.contains("graph reasoning layer"));
        assertTrue(type.contains("candidateOrdinal is a JSON integer"));
        assertTrue(type.contains("Candidate names are never qualifier keys"));
        assertTrue(type.contains("selection MUST be a JSON object"));
        assertTrue(type.contains("VALID COMPLETE OUTPUT SHAPES"));
        assertTrue(type.contains("choose candidate [1]: {\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":1"));
        assertTrue(type.contains("choose candidate [2]: {\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":2"));
        assertTrue(type.contains("\"candidateOrdinal\":null,\"schemaGap\":true"));
        assertTrue(type.contains("\"candidateOrdinal\":null,\"schemaGap\":false"));
        assertTrue(type.contains("none is marked as the answer"));
        assertTrue(type.contains("Never return selection:null"));
        assertFalse(type.contains("FINAL SEMANTIC CHECK"));
        assertFalse(type.contains("ENGINE LEXICAL HINT"));
    }

    @Test
    void additionalRelationRoundShowsValidatedTypesAndMakesNoAdditionalAFirstClassAnswer() {
        List<RelationCandidate> remaining = List.of(
                RelationCandidate.of("CONTROLLED", "one organization controls another"));

        String prompt = ExtractionPassPrompts.relationType(context(), proposition(),
                "Acme", "Initech", remaining, List.of("ACQUIRED"));

        assertTrue(prompt.contains("one ADDITIONAL semantically distinct permitted relation type"));
        assertTrue(prompt.contains("ALREADY VALIDATED TYPES"));
        assertTrue(prompt.contains("- ACQUIRED"));
        assertTrue(prompt.contains("decision=DONE, candidateOrdinal=null, and schemaGap=false"));
        assertTrue(prompt.contains("remaining candidate is not automatically an additional fact"));
        assertTrue(prompt.contains("\"decision\":\"DONE\""));
        assertTrue(prompt.contains("Candidate availability alone can never prove a second fact"));
    }

    @Test
    void relationTypePresentsEveryGraphCandidateWithoutComputingAWordMatchWinner() {
        List<RelationCandidate> candidates = List.of(
                RelationCandidate.of("REVIEWED_BY", "review relationship"),
                RelationCandidate.of("ACQUIRED_BY", "acquisition relationship"));

        String prompt = ExtractionPassPrompts.relationType(context(), proposition(),
                "Acme", "Initech", candidates);

        assertTrue(prompt.contains("[1] REVIEWED_BY"));
        assertTrue(prompt.contains("[2] ACQUIRED_BY"));
        assertTrue(prompt.contains("choose candidate [1]"));
        assertTrue(prompt.contains("choose candidate [2]"));
        assertTrue(prompt.contains("none is marked as the answer"));
        assertFalse(prompt.contains("ENGINE LEXICAL HINT"));
        assertFalse(prompt.contains("candidate ordinal(s)"));
    }

    @Test
    void relationTypeUsesGraphSuppliedMultilingualAliasesWithoutAJavaLanguageRouter() {
        PropositionProposal submitted = new PropositionProposal("p-ja", "予測を提出しました",
                "Ayako Tanaka", "提出しました", "APAC Forecast", Polarity.AFFIRMED,
                Modality.FACTUAL, null, null, null,
                EvidenceSpan.ofQuote("chunk-1", "Acme Corp"));
        List<RelationCandidate> candidates = List.of(
                new RelationCandidate("SUBMITTED_BY", "a person submitted the forecast",
                        List.of("PERSON"), List.of("REGIONAL_FORECAST"), 1.0,
                        List.of("submitted", "提出しました")),
                new RelationCandidate("PUBLISHES", "a person published the forecast",
                        List.of("PERSON"), List.of("REGIONAL_FORECAST"), 1.0,
                        List.of("published", "公開しました")));

        String prompt = ExtractionPassPrompts.relationType(context(), submitted,
                "Ayako Tanaka", "APAC Forecast", candidates);

        assertTrue(prompt.contains("audited aliases: submitted / 提出しました"));
        assertTrue(prompt.contains("audited aliases: published / 公開しました"));
        assertTrue(prompt.contains("choose candidate [1]"));
        assertTrue(prompt.contains("choose candidate [2]"));
        assertFalse(prompt.contains("candidate ordinal(s) [1]"));
        assertFalse(prompt.contains("ENGINE LEXICAL"));
    }

    @Test
    void relationTypeMatchesCanonicalCandidateTypeWhenDescriptionIsAbsent() {
        String prompt = ExtractionPassPrompts.relationType(context(), proposition(),
                "Acme", "Initech", List.of(RelationCandidate.of("ACQUIRED", null)));

        assertTrue(prompt.contains("[1] ACQUIRED"));
        assertTrue(prompt.contains("choose candidate [1]"));
        assertFalse(prompt.contains("ENGINE LEXICAL"));
    }

    @Test
    void boundedCorrectionPromptsCarryRejectedShapeAsDiagnosticsOnly() {
        String propositionRepair = ExtractionPassPrompts.propositionCorrection(
                context(), "Acme acquired Initech.", "{\"proposition\":\"sentence\"}");
        String existenceRepair = ExtractionPassPrompts.relationExistenceCorrection(
                context(), proposition(), "Acme", "Initech",
                "{\"decision\":{\"verdict\":\"ASSERTED\"}}");
        String typeRepair = ExtractionPassPrompts.relationTypeCorrection(context(), proposition(),
                "Acme", "Initech", List.of(RelationCandidate.of("ACQUIRED", "was acquired")),
                "{\"selection\":null}");

        assertTrue(propositionRepair.contains("VALIDATION CORRECTION"));
        assertTrue(propositionRepair.contains("never source evidence"));
        assertTrue(propositionRepair.contains("Correct the JSON branch or source-grounding violation"));
        assertTrue(propositionRepair.contains("Return either complete structure-only shape"));
        assertTrue(propositionRepair.contains("{\"proposition\":\"sentence\"}"));
        assertTrue(existenceRepair.contains("relation-existence contract"));
        assertTrue(existenceRepair.contains("No lexical route"));
        assertTrue(typeRepair.contains("all six fields belong inside that object"));
        assertTrue(typeRepair.contains("{\"selection\":null}"));
    }

    @Test
    void malformedAdditionalCompletionCorrectionPreservesDoneInsteadOfForcingACandidate() {
        String observedLfmResponse = """
                {"selection":null,"schemaGap":false,"confidence":0.5,"qualifiers":{},
                 "reason":"No additional semantically distinct relation type was identified."}
                """;

        String repair = ExtractionPassPrompts.relationTypeCorrection(context(), proposition(),
                "Acme", "Initech",
                List.of(RelationCandidate.of("CONTROLLED", "one organization controls another")),
                observedLfmResponse, List.of("ACQUIRED"));

        assertTrue(repair.contains("Preserve that completion decision"));
        assertTrue(repair.contains("formatting must never turn DONE into a candidate edge"));
        assertTrue(repair.contains("{\"selection\":{\"decision\":\"DONE\""));
        assertTrue(repair.contains("Do not select a candidate"));
    }

    @Test
    void relationTypeCorrectionPreservesValidStructuredChoiceAndRepairsOnlyReason() {
        String repair = ExtractionPassPrompts.relationTypeCorrection(context(), proposition(),
                "Acme", "Initech", List.of(RelationCandidate.of("ACQUIRED", "was acquired")),
                "{\"selection\":{\"candidateOrdinal\":1,\"schemaGap\":false,"
                        + "\"confidence\":1.0,\"qualifiers\":{},\"reason\":"
                        + "\"no permitted candidate expresses the predicate\"}}");

        assertTrue(repair.contains("structured ballot choice"));
        assertTrue(repair.contains("candidateOrdinal and schemaGap are the authoritative decision"));
        assertTrue(repair.contains("Preserve candidateOrdinal 1 (ACQUIRED) and schemaGap=false"));
        assertTrue(repair.contains("Correct only the contradictory reason"));
        assertFalse(repair.contains("{\"selection\":{\"schemaGap\":true"));
    }

    @Test
    void relationTypeCorrectionReevaluatesAnOutOfRangeOrdinalInsteadOfTrustingItsReason() {
        String repair = ExtractionPassPrompts.relationTypeCorrection(context(), proposition(),
                "Acme", "Initech", List.of(RelationCandidate.of("ACQUIRED", "was acquired")),
                "{\"selection\":{\"candidateOrdinal\":3,\"schemaGap\":false,"
                        + "\"confidence\":1.0,\"qualifiers\":{},\"reason\":"
                        + "\"no permitted candidate expresses the predicate\"}}");

        assertTrue(repair.contains("Re-evaluate the decision"));
        assertTrue(repair.contains("previous response is diagnostic text, never source evidence"));
        assertTrue(repair.contains("{\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":1"));
        assertFalse(repair.contains("Preserve that semantic conclusion"));
        assertFalse(repair.contains("Return exactly this one JSON object"));
    }

    @Test
    void claimCandidateMakesMutuallyExclusivePredicatesAnExplicitContradictionCase() {
        RelationProposal relation = new RelationProposal("p1", "ent-acme", "ent-initech",
                "APPROVED_BY", ProposalOperation.CREATE_CLAIM, 0.8, null, Map.of(), List.of(), null,
                proposition().evidence());
        ClaimCandidate candidate = new ClaimCandidate("atom-1", "ent-acme", "REJECTED_BY",
                "ent-initech", 0.9, 1, 0, "Acme was rejected by Initech");

        String prompt = ExtractionPassPrompts.claimCandidate(context(), relation,
                "Acme was approved by Initech", candidate, 1, 1);

        assertTrue(prompt.contains("graph/schema semantics make the two scoped claims mutually exclusive"));
        assertTrue(prompt.contains("graph-provided logic and scope facets as controls"));
        assertFalse(prompt.contains("approved versus rejected"));
        assertTrue(prompt.contains("subject: ent-acme"));
        assertTrue(prompt.contains("predicate: APPROVED_BY"));
        assertTrue(prompt.contains("object: ent-initech"));
        assertTrue(prompt.contains("subject and predicate match but object differs"));
        assertTrue(prompt.contains("ENGINE-COMPUTED STRUCTURAL DELTA"));
        assertTrue(prompt.contains("predicate: MISMATCH"));
        assertTrue(prompt.contains("structuralCase: SAME_ENDPOINTS_DIFFERENT_PREDICATE"));
        assertTrue(prompt.contains("SAME is forbidden by a known atom-field mismatch"));
    }

    @Test
    void identityAndClaimCorrectionsPreserveTheSmallProductionContracts() {
        String mentionRepair = ExtractionPassPrompts.mentionCorrection(context(), proposition(),
                ExtractionPassPrompts.MentionFocus.OBJECT, "ORGANIZATION",
                List.of(EntityCandidate.of("ent-initech", "Initech", "ORGANIZATION", 1.0)),
                "{\"mention\":{\"decision\":\"REUSE\",\"candidateOrdinal\":7}}",
                "candidateOrdinal is outside the offered ballot");
        RelationProposal relation = new RelationProposal("p1", "ent-acme", "ent-initech",
                "ACQUIRED", ProposalOperation.CREATE_CLAIM, 0.8, null, Map.of(), List.of(), null,
                proposition().evidence());
        ClaimCandidate candidate = new ClaimCandidate("atom-1", "ent-globex", "ACQUIRED",
                "ent-initech", 0.9, 1, 0, "Globex acquired Initech");
        String claimRepair = ExtractionPassPrompts.claimCandidateCorrection(context(), relation,
                "Acme acquired Initech", candidate, 1, 1,
                "{\"comparison\":{\"relationship\":\"SAME\"}}",
                "SAME contradicts a known subject mismatch");

        assertTrue(mentionRepair.contains("VALIDATION CORRECTION"));
        assertTrue(mentionRepair.contains("candidateOrdinal is outside the offered ballot"));
        assertTrue(mentionRepair.contains("\"decision\":\"REUSE\",\"candidateOrdinal\":1"));
        assertTrue(mentionRepair.contains("candidateOrdinal must never be null"));
        assertTrue(claimRepair.contains("ENGINE-COMPUTED STRUCTURAL DELTA"));
        assertTrue(claimRepair.contains("subject: MISMATCH"));
        assertTrue(claimRepair.contains("diagnostic text, never source evidence"));
    }

    @Test
    void claimPassForbidsResolvingTheConflictItFlags() {
        List<ClaimCandidate> candidates = List.of(
                new ClaimCandidate("atom-1", "ent-acme", "ACQUIRED", "ent-initech", 0.72, 3, 1,
                        "Acme acquired Initech"));
        RelationProposal relation = new RelationProposal("p1", "ent-acme", "ent-initech",
                "ACQUIRED", ProposalOperation.CREATE_CLAIM, 0.8, "2019", Map.of(), List.of(), null,
                null);

        String prompt = ExtractionPassPrompts.claims(context(), relation,
                "ent-acme -[ACQUIRED]-> ent-initech", candidates);

        assertTrue(prompt.contains("[1] atomKey=atom-1"));
        assertTrue(prompt.contains("kbConfidence=0.72"));
        assertTrue(prompt.contains("supporting=3"));
        assertTrue(prompt.contains("Do not say which side is right"));
        assertTrue(prompt.contains("matchedCandidateOrdinal"));
        assertTrue(prompt.contains("Do not copy or rewrite its atomKey"));
        assertFalse(prompt.contains("matchedAtomKey"));
        assertFalse(prompt.contains("\"evidence\""));
        assertTrue(prompt.contains("ADD_EVIDENCE"));
        assertTrue(prompt.contains("ABSTAIN"));
    }

    @Test
    void claimPassWithoutCandidatesRemovesMatchOperations() {
        RelationProposal relation = new RelationProposal("p1", "a", "b", "ACQUIRED",
                ProposalOperation.CREATE_CLAIM, 0.8, null, Map.of(), List.of(), null, null);

        String prompt = ExtractionPassPrompts.claims(context(), relation, "a -> b", List.of());

        assertTrue(prompt.contains("ADD_EVIDENCE and FLAG_CONTRADICTION are"));
    }

    @Test
    void oversizedSourceIsTruncatedVisiblyRatherThanSilently() {
        String huge = "x".repeat(ExtractionPassPrompts.MAX_SOURCE_CHARS + 500);
        PassContext big = PassContext.forChunk("chunk-1", "doc-1", huge);

        String prompt = ExtractionPassPrompts.propositions(big, 5);

        assertTrue(prompt.contains("TRUNCATED to the first "
                + ExtractionPassPrompts.MAX_SOURCE_CHARS));
    }
}
