package ai.kompile.staging.service;

import ai.kompile.staging.domain.TrainingJobHistory;
import ai.kompile.staging.repository.TrainingJobHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingJobHistoryServiceArtifactTest {

    @TempDir
    Path tempDir;

    @Mock
    TrainingJobHistoryRepository repository;

    TrainingJobHistoryService service;

    @BeforeEach
    void setUp() {
        service = new TrainingJobHistoryService(repository);
    }

    @Test
    void readsArtifactManifestFromCompletedJobOutputDirectory() throws Exception {
        Path outputDir = tempDir.resolve("training-output");
        Files.createDirectories(outputDir);
        Path manifestPath = outputDir.resolve("training-artifact.json");
        Files.writeString(manifestPath,
                "{\"schemaVersion\":\"kompile.training-artifact.v1\","
                        + "\"trainedModelId\":\"base-lora-train-1\","
                        + "\"deployable\":true}\n");

        TrainingJobHistory job = TrainingJobHistory.createQueued(
                "train-1", TrainingJobHistory.TrainingType.LORA, "base-model", "dataset-1");
        job.markCompleted(0.25, 0.3, 12, outputDir.toString());
        when(repository.findByTaskId("train-1")).thenReturn(Optional.of(job));

        Optional<Map<String, Object>> manifest = service.getArtifactManifest("train-1");

        assertTrue(manifest.isPresent());
        assertEquals("kompile.training-artifact.v1", manifest.get().get("schemaVersion"));
        assertEquals("base-lora-train-1", manifest.get().get("trainedModelId"));
        assertEquals(Boolean.TRUE, manifest.get().get("deployable"));
        assertEquals("train-1", manifest.get().get("taskId"));
        assertEquals("base-model", manifest.get().get("baseModelId"));
        assertEquals("dataset-1", manifest.get().get("datasetId"));
        assertEquals(manifestPath.toString(), manifest.get().get("artifactManifestPath"));
    }

    @Test
    void returnsEmptyWhenCompletedJobHasNoManifest() {
        TrainingJobHistory job = TrainingJobHistory.createQueued(
                "train-missing", TrainingJobHistory.TrainingType.FINETUNE, "base-model", "dataset-1");
        job.markCompleted(0.25, 0.3, 12, tempDir.resolve("missing-output").toString());
        when(repository.findByTaskId("train-missing")).thenReturn(Optional.of(job));

        assertTrue(service.getArtifactManifest("train-missing").isEmpty());
    }
}
