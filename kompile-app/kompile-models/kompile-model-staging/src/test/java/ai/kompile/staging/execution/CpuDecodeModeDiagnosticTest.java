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

import ai.kompile.core.graphrag.passes.ExtractionPassPrompts;
import ai.kompile.core.graphrag.passes.PassContext;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.eclipse.deeplearning4j.model.benchmark.BenchmarkConfig;
import org.junit.jupiter.api.Test;
import org.nd4j.autodiff.samediff.diagnostics.DspDiagnostics;
import org.nd4j.autodiff.samediff.execution.GraphExecutionMode;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bisects CPU decode correctness across the graph-execution modes.
 *
 * <p>Greedy decoding of {@code "The capital of France is"} has exactly one right answer, so this
 * needs no reference tensors and no tolerance tuning: a mode either continues the sentence or it
 * does not. Degenerate output — the same token repeated for the whole budget — is the signature
 * this exists to localize, so each mode is scored by how many <em>distinct</em> characters it
 * produced, not only by whether the expected word appears.</p>
 *
 * <p>The modes are ordered cheapest-trust-first: {@code SLOT_BY_SLOT} runs op by op with no graph
 * backend and is the reference; {@code OPENVINO} forces that backend; portable replay selects the
 * platform's available CPU graph recorder; and the {@code AUTO} rows run the real cascade, with
 * {@code CPU_CASCADE} additionally freezing and merging DSP segments. Whichever step first turns
 * sane text into repetition is the one that corrupts the logits.</p>
 *
 * <p>The focused rows are what make the AUTO rows readable. AUTO builds a chain and hands each
 * segment to the first backend that accepts it, so a wrong answer from AUTO names the chain, not a
 * member of it. Portable replay deliberately follows the current native capability resolver instead
 * of naming a removed oneDNN-only Java mode.</p>
 *
 * <p>A focused row is a request, not a guarantee — an unavailable backend can still demote a
 * segment to slot-by-slot. Read the row together with the native
 * {@code POST_EXEC ... segs(cpuGraph=N[name])} counter, which names the backend that actually ran;
 * a row that reports {@code cpuGraph=0} ran native and attributes nothing.</p>
 *
 * <p>Opt-in like the other real-model harnesses (loading a multi-GB model is slow):
 * {@code mvn -o test -pl :kompile-model-staging -Dtest=CpuDecodeModeDiagnosticTest
 * -Dkompile.samediff.llm.harness=true}.</p>
 */
class CpuDecodeModeDiagnosticTest {

