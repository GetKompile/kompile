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
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Graph;

import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.junit.jupiter.api.Test;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prompt-tuning harness: load an actual small local model in the SameDiff format kompile ships
 * ({@code lfm2.5-1.2b-instruct}) <b>in-process</b> via the same {@code samediff-llm}
 * {@link GenerationPipeline} the staging server uses — no serving subprocess, no CLI, no HTTP — feed
 * it kompile's real relation-extraction prompt
 * ({@link GraphExtractionValidator#getExtractionPromptInstructions()}), and see what graph it builds.
 * The goal is to iterate on the prompt and judge whether small local models produce usable graphs, so
 * it always prints the raw model output and treats malformed JSON as a tuning signal.
 *
 * <p>Opt-in (loading a multi-GB model is slow): run explicitly with
 * {@code mvn -o -pl kompile-app/kompile-models/kompile-model-staging test
 * -Dtest=SameDiffLlmExtractionHarnessTest -Dkompile.samediff.llm.harness=true}. Point at a different
 * local model by editing {@link #MODEL_DIR}.</p>
 */
class SameDiffLlmExtractionHarnessTest {

    private static final Path MODEL_DIR = Paths.get(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls", "lfm2.5-1.2b-instruct");

    private static final String TEXT =
            "Acme Corporation, based in Paris, hired Jane Chen as its CEO. "
                    + "Acme partnered with Globex Ltd on a new product.";

    private static final List<String> ENTITY_TYPES = List.of("PERSON", "ORGANIZATION", "LOCATION", "PRODUCT");

    @Test
    void lfm2_5_extractsAGraphFromText() throws Exception {
        assumeTrue(Boolean.getBoolean("kompile.samediff.llm.harness"),
                "opt-in harness — run with -Dkompile.samediff.llm.harness=true");
        Path modelFile = MODEL_DIR.resolve("model.sdnb");
        assumeTrue(Files.exists(modelFile) && Files.exists(MODEL_DIR.resolve("tokenizer.json")),
                "lfm2.5 SameDiff model not staged at " + MODEL_DIR);

        // Populate the ND4J op registry before SameDiff deserialization (idempotent).
        DifferentialFunctionClassHolder.initInstance();

        // dl4j canonical construction: explicit tokenizer from tokenizer.json + decoder path.
        HuggingFaceTokenizer tokenizer =
                HuggingFaceTokenizer.fromFile(MODEL_DIR.resolve("tokenizer.json").toString());
        GenerationPipelineConfig config = GenerationPipelineConfig.builder()
                .decoderPath(modelFile.toString())
                .tokenizer(tokenizer)
                .samplingConfig(SamplingConfig.defaultConfig())
                .kvCacheStrategy(KvCacheStrategy.STATIC)
                .maxKvCacheLength(512)
                .build();

        GenerationPipeline pipeline = GenerationPipeline.create(config);
        try {
            String prompt = buildExtractionPrompt(TEXT, ENTITY_TYPES);

            long t0 = System.nanoTime();
            // Continue-generation self-heal: a deliberately small first budget truncates the JSON, then
            // continueGeneration() resumes from the RETAINED in-graph KV cache (no re-prefill) in fixed
            // steps until the model emits EOS or the context buffer is full — instead of blindly bumping
            // maxNewTokens. Bounded by the model's 512-token context.
            String raw;
            String finishReason;
            int continues = 0;
            try (GenerationPipeline.GenerationSession session = pipeline.startSession(prompt)) {
                GenerationResult r = session.generate(48);
                while (r.isTruncated() && session.getRemainingCapacity() > 0) {
                    r = session.continueGeneration(48);
                    continues++;
                }
                raw = session.getFullText();
                finishReason = r.getFinishReason() + (session.isEosReached() ? " (EOS reached)" : "");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;

            System.out.println("\n================ lfm2.5-1.2b-instruct extraction (" + ms + " ms) ================");
            System.out.println("PROMPT (" + prompt.length() + " chars):\n" + prompt);
            System.out.println("---------------- raw model output  [finishReason=" + finishReason
                    + ", continueRounds=" + continues + "] ----------------\n" + raw);
            System.out.println("--------------------------------------------------");

            assertNotNull(raw);
            assertFalse(raw.isBlank(), "the local model produced no output at all");

            String cleaned = stripFences(raw);
            try {
                ExtractionResult extraction = GraphExtractionValidator.fromJson(cleaned);
                Graph graph = GraphExtractionValidator.toGraph(extraction);
                int nodes = graph.getEntities() == null ? 0 : graph.getEntities().size();
                int edges = graph.getRelationships() == null ? 0 : graph.getRelationships().size();
                System.out.println("PARSED → graph with " + nodes + " nodes, " + edges + " edges:");
                extraction.entities().forEach(e -> System.out.println(
                        "  ENTITY  " + e.id() + "  [" + e.type() + "]  " + e.name() + "  (conf=" + e.confidence() + ")"));
                extraction.relations().forEach(r -> System.out.println(
                        "  RELATION  " + r.source() + " -[" + r.type() + "]-> " + r.target() + "  (conf=" + r.confidence() + ")"));
                System.out.println("=> lfm2.5 built a graph with " + nodes + " nodes / " + edges
                        + " edges from local, in-process SameDiff inference.");
            } catch (Exception parseErr) {
                System.out.println("PARSE FAILED (prompt-tuning signal — the small model's JSON was not conformant): "
                        + parseErr.getMessage());
            }
            System.out.println("==================================================================================\n");
        } finally {
            try {
                pipeline.close();
            } catch (Exception ignored) {
                // best-effort release of native resources
            }
        }
    }

    /**
     * TUNING FINDING: kompile's full {@link GraphExtractionValidator#getExtractionPromptInstructions()}
     * prompt is ~596 tokens and overflows lfm2.5's 512-token context. This compact, small-model-tuned
     * variant keeps the same JSON contract but fits, leaving room to generate.
     */
    private static String buildExtractionPrompt(String text, List<String> entityTypes) {
        return "Extract entities and relations from the text. Output ONLY JSON:\n"
                + "{\"entities\":[{\"id\":\"e1\",\"name\":\"\",\"type\":\"\",\"confidence\":0.9}],"
                + "\"relations\":[{\"source\":\"e1\",\"target\":\"e2\",\"type\":\"\",\"confidence\":0.9}]}\n"
                + "Entity types: " + String.join(", ", entityTypes) + ". Relation types UPPER_SNAKE_CASE.\n\n"
                + "Text: " + text + "\n";
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }
}
