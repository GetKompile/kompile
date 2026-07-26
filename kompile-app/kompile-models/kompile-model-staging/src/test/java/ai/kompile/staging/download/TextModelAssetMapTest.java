package ai.kompile.staging.download;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextModelAssetMapTest {

    @Test
    void completeDeclarationUsesOneCanonicalDownloaderMap() {
        TextModelAssetMap assets = TextModelAssetMap.builder()
                .model("weights/model.gguf")
                .tokenizer("tokenizer.json")
                .tokenizerConfig("tokenizer_config.json")
                .modelConfig("config.json")
                .generationConfig("generation_config.json")
                .build();

        assertTrue(assets.isRunnableChatDeclaration());
        assertEquals("weights/model.gguf", assets.toFileMap().get(TextModelAssetMap.MODEL));
        assertEquals(
                "tokenizer_config.json",
                assets.toFileMap().get(TextModelAssetMap.TOKENIZER_CONFIG));
    }

    @Test
    void chatTemplateAndAuthoredSdxContractAreValidAlternatives() {
        TextModelAssetMap assets = TextModelAssetMap.builder()
                .model("model.ggml")
                .tokenizer("tokenizer.json")
                .chatTemplate("chat_template.jinja")
                .textGeneration("text-generation.json")
                .build();

        assertTrue(assets.missingRunnableChatAssets().isEmpty());
    }

    @Test
    void reportsOnlySemanticallyRequiredAssets() {
        TextModelAssetMap assets = TextModelAssetMap.builder()
                .model("model.gguf")
                .tokenizer("tokenizer.json")
                .build();

        assertEquals(
                java.util.List.of(
                        "tokenizer_config.json or chat_template.jinja",
                        "config.json or text-generation.json"),
                assets.missingRunnableChatAssets());
        assertFalse(assets.toFileMap().containsKey(TextModelAssetMap.SPECIAL_TOKENS_MAP));
        assertFalse(assets.toFileMap().containsKey(TextModelAssetMap.GENERATION_CONFIG));
    }

    @Test
    void typedAssetsOverrideLegacyFilesWithoutCarryingUnknownKeys() {
        DownloadRequest request = DownloadRequest.builder()
                .files(Map.of(
                        "model", "legacy.gguf",
                        "unrelated", "vocab.txt"))
                .textAssets(TextModelAssetMap.builder()
                        .model("selected.gguf")
                        .tokenizer("tokenizer.json")
                        .tokenizerConfig("tokenizer_config.json")
                        .modelConfig("config.json")
                        .build())
                .build();

        assertEquals("selected.gguf", request.effectiveFiles().get("model"));
        assertTrue(request.effectiveFiles().containsKey("unrelated"));
        assertEquals(
                "selected.gguf",
                request.effectiveTextAssets().getModel());
    }
}
