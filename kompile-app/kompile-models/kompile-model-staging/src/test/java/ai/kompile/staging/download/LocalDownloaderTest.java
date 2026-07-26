package ai.kompile.staging.download;

import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.staging.config.StagingAssetLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

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
                .source("trusted-local")
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

    @Test
    void publicLocalSourceResolvesOnlyOpaqueUploadHandles() throws Exception {
        Path modelDir = tempDir.resolve("models");
        String handle = UUID.randomUUID().toString();
        Path upload = modelDir.resolve(".staging/uploads").resolve(handle);
        Files.createDirectories(upload);
        Files.write(upload.resolve("model.gguf"), new byte[]{1, 2, 3});

        LocalDownloader downloader = new LocalDownloader(modelDir, tempDir);
        DownloadRequest request = DownloadRequest.builder()
                .source("local")
                .repository(handle)
                .modelId("opaque")
                .format("gguf")
                .files(Map.of(TextModelAssetMap.MODEL, "model.gguf"))
                .build();

        DownloadResult result = downloader.download(request, tempDir.resolve("opaque-output"));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertTrue(Files.isRegularFile(tempDir.resolve("opaque-output/model.gguf")));
    }

    @Test
    void publicLocalSourceRejectsPathsAndTraversal() {
        Path modelDir = tempDir.resolve("models");
        LocalDownloader downloader = new LocalDownloader(modelDir, tempDir);

        for (String repository : new String[]{"/tmp/model.gguf", "../model.gguf", "nested/model"}) {
            DownloadResult result = downloader.download(
                    DownloadRequest.builder()
                            .source("local")
                            .repository(repository)
                            .modelId("rejected")
                            .files(Map.of(TextModelAssetMap.MODEL, "model.gguf"))
                            .build(),
                    tempDir.resolve("rejected-" + Math.abs(repository.hashCode())));
            assertFalse(result.isSuccess(), repository);
        }
    }

    @Test
    void rejectsSymlinkedBundleAssets() throws Exception {
        Path modelDir = tempDir.resolve("models");
        Path source = tempDir.resolve("trusted");
        Files.createDirectories(source);
        Path outside = tempDir.resolve("outside.gguf");
        Files.write(outside, new byte[]{1});
        Files.createSymbolicLink(source.resolve("model.gguf"), outside);

        DownloadResult result = new LocalDownloader(modelDir, tempDir).download(
                DownloadRequest.builder()
                        .source("trusted-local")
                        .repository(source.toString())
                        .modelId("symlink")
                        .files(Map.of(TextModelAssetMap.MODEL, "model.gguf"))
                        .build(),
                tempDir.resolve("symlink-output"));

        assertFalse(result.isSuccess());
        assertFalse(Files.exists(tempDir.resolve("symlink-output/model.gguf")));
    }

    @Test
    void enforcesConfiguredPerAssetAndTotalLimits() throws Exception {
        Path modelDir = tempDir.resolve("models");
        Path source = tempDir.resolve("bounded");
        Files.createDirectories(source);
        Files.write(source.resolve("model.gguf"), new byte[]{1, 2, 3});

        StagingAssetLimits limits = new StagingAssetLimits();
        limits.setModelBytes(2L);
        limits.setTotalBytes(2L);
        DownloadResult result = new LocalDownloader(modelDir, tempDir, limits).download(
                DownloadRequest.builder()
                        .source("trusted-local")
                        .repository(source.toString())
                        .modelId("bounded")
                        .files(Map.of(TextModelAssetMap.MODEL, "model.gguf"))
                        .build(),
                tempDir.resolve("bounded-output"));

        assertFalse(result.isSuccess());
    }
}
