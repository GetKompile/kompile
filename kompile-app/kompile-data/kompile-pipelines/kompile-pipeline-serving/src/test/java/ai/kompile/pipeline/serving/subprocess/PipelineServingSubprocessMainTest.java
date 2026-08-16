package ai.kompile.pipeline.serving.subprocess;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineServingSubprocessMainTest {

    @Test
    void definitionModelContextIsAuthoritativeForEveryRequest() {
        UnifiedPipelineDefinition definition = UnifiedPipelineDefinition.builder()
                .pipelineId("bound-pipeline")
                .modelSetId("generator-model")
                .modelBindings(Map.of("generator", "generator-config"))
                .resolvedModels(Map.of("generator", Map.of(
                        "modelId", "generator-model",
                        "modelPath", "/models/generator.gguf")))
                .build();

        Map<String, Object> input = PipelineServingSubprocessMain.withDefinitionContext(
                Map.of(
                        "text", "hello",
                        "modelBindings", Map.of("generator", "untrusted-override")),
                definition);

        assertEquals("hello", input.get("text"));
        assertEquals("generator-model", input.get("modelSetId"));
        assertEquals(Map.of("generator", "generator-config"), input.get("modelBindings"));
        assertEquals("/models/generator.gguf",
                ((Map<?, ?>) ((Map<?, ?>) input.get("resolvedModels")).get("generator"))
                        .get("modelPath"));
    }
}
