package ai.kompile.staging.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceDownloaderTest {

    @Test
    void ggufTokenizerSidecarsAreOptionalButModelIsMandatory() {
        DownloadRequest request = DownloadRequest.builder()
                .modelId("lfm2.5-1.2b-instruct")
                .source("huggingface")
                .repository("LiquidAI/LFM2.5-1.2B-Instruct-GGUF")
                .format("gguf")
                .build();

        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "vocab"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer_config"));
        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "model"));
    }

    @Test
    void encoderTokenizerSidecarsRemainMandatory() {
        DownloadRequest request = DownloadRequest.builder()
                .modelId("bge-base-en-v1.5")
                .source("huggingface")
                .repository("BAAI/bge-base-en-v1.5")
                .format("onnx")
                .build();

        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "vocab"));
        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer_config"));
    }
}
