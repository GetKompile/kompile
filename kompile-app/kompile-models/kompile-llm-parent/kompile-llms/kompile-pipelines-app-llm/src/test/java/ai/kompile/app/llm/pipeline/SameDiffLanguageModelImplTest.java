package ai.kompile.app.llm.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.ModelSamplingDefaults.GenerationMode;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    @AfterEach
    void tearDown() {
        impl.shutdown();
    }

    // -- Verify the class implements LanguageModel --

    @Test
    void implementsLanguageModel() {
        assertInstanceOf(ai.kompile.core.llm.LanguageModel.class, impl);
    }

    @Test
    void modelAdmissionIncludesTheOptimizerClonePeak() {
        assertEquals(15_054_600_704L,
                SameDiffLanguageModelImpl.modelLoadPeakBytes(7_527_300_352L, true));
        assertEquals(7_527_300_352L,
                SameDiffLanguageModelImpl.modelLoadPeakBytes(7_527_300_352L, false));
        assertThrows(ArithmeticException.class,
                () -> SameDiffLanguageModelImpl.modelLoadPeakBytes(Long.MAX_VALUE, true));
    }

    @Test
    void prefixCacheOptionsAreBoundedAndRequireStaticKv() {
        SameDiffLanguageModelImpl.PrefixCacheOptions options =
                SameDiffLanguageModelImpl.prefixCacheOptions(
                        Map.of("prefixCacheEnabled", true,
                                "prefixCacheMaxBytes", 268_435_456L,
                                "prefixCacheBlockSize", 32),
                        KvCacheStrategy.STATIC);
        assertTrue(options.enabled());
        assertEquals(268_435_456L, options.maxBytes());
        assertEquals(32, options.blockSize());

        assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.prefixCacheOptions(
                        Map.of("prefixCacheEnabled", true), KvCacheStrategy.PAGED));
        assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.prefixCacheOptions(
                        Map.of("prefixCacheMaxBytes", -1), KvCacheStrategy.STATIC));
        assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.prefixCacheOptions(
                        Map.of("prefixCacheBlockSize", -1), KvCacheStrategy.STATIC));
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
                        "<think>inspect source</think><native>",
                        "",
                        "inspect source",
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
                        ai.kompile.core.llm.StructuredChatLanguageModel.ToolCallFormat.NATIVE,
                        ai.kompile.core.llm.StructuredChatLanguageModel.ToolChoice.REQUIRED,
                        Map.of("enable_thinking", true));

        ai.kompile.core.llm.StructuredChatLanguageModel.Response response =
                impl.generateChat(request, 192);

        assertEquals("<think>inspect source</think><native>", response.rawText());
        assertEquals("", response.content());
        assertEquals("inspect source", response.reasoningContent());
        assertEquals(List.of("think"), response.outputBlocks().stream()
                .map(ai.kompile.core.llm.StructuredChatLanguageModel.OutputBlock::type)
                .toList());
        assertEquals("inspect source", response.outputBlocks().get(0).content());
        assertEquals("submit_graph_delta", response.toolCalls().get(0).name());
        var captor = org.mockito.ArgumentCaptor.forClass(ChatTemplate.Request.class);
        verify(mockStructuredBackend).generateChat(captor.capture(), eq(192));
        assertEquals(ChatTemplate.ToolDefinitionFormat.FLAT,
                captor.getValue().getToolDefinitionFormat());
        assertEquals(ChatTemplate.ToolCallFormat.NATIVE,
                captor.getValue().getToolCallFormat());
        assertEquals(ChatTemplate.ToolChoice.REQUIRED,
                captor.getValue().getToolChoice());
        assertEquals(Map.of("enable_thinking", true),
                captor.getValue().getTemplateArguments());
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
    void pooledBackendOperationsUseOneModelOwnedExecutionLane() throws Exception {
        injectLoadedModel("test-model", mockBackend);
        Set<Thread> backendThreads = ConcurrentHashMap.newKeySet();
        when(mockBackend.generate(anyString())).thenAnswer(invocation -> {
            backendThreads.add(Thread.currentThread());
            return "answer";
        });
        when(mockBackend.countPromptTokens(anyString())).thenAnswer(invocation -> {
            backendThreads.add(Thread.currentThread());
            return 3;
        });
        doAnswer(invocation -> {
            backendThreads.add(Thread.currentThread());
            return null;
        }).when(mockBackend).close();

        CompletableFuture<String> generation = CompletableFuture.supplyAsync(
                () -> impl.generateResponse("question", List.of()));
        CompletableFuture<Integer> tokenCount = CompletableFuture.supplyAsync(
                () -> impl.countPromptTokens("question"));

        assertEquals("answer", generation.get());
        assertEquals(3, tokenCount.get());
        impl.unloadModel();

        assertEquals(1, backendThreads.size(),
                "generate, token counting, and close must share one execution lane");
        assertEquals("samediff-model-execution",
                backendThreads.iterator().next().getName());
    }

    @Test
    void pooledBackendRestoresItsHomeDeviceAtEveryOperationBoundary() throws Exception {
        FakeModelDeviceContext deviceContext = new FakeModelDeviceContext(0);
        SameDiffLanguageModelImpl deviceBoundImpl = new SameDiffLanguageModelImpl(
                Optional.empty(), Optional.empty(), deviceContext);
        SameDiffLanguageModelImpl.InferenceBackend backend = mock(
                SameDiffLanguageModelImpl.InferenceBackend.class);
        try {
            injectLoadedModel(deviceBoundImpl, "test-model", backend);
            bindModelExecutionDevice(deviceBoundImpl, 1);
            when(backend.generate(anyString())).thenAnswer(invocation -> {
                assertEquals(1, deviceContext.currentDevice(),
                        "generation must begin on the model home device");
                // Simulate an internal physically sharded plan finishing on another GPU.
                deviceContext.setCurrentDevice(0);
                return "answer";
            });
            when(backend.countPromptTokens(anyString())).thenAnswer(invocation -> {
                assertEquals(1, deviceContext.currentDevice(),
                        "the next operation must begin back on the model home device");
                return 3;
            });
            doAnswer(invocation -> {
                assertEquals(1, deviceContext.currentDevice(),
                        "model cleanup must run on the model home device");
                return null;
            }).when(backend).close();

            assertEquals("answer", deviceBoundImpl.generateResponse("question", List.of()));
            assertEquals(1, deviceContext.currentDevice(),
                    "the lane must restore its home device after internal sharding");
            assertEquals(3, deviceBoundImpl.countPromptTokens("question"));
            deviceBoundImpl.unloadModel();

            assertNull(deviceBoundImpl.getModelExecutionDevice());
            assertEquals(2, deviceBoundImpl.getModelDeviceRestorations(),
                    "both stale entry state and internal post-generation drift must be corrected");
            assertEquals(2, deviceContext.switchCount());
        } finally {
            deviceBoundImpl.shutdown();
        }
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
    void configuredSamplingPropagatesStructuredOutputBudgets() {
        SamplingConfig configured = SameDiffLanguageModelImpl.configuredSampling(
                Map.of(
                        "maxOutputBlockTokens", 48,
                        "structuredOutputTokenReserve", 96),
                256,
                SamplingConfig.greedy());

        assertEquals(48, configured.getMaxOutputBlockTokens());
        assertEquals(96, configured.getStructuredOutputTokenReserve());
    }

    @Test
    void configuredSamplingPreservesModelDefaultStructuredOutputBudgets() {
        SamplingConfig defaults = SamplingConfig.greedy().toBuilder()
                .maxOutputBlockTokens(32)
                .structuredOutputTokenReserve(64)
                .build();

        SamplingConfig configured = SameDiffLanguageModelImpl.configuredSampling(
                Map.of(), 256, defaults);

        assertEquals(32, configured.getMaxOutputBlockTokens());
        assertEquals(64, configured.getStructuredOutputTokenReserve());
    }

    @Test
    void resolvesQwenSamplingByThinkingMode() {
        SamplingConfig nonThinking = SameDiffLanguageModelImpl.modelSamplingDefaults(
                "qwen3.5-2b-instruct", "qwen3_5", "Qwen3.5-2B",
                GenerationMode.NON_THINKING_TEXT);
        SamplingConfig thinking = SameDiffLanguageModelImpl.modelSamplingDefaults(
                "qwen3.5-2b-instruct", "qwen3_5", "Qwen3.5-2B",
                GenerationMode.THINKING_TEXT);

        assertEquals(1.0d, nonThinking.getTopP());
        assertEquals(2.0d, nonThinking.getPresencePenalty());
        assertEquals(0.95d, thinking.getTopP());
        assertEquals(1.5d, thinking.getPresencePenalty());
    }

    @Test
    void detectsThinkingModeFromTemplateArguments() {
        ChatTemplate.Request defaultRequest = ChatTemplate.Request.builder().build();
        ChatTemplate.Request thinkingRequest = ChatTemplate.Request.builder()
                .templateArguments(Map.of("enable_thinking", true))
                .build();
        ChatTemplate.Request stringThinkingRequest = ChatTemplate.Request.builder()
                .templateArguments(Map.of("enable_thinking", "true"))
                .build();

        assertFalse(SameDiffLanguageModelImpl.thinkingEnabled(defaultRequest));
        assertTrue(SameDiffLanguageModelImpl.thinkingEnabled(thinkingRequest));
        assertTrue(SameDiffLanguageModelImpl.thinkingEnabled(stringThinkingRequest));
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
        injectLoadedModel(impl, modelId, backend);
    }

    private static void injectLoadedModel(
            SameDiffLanguageModelImpl target,
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
        loadedField.set(target, loadedModel);

        assertTrue(target.isLoaded());
        assertEquals(modelId, target.getLoadedModelId());
    }

    private static void bindModelExecutionDevice(
            SameDiffLanguageModelImpl target, int deviceId) throws Exception {
        Field deviceField = SameDiffLanguageModelImpl.class.getDeclaredField("modelExecutionDevice");
        deviceField.setAccessible(true);
        deviceField.set(target, deviceId);
    }

    private static final class FakeModelDeviceContext
            implements SameDiffLanguageModelImpl.ModelDeviceContext {
        private final AtomicInteger currentDevice;
        private final AtomicInteger switchCount = new AtomicInteger();

        private FakeModelDeviceContext(int initialDevice) {
            this.currentDevice = new AtomicInteger(initialDevice);
        }

        @Override
        public int selectDeviceForModel(long requiredBytes) {
            return currentDevice.get();
        }

        @Override
        public int currentDevice() {
            return currentDevice.get();
        }

        @Override
        public void switchTo(int deviceId, String reason) {
            currentDevice.set(deviceId);
            switchCount.incrementAndGet();
        }

        private void setCurrentDevice(int deviceId) {
            currentDevice.set(deviceId);
        }

        private int switchCount() {
            return switchCount.get();
        }
    }
}
