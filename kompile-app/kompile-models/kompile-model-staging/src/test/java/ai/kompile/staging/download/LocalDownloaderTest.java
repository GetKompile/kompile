package ai.kompile.staging.download;

import ai.kompile.modelmanager.registry.ModelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesModelDirRelativeToModelDirAndReturnsPipelineManifest() throws Exception {
        Path modelDir = tempDir.resolve("models");
        Path source = modelDir.resolve("vlm-pipelines/smoldocling-256m");
        Files.createDirectories(source);
        Files.writeString(source.resolve("pipeline.json"), "{\"id\":\"smoldocling-256m\"}");
        Files.writeString(source.resolve("tokenizer.json"), "{\"tokenizer\":true}");

        LocalDownloader downloader = new LocalDownloader(modelDir, tempDir);
        DownloadRequest request = DownloadRequest.builder()
                .source("local")
                .repository("vlm-pipelines/smoldocling-256m")
                .modelId("smoldocling-256m")
                .modelType(ModelType.VLM_PIPELINE)
                .format("vlm")
                .files(Map.of("model", "pipeline.json", "vocab", "tokenizer.json"))
                .build();

        Path destination = tempDir.resolve("pending/smoldocling-256m");
        DownloadResult result = downloader.download(request, destination);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(destination.resolve("pipeline.json"), result.getModelPath());
        assertEquals(destination.resolve("tokenizer.json"), result.getVocabPath());
        assertTrue(Files.exists(destination.resolve("pipeline.json")));
        assertTrue(Files.exists(destination.resolve("tokenizer.json")));
        assertFalse(result.getDownloadedFiles().isEmpty());
    }
}
