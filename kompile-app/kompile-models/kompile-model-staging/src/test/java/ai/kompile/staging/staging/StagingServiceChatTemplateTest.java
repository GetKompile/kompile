/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.staging;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.LocalDownloader;
import ai.kompile.staging.download.TextModelAssetMap;
import ai.kompile.staging.optimization.OptimizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Staging a GGUF must carry the model's chat template forward.
 *
 * <p>Conversion produces the SameDiff graph and copies {@code tokenizer.json}, but
 * {@code tokenizer.json} never carries {@code chat_template} — that field lives in
 * {@code tokenizer_config.json}, which a GGUF-only source has no copy of. A model staged without it
 * is loaded with no template, so prompts are encoded verbatim with no turn markers and no
 * generation prompt, and the model answers as a base completion model: on an instruction that ends
 * in a directive the likeliest continuation is the end of the document, so it emits end-of-sequence
 * and says nothing at all. {@link TextModelAssetMap} already names {@code tokenizer_config.json} or
 * {@code chat_template.jinja} as required for a runnable chat model, so a staged model missing both
 * violates kompile's own asset contract.
 *
 * <p>The GGUF itself declares {@code tokenizer.chat_template}, so staging reads it from there. The
 * fixtures below are real GGUF headers written byte by byte and parsed by the production reader —
 * nothing is downloaded and nothing is mocked out of the path under test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StagingServiceChatTemplateTest {

    private static final String TEMPLATE =
            "{% for message in messages %}<|im_start|>{{ message['role'] }}\n"
                    + "{{ message['content'] }}<|im_end|>\n{% endfor %}"
                    + "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}";

    @TempDir
    Path tempDir;

    @Mock
    private ConversionService conversionService;
    @Mock
    private OptimizationService optimizationService;

    private final ObjectMapper mapper = new ObjectMapper();
    private RegistryService registryService;
    private StagingService stagingService;
    private Path sourceDir;

    @BeforeEach
    void setUp() throws Exception {
        registryService = new RegistryService(tempDir);
        stagingService = new StagingService(
                registryService,
                conversionService,
                List.of(new LocalDownloader(registryService)),
                optimizationService);
        sourceDir = tempDir.resolve("source-models");
        Files.createDirectories(sourceDir);

        when(conversionService.convert(any(), any(), eq("gguf"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
                            .checksum("sha256:fake")
                            .build();
                });
        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(10, 50));
    }

    @Test
    @DisplayName("a staged GGUF carries its own chat template into tokenizer_config.json")
    void writesTheTemplateDeclaredByTheGguf() throws Exception {
        Path gguf = sourceDir.resolve("templated.gguf");
        Files.write(gguf, ggufHeader(TEMPLATE));
        Files.writeString(sourceDir.resolve(TextModelAssetMap.TOKENIZER_FILE),
                "{\"model\": {\"vocab\": {}}}");

        JsonNode config = stageAndReadTokenizerConfig("chat-template-from-gguf", gguf);

        assertEquals(TEMPLATE, config.path("chat_template").asText(),
                "the staged model must carry the template the GGUF declares, verbatim");
        // The template interpolates bos_token/eos_token, so they have to travel with it.
        assertEquals("<|startoftext|>", config.path("bos_token").asText());
        assertEquals("<|im_end|>", config.path("eos_token").asText());
    }

    @Test
    @DisplayName("an existing chat template is never overwritten by the GGUF's")
    void keepsATemplateThatWasAlreadyStaged() throws Exception {
        Path gguf = sourceDir.resolve("templated.gguf");
        Files.write(gguf, ggufHeader(TEMPLATE));
        Files.writeString(sourceDir.resolve(TextModelAssetMap.TOKENIZER_FILE),
                "{\"model\": {\"vocab\": {}}}");
        Files.writeString(sourceDir.resolve(TextModelAssetMap.TOKENIZER_CONFIG_FILE),
                "{\"chat_template\": \"AUTHORED\", \"bos_token\": \"<s>\"}");

        JsonNode config = stageAndReadTokenizerConfig("chat-template-authored", gguf);

        assertEquals("AUTHORED", config.path("chat_template").asText(),
                "a template shipped with the model outranks the one derived from the GGUF");
        assertEquals("<s>", config.path("bos_token").asText());
    }

    @Test
    @DisplayName("a GGUF that declares no template stages without inventing one")
    void doesNotInventATemplate() throws Exception {
        Path gguf = sourceDir.resolve("untemplated.gguf");
        Files.write(gguf, ggufHeader(null));
        Files.writeString(sourceDir.resolve(TextModelAssetMap.TOKENIZER_FILE),
                "{\"model\": {\"vocab\": {}}}");

        Path productionDir = stageAndPromote("chat-template-absent", gguf);

        // A generic stand-in that merely resembles the model's template would silently change how
        // every prompt is framed, so no template at all is the honest outcome. Staging still
        // succeeds — this is a capability the model lacks, not a staging failure.
        assertFalse(Files.exists(productionDir.resolve(TextModelAssetMap.TOKENIZER_CONFIG_FILE)),
                "no template was declared, so none should be fabricated");
    }

    // ---------------------------------------------------------------------------------------

    private JsonNode stageAndReadTokenizerConfig(String modelId, Path gguf) throws Exception {
        Path config = stageAndPromote(modelId, gguf)
                .resolve(TextModelAssetMap.TOKENIZER_CONFIG_FILE);
        assertTrue(Files.isRegularFile(config),
                TextModelAssetMap.TOKENIZER_CONFIG_FILE + " should have been staged at " + config);
        return mapper.readTree(config.toFile());
    }

    private Path stageAndPromote(String modelId, Path gguf) throws Exception {
        stagingService.stageLocalModel(modelId, gguf.toString(), "gguf", true);
        ModelEntry entry = awaitPromotion(modelId);
        Path productionDir = tempDir.resolve(entry.getPath());
        assertTrue(Files.isDirectory(productionDir), "production directory should exist");
        return productionDir;
    }

    /**
     * Promotion removes the model from the staging map, so an empty staging map is not a result —
     * the registry entry is. Waiting on the entry means the assertions run against a model that
     * really finished promoting, and a staging failure is reported as itself instead of as a
     * timeout.
     */
    private ModelEntry awaitPromotion(String modelId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            Optional<ModelEntry> entry = registryService.getModel(modelId);
            if (entry.isPresent()) {
                return entry.get();
            }
            StagingModelInfo info = stagingService.getStagingModel(modelId);
            if (info != null && info.getStatus() == StagingStatus.FAILED) {
                throw new AssertionError("staging failed: " + info.getError());
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("model " + modelId + " was never promoted into the registry");
    }

    // ---------------------------------------------------------------------------------------
    // A minimal but real GGUF: magic, version, counts, then metadata. No tensors — the reader
    // parses tensor info separately, and only the header is under test here.
    // ---------------------------------------------------------------------------------------

    private static final int GGUF_MAGIC = 0x46554747;
    private static final int TYPE_UINT32 = 4;
    private static final int TYPE_STRING = 8;
    private static final int TYPE_ARRAY = 9;

    private static byte[] ggufHeader(String chatTemplate) throws IOException {
        List<String> tokens = List.of("<|startoftext|>", "<|im_start|>", "<|im_end|>", "hello");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(le4(GGUF_MAGIC));
        out.write(le4(3));                                   // GGUF version
        out.write(le8(0L));                                  // tensor count
        out.write(le8(chatTemplate == null ? 4L : 5L));      // metadata KV count

        writeKvString(out, "general.architecture", "llama");
        writeKvStringArray(out, "tokenizer.ggml.tokens", tokens);
        writeKvUInt32(out, "tokenizer.ggml.bos_token_id", tokens.indexOf("<|startoftext|>"));
        writeKvUInt32(out, "tokenizer.ggml.eos_token_id", tokens.indexOf("<|im_end|>"));
        if (chatTemplate != null) {
            writeKvString(out, "tokenizer.chat_template", chatTemplate);
        }
        return out.toByteArray();
    }

    private static void writeKvString(ByteArrayOutputStream out, String key, String value)
            throws IOException {
        writeGgufString(out, key);
        out.write(le4(TYPE_STRING));
        writeGgufString(out, value);
    }

    private static void writeKvUInt32(ByteArrayOutputStream out, String key, int value)
            throws IOException {
        writeGgufString(out, key);
        out.write(le4(TYPE_UINT32));
        out.write(le4(value));
    }

    private static void writeKvStringArray(ByteArrayOutputStream out, String key, List<String> values)
            throws IOException {
        writeGgufString(out, key);
        out.write(le4(TYPE_ARRAY));
        out.write(le4(TYPE_STRING));
        out.write(le8(values.size()));
        for (String value : values) {
            writeGgufString(out, value);
        }
    }

    private static void writeGgufString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.write(le8(utf8.length));
        out.write(utf8);
    }

    private static byte[] le4(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] le8(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }
}
