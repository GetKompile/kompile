package ai.kompile.app.llm.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SameDiffLanguageModelImplTest {

    @Mock
    private SameDiffLanguageModelImpl.InferenceBackend mockBackend;

    @Mock
    private SameDiffLanguageModelImpl.StructuredChatInferenceBackend mockStructuredBackend;

    private SameDiffLanguageModelImpl impl;

    @TempDir
    Path tempDir;

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
    void toolCapableGenerationUsesTheActualTextGenerationPath() throws Exception {
        injectLoadedModel("test-model", mockBackend);
        String prompt = "Use the exact tool envelope for this request.";
        String modelOutput = "{\"tool\":\"search_graph\",\"args\":{}}";
        when(mockBackend.generate(prompt)).thenReturn(modelOutput);

        ChatResponse response = impl.generateResponseWithPotentialToolCalls(prompt, List.of());

        assertNotNull(response);
        assertEquals(modelOutput, response.getResult().getOutput().getText());
        verify(mockBackend).generate(prompt);
    }

    @Test
    void structuredChatDelegatesRequestAndBudgetToNativeBackend() throws Exception {
        injectLoadedModel("test-model", mockStructuredBackend);
        ChatTemplate.Request request = ChatTemplate.Request.builder()
                .messages(List.of(
                        ChatTemplate.Message.system("You are a graph extraction tool."),
                        ChatTemplate.Message.user("Find the approval role.")))
                .tools(List.of(ChatTemplate.Tool.function(
                        "search_graph", "Search the graph.",
                        Map.of("type", "object"))))
                .build();

        assertNull(impl.generateChat(request, 192));
        verify(mockStructuredBackend).generateChat(request, 192);
    }

    @Test
    void portableStructuredCapabilityPreservesNativeFormatsAndParsedCalls() throws Exception {
        injectLoadedModel("test-model", mockStructuredBackend);
        when(mockStructuredBackend.generateChat(any(ChatTemplate.Request.class), eq(192)))
                .thenReturn(new org.eclipse.deeplearning4j.llm.generation.ChatGenerationResult(
                        "<native>",
                        "",
                        List.of(ChatTemplate.ToolCall.function(
                                "call-1", "submit_graph_delta",
                                Map.of("entities", List.of(), "relations", List.of()))),
                        List.of()));

        ai.kompile.core.llm.StructuredChatLanguageModel.Request request =
                new ai.kompile.core.llm.StructuredChatLanguageModel.Request(
                        List.of(
                                new ai.kompile.core.llm.StructuredChatLanguageModel.Message(
                                        "system", "extract"),
                                new ai.kompile.core.llm.StructuredChatLanguageModel.Message(
                                        "user", "source")),
                        List.of(new ai.kompile.core.llm.StructuredChatLanguageModel.Tool(
                                "submit_graph_delta", "submit", Map.of("type", "object"))),
                        true,
                        ai.kompile.core.llm.StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                        ai.kompile.core.llm.StructuredChatLanguageModel.ToolCallFormat.NATIVE);

        ai.kompile.core.llm.StructuredChatLanguageModel.Response response =
                impl.generateChat(request, 192);

        assertEquals("<native>", response.rawText());
        assertEquals("submit_graph_delta", response.toolCalls().get(0).name());
        var captor = org.mockito.ArgumentCaptor.forClass(ChatTemplate.Request.class);
        verify(mockStructuredBackend).generateChat(captor.capture(), eq(192));
        assertEquals(ChatTemplate.ToolDefinitionFormat.FLAT,
                captor.getValue().getToolDefinitionFormat());
        assertEquals(ChatTemplate.ToolCallFormat.NATIVE,
                captor.getValue().getToolCallFormat());
        assertEquals(List.of("system", "user"),
                captor.getValue().getMessages().stream()
                        .map(ChatTemplate.Message::getRole).toList());
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
    void lastPositionPrefillLogitsAreMemorySafeByDefaultAndConfigurable() {
        assertTrue(SameDiffLanguageModelImpl.prefillLastPositionLogitsEnabled(Map.of()));
        assertFalse(SameDiffLanguageModelImpl.prefillLastPositionLogitsEnabled(
                Map.of("prefillLastPositionLogitsEnabled", false)));
        assertFalse(SameDiffLanguageModelImpl.prefillLastPositionLogitsEnabled(
                Map.of("prefillLastPositionLogitsEnabled", "false")));
    }

    @Test
    void recognizesTokenizerTypesThatUseGenerationPipeline() {
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline("huggingface"));
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline("HF"));
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline("bpe"));
        assertFalse(SameDiffLanguageModelImpl.usesGenerationPipeline("wordpiece"));
        assertFalse(SameDiffLanguageModelImpl.usesGenerationPipeline(
                "huggingface", Map.of("legacyGeneration", true)));
        assertTrue(SameDiffLanguageModelImpl.usesGenerationPipeline(
                "huggingface", Map.of("legacyGeneration", false)));
    }

    @Test
    void implicitContinuationRequiresTheExecutedDecoderToBeGguf() throws Exception {
        Path stagedModel = Files.createFile(tempDir.resolve("model.sdnb"));
        Files.createFile(tempDir.resolve("source-model.gguf"));

        assertFalse(SameDiffLanguageModelImpl.isDirectGgufDecoder(stagedModel),
                "a sibling GGUF is provenance, not proof that the executed SDNB supports sessions");
        assertTrue(SameDiffLanguageModelImpl.isDirectGgufDecoder(
                tempDir.resolve("direct.GGUF")));
        assertFalse(SameDiffLanguageModelImpl.isDirectGgufDecoder(null));
    }

    @Test
    void continuationChunkMustBePositive() {
        assertEquals(384,
                SameDiffLanguageModelImpl.validateContinuationChunkTokens(384));
        assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.validateContinuationChunkTokens(0));
    }

    @Test
    void resolvesAdditionalStopTokenIdsWithoutDuplicates() {
        assertEquals(
                Set.of(7, 8),
                SameDiffLanguageModelImpl.additionalStopTokenIdsOpt(
                        Map.of("additionalStopTokenIds", List.of(7, 8, 7))));
        assertEquals(
                Set.of(),
                SameDiffLanguageModelImpl.additionalStopTokenIdsOpt(Map.of()));
    }

    @Test
    void mapsImportedGgufProtocolMetadataWithoutGuessingTokens() {
        org.nd4j.ggml.format.GGMLMetadata.TokenizerInfo tokenizerInfo =
                org.nd4j.ggml.format.GGMLMetadata.TokenizerInfo.builder()
                        .bosTokenId(1)
                        .eosTokenId(7)
                        .padTokenId(0)
                        .chatTemplate("{{ messages }}")
                        .build();

        GenerationPipeline.ModelMetadata metadata =
                SameDiffLanguageModelImpl.generationMetadata(tokenizerInfo);

        assertEquals(1, metadata.getBosTokenId());
        assertEquals(7, metadata.getEosTokenId());
        assertEquals(0, metadata.getPadTokenId());
        assertEquals("{{ messages }}", metadata.getChatTemplate());
        assertEquals(Set.of(7), metadata.getStopTokenIds());
        assertEquals(Set.of(), metadata.getSpecialTokenIds());
    }

    @Test
    void rejectsMalformedAdditionalStopTokenIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.additionalStopTokenIdsOpt(
                        Map.of("additionalStopTokenIds", "7,8")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.additionalStopTokenIdsOpt(
                        Map.of("additionalStopTokenIds", List.of(7, "8"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.additionalStopTokenIdsOpt(
                        Map.of("additionalStopTokenIds", List.of(-1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.additionalStopTokenIdsOpt(
                        Map.of("additionalStopTokenIds", List.of(7.5d))));
    }

    @Test
    void doesNotInventChatTemplateOrEndTokenFromTokenizerMarkers() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getChatTemplate()).thenReturn(null);
        when(tokenizer.getEosTokenId()).thenReturn(-1);

        String template = SameDiffLanguageModelImpl.resolveChatTemplate(tokenizer, null, null);

        assertNull(template);
        assertEquals(
                -1,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, Map.of()));
        verify(tokenizer, never()).getTokenId(anyString());
    }

    @Test
    void explicitTemplateAndTokenOptionsTakePrecedence() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        String configuredTemplate = "[INST] {{ message.content }} [/INST]";

        assertSame(
                configuredTemplate,
                SameDiffLanguageModelImpl.resolveChatTemplate(
                        tokenizer, configuredTemplate, null));
        assertEquals(
                42,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, Map.of("eosTokenId", 42)));
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
                        tokenizer, Map.of()));
        verify(tokenizer, never()).getTokenId("<|im_end|>");
    }

    @Test
    void leavesPlainTokenizerUnwrappedAndUsesItsNativeEndToken() {
        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getChatTemplate()).thenReturn(null);
        when(tokenizer.getEosTokenId()).thenReturn(2);

        String template = SameDiffLanguageModelImpl.resolveChatTemplate(tokenizer, null, null);

        assertNull(template);
        assertEquals(
                2,
                SameDiffLanguageModelImpl.resolveEosTokenId(
                        tokenizer, Map.of()));
    }

    @Test
    void prefersTheSourceGgufsTemplateOverTheGenericChatMlStandIn() throws Exception {
        // A model staged before staging carried tokenizer_config.json forward has its real template
        // in one place only: the GGUF it was converted from. Falling through to the built-in ChatML
        // template would frame every prompt in a template that merely resembles the model's own.
        String declared = "{% for m in messages %}<|im_start|>{{ m['role'] }}\n"
                + "{{ m['content'] }}<|im_end|>\n{% endfor %}";
        Files.write(tempDir.resolve("source-model.gguf"), GgufFixture.headerWithChatTemplate(declared));
        Path stagedModel = Files.createFile(tempDir.resolve("model.sdnb"));

        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getChatTemplate()).thenReturn(null);

        assertEquals(declared,
                SameDiffLanguageModelImpl.resolveChatTemplate(tokenizer, null, stagedModel));
        // The GGUF answered, so the ChatML-marker probe is never reached.
        verify(tokenizer, never()).getTokenId("<|im_start|>");
    }

    @Test
    void sourceGgufWithoutTemplateDoesNotTriggerAProtocolFallback() throws Exception {
        Files.write(tempDir.resolve("source-model.gguf"), GgufFixture.headerWithChatTemplate(null));
        Path stagedModel = Files.createFile(tempDir.resolve("model.sdnb"));

        Tokenizer tokenizer = mock(Tokenizer.class);
        when(tokenizer.getChatTemplate()).thenReturn(null);

        assertNull(SameDiffLanguageModelImpl.resolveChatTemplate(
                tokenizer, null, stagedModel));
        verify(tokenizer, never()).getTokenId(anyString());
    }

    @Test
    void anUnreadableGgufDoesNotBreakTemplateResolution() throws Exception {
        Files.write(tempDir.resolve("truncated.gguf"), new byte[]{1, 2, 3});
        Path stagedModel = Files.createFile(tempDir.resolve("model.sdnb"));

        assertNull(SameDiffLanguageModelImpl.chatTemplateFromGguf(stagedModel));
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
