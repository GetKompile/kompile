package ai.kompile.staging.sdx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdxTextModelStagerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void standaloneChatTemplateAndSpecialTokensBecomeCanonicalTokenizerConfig() throws Exception {
        Path workspace = completeWorkspace();
        Files.writeString(
                workspace.resolve("chat_template.jinja"),
                "{% for message in messages %}{{ message['content'] }}{% endfor %}");
        Files.writeString(
                workspace.resolve("special_tokens_map.json"),
                """
                {"bos_token":"<s>","eos_token":"</s>"}
                """);

        SdxTextModelStager.PreparedTextAssets prepared =
                SdxTextModelStager.prepare(
                        workspace,
                        workspace.resolve("model.sdz"),
                        workspace.resolve("operation"));

        JsonNode normalized = JSON.readTree(prepared.tokenizerConfig().toFile());
        assertEquals("<s>", normalized.path("bos_token").asText());
        assertEquals("</s>", normalized.path("eos_token").asText());
        assertTrue(normalized.path("chat_template").asText().contains("messages"));
    }

    @Test
    void tokenizerConfigAloneIsCompleteAndOptionalSidecarsAreNotRequired() throws Exception {
        Path workspace = completeWorkspace();
        Files.writeString(
                workspace.resolve("tokenizer_config.json"),
                """
                {"chat_template":"{{ messages }}","model_max_length":128}
                """);

        SdxTextModelStager.PreparedTextAssets prepared =
                SdxTextModelStager.prepare(
                        workspace,
                        workspace.resolve("model.sdz"),
                        workspace.resolve("operation"));

        assertTrue(Files.isRegularFile(prepared.tokenizer()));
        assertTrue(Files.isRegularFile(prepared.tokenizerConfig()));
        assertTrue(Files.isRegularFile(prepared.textGenerationConfig()));
    }

    @Test
    void missingTokenizerConfigAndChatTemplateFailsBeforeCompilation() throws Exception {
        Path workspace = completeWorkspace();

        IOException failure = assertThrows(
                IOException.class,
                () -> SdxTextModelStager.prepare(
                        workspace,
                        workspace.resolve("model.sdz"),
                        workspace.resolve("operation")));

        assertTrue(failure.getMessage().contains("chat template"));
    }

    @Test
    void deeplyNestedTokenizerConfigurationIsRejectedBeforeCompilation() throws Exception {
        Path workspace = completeWorkspace();
        String nested = "{\"chat_template\":\"{{ messages }}\",\"nested\":"
                + "{\"value\":".repeat(70)
                + "0"
                + "}".repeat(70)
                + "}";
        Files.writeString(workspace.resolve("tokenizer_config.json"), nested);

        IOException failure = assertThrows(
                IOException.class,
                () -> SdxTextModelStager.prepare(
                        workspace,
                        workspace.resolve("model.sdz"),
                        workspace.resolve("operation")));

        assertTrue(failure.getMessage().contains("tokenizer_config.json"));
        assertTrue(failure.getMessage().contains("not valid JSON"));
    }

    @Test
    void oversizedTokenizerConfigurationIsRejectedBeforeParsing() throws Exception {
        Path workspace = completeWorkspace();
        Files.writeString(
                workspace.resolve("tokenizer_config.json"),
                "{\"chat_template\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}");

        IOException failure = assertThrows(
                IOException.class,
                () -> SdxTextModelStager.prepare(
                        workspace,
                        workspace.resolve("model.sdz"),
                        workspace.resolve("operation")));

        assertTrue(failure.getMessage().contains("8 MiB JSON-asset limit"));
        assertTrue(failure.getMessage().contains("tokenizer_config.json"));
    }

    @Test
    void addedTokensMustAlreadyBeEmbeddedInTokenizerJson() throws Exception {
        Path workspace = completeWorkspace();
        Files.writeString(
                workspace.resolve("tokenizer_config.json"),
                """
                {"chat_template":"{{ messages }}"}
                """);
        Files.writeString(
                workspace.resolve("added_tokens.json"),
                """
                {"<tool>":42}
                """);

        IOException failure = assertThrows(
                IOException.class,
                () -> SdxTextModelStager.prepare(
                        workspace,
                        workspace.resolve("model.sdz"),
                        workspace.resolve("operation")));

        assertTrue(failure.getMessage().contains("self-contained"));
    }

    private Path completeWorkspace() throws IOException {
        Path workspace = temp.resolve("workspace-" + System.nanoTime());
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("model.sdz"), "unused-with-authored-contract");
        Files.writeString(
                workspace.resolve("tokenizer.json"),
                """
                {
                  "version": "1.0",
                  "added_tokens": [
                    {"id": 0, "content": "<unk>", "special": true},
                    {"id": 1, "content": "<s>", "special": true},
                    {"id": 2, "content": "</s>", "special": true}
                  ],
                  "model": {
                    "type": "WordLevel",
                    "vocab": {"<unk>": 0, "<s>": 1, "</s>": 2, "hello": 3},
                    "unk_token": "<unk>"
                  }
                }
                """);
        Files.writeString(workspace.resolve("text-generation.json"), contract());
        return workspace;
    }

    private static String contract() {
        return """
                {
                  "formatVersion": 1,
                  "profile": "causal-lm-in-graph-kv-v1",
                  "io": {
                    "inputIds": "input_ids",
                    "causalMask": "attention_mask",
                    "positionOffset": "position_offset",
                    "cachePosition": "cache_position",
                    "actualSequenceLength": "actual_sequence_length",
                    "logits": "logits",
                    "kvKeyInputs": ["past_key_values.0.key"],
                    "kvValueInputs": ["past_key_values.0.value"],
                    "prefillKeyOutputs": ["present.0.key"],
                    "prefillValueOutputs": ["present.0.value"]
                  },
                  "execution": {
                    "kvLayout": "BSHD",
                    "kvDtype": "FLOAT16",
                    "maskDtype": "FLOAT16",
                    "planOwnsKvScatter": true
                  },
                  "tokens": {
                    "bosId": 1,
                    "padId": 2,
                    "eosIds": [2]
                  },
                  "limits": {
                    "contextLength": 128,
                    "maxPrefillLength": 64,
                    "maxBatchSize": 1
                  },
                  "samplingDefaults": {
                    "maxNewTokens": 16,
                    "minNewTokens": 0,
                    "temperature": 0.0,
                    "topK": 0,
                    "topP": 1.0,
                    "repetitionPenalty": 1.0,
                    "seed": 0
                  }
                }
                """;
    }
}
