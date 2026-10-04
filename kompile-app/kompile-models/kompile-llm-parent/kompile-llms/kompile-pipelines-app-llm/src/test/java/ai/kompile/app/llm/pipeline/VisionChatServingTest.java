package ai.kompile.app.llm.pipeline;

import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.core.llm.StructuredChatLanguageModel.InlineImage;
import ai.kompile.core.llm.StructuredChatLanguageModel.Message;
import ai.kompile.core.llm.StructuredChatLanguageModel.Request;
import ai.kompile.core.llm.StructuredChatLanguageModel.ToolCallFormat;
import ai.kompile.core.llm.StructuredChatLanguageModel.ToolChoice;
import ai.kompile.core.llm.StructuredChatLanguageModel.ToolDefinitionFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.eclipse.deeplearning4j.vlm.model.VisionLanguageModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Local vision chat on the serving side: which staged paths serve as a vision-language package,
 * how inline images decode, and how a conversation's images reach the package in the turns that
 * sent them. The DL4J model is mocked, so nothing is loaded.
 */
class VisionChatServingTest {

    @TempDir
    Path tempDir;

    private SameDiffLanguageModelImpl impl;

    @BeforeEach
    void setUp() {
        impl = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
    }

    @AfterEach
    void tearDown() {
        impl.shutdown();
    }

    // -- package detection --

    @Test
    void onnxPackageIsDetectedFromItsDirectoryOrAComponentFile() throws IOException {
        Path dir = stage("smolvlm", "vision_encoder.onnx", "decoder_model_merged.onnx",
                "embed_tokens.onnx", "tokenizer.json");

        VisionLanguagePackage byDirectory = VisionLanguagePackage.detect(dir).orElseThrow();
        assertEquals(dir.toAbsolutePath().normalize(), byDirectory.directory());
        assertEquals(byDirectory.directory().resolve("tokenizer.json"), byDirectory.tokenizer());

        VisionLanguagePackage byComponent = VisionLanguagePackage.detect(
                dir.resolve("decoder_model_merged.onnx")).orElseThrow();
        assertEquals(byDirectory.directory(), byComponent.directory());
    }

    @Test
    void aPackageNeedsBothAVisionEncoderAndADecoder() throws IOException {
        assertTrue(VisionLanguagePackage.detect(
                stage("decoder-only", "decoder_model_merged.onnx", "tokenizer.json")).isEmpty());
        assertTrue(VisionLanguagePackage.detect(
                stage("encoder-only", "vision_encoder.onnx", "tokenizer.json")).isEmpty());
        assertTrue(VisionLanguagePackage.detect(tempDir.resolve("missing")).isEmpty());
        assertTrue(VisionLanguagePackage.detect(null).isEmpty());
    }

    @Test
    void genericModelSdzIsTheDecoderOnlyWhenTheDirectoryIsServed() throws IOException {
        Path dir = stage("sdz", "vision_encoder.sdz", "model.sdz", "tokenizer.json");

        assertTrue(VisionLanguagePackage.detect(dir).isPresent());
        // A text model staged as model.sdz beside VLM parts keeps serving as text.
        assertTrue(VisionLanguagePackage.detect(dir.resolve("model.sdz")).isEmpty());
    }

    // -- inline image decoding --

    @Test
    void pngDecodesAtItsOwnSize() throws IOException {
        BufferedImage decoded = SameDiffLanguageModelImpl.decodeChatImage(png(5, 3));

        assertEquals(5, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
    }

    @Test
    void base64WrappedAtLineBreaksStillDecodes() throws IOException {
        String wrapped = Base64.getMimeEncoder(16, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pngBytes(4, 2));
        assertTrue(wrapped.contains("\r\n"));

        BufferedImage decoded = SameDiffLanguageModelImpl.decodeChatImage(
                new InlineImage("image/png", wrapped, null));

        assertEquals(4, decoded.getWidth());
    }

    @Test
    void invalidBase64IsRejectedAsSuch() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.decodeChatImage(
                        new InlineImage("image/png", "A", null)));