    private static final Path MODEL_DIR = Paths.get(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls", "lfm2.5-1.2b-instruct");

    /** One unambiguous continuation; greedy decoding makes the right answer deterministic. */
    private static final String PROMPT = "The capital of France is";

    private static final String EXPECTED = "Paris";

    /** Small budget: enough to tell "Paris, the largest city..." from "........". */
    private static final int TOKENS = Integer.getInteger("kompile.samediff.llm.step", 24);

    /** Filler sentence counts for the prompt-length sweep; ~14 tokens each. */
    private static final int[] FILLER_SENTENCES = {0, 8, 30, 48};

    /**
     * Optional native DSP diagnostic category mask (see {@code DspDiagnostics} for the bits;
     * {@code COMPILE} is 1). The graph backends do their backend selection, partitioning and
     * fusion decisions in native code and log through that channel, so when a mode disagrees
     * with the reference this is the only way to see <em>why</em> from here. Left at 0 the
     * native side stays silent, which is what the normal pass/fail run wants.
     */
    private static final int DSP_DIAG_MASK = Integer.getInteger("kompile.samediff.llm.dspdiag", 0);

    @Test
    void everyCpuExecutionModeDecodesTheSameSentence() throws Exception {
        assumeTrue(Boolean.getBoolean("kompile.samediff.llm.harness"),
                "opt-in harness — run with -Dkompile.samediff.llm.harness=true");
        Path modelFile = MODEL_DIR.resolve("model.sdnb");
        assumeTrue(Files.exists(modelFile) && Files.exists(MODEL_DIR.resolve("tokenizer.json")),
                "lfm2.5 SameDiff model not staged at " + MODEL_DIR);

        DifferentialFunctionClassHolder.initInstance();
        enableNativeDiagnostics();

        // Ordered cheapest-trust-first so the report reads as a bisect.
        Map<String, Supplier<BenchmarkConfig>> modes = new LinkedHashMap<>();
        modes.put("SLOT_BY_SLOT (reference)", BenchmarkConfig::cpuSlotBySlot);
        modes.put("OPENVINO only (forced)", BenchmarkConfig::cpuOpenVino);
        modes.put("portable CPU graph replay", () -> BenchmarkConfig.create("CPU_PORTABLE_REPLAY")
                .executionMode(GraphExecutionMode.PORTABLE_REPLAY)
                .maxTokens(TOKENS).minDiversityPct(5));
        modes.put("AUTO cascade, no merge", BenchmarkConfig::cpuCascadeNoMerge);
        modes.put("AUTO cascade + freeze/merge", BenchmarkConfig::cpuCascade);

        List<String> report = new ArrayList<>();
        for (Map.Entry<String, Supplier<BenchmarkConfig>> mode : modes.entrySet()) {
            clearNativeDiagnostics();
            report.add(runOne(mode.getKey(), mode.getValue().get(), modelFile, PROMPT, 512));
            dumpNativeDiagnostics(mode.getKey());
        }

        System.out.println("\n================ CPU decode mode bisect ================");
        System.out.println("prompt: \"" + PROMPT + "\"  expected continuation to mention: " + EXPECTED);
        report.forEach(System.out::println);
        System.out.println("========================================================\n");
    }

    /**
     * Sweeps prompt length at a fixed execution mode.
     *
     * <p>The mode bisect above proves short-prompt decode; it says nothing about prefill over the
     * 458-715 token prompts the extraction passes actually send. This asks the same unambiguous
     * question with a growing block of irrelevant filler in front of it: the right answer never
     * changes, so any length at which the answer stops being "Paris" — or at which the model
     * emits end-of-sequence as its very first token — is a prefill defect at that length, not a
     * property of the question. LFM2 is a hybrid conv/attention model, and its {@code causal_conv1d}
     * recurrent states are barely exercised by a seven-token prompt.</p>
     */
    @Test
    void prefillStaysCorrectAsThePromptGrows() throws Exception {
        assumeTrue(Boolean.getBoolean("kompile.samediff.llm.harness"),
                "opt-in harness — run with -Dkompile.samediff.llm.harness=true");
        Path modelFile = MODEL_DIR.resolve("model.sdnb");
        assumeTrue(Files.exists(modelFile) && Files.exists(MODEL_DIR.resolve("tokenizer.json")),
                "lfm2.5 SameDiff model not staged at " + MODEL_DIR);

        DifferentialFunctionClassHolder.initInstance();

        List<String> report = new ArrayList<>();
        for (int sentences : FILLER_SENTENCES) {
            String prompt = filler(sentences) + PROMPT;
            report.add(runOne("filler=" + sentences + " (" + prompt.length() + " chars)",
                    BenchmarkConfig.cpuSlotBySlot(), modelFile, prompt, 2048));
        }

        System.out.println("\n============ prompt-length prefill sweep (SLOT_BY_SLOT) ============");
        System.out.println("question: \"" + PROMPT + "\"  expected continuation to mention: " + EXPECTED);
        report.forEach(System.out::println);
        System.out.println("====================================================================\n");
    }

    /**
     * Asks a real extraction-pass prompt with and without the model's own chat template.
     *
     * <p>With the prompt-length sweep clean, an extraction pass that answers with end-of-sequence
     * and nothing else is not a numerics failure — it is the model never being put in a turn. A
     * pipeline given no chat template encodes the prompt verbatim and completes it as raw text, and
     * the likeliest continuation of a document that ends in a directive is the end of that
     * document. This pins that down: the same prompt, the same mode, one row without a template and
     * the rest with the template the GGUF itself declares.</p>
     */
    @Test
    void aRealPassPromptNeedsTheModelsChatTemplate() throws Exception {
        assumeTrue(Boolean.getBoolean("kompile.samediff.llm.harness"),
                "opt-in harness — run with -Dkompile.samediff.llm.harness=true");
        Path modelFile = MODEL_DIR.resolve("model.sdnb");
        assumeTrue(Files.exists(modelFile) && Files.exists(MODEL_DIR.resolve("tokenizer.json")),
                "lfm2.5 SameDiff model not staged at " + MODEL_DIR);

        DifferentialFunctionClassHolder.initInstance();

        PassContext context = PassContext
                .forChunk("chunk-fpna-1", "doc-fpna-board-minutes",
                        "Acme Corporation hired Jane Chen as CFO in March 2025. "
                                + "Chen said margins would probably improve next year. "
                                + "Acme did not acquire Globex Ltd.")
                .withSchema("kompile-extraction-passes/v1", "lfm2.5-1.2b-instruct");
        String prompt = ExtractionPassPrompts.propositions(context, 5);

        String template = LocalModelChatTemplate.resolve(MODEL_DIR);
        assumeTrue(template != null, "no chat template in the staged GGUF at " + MODEL_DIR);

        List<String> report = new ArrayList<>();
        report.add(runOne("no template, greedy", BenchmarkConfig.cpuSlotBySlot(), modelFile,
                prompt, 2048, 96, "propositions", null, b -> b.doSample(false)));
        report.add(runOne("GGUF template, greedy", BenchmarkConfig.cpuSlotBySlot(), modelFile,
                prompt, 2048, 96, "propositions", template, b -> b.doSample(false)));
        report.add(runOne("GGUF template, sampled 0.7", BenchmarkConfig.cpuSlotBySlot(), modelFile,
                prompt, 2048, 96, "propositions", template,
                b -> b.doSample(true).temperature(0.7).topP(0.95)));

        System.out.println("\n============ pass-1 prompt, chat template on/off ============");
        System.out.println("prompt: " + prompt.length() + " chars; template: " + template.length()
                + " chars; expected: a JSON object with \"propositions\"");
        report.forEach(System.out::println);
        System.out.println("=============================================================\n");
    }

    /**
     * Turns on the requested native DSP diagnostic channels; a zero mask leaves them off.
     *
     * <p>Both knobs are required. The category mask decides which events are <em>recorded</em>,
     * but {@code recordEventV} only echoes an event to stdout at {@code LEVEL_FULL} (or under
     * debug+verbose) — enabling a category alone fills the native ring buffer and prints nothing,
     * which reads exactly like "the condition never fired". {@code DspDebugger} pairs the two
     * calls for the same reason; keep them paired here.</p>
     */
    private static void enableNativeDiagnostics() {
        if (DSP_DIAG_MASK == 0) {
            return;
        }
        NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
        ops.dspDiagEnableCategories(DSP_DIAG_MASK);
        ops.dspDiagSetLevel(DspDiagnostics.LEVEL_FULL);
        System.out.println("native DSP diagnostics enabled, requested=0x"
                + Integer.toHexString(DSP_DIAG_MASK) + ", readback=0x"
                + Integer.toHexString(ops.dspDiagGetEnabledMask()) + ", level=FULL");
    }

    /** Empties the native event ring so each mode's dump contains only that mode's events. */
    private static void clearNativeDiagnostics() {
        if (DSP_DIAG_MASK == 0) {
            return;
        }
        NativeOpsHolder.getInstance().getDeviceNativeOps().dspDiagClear();
    }

    /**
     * Pulls the native event ring back into Java and writes it beside the surefire output.
     *
     * <p>Not a convenience over reading stdout — the native echo is unusable from here. Events are
     * always recorded to the ring, but the stdout echo in {@code recordEventV} is a bare
     * {@code fprintf(stdout)} straight to fd 1, while surefire wraps the forked JVM's Java stream
     * in its own fork protocol. Java {@code System.out} therefore reaches the build log and native
     * writes do not, so an unprinted event and an event that never fired look identical. Reading
     * the ring back through JNI removes that ambiguity: {@code dspDiagGetTotalEventCount()} counts
     * what actually fired regardless of whether anything was ever echoed.</p>
     */
    private static void dumpNativeDiagnostics(String modeLabel) {
        if (DSP_DIAG_MASK == 0) {
            return;
        }
        NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
        long events = ops.dspDiagGetTotalEventCount();
        String slug = modeLabel.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase();
        Path out = Paths.get("target", "dspdiag-" + slug + ".json");
        System.out.println("native DSP events recorded for [" + modeLabel + "]: " + events
                + " -> " + out.toAbsolutePath());
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, ops.dspDiagGetJsonReport());
        } catch (Exception e) {
            System.out.println("could not write " + out + ": " + e);
        }
    }

    /** Neutral, non-repeating background text that does not answer the question. */
    private static String filler(int sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= sentences; i++) {
            sb.append("Note ").append(i)
              .append(": routine background material recorded for completeness only.\n");
        }
        return sb.toString();
    }

    private String runOne(String label, BenchmarkConfig benchmark, Path modelFile,
                          String prompt, int maxKv) {
        // Greedy so the comparison between modes is token-identical when they agree.
        // No chat template: a bare completion prompt is the purest probe of decode numerics.
        return runOne(label, benchmark, modelFile, prompt, maxKv, TOKENS, EXPECTED, null,
                b -> b.doSample(false));
    }

    private String runOne(String label, BenchmarkConfig benchmark, Path modelFile,
                          String prompt, int maxKv, int tokens, String expected,
                          String chatTemplate,
                          UnaryOperator<SamplingConfig.SamplingConfigBuilder> policy) {
        long t0 = System.nanoTime();
        try {
            HuggingFaceTokenizer tokenizer =
                    HuggingFaceTokenizer.fromFile(MODEL_DIR.resolve("tokenizer.json").toString());
            SamplingConfig sampling = policy
                    .apply(SamplingConfig.builder().eosTokenId(tokenizer.getEosTokenId()))
                    .build();
            GenerationPipelineConfig config = GenerationPipelineConfig.builder()
                    .decoderPath(modelFile.toString())
                    .tokenizer(tokenizer)
                    .samplingConfig(sampling)
                    .kvCacheStrategy(KvCacheStrategy.STATIC)
                    .maxKvCacheLength(maxKv)
                    .benchmarkConfig(benchmark)
                    .chatTemplate(chatTemplate)
                    .build();

            String text;
            int generated;
            GenerationResult.FinishReason finish;
            try (GenerationPipeline pipeline = GenerationPipeline.create(config);
                 GenerationPipeline.GenerationSession session = pipeline.startSession(prompt)) {
                GenerationResult result = session.generate(tokens);
                text = result.getText() == null ? "" : result.getText();
                generated = result.getGeneratedTokenCount();
                finish = result.getFinishReason();
            }

            long ms = (System.nanoTime() - t0) / 1_000_000;
            long distinct = text.chars().distinct().count();
            boolean correct = text.contains(expected);
            String verdict;
            if (correct) {
                verdict = "CORRECT";
            } else if (generated <= 1 && finish == GenerationResult.FinishReason.EOS) {
                verdict = "EOS-AT-ONCE";   // model ended the turn without saying anything
            } else if (distinct <= 3) {
                verdict = "DEGENERATE";
            } else {
                verdict = "WRONG";
            }
            System.out.println("\n---- " + label + " [" + verdict + "] ----\n" + text);
            return String.format("%-34s %-12s %6d ms  tokens=%-3d %-10s distinctChars=%-3d %s",
                    label, verdict, ms, generated, finish, distinct, oneLine(text));
        } catch (Throwable t) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("\n---- " + label + " [THREW] ----");
            t.printStackTrace(System.out);
            return String.format("%-34s %-12s %6d ms  %s: %s",
                    label, "THREW", ms, t.getClass().getSimpleName(), oneLine(String.valueOf(t.getMessage())));
        }
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 70 ? flat.substring(0, 70) + "..." : flat;
    }
}
