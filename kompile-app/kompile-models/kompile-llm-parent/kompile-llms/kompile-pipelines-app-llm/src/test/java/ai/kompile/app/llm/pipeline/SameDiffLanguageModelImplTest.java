package ai.kompile.app.llm.pipeline;

import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Profiler;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.pipelines.steps.samediff.llm.SameDiffLanguageModelStepRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SameDiffLanguageModelImplTest {

    @Mock
    private SameDiffLanguageModelStepRunner mockRunner;

    private SameDiffLanguageModelImpl impl;

    @BeforeEach
    void setUp() {
        impl = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
    }

    // -- Verify the class implements LanguageModel --

    @Test
    void implementsLanguageModel() {
        assertInstanceOf(ai.kompile.core.llm.LanguageModel.class, impl);
    }

    // -- LanguageModel contract --

    @Test
    void generateResponseThrowsWhenNoModelLoaded() {
        assertThrows(IllegalStateException.class,
                () -> impl.generateResponse("hello", List.of()));
    }

    @Test
    void generateResponseDelegatesToRunner() throws Exception {
        injectLoadedModel("test-model", mockRunner, "prompt", "llm_response");

        Data outputData = Data.empty();
        outputData.put("llm_response", "hello world");
        when(mockRunner.exec(any(Data.class), any(Context.class))).thenReturn(outputData);

        String result = impl.generateResponse("test query", List.of("context doc"));
        assertEquals("hello world", result);
    }

    @Test
    void generateResponseWithContextComposesPrompt() throws Exception {
        injectLoadedModel("test-model", mockRunner, "prompt", "llm_response");

        Data outputData = Data.empty();
        outputData.put("llm_response", "answer");
        when(mockRunner.exec(any(Data.class), any(Context.class))).thenReturn(outputData);

        impl.generateResponse("question", List.of("doc1", "doc2"));

        verify(mockRunner).exec(argThat(input -> {
            String promptText = input.getString("prompt", "");
            return promptText.contains("doc1") && promptText.contains("doc2") && promptText.contains("question");
        }), any(Context.class));
    }

    // -- Lifecycle --

    @Test
    void isLoadedReturnsFalseByDefault() {
        assertFalse(impl.isLoaded());
        assertNull(impl.getLoadedModelId());
    }

    @Test
    void unloadWhenNothingLoadedIsNoOp() {
        assertDoesNotThrow(() -> impl.unloadModel());
    }

    /**
     * Reflectively inject a LoadedModel into the impl to simulate a loaded model
     * without needing actual SameDiff files.
     */
    private void injectLoadedModel(String modelId, SameDiffLanguageModelStepRunner runner,
                                    String promptInputName, String responseOutputName) throws Exception {
        LLMStepConfig config = LLMStepConfig.builder()
                .name("test")
                .type("SAMEDIFF_LANGUAGE_MODEL")
                .runnerClassName(SameDiffLanguageModelStepRunner.class.getName())
                .modelUri("file:///fake/model.sdnb")
                .tokenizerUri("file:///fake/tokenizer.json")
                .tokenizerType("huggingface")
                .promptInputName(promptInputName)
                .responseOutputName(responseOutputName)
                .build();

        // Access the private LoadedModel inner class via reflection
        Class<?> loadedModelClass = null;
        for (Class<?> inner : SameDiffLanguageModelImpl.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("LoadedModel")) {
                loadedModelClass = inner;
                break;
            }
        }
        assertNotNull(loadedModelClass, "LoadedModel inner class not found");

        var ctor = loadedModelClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object loadedModel = ctor.newInstance(modelId, runner, config, 100L);

        Field loadedField = SameDiffLanguageModelImpl.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        loadedField.set(impl, loadedModel);

        assertTrue(impl.isLoaded());
        assertEquals(modelId, impl.getLoadedModelId());
    }
}
