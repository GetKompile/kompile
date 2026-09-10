/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.learning.subprocess;

import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Executes one isolated portable KGE + PSL/MEBN learning phase over a complete graph archive. */
public final class PortableGraphLearningJob {
    private PortableGraphLearningJob() {
    }

    public record Result(UnifiedGraph graph,
                         Path outputPath,
                         UnifiedGraphKgeLifecycle.Summary embedding,
                         UnifiedGraphReasoningLifecycle.Summary reasoning) {
    }

    public static Result run(PortableGraphLearningSubprocessArgs args) throws Exception {
        Objects.requireNonNull(args, "args");
        if (args.protocolVersion() != PortableGraphLearningSubprocessArgs.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported portable graph learning protocol version: "
                    + args.protocolVersion());
        }
        if (!PortableGraphLearningSubprocessArgs.OPERATION.equalsIgnoreCase(args.operation())) {
            throw new IllegalArgumentException("Unsupported learning operation: " + args.operation());
        }
        Path input = requiredPath(args.inputGraphPath(), "inputGraphPath");
        Path output = requiredPath(args.outputGraphPath(), "outputGraphPath");
        if (input.equals(output)) {
            throw new IllegalArgumentException("inputGraphPath and outputGraphPath must be different");
        }
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input graph does not exist: " + input);
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        UnifiedGraph graph = UnifiedGraph.load(input);
        if (args.factSheetId() != null && graph.factSheetId() != null
                && !args.factSheetId().equals(graph.factSheetId())) {
            throw new IllegalArgumentException("Fact-sheet mismatch: request=" + args.factSheetId()
                    + " graph=" + graph.factSheetId());
        }
        if (graph.factSheetId() == null && args.factSheetId() != null) {
            graph.factSheetId(args.factSheetId());
        }

        UnifiedGraphKgeLifecycle.Summary embedding = args.embeddingEnabled()
                ? UnifiedGraphKgeLifecycle.learn(graph, new UnifiedGraphKgeLifecycle.Config(
                        true, args.embeddingAlgorithm(), args.embeddingDim(), args.embeddingEpochs(),
                        args.embeddingLearningRate(), args.embeddingWarmStartEpochs(),
                        args.embeddingSeed()))
                : UnifiedGraphKgeLifecycle.Summary.disabled();
        UnifiedGraphReasoningLifecycle.Summary reasoning = args.reasoningEnabled()
                ? UnifiedGraphReasoningLifecycle.learn(graph,
                        new UnifiedGraphReasoningLifecycle.Config(true, args.pslSteps(),
                                args.mebnEpochs(), args.consensusRounds(), args.consensusWeight(),
                                args.maxRelationTypes()))
                : UnifiedGraphReasoningLifecycle.Summary.disabled();

        graph.meta("learning.execution", "SUBPROCESS")
                .meta("learning.protocolVersion", args.protocolVersion())
                .meta("learning.crawlJobId", args.crawlJobId())
                .meta("learning.completedAt", Instant.now().toString());
        graph.save(output);
        return new Result(graph, output, embedding, reasoning);
    }

    private static Path requiredPath(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
