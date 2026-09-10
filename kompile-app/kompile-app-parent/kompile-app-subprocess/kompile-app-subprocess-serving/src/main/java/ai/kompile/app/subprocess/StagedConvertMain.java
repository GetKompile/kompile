/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.subprocess;

import org.eclipse.deeplearning4j.onnx.ONNXPipelineLoader;
import org.eclipse.deeplearning4j.pipeline.PipelineLoader;
import org.eclipse.deeplearning4j.pipeline.PipelineLoader.LoadConfig;
import org.eclipse.deeplearning4j.safetensors.SafeTensorsPipelineLoader;
import org.eclipse.deeplearning4j.ggml.GGMLPipelineLoader;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.optimize.GraphOptimizer;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.ggml.format.GGUFHeader;
import org.nd4j.ggml.format.GGUFReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-shot model → canonical SDZ staging converter for the local CLI serving path.
 *
 * <p>Deliberately minimal and format-agnostic: dispatch by file extension to the
 * upstream {@link PipelineLoader} for the source format (GGUF/GGML, SafeTensors,
 * ONNX — TensorFlow/Keras go through the ONNX importer's ecosystem and are added the
 * same way when needed), run the same GraphOptimizer the serving load path applies,
 * save one canonical SDZ, and exit. No Spring, no staging/web infrastructure — the
 * parent launches this class from the serving jar classpath and the JVM's exit
 * reclaims all conversion memory before serving starts.</p>
 *
 * <p>Weight precision is fully configurable via {@code --weight-dtype}:</p>
 * <ul>
 *   <li>{@code auto} (default) — weights land exactly as authored in the source file
 *       (GGUF packed quantization is preserved as packed bytes and executed through
 *       runtime-quantized matmul; non-quantized tensors stay dense in their native
 *       dtype). No precision decision is made for the user.</li>
 *   <li>Any explicit {@code ND4JInferenceWeightDataType} name — {@code fp32, fp16,
 *       bf16, fp8, fp8_e5m2, int8, int4} — dequantizes/repacks to that storage dtype
 *       at conversion time.</li>
 * </ul>
 *
 * <p>Usage: {@code --input=<model> --output=<model.sdz> [--weight-dtype=auto|fp32|fp16|bf16|fp8|fp8_e5m2|int8|int4]}</p>
 */
public final class StagedConvertMain {

