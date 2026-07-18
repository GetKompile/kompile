package ai.kompile.app.llm.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SameDiffLanguageModelImplTest {

    @Mock
    private SameDiffLanguageModelImpl.InferenceBackend mockBackend;

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
    void generateResponseDelegatesToLifecycleManagedBackend() throws Exception {
        injectLoadedModel("test-model", mockBackend);
        when(mockBackend.generate("Context:\n[1] context doc\n\nUser: test query"))
                .thenReturn("hello world");

        String result = impl.generateResponse("test query", List.of("context doc"));
        assertEquals("hello world", result);
        verify(mockBackend).generate("Context:\n[1] context doc\n\nUser: test query");
    }

    @Test
    void requestScopedTokenBudgetDelegatesWithoutReplacingLoadedModel() throws Exception {
        injectLoadedModel("test-model", mockBackend);
        String prompt = "Context:\n[1] context doc\n\nUser: test query";
        when(mockBackend.generate(prompt, 1536)).thenReturn("bounded answer");

        String result = impl.generateResponse("test query", List.of("context doc"), 1536);

        assertEquals("bounded answer", result);
        assertEquals("test-model", impl.getLoadedModelId());
        verify(mockBackend).generate(prompt, 1536);
        verify(mockBackend, never()).generate(prompt);
    }

    @Test
    void requestScopedTokenBudgetMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> impl.generateResponse("test query", List.of(), 0));
    }

    @Test
    void generateResponseWithContextComposesPrompt() throws Exception {
        injectLoadedModel("test-model", mockBackend);
        when(mockBackend.generate(anyString())).thenReturn("answer");

        impl.generateResponse("question", List.of("doc1", "doc2"));

        verify(mockBackend).generate(argThat(prompt ->
                prompt.contains("doc1")
                        && prompt.contains("doc2")
                        && prompt.contains("question")));
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

    @Test
    void unloadClosesTheActiveBackendExactlyOnce() throws Exception {
        injectLoadedModel("test-model", mockBackend);

        impl.unloadModel();
        impl.unloadModel();

        verify(mockBackend, times(1)).close();
        assertFalse(impl.isLoaded());
        assertNull(impl.getLoadedModelId());
    }

    @Test
    void recognizesTokenizerTypesThatUseGenerationPipeline() {
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline("huggingface"));
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline("HF"));
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline("bpe"));
        assertFalse(SameDiffLanguageModelImpl.usesGenerationPipeline("wordpiece"));
    }

    @Test
    void infersChatMlTemplateAndEndTokenFromTokenizerMarkers() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getChatTemplate()).thenReturn(null);
        when(tokenizer.getTokenId("<|im_start|>")).thenReturn(6);
        when(tokenizer.getTokenId("<|im_end|>")).thenReturn(7);

        String template = SameDiffLanguageModelImpl.resolveChatTemplate(tokenizer, null);

        assertSame(SameDiffLanguageModelImpl.CHATML_TEMPLATE, template);
        assertEquals(
                7,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, template, Map.of()));
    }

    @Test
    void explicitTemplateAndTokenOptionsTakePrecedence() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        String configuredTemplate = "[INST] {{ message.content }} [/INST]";

        assertSame(
                configuredTemplate,
                SameDiffLanguageModelImpl.resolveChatTemplate(
                        tokenizer, configuredTemplate));
        assertEquals(
                42,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, configuredTemplate, Map.of("eosTokenId", 42)));
        verifyNoInteractions(tokenizer);
    }

    @Test
    void customTemplateWithChatMlStartMarkerUsesNativeEndToken() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getEosTokenId()).thenReturn(2);
        String configuredTemplate = "<|im_start|>custom {{ message.content }}<|custom_end|>";

        assertEquals(
                2,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, configuredTemplate, Map.of()));
        verify(tokenizer, never()).getTokenId("<|im_end|>");
    }

    @Test
    void leavesPlainTokenizerUnwrappedAndUsesItsNativeEndToken() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getChatTemplate()).thenReturn(null);
        when(tokenizer.getTokenId("<|im_start|>")).thenReturn(null);
        when(tokenizer.getTokenId("<|im_end|>")).thenReturn(null);
        when(tokenizer.getEosTokenId()).thenReturn(2);

        String template = SameDiffLanguageModelImpl.resolveChatTemplate(tokenizer, null);

        assertNull(template);
        assertEquals(
                2,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, template, Map.of()));
    }

    @Test
    void resolvesAndStripsOnlyTheConfiguredTrailingEndToken() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.decode(any(int[].class), eq(false))).thenReturn("<|im_end|>");

        String eosTokenText = SameDiffLanguageModelImpl.resolveEosTokenText(tokenizer, 7);

        assertEquals("<|im_end|>", eosTokenText);
        assertEquals(
                "{\"entities\":[]}",
                SameDiffLanguageModelImpl.stripTrailingEosToken(
                        "{\"entities\":[]}<|im_end|>", eosTokenText));
        assertEquals(
                "prefix<|im_end|>suffix",
                SameDiffLanguageModelImpl.stripTrailingEosToken(
                        "prefix<|im_end|>suffix", eosTokenText));
    }

    @Test
    void strippingTrailingEndTokenPreservesTrailingWhitespaceAndNulls() {
        assertEquals(
                "OK.\n",
                SameDiffLanguageModelImpl.stripTrailingEosToken(
                        "OK.<|im_end|>\n", "<|im_end|>"));
        assertNull(SameDiffLanguageModelImpl.stripTrailingEosToken(null, "<|im_end|>"));
        assertEquals(
                "unchanged",
                SameDiffLanguageModelImpl.stripTrailingEosToken("unchanged", null));
    }

    /**
     * Reflectively inject a LoadedModel into the impl to simulate a loaded model
     * without needing actual SameDiff files.
     */
    private void injectLoadedModel(
            String modelId,
            SameDiffLanguageModelImpl.InferenceBackend backend) throws Exception {
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
        Object loadedModel = ctor.newInstance(modelId, backend, 100L);

        Field loadedField = SameDiffLanguageModelImpl.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        loadedField.set(impl, loadedModel);

        assertTrue(impl.isLoaded());
        assertEquals(modelId, impl.getLoadedModelId());
    }
}
