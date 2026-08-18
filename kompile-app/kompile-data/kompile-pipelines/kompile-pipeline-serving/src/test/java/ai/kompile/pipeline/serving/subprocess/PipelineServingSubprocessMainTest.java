package ai.kompile.pipeline.serving.subprocess;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;
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
                        "pages", List.of(Map.of("text", "page one")),
                        "modelBindings", Map.of("generator", "untrusted-override")),
                definition);

        assertEquals("hello", input.get("text"));
        assertEquals("generator-model", input.get("modelSetId"));
        assertEquals(Map.of("generator", "generator-config"), input.get("modelBindings"));
        assertEquals("/models/generator.gguf",
                ((Map<?, ?>) ((Map<?, ?>) input.get("resolvedModels")).get("generator"))
                        .get("modelPath"));

        Data data = Data.fromMap(input);
        assertEquals("generator-config",
                data.getData("modelBindings").getString("generator"));
        assertEquals("/models/generator.gguf",
                data.getData("resolvedModels").getData("generator").getString("modelPath"));
        assertEquals("page one",
                data.<Data>getList("pages", ValueType.DATA).get(0).getString("text"));
    }
}
