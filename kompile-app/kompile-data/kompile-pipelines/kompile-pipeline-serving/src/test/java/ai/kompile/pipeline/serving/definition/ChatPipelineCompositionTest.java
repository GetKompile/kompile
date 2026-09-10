package ai.kompile.pipeline.serving.definition;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatPipelineCompositionTest {
    @Test
    void derivesPerStageWorkloadsWithoutChangingTheLlmEnvelope() {
        UnifiedPipelineDefinition definition = sequence(List.of(step(text(), Map.of()), step(translation(), Map.of())));
        var plan = ChatPipelineComposition.plan(definition);
        assertEquals(List.of("step0", "step1"), plan.stages().stream().map(ChatPipelineComposition.Stage::name).toList());
        assertEquals("step1", plan.output());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.LLM, plan.stages().get(0).definition().getKind());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.TRANSLATION, plan.stages().get(1).definition().getKind());
        assertEquals(List.of("step0"), plan.stages().get(1).inputs());
        assertNull(plan.stages().get(1).definition().getPipelineSpec());
        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE, plan.stages().get(1).definition().getTopology());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.LLM, definition.getKind());
        assertNull(definition.getProcessor());
        assertNotNull(definition.getPipelineSpec());
        assertTrue(PipelineDefinitionValidator.validate(definition).valid());
    }

    @Test
    void preservesStageSpecificModelBindingsOverTheDefault() {
        UnifiedPipelineDefinition definition = sequence(List.of(step(translation(), Map.of()), step(text(), Map.of())));
        definition.setModelBindings(Map.of("default", "fallback", "step0", "translator"));
        definition.setModelDefinitions(Map.of(
                "fallback", Map.of("source", "chat", "provider", "provider-a", "modelId", "manual-default"),
                "translator", Map.of("source", "chat", "provider", "provider-b", "modelId", "manual-translator")));
        var plan = ChatPipelineComposition.plan(definition);
        assertEquals(Map.of("generator", "translator"), plan.stages().get(0).definition().getModelBindings());
        assertEquals(Map.of("generator", "fallback"), plan.stages().get(1).definition().getModelBindings());
        assertEquals(definition.getModelDefinitions(), plan.stages().get(0).definition().getModelDefinitions());
        assertEquals(Map.of("default", "fallback", "step0", "translator"), definition.getModelBindings());
    }

    @Test
    void ordersStandardDagStagesAndKeepsEveryTranslationStage() {
        UnifiedPipelineDefinition definition = graph(List.of(
                node("french", List.of("english"), translation()),
                node("english", List.of("pipeline_input"), Map.of("type", "CHAT_MODEL", "operation", "translation",
                        "translation", Map.of("targetLanguage", "en")))), "french");
        var plan = ChatPipelineComposition.plan(definition);
        assertEquals(List.of("english", "french"), plan.stages().stream().map(ChatPipelineComposition.Stage::name).toList());
        assertTrue(plan.stages().stream().allMatch(stage -> stage.definition().getKind() == UnifiedPipelineDefinition.PipelineKind.TRANSLATION));
        assertEquals(Map.of("text", "Hello"), ChatPipelineComposition.input(plan.stages().get(1), Map.of("english", Map.of("text", "Hello"))));
    }

    @Test
    void explicitTranslationTextBindingNeverInterpretsGeneratedTextAsAFile() {
        var definition = sequence(List.of(step(translation(), Map.of("text", "pipeline_input.text"))));
        var stage = ChatPipelineComposition.plan(definition).stages().get(0);
        assertEquals(Map.of("text", "/tmp/document.pdf"), ChatPipelineComposition.input(stage,
                Map.of("pipeline_input", Map.of("text", "/tmp/document.pdf"))));
        assertEquals(Map.of("text", ""), ChatPipelineComposition.input(stage, Map.of("pipeline_input", Map.of("text", ""))));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.input(stage,
                Map.of("pipeline_input", Map.of("text", 12))));
    }

    @Test
    void rejectsTranslationFileBindingsAtAuthoringTime() {
        for (String field : List.of("filePath", "path")) {
            var definition = sequence(List.of(step(translation(), Map.of(field, "pipeline_input." + field))));
            assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(definition));
            assertFalse(PipelineDefinitionValidator.validate(definition).valid());
        }
        var wrongSourceKey = sequence(List.of(step(translation(), Map.of("text", "pipeline_input.path"))));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(wrongSourceKey));
    }

    @Test
    void implicitTranslationInputRequiresMaterializedTextButAllowsBlankText() {
        var stage = ChatPipelineComposition.plan(sequence(List.of(step(translation(), Map.of())))).stages().get(0);
        assertEquals(Map.of("text", " \n"), ChatPipelineComposition.input(stage, Map.of("pipeline_input", Map.of("text", " \n"))));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.input(stage, Map.of()));
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("filePath", "source.pdf"), Map.of("path", "source.pdf"), Map.of("text", 1),
                Map.of("text", "already text", "filePath", "source.pdf"))) {
            assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.input(stage, Map.of("pipeline_input", input)));
        }
    }

    @Test
    void translationFanInUsesDeclaredOrderAndRetainsWhitespace() {
        var definition = graph(List.of(node("a", List.of("pipeline_input"), text()),
                node("b", List.of("pipeline_input"), text()), node("translate", List.of("b", "a"), translation())), "translate");
        var stage = ChatPipelineComposition.plan(definition).stages().get(2);
        assertEquals(Map.of("text", " \r\n\n\nFirst"), ChatPipelineComposition.input(stage,
                Map.of("a", Map.of("text", "First"), "b", Map.of("text", " \r\n"))));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.input(stage,
                Map.of("a", Map.of("text", "First"), "b", Map.of("filePath", "source.pdf"))));
    }

    @Test
    void ordinaryChatStillRejectsBlankBoundInputAndFanIn() {
        var bound = ChatPipelineComposition.plan(sequence(List.of(step(text(), Map.of("text", "pipeline_input.text"))))).stages().get(0);
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.input(bound, Map.of("pipeline_input", Map.of("text", ""))));
        var graph = graph(List.of(node("a", List.of("pipeline_input"), text()), node("b", List.of("pipeline_input"), text()),
                node("join", List.of("a", "b"), text())), "join");
        var join = ChatPipelineComposition.plan(graph).stages().get(2);
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.input(join,
                Map.of("a", Map.of("text", "text"), "b", Map.of("text", ""))));
    }

    @Test
    void rejectsMixedTensorExecutionAndLocalModelDefinitions() {
        var mixed = sequence(List.of(step(translation(), Map.of()), Map.of(
                "@class", ChatPipelineComposition.STEP, "runnerClassName", "local-tensor-runner", "parameters", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(mixed));
        var local = sequence(List.of(step(translation(), Map.of())));
        local.setModelBindings(Map.of("step0", "local"));
        local.setModelDefinitions(Map.of("local", Map.of("source", "local", "localPath", "model.gguf")));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(local));
    }

    @Test
    void rejectsInvalidEnvelopesAndInvalidTranslationOptionsBeforeExecution() {
        var wrongKind = sequence(List.of(step(translation(), Map.of())));
        wrongKind.setKind(UnifiedPipelineDefinition.PipelineKind.TRANSLATION);
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(wrongKind));
        var missingTopology = sequence(List.of(step(translation(), Map.of())));
        missingTopology.setTopology(null);
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(missingTopology));
        var missingTarget = sequence(List.of(step(Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of()), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> ChatPipelineComposition.plan(missingTarget));
    }

    private static Map<String, Object> translation() {
        return Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "fr"));
    }

    private static Map<String, Object> text() {
        return Map.of("type", "CHAT_MODEL", "operation", "text");
    }

    private static Map<String, Object> step(Map<String, Object> processor, Map<String, String> bindings) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("processor", processor);
        if (!bindings.isEmpty()) parameters.put("inputDataBindings", bindings);
        return Map.of("@class", ChatPipelineComposition.STEP, "runnerClassName", ChatPipelineComposition.RUNNER, "parameters", parameters);
    }

    private static Map<String, Object> node(String name, List<String> inputs, Map<String, Object> processor) {
        return Map.of("@graphNodeType", "STANDARD", "name", name, "inputs", inputs, "stepConfig", step(processor, Map.of()));
    }

    private static UnifiedPipelineDefinition sequence(List<Map<String, Object>> steps) {
        return UnifiedPipelineDefinition.builder().pipelineId("composed")
                .kind(UnifiedPipelineDefinition.PipelineKind.LLM).topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of("@class", ChatPipelineComposition.SEQUENCE, "steps", steps)).build();
    }

    private static UnifiedPipelineDefinition graph(List<Map<String, Object>> nodes, String output) {
        return UnifiedPipelineDefinition.builder().pipelineId("composed")
                .kind(UnifiedPipelineDefinition.PipelineKind.LLM).topology(UnifiedPipelineDefinition.ExecutionTopology.GRAPH)
                .pipelineSpec(Map.of("@class", ChatPipelineComposition.GRAPH, "nodes", nodes, "outputNodeName", output)).build();
    }
}