    public static void main(String[] args) {
        Path input = null;
        Path output = null;
        String weightDtype = "auto";
        String backend = "cpu";   // conversion is CPU work by default; gpu opt-in via MCP
        for (String arg : args) {
            if (arg.startsWith("--input=")) {
                input = Path.of(arg.substring("--input=".length()));
            } else if (arg.startsWith("--output=")) {
                output = Path.of(arg.substring("--output=".length()));
            } else if (arg.startsWith("--weight-dtype=")) {
                weightDtype = arg.substring("--weight-dtype=".length()).trim();
            } else if (arg.startsWith("--backend=")) {
                backend = arg.substring("--backend=".length()).trim().toLowerCase(Locale.ROOT);
            }
        }
        // Backend selection MUST happen before any ND4J class loads. Both backends are
        // on this jar's classpath; Nd4jBackend picks per-process via priority properties
        // (org.nd4j.cpu.priority / org.nd4j.gpu.priority). Default CPU: conversion is
        // I/O + dequantize work with no benefit from GPU context initialization, and
        // CPU placement is deterministic under host memory pressure.
        switch (backend) {
            case "cpu" -> {
                System.setProperty("org.nd4j.cpu.priority", "1000");
                System.setProperty("org.nd4j.gpu.priority", "0");
            }
            case "gpu" -> System.setProperty("org.nd4j.gpu.priority", "1000");
            case "auto" -> { /* ND4J defaults: GPU priority 100 > CPU 0 */ }
            default -> {
                System.err.println("unsupported --backend '" + backend
                        + "' (expected cpu|gpu|auto)");
                System.exit(2);
            }
        }
        if (input == null || output == null) {
            System.err.println("usage: StagedConvertMain --input=<model> --output=<model.sdz> "
                    + "[--weight-dtype=auto|fp32|fp16|bf16|fp8|fp8_e5m2|int8|int4]");
            System.exit(2);
        }
        if (!Files.isRegularFile(input)) {
            System.err.println("input not found: " + input);
            System.exit(2);
        }

        String lower = input.getFileName().toString().toLowerCase(Locale.ROOT);
        String format;
        if (lower.endsWith(".gguf") || lower.endsWith(".ggml")) {
            format = "gguf";
        } else if (lower.endsWith(".safetensors")) {
            format = "safetensors";
        } else if (lower.endsWith(".onnx")) {
            format = "onnx";
        } else if (lower.endsWith(".pb") || lower.endsWith(".h5") || lower.endsWith(".keras")) {
            format = "tensorflow";
        } else {
            System.err.println("unsupported source format for file: " + input.getFileName());
            System.exit(2);
            return;
        }

        try {
            long start = System.currentTimeMillis();
            System.out.println("[stage] converting " + input.getFileName()
                    + " (" + format + ") -> " + output.getFileName()
                    + " (weightDtype=" + weightDtype + ")");
            PipelineLoader loader = loaderFor(format);
            LoadConfig config = loadConfig(weightDtype, format);
            SameDiff graph = loader.loadModel(input.toFile(), config);
            int importedOps = graph.ops() == null ? 0 : graph.ops().length;
            int importedVars = graph.variables() == null ? 0 : graph.variables().size();

            // Run the same optimizer the serving load path applies, so the staged
            // archive is serve-ready and serving never needs a dup() round-trip.
            List<String> outputs = graph.outputs() == null ? List.of() : graph.outputs();
            org.nd4j.autodiff.samediff.SameDiff optimized =
                    org.nd4j.autodiff.samediff.optimize.GraphOptimizer.optimize(graph, outputs);
            int optimizedOps = optimized.ops() == null ? 0 : optimized.ops().length;
            int optimizedVars = optimized.variables() == null ? 0 : optimized.variables().size();

            Map<String, String> metadata = Map.of(
                    "source_format", format,
                    "source_file", input.getFileName().toString(),
                    "weight_dtype", weightDtype);

            // ATOMIC staged write: save to a pending sibling, VALIDATE by full reload,
            // then move into place. A killed converter can never leave a truncated or
            // corrupt artifact at the canonical path — the bootstrap would otherwise
            // cache-hit on it forever.
            Path pending = output.resolveSibling("." + output.getFileName() + ".pending");
            Files.deleteIfExists(pending);
            SDZSerializer.save(optimized, pending.toFile(), false, metadata);

            SameDiff reloaded = SDZSerializer.load(pending.toFile(), false);
            int reloadedOps = reloaded == null || reloaded.ops() == null
                    ? 0 : reloaded.ops().length;
            if (reloaded == null || reloadedOps == 0 || reloadedOps != optimizedOps) {
                System.err.println("[stage] post-save validation FAILED: reloaded " + reloadedOps
                        + " ops, expected " + optimizedOps);
                System.exit(1);
            }
            Files.move(pending, output,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Inspectability: a machine-readable report beside the staged artifact so any
            // consumer (human or agent) can see exactly what was staged, by which converter,
            // with which dtype, and what the optimizer did — without loading the SDZ.
            writeStageReport(input, output, format, weightDtype, start,
                    importedOps, importedVars, optimizedOps, optimizedVars, reloadedOps);

            // A staged model must be self-describing: the serving child resolves a
            // tokenizer.json sidecar next to the model file, and the chat template /
            // BOS-EOS-PAD roles live in tokenizer_config.json (read from the source
            // GGUF header — the same migration StagingService performs for the
            // centralized staging service). GGUF-embedded tokenizer metadata does NOT
            // survive SDZ serialization. If the source directory has no tokenizer.json
            // sidecar yet, warn (synthesizing one lossy is worse than failing fast at
            // serve time).
            if ("gguf".equals(format)) {
                materializeTokenizerSidecar(input, output);
                ensureTokenizerMetadata(input, output);
            }

            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                System.err.println("[stage] converter produced no output artifact");
                System.exit(1);
            }
            System.out.printf("[stage] done in %ds: %s (%d bytes) ops %d->%d (validated %d)%n",
                    (System.currentTimeMillis() - start) / 1000,
                    output, Files.size(output), importedOps, optimizedOps, reloadedOps);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("[stage] conversion failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Converter identity — bump when staging semantics change so bootstrap validation
     *  can invalidate previously staged artifacts. v3: writes tokenizer_config.json
     *  from the source GGUF header (chat template + BOS/EOS/PAD), same as
     *  StagingService.ensureTokenizerMetadata. */
    static final String STAGE_FORMAT_VERSION = "3";

    private static void writeStageReport(
            Path input, Path output, String format, String weightDtype, long startMs,
            int importedOps, int importedVars, int optimizedOps, int optimizedVars,
            int validatedOps) throws java.io.IOException {
        Path report = output.resolveSibling(output.getFileName() + ".stage.json");
        String json = "{\n"
                + "  \"stageFormatVersion\": \"" + STAGE_FORMAT_VERSION + "\",\n"
                + "  \"sourceFile\": \"" + input.getFileName() + "\",\n"
                + "  \"sourceBytes\": " + safeSize(input) + ",\n"
                + "  \"sourceLastModifiedMs\": " + safeMtime(input) + ",\n"
                + "  \"sourceFormat\": \"" + format + "\",\n"
                + "  \"weightDtype\": \"" + weightDtype + "\",\n"
                + "  \"stagedFile\": \"" + output.getFileName() + "\",\n"
                + "  \"stagedBytes\": " + safeSize(output) + ",\n"
                + "  \"importedOps\": " + importedOps + ",\n"
                + "  \"importedVariables\": " + importedVars + ",\n"
                + "  \"optimizedOps\": " + optimizedOps + ",\n"
                + "  \"optimizedVariables\": " + optimizedVars + ",\n"
                + "  \"validatedOps\": " + validatedOps + ",\n"
                + "  \"optimizer\": \"GraphOptimizer.optimize (same passes as serving load)\",\n"
                + "  \"durationSeconds\": " + (System.currentTimeMillis() - startMs) / 1000 + ",\n"
                + "  \"convertedAtMs\": " + System.currentTimeMillis() + "\n"
                + "}\n";
        Files.writeString(report, json);
        System.out.println("[stage] report: " + report.getFileName());
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return -1; }
    }

    private static long safeMtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return -1; }
    }