        assertTrue(e.getMessage().contains("image/png") && e.getMessage().contains("not valid base64"),
                e.getMessage());
    }

    @Test
    void aFormatImageIoCannotReadIsRejectedByItsMimeType() {
        String notPixels = Base64.getEncoder().encodeToString(
                "plain text, not pixels".getBytes(StandardCharsets.US_ASCII));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.decodeChatImage(
                        new InlineImage("image/webp", notPixels, null)));

        assertTrue(e.getMessage().contains("cannot decode image/webp"), e.getMessage());
    }

    @Test
    void anImageOverThePerImageLimitIsRefusedByItsSize() {
        // At the limit an image gets as far as decoding; one byte more is refused before that.
        byte[] atLimit = new byte[Math.toIntExact(StructuredChatLanguageModel.MAX_INLINE_IMAGE_BYTES)];
        IllegalArgumentException undecodable = assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.decodeChatImage(new InlineImage(
                        "image/png", Base64.getEncoder().encodeToString(atLimit), null)));
        assertTrue(undecodable.getMessage().contains("cannot decode image/png"),
                undecodable.getMessage());

        byte[] overLimit = new byte[atLimit.length + 1];
        IllegalArgumentException oversized = assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.decodeChatImage(new InlineImage(
                        "image/png", Base64.getEncoder().encodeToString(overLimit), null)));
        assertTrue(oversized.getMessage().contains("at most "
                + StructuredChatLanguageModel.MAX_INLINE_IMAGE_BYTES + " bytes"), oversized.getMessage());
    }

    // -- conversation assembly --

    @Test
    void imagesReachThePackageAheadOfTheTextOfTheTurnThatSentThem() throws IOException {
        VisionLanguageModel model = serveVisionPackage("a second invoice");
        Request request = new Request(List.of(
                new Message("system", "You read documents."),
                new Message("user", "What is this?", List.of(png(3, 2))),
                new Message("assistant", "An invoice."),
                new Message("user", "And this one?", List.of(png(5, 4)))),
                List.of());

        StructuredChatLanguageModel.Response response = impl.generateChat(request, 64);

        assertEquals("a second invoice", response.content());
        assertTrue(response.toolCalls().isEmpty());
        SentTurn sent = sentTurn(model);
        assertEquals(List.of("system", "user", "assistant", "user"),
                sent.messages().stream().map(ChatTemplate.Message::getRole).toList());
        assertNull(sent.messages().get(0).getContentParts());
        assertEquals(List.of("image", "text"), partTypes(sent.messages().get(1)));
        assertEquals("What is this?", sent.messages().get(1).getContentParts().get(1).getText());
        assertNull(sent.messages().get(2).getContentParts());
        assertEquals(List.of("image", "text"), partTypes(sent.messages().get(3)));
        assertEquals(List.of(3, 5), widths(sent.images()));
        assertEquals(64, sent.sampling().getMaxNewTokens());
    }

    @Test
    void requestLevelImagesJoinTheLastUserTurnAfterItsOwn() throws IOException {
        VisionLanguageModel model = serveVisionPackage("two pages");
        Request request = new Request(
                List.of(new Message("user", "Compare them.", List.of(png(3, 2))),
                        new Message("assistant", "Send the other page."),
                        new Message("user", "", List.of(png(5, 4)))),
                List.of(), true, ToolDefinitionFormat.STANDARD, ToolCallFormat.NATIVE,
                ToolChoice.NONE, null, List.of(png(7, 6)));

        impl.generateChat(request, 32);

        SentTurn sent = sentTurn(model);
        assertEquals(List.of("image", "text"), partTypes(sent.messages().get(0)));
        // An image-only turn carries no empty text part.
        assertEquals(List.of("image", "image"), partTypes(sent.messages().get(2)));
        assertEquals(List.of(3, 5, 7), widths(sent.images()));
    }

    @Test
    void moreThanEightImagesAreRefusedBeforeAnyIsDecoded() {
        VisionLanguageModel model = serveVisionPackage("unused");
        List<InlineImage> nine = new ArrayList<>();
        for (int i = 0; i < StructuredChatLanguageModel.MAX_INLINE_IMAGES_PER_REQUEST + 1; i++) {
            // Undecodable on purpose: the count check has to fire first.
            nine.add(new InlineImage("image/png", "A", null));
        }
        Request request = new Request(List.of(new Message("user", "read these", nine)), List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> impl.generateChat(request, 32));

        assertTrue(e.getMessage().contains("at most 8"), e.getMessage());
        verify(model, never()).generateChat(anyList(), anyList(), any());
    }

    @Test
    void requestLevelImagesWithoutAUserTurnAreRefused() throws IOException {
        VisionLanguageModel model = serveVisionPackage("unused");
        Request request = new Request(List.of(new Message("system", "You read documents.")),
                List.of(), true, ToolDefinitionFormat.STANDARD, ToolCallFormat.NATIVE,
                ToolChoice.NONE, null, List.of(png(2, 2)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> impl.generateChat(request, 32));

        assertTrue(e.getMessage().contains("need a user message"), e.getMessage());
        verify(model, never()).generateChat(anyList(), anyList(), any());
    }

    @Test
    void theServedPackageAnswersTextTurnsToo() {
        VisionLanguageModel model = serveVisionPackage("hello");

        StructuredChatLanguageModel.Response response = impl.generateChat(
                new Request(List.of(new Message("user", "hi")), List.of()), 16);

        assertEquals("hello", response.content());
        assertTrue(impl.supportsImageInput());
        SentTurn sent = sentTurn(model);
        assertNull(sent.messages().get(0).getContentParts());
        assertTrue(sent.images().isEmpty());
        assertEquals(16, sent.sampling().getMaxNewTokens());
    }

    @Test
    void aTextModelRefusesImagesInsteadOfDroppingThem() throws IOException {
        assertFalse(impl.supportsImageInput(), "nothing is loaded yet");
        SameDiffLanguageModelImpl.InferenceBackend text =
                mock(SameDiffLanguageModelImpl.InferenceBackend.class);
        impl.loaded = new SameDiffLanguageModelImpl.LoadedModel("lfm-text", text, 0L);
        Request request = new Request(
                List.of(new Message("user", "What is this?", List.of(png(2, 2)))), List.of());

        StructuredChatLanguageModel.ImageInputUnsupportedException e = assertThrows(
                StructuredChatLanguageModel.ImageInputUnsupportedException.class,
                () -> impl.generateChat(request, 32));

        assertTrue(e.getMessage().contains("lfm-text"), e.getMessage());
        assertFalse(impl.supportsImageInput());
        verifyNoInteractions(text);
    }

    // -- context window --

    @Test
    void statusReportsTheContextWindowTheServedPackageEnforces() {
        LlmModelController controller = new LlmModelController(impl, new RestTemplateBuilder(),
                new ObjectMapper(), "http://localhost:8090", tempDir.toString());
        assertEquals(0, impl.getMaxContextLength(), "nothing is loaded yet");
        assertFalse(controller.status().getBody().containsKey("maxContextLength"),
                "an unknown window is left out so clients keep their default");

        VisionLanguageModel model = serveVisionPackage("unused");
        when(model.resolveContextWindow()).thenReturn(8192);

        assertEquals(8192, impl.getMaxContextLength());
        assertEquals(8192, controller.status().getBody().get("maxContextLength"));
    }

    // -- helpers --

    private record SentTurn(List<ChatTemplate.Message> messages,
                            List<BufferedImage> images,
                            SamplingConfig sampling) {
    }

    /** Serve a mocked vision-language package that answers every turn with {@code reply}. */
    private VisionLanguageModel serveVisionPackage(String reply) {
        VisionLanguageModel model = mock(VisionLanguageModel.class);
        when(model.generateChat(anyList(), anyList(), any(SamplingConfig.class)))
                .thenReturn(GenerationResult.builder().text(reply).build());
        impl.loaded = new SameDiffLanguageModelImpl.LoadedModel("smolvlm",
                new SameDiffLanguageModelImpl.VisionLanguageBackend(model, 256, -1), 0L);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static SentTurn sentTurn(VisionLanguageModel model) {
        ArgumentCaptor<List<ChatTemplate.Message>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<BufferedImage>> images = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<SamplingConfig> sampling = ArgumentCaptor.forClass(SamplingConfig.class);
        verify(model).generateChat(messages.capture(), images.capture(), sampling.capture());
        return new SentTurn(messages.getValue(), images.getValue(), sampling.getValue());
    }

    private static List<String> partTypes(ChatTemplate.Message message) {
        return message.getContentParts().stream().map(ChatTemplate.ContentPart::getType).toList();
    }

    private static List<Integer> widths(List<BufferedImage> images) {
        return images.stream().map(BufferedImage::getWidth).toList();
    }

    private Path stage(String name, String... files) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        for (String file : files) {
            Files.writeString(dir.resolve(file), "x");
        }
        return dir;
    }

    private static InlineImage png(int width, int height) throws IOException {
        return new InlineImage("image/png",
                Base64.getEncoder().encodeToString(pngBytes(width, height)), null);
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
