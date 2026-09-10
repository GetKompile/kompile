/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.definition;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-only subset of the canonical sequence/DAG contract. No tensor runner is substituted. */
public final class ChatPipelineComposition {
    public static final String RUNNER = "CHAT_MODEL";
    public static final String STEP = "ai.kompile.pipelines.framework.core.config.GenericStepConfig";
    public static final String SEQUENCE = "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline";
    public static final String GRAPH = "ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline";
    private ChatPipelineComposition() {}

    /** Detect even a mixed graph so that validation rejects it before a local runtime is launched. */
    public static boolean containsChat(UnifiedPipelineDefinition definition) {
        return definition != null && containsChat(definition.getPipelineSpec());
    }

    private static boolean containsChat(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (RUNNER.equals(map.get("runnerClassName")) || RUNNER.equals(map.get("type"))) return true;
            return map.values().stream().anyMatch(ChatPipelineComposition::containsChat);
        }
        return value instanceof List<?> list && list.stream().anyMatch(ChatPipelineComposition::containsChat);
    }

    public record Stage(String name, List<String> inputs, Map<String, String> bindings,
                        UnifiedPipelineDefinition definition) {}
    public record Plan(List<Stage> stages, String output) {}

    public static Plan plan(UnifiedPipelineDefinition definition) {
        if (definition.getProcessor() != null) throw invalid("Use either a standalone processor or composed steps, not both");
        if (definition.getKind() != UnifiedPipelineDefinition.PipelineKind.LLM) throw invalid("Composed host pipelines require kind=LLM; translation is a per-stage workload");
        if (definition.getTopology() == null) throw invalid("topology is required");
        if (definition.getModelSetId() != null) throw invalid("Composed chat uses per-stage modelBindings, not modelSetId");
        Map<String, Object> spec = definition.getPipelineSpec();
        boolean sequence = definition.getTopology() == UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE;
        String expected = sequence ? SEQUENCE : GRAPH;
        if (spec == null || !expected.equals(spec.get("@class"))) throw invalid("pipelineSpec.@class must match topology");
        fields(spec, sequence ? Set.of("@class", "id", "steps")
                : Set.of("@class", "id", "nodes", "inputNodeName", "outputNodeName"));
        String external = String.valueOf(spec.getOrDefault("inputNodeName", "pipeline_input"));
        if (!external.equals("pipeline_input")) throw invalid("Host graphs use inputNodeName=pipeline_input");
        Object entries = spec.get(sequence ? "steps" : "nodes");
        if (!(entries instanceof List<?> items) || items.isEmpty() || items.size() > 100) throw invalid("Provide 1-100 chat stages");
        Map<String, Stage> stages = new LinkedHashMap<>();
        String previous = "pipeline_input";
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> node = object(items.get(i));
            String name;
            List<String> inputs;
            Map<String, Object> step;
            if (sequence) {
                name = "step" + i;
                inputs = List.of(previous);
                step = node;
            } else {
                fields(node, Set.of("@graphNodeType", "name", "inputs", "stepConfig"));
                if (!"STANDARD".equals(node.get("@graphNodeType"))) throw invalid("Only STANDARD chat graph nodes are supported");
                name = string(node.get("name"));
                if (!(node.get("inputs") instanceof List<?> parents) || parents.isEmpty()) throw invalid("Node inputs are required");
                inputs = parents.stream().map(ChatPipelineComposition::string).toList();
                if (new LinkedHashSet<>(inputs).size() != inputs.size()) throw invalid("Duplicate input dependency");
                step = object(node.get("stepConfig"));
            }
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*") || name.equals("pipeline_input")) throw invalid("Invalid stage name: " + name);
            fields(step, Set.of("@class", "runnerClassName", "parameters"));
            if (!STEP.equals(step.get("@class")) || !RUNNER.equals(step.get("runnerClassName"))) {
                throw invalid("All host stages must be GenericStepConfig with runnerClassName=CHAT_MODEL; mixed tensor/remote execution is unsupported");
            }
            Map<String, Object> parameters = object(step.get("parameters"));
            fields(parameters, Set.of("processor", "inputDataBindings"));
            Map<String, Object> processor = object(parameters.get("processor"));
            if (!RUNNER.equals(processor.get("type"))) throw invalid("Each stage requires processor.type=CHAT_MODEL");
            boolean translation = TranslationPipelineOptions.isTranslation(processor);
            Map<String, String> bindings = new LinkedHashMap<>();
            if (parameters.containsKey("inputDataBindings")) {
                object(parameters.get("inputDataBindings")).forEach((slot, reference) -> {
                    if (!Set.of("text", "filePath", "path").contains(slot)) throw invalid("Unsupported input slot: " + slot);
                    if (translation && !slot.equals("text")) throw invalid("Translation stages accept only materialized text");
                    String ref = string(reference);
                    int dot = ref.lastIndexOf('.');
                    if (dot < 1) throw invalid("Input binding must be node.text or pipeline_input.text/filePath/path");
                    String source = ref.substring(0, dot), key = ref.substring(dot + 1);
                    if (!inputs.contains(source)) throw invalid("Binding source must be listed in inputs: " + source);
                    if (!(source.equals("pipeline_input") ? slot.equals(key) : slot.equals("text") && key.equals("text"))) {
                        throw invalid("Only original input files may be used; generated text is never interpreted as a file path");
                    }
                    bindings.put(slot, ref);
                });
                if (bindings.size() != 1) throw invalid("Specify exactly one bound input slot");
            }
            UnifiedPipelineDefinition single = ObjectMappers.getJsonMapper().convertValue(definition, UnifiedPipelineDefinition.class);
            single.setPipelineId(definition.getPipelineId() + ":" + name);
            single.setPipelineSpec(null);
            single.setTopology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE);
            single.setProcessor(processor);
            single.setKind(translation ? UnifiedPipelineDefinition.PipelineKind.TRANSLATION : UnifiedPipelineDefinition.PipelineKind.LLM);
            Map<String, String> allBindings = definition.getModelBindings();
            String reference = allBindings == null ? null : allBindings.getOrDefault(name, allBindings.get("default"));
            single.setModelBindings(reference == null ? Map.of() : Map.of("generator", reference));
            // Original input requirements apply at the graph boundary, not to intermediate text.
            single.setInputs(null);
            var validation = PipelineDefinitionValidator.validate(single);
            if (!validation.valid()) throw invalid(name + ": " + String.join("; ", validation.errors()));
            if (stages.putIfAbsent(name, new Stage(name, inputs, Map.copyOf(bindings), single)) != null) throw invalid("Duplicate stage: " + name);
            previous = name;
        }
        if (definition.getModelBindings() != null) definition.getModelBindings().forEach((role, reference) -> {
            if (!("default".equals(role) || stages.containsKey(role)) || reference == null || reference.isBlank()) {
                throw invalid("Bind stage names (step0, step1 for sequences) or default to model ids");
            }
        });
        String output = sequence ? previous : string(spec.get("outputNodeName"));
        if (!stages.containsKey(output)) throw invalid("Unknown outputNodeName: " + output);
        Set<String> done = new LinkedHashSet<>(Set.of("pipeline_input"));
        List<Stage> ordered = new ArrayList<>();
        while (ordered.size() < stages.size()) {
            int before = ordered.size();
            for (Stage stage : stages.values()) if (!done.contains(stage.name()) && done.containsAll(stage.inputs())) {
                ordered.add(stage);
                done.add(stage.name());
            }
            if (before == ordered.size()) throw invalid("Cycle or unknown graph dependency");
        }
        Set<String> used = new LinkedHashSet<>();
        collect(output, stages, used);
        if (used.size() != stages.size()) throw invalid("All stages must contribute to outputNodeName");
        return new Plan(List.copyOf(ordered), output);
    }

    private static void collect(String name, Map<String, Stage> stages, Set<String> used) {
        if (name.equals("pipeline_input") || !used.add(name)) return;
        stages.get(name).inputs().forEach(parent -> collect(parent, stages, used));
    }

    /** Pass a single record through, or join fan-in text in declared inputs order. */
    public static Map<String, Object> input(Stage stage, Map<String, Map<String, Object>> results) {
        boolean translation = TranslationPipelineOptions.isTranslation(stage.definition().getProcessor());
        if (!stage.bindings().isEmpty()) {
            var binding = stage.bindings().entrySet().iterator().next();
            int dot = binding.getValue().lastIndexOf('.');
            Object value = results.get(binding.getValue().substring(0, dot)).get(binding.getValue().substring(dot + 1));
            if (!(value instanceof String text) || (!translation && text.isBlank())) throw invalid("Missing bound input for " + stage.name());
            return Map.of(binding.getKey(), value);
        }
        if (stage.inputs().size() == 1) {
            Map<String, Object> input = results.get(stage.inputs().get(0));
            if (translation && (input == null || !(input.get("text") instanceof String)
                    || input.containsKey("filePath") || input.containsKey("path"))) {
                throw invalid("Translation stages accept only materialized text");
            }
            return input;
        }
        List<String> texts = new ArrayList<>();
        long size = 0;
        for (String source : stage.inputs()) {
            Object value = results.get(source).get("text");
            if (!(value instanceof String text) || (!translation && text.isBlank())) throw invalid("Fan-in requires text or an explicit inputDataBindings selection");
            size += text.length() + 2L;
            if (size > 20_000_000) throw invalid("Fan-in exceeds the 20M character limit");
            texts.add(text);
        }
        return Map.of("text", String.join("\n\n", texts));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw invalid("Expected object");
        return (Map<String, Object>) map;
    }
    private static String string(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw invalid("Expected non-empty string");
        return s;
    }
    private static void fields(Map<String, Object> value, Set<String> allowed) {
        for (String field : value.keySet()) if (!allowed.contains(field)) throw invalid("Unsupported composition field: " + field);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("CHAT_MODEL: " + message); }
}