    private static PipelineLoader loaderFor(String format) {
        switch (format) {
            case "gguf":       return new GGMLPipelineLoader();
            case "safetensors": return new SafeTensorsPipelineLoader();
            case "onnx":
            case "tensorflow": return new ONNXPipelineLoader();
            default:
                throw new IllegalArgumentException("no loader for format " + format);
        }
    }

    /**
     * Surface a {@code tokenizer.json} sidecar beside the staged SDZ when the source
     * directory already carries one is a no-op (same directory). There is deliberately
     * NO synthesis of a tokenizer.json from GGUF token lists: a valid tokenizer.json
     * needs the full merges/normalizer structure, and a lossy guess would silently
     * corrupt encoding. A sidecar-less GGUF fails serve-time load with the explicit
     * "Tokenizer file/directory does not exist" error instead.
     */
    private static void materializeTokenizerSidecar(Path gguf, Path stagedSdz) {
        Path sidecar = stagedSdz.resolveSibling("tokenizer.json");
        if (!Files.isRegularFile(sidecar)) {
            System.out.println("[stage] WARNING: source directory has no tokenizer.json "
                    + "sidecar; serving this staged model requires one next to the SDZ");
        }
    }

    /**
     * Ensure the staged model carries the tokenizer protocol metadata declared by its
     * source GGUF — the SAME logic StagingService.ensureTokenizerMetadata applies for
     * the centralized staging service, ported to the local one-shot converter.
     *
     * <p>{@code tokenizer.json} carries vocabulary and merges; the chat template and
     * BOS/EOS/PAD roles live in {@code tokenizer_config.json}. This migration reads
     * those roles from the source container with {@link GGUFReader}. It never guesses
     * token strings and never overwrites existing fields, and it writes atomically
     * (pending file + move) so a killed converter cannot leave a truncated config.</p>
     */
    private static void ensureTokenizerMetadata(Path sourceGguf, Path stagedSdz) {
        Path configPath = stagedSdz.resolveSibling("tokenizer_config.json");
        try {
            Path gguf = findGgufBeside(stagedSdz.getParent(), sourceGguf);
            if (gguf == null) {
                System.out.println("[stage] WARNING: no source GGUF beside the staged "
                        + "SDZ; skipping tokenizer_config.json migration");
                return;
            }

            String template;
            List<String> tokens;
            int bosId;
            int eosId;
            int padId;
            try (GGUFReader reader = new GGUFReader(gguf.toFile())) {
                GGUFHeader header = reader.getHeader();
                template = header.getChatTemplate();
                tokens = header.getTokens();
                bosId = header.getBosTokenId();
                eosId = header.getEosTokenId();
                padId = header.getPadTokenId();
            }

            com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode config;
            if (Files.isRegularFile(configPath)) {
                var root = objectMapper.readTree(configPath.toFile());
                if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
                    System.err.println("[stage] " + configPath.getFileName()
                            + " must contain a JSON object; skipping migration");
                    return;
                }
                config = object;
            } else {
                config = objectMapper.createObjectNode();
            }
            boolean changed = applyTokenizerMetadata(
                    config, template, tokens, bosId, eosId, padId);
            if (!changed) {
                System.out.println("[stage] tokenizer_config.json already complete");
                return;
            }

            Path partial =
                    stagedSdz.resolveSibling("tokenizer_config.json.part");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), config);
            replaceAtomically(partial, configPath);
            System.out.println("[stage] wrote tokenizer_config.json from source container "
                    + gguf.getFileName() + " (chatTemplate="
                    + config.hasNonNull("chat_template") + ", bos="
                    + config.hasNonNull("bos_token") + ", eos="
                    + config.hasNonNull("eos_token") + ", pad="
                    + config.hasNonNull("pad_token") + ")");
        } catch (Exception e) {
            // Metadata migration is best-effort: the model remains servable when a
            // pre-existing tokenizer_config.json already carries the template, and
            // serve-time load reports the exact deficiency otherwise. Never fail the
            // conversion for a warning-grade sidecar.
            System.err.println("[stage] WARNING: tokenizer_config.json migration failed: "
                    + e.getMessage());
        }
    }

    static boolean applyTokenizerMetadata(
            com.fasterxml.jackson.databind.node.ObjectNode config,
            String chatTemplate,
            List<String> tokens,
            int bosId,
            int eosId,
            int padId) {
        boolean changed = false;
        if ((!config.hasNonNull("chat_template")
                || config.path("chat_template").asText("").isBlank())
                && chatTemplate != null
                && !chatTemplate.isBlank()) {
            config.put("chat_template", chatTemplate);
            changed = true;
        }
        changed |= putTokenIfResolvable(config, "bos_token", tokens, bosId);
        changed |= putTokenIfResolvable(config, "eos_token", tokens, eosId);
        changed |= putTokenIfResolvable(config, "pad_token", tokens, padId);
        return changed;
    }

    private static boolean putTokenIfResolvable(
            com.fasterxml.jackson.databind.node.ObjectNode config,
            String field, List<String> tokens, int id) {
        if (config.hasNonNull(field) || tokens == null || id < 0 || id >= tokens.size()) {
            return false;
        }
        config.put(field, tokens.get(id));
        return true;
    }

    /** Prefer the exact source container; else any GGUF in the staged directory
     *  (mirrors StagingService.findGgufBeside). */
    private static Path findGgufBeside(Path dir, Path originalModelPath) throws java.io.IOException {
        if (originalModelPath != null
                && originalModelPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf")
                && Files.isRegularFile(originalModelPath)) {
            return originalModelPath;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void replaceAtomically(Path source, Path destination)
            throws java.io.IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Map the requested dtype to a loader LoadConfig.
     *
     * <p>{@code auto} (default) means: weights land exactly as authored. For GGUF that is
     * {@link org.nd4j.ggml.convert.ConversionOptions.QuantizationMode#RUNTIME_QUANTIZED_MATMUL}
     * — packed quantization stays packed (executed via runtime-quantized matmul) and
     * non-quantized tensors stay dense in their native dtype; no precision decision is
     * made for the user. SafeTensors/ONNX have no packed-quantization axis, so auto is
     * simply the loader's native dtype handling.</p>
     */
    private static LoadConfig loadConfig(String weightDtype, String format) {
        boolean auto = weightDtype == null || weightDtype.isBlank()
                || "auto".equalsIgnoreCase(weightDtype);
        PipelineLoader.LoadConfigBuilder builder = LoadConfig.builder()
                .useMmap(true);
        if (auto) {
            if ("gguf".equals(format)) {
                // Runtime-quantized matmul: keeps packed tensors packed AND emits the
                // __q__ companion the architecture builders need to emit ggml_qmatmul;
                // non-quantized linears fall back to dense automatically.
                builder.dataType("int4");
            } else {
                builder.dataType("fp16");   // native dtype handling for dense formats
            }
        } else {
            builder.dataType(weightDtype);
        }
        return builder.build();
    }
}
