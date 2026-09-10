/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.staging.execution;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.passes.ClaimCandidateProvider;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline;
import ai.kompile.core.graphrag.passes.EntityCandidateProvider;
import ai.kompile.core.graphrag.passes.EvidenceSpanValidator;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers;
import ai.kompile.core.graphrag.passes.ExtractionPassPrompts;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.MentionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.core.graphrag.passes.ProposalOperation;
import ai.kompile.core.graphrag.passes.RelationCandidateProvider;

import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-model harness for the <b>decomposed</b> extraction passes: each of the five bounded passes
 * is dispatched, on its own, to an actual small local model ({@code lfm2.5-1.2b-instruct}) loaded
 * in-process through {@code samediff-llm} — no serving subprocess, no HTTP, no scripted stub.
 *
 * <p>Every other test of this machinery supplies a canned {@code LlmCaller}, so they prove that
 * <i>given</i> a reply the pipeline stays in bounds. This harness asks the question those cannot:
 * given the real pass prompt, does a small model stay in bounds? Each test is self-contained — it
 * builds its own {@link PassContext} and inputs and calls exactly one pass, so a failure names the
 * pass that broke rather than a whole pipeline.</p>
 *
 * <p><b>What is asserted vs. what is reported.</b> Hard assertions cover only the scope contract
 * that must hold no matter how weak the model is: an answer never leaves the vocabulary it was
 * given (no invented entity id, relation type or claim key), and abstention is always allowed.
 * Semantic accuracy is <i>printed</i> as a scorecard rather than asserted, because a 1.2B model is
 * not a correctness oracle and a flaky assertion would tell us less than the number does.</p>
 *
 * <p>Opt-in (loading the model costs several GB and ~15s):
 * {@code mvn -o test -pl :kompile-model-staging -Dtest=DecomposedPassLlmHarnessTest
 * -Dkompile.samediff.llm.harness=true}. Context window and per-step token budget are overridable
 * with {@code -Dkompile.samediff.llm.ctx} and {@code -Dkompile.samediff.llm.step}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecomposedPassLlmHarnessTest {

    private static final Path MODEL_DIR = Paths.get(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls", "lfm2.5-1.2b-instruct");

    /**
     * The registry records {@code max_sequence_length: 512} for this model, which is what the
     * single-shot harness pinned. A decomposed pass prompt is smaller than the monolithic one but
     * still larger than 512 tokens, so the KV buffer is sized from the model's real window here;
     * whether the bounded prompts fit is one of the things this harness measures.
     */
    private static final int CONTEXT_TOKENS = Integer.getInteger("kompile.samediff.llm.ctx", 4096);

    /** Tokens generated per continue-round; the session loops until EOS or the budget is spent. */
    private static final int STEP_TOKENS = Integer.getInteger("kompile.samediff.llm.step", 64);

    /**
     * Output-token budget for one pass, mirroring {@code LocalStagingLlmService.maxTokens}:
     * {@code min(1024, min(ctx, max(128, ctx/2)))}.
     *
     * <p>Production never lets a pass generate until the KV window fills — it asks for a bounded
     * completion and takes what comes back. A harness that instead loops to the end of the buffer
     * measures a request production never makes, and reports "the model rambled for 3400 tokens"
     * where production would have reported a truncated answer after 1024. Budget exhaustion is a
     * real pass outcome, so it is bounded here and named in the scorecard rather than hidden.</p>
     */
    private static final int MAX_OUTPUT_TOKENS = Math.max(1,
            Math.min(Math.min(1024, CONTEXT_TOKENS), Math.max(128, CONTEXT_TOKENS / 2)));

    private static final String CHUNK_ID = "chunk-planning-1";
    private static final String DOCUMENT_ID = "doc-planning-board-minutes";

    private static final String SOURCE_TEXT =
            "Acme Corporation hired Jane Chen as CFO in March 2025. "
                    + "Chen said margins would probably improve next year. "
                    + "Acme did not acquire Globex Ltd.";

    /** Deliberately contains a decoy: a different person who shares a first name with the mention. */
    private static final List<EntityCandidate> ENTITY_CANDIDATES = List.of(
            EntityCandidate.of("e-acme", "Acme Corporation", "ORGANIZATION", 0.91),
            EntityCandidate.of("e-jane-doe", "Jane Doe", "PERSON", 0.44),
            EntityCandidate.of("e-globex", "Globex Ltd", "ORGANIZATION", 0.62));

    /** Covers employment but deliberately has no acquisition type, so pass 4 must report a gap. */
    private static final List<RelationCandidate> RELATION_CANDIDATES = List.of(
            RelationCandidate.of("EMPLOYS", "the organization employs the person"),
            RelationCandidate.of("HEADQUARTERED_IN", "the organization is based in the location"),
            RelationCandidate.of("SUBSIDIARY_OF", "the organization is owned by the other"),
            RelationCandidate.of("COMPETES_WITH", "the two organizations compete"));

    private static final List<ClaimCandidate> CLAIM_CANDIDATES = List.of(
            ClaimCandidate.of("atom-cfo-chen", "Acme Corporation employs Jane Chen as CFO", 0.80),
            ClaimCandidate.of("atom-cfo-diaz", "Acme Corporation employs Robert Diaz as CFO", 0.70));

    private static final PassContext CONTEXT = PassContext
            .forChunk(CHUNK_ID, DOCUMENT_ID, SOURCE_TEXT)
            .withSchema("kompile-extraction-passes/v1", "lfm2.5-1.2b-instruct");

    /** The employment proposition, fixed so passes 2-4 do not depend on pass 1 having succeeded. */
    private static final PropositionProposal HIRING = new PropositionProposal(
            "p1", "Acme Corporation hired Jane Chen as CFO in March 2025.",
            "Acme Corporation", "hired as CFO", "Jane Chen",
            Polarity.AFFIRMED, Modality.FACTUAL, "March 2025", null, null,
            EvidenceSpan.ofQuote(CHUNK_ID, "Acme Corporation hired Jane Chen as CFO in March 2025."));

    /** The attributed, hedged proposition — the one pass 3 must not flatten into a bare fact. */
    private static final PropositionProposal OPINION = new PropositionProposal(
            "p2", "Margins would probably improve next year.",
            "margins", "would improve", "next year",
            Polarity.AFFIRMED, Modality.POSSIBILITY, "next year", null, "Chen",
            EvidenceSpan.ofQuote(CHUNK_ID, "Chen said margins would probably improve next year."));

    /** The negated proposition, whose relation type is absent from the permitted list. */
    private static final PropositionProposal NEGATED = new PropositionProposal(
            "p3", "Acme did not acquire Globex Ltd.",
            "Acme", "acquire", "Globex Ltd",
            Polarity.NEGATED, Modality.FACTUAL, null, null, null,
            EvidenceSpan.ofQuote(CHUNK_ID, "Acme did not acquire Globex Ltd."));

    private static final List<String> SCORECARD = new ArrayList<>();

    private GenerationPipeline pipeline;

    @BeforeAll
    void loadModel() throws Exception {
        assumeTrue(Boolean.getBoolean("kompile.samediff.llm.harness"),
                "opt-in harness — run with -Dkompile.samediff.llm.harness=true");
        Path decoder = MODEL_DIR.resolve("model.sdnb");
        Path tokenizerFile = MODEL_DIR.resolve("tokenizer.json");
        assumeTrue(Files.exists(decoder) && Files.exists(tokenizerFile),
                "lfm2.5 SameDiff model not staged at " + MODEL_DIR);

        // Populate the ND4J op registry before SameDiff deserialization (idempotent).
        DifferentialFunctionClassHolder.initInstance();

        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.fromFile(tokenizerFile.toString());
        // Greedy: sampled decoding makes a pass answer differ run to run, which would turn every
        // scope assertion below into a coin flip.
        SamplingConfig sampling = SamplingConfig.builder()
                .doSample(false)
                .eosTokenId(tokenizer.getEosTokenId())
                .padTokenId(tokenizer.getPadTokenId())
                .build();

        // Without a chat template the pipeline encodes the prompt verbatim and the model answers as
        // a base completion model — on a prompt that ends in a directive it emits end-of-sequence
        // and says nothing. Production resolves a template before building the pipeline; so does
        // this, from the staged GGUF's own metadata.
        String chatTemplate = LocalModelChatTemplate.resolve(MODEL_DIR);
        assumeTrue(chatTemplate != null,
                "no chat template in the staged model at " + MODEL_DIR
                        + " — the passes would be measured against base-model completion");

        long t0 = System.nanoTime();
        pipeline = GenerationPipeline.create(GenerationPipelineConfig.builder()
                .decoderPath(decoder.toString())
                .tokenizer(tokenizer)
                .samplingConfig(sampling)
                .kvCacheStrategy(KvCacheStrategy.STATIC)
                .maxKvCacheLength(CONTEXT_TOKENS)
                .chatTemplate(chatTemplate)
                .build());
        System.out.println("\n[harness] loaded lfm2.5-1.2b-instruct in "
                + ((System.nanoTime() - t0) / 1_000_000) + " ms, kv window " + CONTEXT_TOKENS
                + " tokens, greedy decoding, chat template from GGUF ("
                + chatTemplate.length() + " chars)");
    }

    @AfterAll
    void reportAndClose() {
        if (!SCORECARD.isEmpty()) {
            System.out.println("\n================ decomposed pass scorecard (lfm2.5-1.2b-instruct) ================");
            SCORECARD.forEach(line -> System.out.println("  " + line));
            System.out.println("==================================================================================\n");
        }
        if (pipeline != null) {
            try {
                pipeline.close();
            } catch (Exception ignored) {
                // best-effort release of native resources
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Pass 1 — propositions
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pass 1 (propositions): splits the chunk without exceeding its cap")
    void propositionsPass() {
        int cap = 5;
        String prompt = ExtractionPassPrompts.propositions(CONTEXT, cap);
        String raw = ask(ExtractionPassPrompts.PASS_PROPOSITIONS, prompt);

        List<PropositionProposal> propositions =
                ExtractionPassParsers.propositions(raw, CONTEXT, cap);

        assertTrue(propositions.size() <= cap,
                "pass 1 returned more propositions than the prompt permitted: " + propositions.size());
        propositions.forEach(p -> assertNotNull(p.id(), "every proposition needs an id to be addressable"));

        long verifiable = propositions.stream()
                .filter(p -> EvidenceSpanValidator.check(p.evidence(), CONTEXT).usable())
                .count();
        long negationKept = propositions.stream().filter(p -> p.polarity() == Polarity.NEGATED).count();
        long hedgeKept = propositions.stream().filter(p -> p.modality() != Modality.FACTUAL).count();

        propositions.forEach(p -> System.out.println("  [p1] " + p.id() + " " + p.polarity() + "/"
                + p.modality() + " attributedTo=" + p.attributedTo() + " :: " + p.render()));
        score("propositions", propositions.size() + " proposed, " + verifiable
                + " with a verifiable quote, negation kept on " + negationKept
                + ", non-factual modality kept on " + hedgeKept);
    }

    // -------------------------------------------------------------------------------------------
    // Pass 2 — mentions (the identity firewall)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pass 2 (mentions): never selects an entity id it was not offered")
    void mentionsPass() {
        String prompt = ExtractionPassPrompts.mentions(CONTEXT, HIRING, ENTITY_CANDIDATES);
        String raw = ask(ExtractionPassPrompts.PASS_MENTIONS, prompt);

        List<MentionProposal> mentions = ExtractionPassParsers.mentions(raw, CONTEXT, HIRING.id());
        Set<String> offered = ENTITY_CANDIDATES.stream()
                .map(EntityCandidate::id).collect(Collectors.toSet());

        for (MentionProposal mention : mentions) {
            if (mention.operation() == ProposalOperation.REUSE_ENTITY) {
                assertTrue(offered.contains(mention.selectedEntityId()),
                        "pass 2 invented an entity id outside the candidate set: "
                                + mention.selectedEntityId() + " not in " + offered);
            }
            System.out.println("  [p2] " + mention.mentionText() + " -> " + mention.operation()
                    + " id=" + mention.selectedEntityId() + " provisional=" + mention.provisionalName()
                    + " conf=" + mention.confidence());
        }

        boolean fellForDecoy = mentions.stream().anyMatch(m ->
                "e-jane-doe".equals(m.selectedEntityId()));
        score("mentions", mentions.size() + " decisions, in-vocabulary: yes, decoy 'Jane Doe' "
                + (fellForDecoy ? "WRONGLY REUSED" : "not reused"));
    }

    // -------------------------------------------------------------------------------------------
    // Pass 3 — epistemic (the attribution firewall)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pass 3 (epistemic): classifies an attributed hedge without asserting it")
    void epistemicPass() {
        String prompt = ExtractionPassPrompts.epistemic(CONTEXT, OPINION);
        String raw = ask(ExtractionPassPrompts.PASS_EPISTEMIC, prompt);

        Optional<EpistemicProposal> classification =
                ExtractionPassParsers.epistemic(raw, CONTEXT, OPINION.id());

        classification.ifPresent(c -> {
            assertTrue(c.certainty() >= 0.0 && c.certainty() <= 1.0,
                    "pass 3 returned a certainty outside [0,1]: " + c.certainty());
            System.out.println("  [p3] speechAct=" + c.speechAct() + " holder=" + c.holder()
                    + " certainty=" + c.certainty() + " reason=" + c.reason());
        });

        String verdict = classification
                .map(c -> c.speechAct() + " held by " + c.holder() + " (certainty " + c.certainty() + ")")
                .orElse("UNPARSEABLE: " + firstLine(raw));
        score("epistemic", "\"Chen said margins would probably improve\" classified as " + verdict);
    }

    // -------------------------------------------------------------------------------------------
    // Pass 4 — relations (the schema firewall), twice: a type that exists and one that does not
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pass 4 (relations): picks a permitted type, never one it invented")
    void relationsPassWithAPermittedType() {
        String prompt = ExtractionPassPrompts.relations(CONTEXT, HIRING,
                "Acme Corporation (ORGANIZATION)", "Jane Chen (PERSON)", RELATION_CANDIDATES);
        String raw = ask(ExtractionPassPrompts.PASS_RELATIONS, prompt);

        Optional<RelationProposal> relation =
                ExtractionPassParsers.relation(raw, CONTEXT, HIRING.id(), "e-acme", "e-jane-chen");

        Set<String> permitted = RELATION_CANDIDATES.stream()
                .map(RelationCandidate::type).collect(Collectors.toSet());
        relation.ifPresent(r -> {
            if (r.committing()) {
                assertTrue(permitted.contains(r.type()),
                        "pass 4 committed to a relation type outside the permitted set: "
                                + r.type() + " not in " + permitted);
            }
            System.out.println("  [p4a] " + r.operation() + " type=" + r.type() + " conf=" + r.confidence()
                    + " occurredAt=" + r.occurredAt() + " qualifiers=" + r.qualifiers());
        });

        score("relations (type available)", relation
                .map(r -> r.operation() + " -> " + r.type() + "; expected CREATE_CLAIM -> EMPLOYS")
                .orElse("UNPARSEABLE: " + firstLine(raw)));
    }

    @Test
    @DisplayName("pass 4 (relations): reports a schema gap rather than forcing the nearest type")
    void relationsPassWithNoPermittedType() {
        String prompt = ExtractionPassPrompts.relations(CONTEXT, NEGATED,
                "Acme Corporation (ORGANIZATION)", "Globex Ltd (ORGANIZATION)", RELATION_CANDIDATES);
        String raw = ask(ExtractionPassPrompts.PASS_RELATIONS, prompt);

        Optional<RelationProposal> relation =
                ExtractionPassParsers.relation(raw, CONTEXT, NEGATED.id(), "e-acme", "e-globex");

        Set<String> permitted = RELATION_CANDIDATES.stream()
                .map(RelationCandidate::type).collect(Collectors.toSet());
        relation.ifPresent(r -> {
            if (r.committing()) {
                assertTrue(permitted.contains(r.type()),
                        "pass 4 invented a relation type for an unmodelled relation: " + r.type());
            }
            System.out.println("  [p4b] " + r.operation() + " type=" + r.type()
                    + " reason=" + r.reason());
        });

        // A missing parse is not an abstention. The model can force a type and still emit output
        // the parser cannot read, so treating an empty Optional as honest credits a failure as a
        // success and hides exactly the behaviour this test exists to catch. Only a parsed
        // PROPOSE_SCHEMA_GAP or ABSTAIN is honest; unreadable output is reported as unreadable.
        String verdict = relation
                .map(r -> r.operation() + " -> " + r.type())
                .orElse("nothing parseable in " + raw.length() + " chars: " + firstLine(raw));
        String judgement = relation
                .map(r -> r.operation() == ProposalOperation.PROPOSE_SCHEMA_GAP
                                || r.operation() == ProposalOperation.ABSTAIN
                        ? "honest" : "FORCED A TYPE")
                .orElse("UNPARSEABLE");
        score("relations (negated, no type)",
                verdict + "; expected PROPOSE_SCHEMA_GAP or ABSTAIN -> " + judgement);
    }

    // -------------------------------------------------------------------------------------------
    // Pass 5 — claims (the conflict firewall)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pass 5 (claims): matches only claim keys it was shown")
    void claimsPass() {
        RelationProposal proposed = new RelationProposal(HIRING.id(), "e-acme", "e-jane-chen",
                "EMPLOYS", ProposalOperation.CREATE_CLAIM, 0.85, "2025-03", Map.of(),
                List.of(), "the text states the hiring", HIRING.evidence());

        String prompt = ExtractionPassPrompts.claims(CONTEXT, proposed,
                "Acme Corporation EMPLOYS Jane Chen (as CFO, March 2025)", CLAIM_CANDIDATES);
        String raw = ask(ExtractionPassPrompts.PASS_CLAIMS, prompt);

        Optional<ClaimProposal> decision =
                ExtractionPassParsers.claim(raw, CONTEXT, HIRING.id(), "e-acme|EMPLOYS|e-jane-chen");

        Set<String> keys = CLAIM_CANDIDATES.stream()
                .map(ClaimCandidate::atomKey).collect(Collectors.toSet());
        decision.ifPresent(d -> {
            if (d.matchedAtomKey() != null && !d.matchedAtomKey().isBlank()) {
                assertTrue(keys.contains(d.matchedAtomKey()),
                        "pass 5 matched a claim key it was never shown: " + d.matchedAtomKey());
            }
            assertTrue(d.operation() != ProposalOperation.RECORD_OPINION,
                    "pass 5 must not resolve a conflict by recording an opinion");
            System.out.println("  [p5] " + d.operation() + " matched=" + d.matchedAtomKey()
                    + " conf=" + d.confidence() + " reason=" + d.reason());
        });

        score("claims", decision
                .map(d -> d.operation() + " -> " + d.matchedAtomKey()
                        + "; expected ADD_EVIDENCE -> atom-cfo-chen")
                .orElse("no decision returned"));
    }

    // -------------------------------------------------------------------------------------------
    // All five passes, chained, against the real model
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("all five passes chained: real per-pass dispatch produces a projected result")
    void wholePipelineAgainstTheRealModel() {
        EntityCandidateProvider entities =
                (mentionText, typeHint, context, limit) -> ENTITY_CANDIDATES;
        RelationCandidateProvider relations =
                (sourceType, targetType, context, limit) -> RELATION_CANDIDATES;
        ClaimCandidateProvider claims =
                (subjectEntityId, predicate, objectEntityId, context, limit) -> CLAIM_CANDIDATES;

        DecomposedExtractionPipeline pipelineUnderTest = new DecomposedExtractionPipeline(
                entities, relations, claims,
                DecomposedExtractionPipeline.Options.defaults().withMaxPropositions(3));

        long t0 = System.nanoTime();
        DecomposedExtractionPipeline.Outcome outcome =
                pipelineUnderTest.run(CONTEXT, this::ask);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(outcome, "the pipeline returned nothing at all");
        assertNotNull(outcome.result(), "the pipeline produced no projected result");

        Set<String> knownPasses = Set.copyOf(ExtractionPassPrompts.PASS_IDS);
        outcome.stats().forEach(stat -> assertTrue(knownPasses.contains(stat.passId()),
                "unknown pass id in telemetry: " + stat.passId()));

        System.out.println("\n---- whole decomposed pipeline on a real model (" + ms + " ms) ----");
        outcome.stats().forEach(s -> System.out.println("  [stats] " + s.passId()
                + " calls=" + s.calls() + " proposed=" + s.proposed() + " accepted=" + s.accepted()
                + " abstained=" + s.abstained() + " spanRejected=" + s.spanRejected()
                + " outOfVocabulary=" + s.outOfVocabulary() + " failures=" + s.failures()));
        outcome.notes().forEach(n -> System.out.println("  [note] " + n));

        ExtractionResult result = outcome.result();
        int entityCount = result.entities() == null ? 0 : result.entities().size();
        int relationCount = result.relations() == null ? 0 : result.relations().size();
        System.out.println("  [projected] " + entityCount + " entities, " + relationCount + " relations");

        int totalCalls = outcome.stats().stream()
                .mapToInt(DecomposedExtractionPipeline.PassStats::calls).sum();
        int outOfVocabulary = outcome.stats().stream()
                .mapToInt(DecomposedExtractionPipeline.PassStats::outOfVocabulary).sum();
        assertTrue(totalCalls > 0, "no pass was dispatched to the model at all");

        score("whole pipeline", totalCalls + " model calls across " + outcome.stats().size()
                + " passes in " + ms + " ms -> " + entityCount + " entities / " + relationCount
                + " relations, " + outOfVocabulary + " out-of-vocabulary answers rejected");
    }

    // -------------------------------------------------------------------------------------------
    // dispatch
    // -------------------------------------------------------------------------------------------

    /**
     * Dispatches one pass prompt to the local model, self-healing a truncated answer by continuing
     * from the retained in-graph KV cache instead of re-prefilling. This is the real
     * {@code LlmCaller} the pipeline is handed, so a pass that overruns the window fails here in
     * exactly the way it would in production.
     */
    private String ask(String passId, String prompt) {
        int approxPromptTokens = prompt.length() / 4;
        long t0 = System.nanoTime();
        String raw;
        String finishReason;
        int continues = 0;
        try (GenerationPipeline.GenerationSession session = pipeline.startSession(prompt)) {
            GenerationResult latest = session.generate(Math.min(STEP_TOKENS, MAX_OUTPUT_TOKENS));
            int requested = Math.min(STEP_TOKENS, MAX_OUTPUT_TOKENS);
            while (latest.isTruncated() && session.getRemainingCapacity() > 0
                    && requested < MAX_OUTPUT_TOKENS) {
                int step = Math.min(STEP_TOKENS, MAX_OUTPUT_TOKENS - requested);
                latest = session.continueGeneration(step);
                requested += step;
                continues++;
            }
            raw = session.getFullText();
            boolean budgetSpent = latest.isTruncated() && requested >= MAX_OUTPUT_TOKENS;
            finishReason = latest.getFinishReason()
                    + (session.isEosReached() ? " (EOS)" : "")
                    + (budgetSpent ? " (spent the " + MAX_OUTPUT_TOKENS + "-token output budget)" : "");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("\n---- pass " + passId + " (" + ms + " ms, prompt " + prompt.length()
                + " chars ~" + approxPromptTokens + " tok, " + continues + " continue rounds, "
                + finishReason + ") ----");
        System.out.println(raw);
        return raw;
    }

    private static void score(String pass, String line) {
        SCORECARD.add(String.format("%-28s %s", pass, line));
    }

    /**
     * The first line of a model reply, clipped, so a scorecard entry for output the parser could
     * not read still shows what the model actually said.
     */
    private static String firstLine(String raw) {
        String head = raw == null ? "" : raw.strip();
        int newline = head.indexOf('\n');
        if (newline >= 0) {
            head = head.substring(0, newline);
        }
        return head.length() <= 120 ? head : head.substring(0, 120) + "...";
    }
}
