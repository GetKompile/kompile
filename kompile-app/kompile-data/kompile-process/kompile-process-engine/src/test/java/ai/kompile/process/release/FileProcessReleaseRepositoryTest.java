package ai.kompile.process.release;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProcessReleaseRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void persistsAndReloadsReleaseAcrossRepositoryInstances() {
        FileProcessReleaseRepository first =
                new FileProcessReleaseRepository(directory, JsonUtils.newStandardMapper());
        first.save(ProcessRelease.builder()
                .id("release/with unsafe filename")
                .processDefinitionId("proc")
                .processDefinitionVersion(2)
                .environment("staging")
                .status(ProcessReleaseStatus.DRAFT)
                .secretRefs(List.of("vault:billing"))
                .build());

        FileProcessReleaseRepository restarted =
                new FileProcessReleaseRepository(directory, JsonUtils.newStandardMapper());
        ProcessRelease loaded = restarted.findById("release/with unsafe filename").orElseThrow();

        assertEquals("proc", loaded.getProcessDefinitionId());
        assertEquals(List.of("vault:billing"), loaded.getSecretRefs());
        assertEquals(1, restarted.findAll().size());
    }

    @Test
    void usesEncodedFileNameAndLeavesNoTemporaryFile() throws Exception {
        FileProcessReleaseRepository repository =
                new FileProcessReleaseRepository(directory, JsonUtils.newStandardMapper());
        repository.save(ProcessRelease.builder().id("../escape").build());

        try (var files = Files.list(directory)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertEquals(1, names.size());
            assertFalse(names.get(0).contains(".."));
            assertTrue(names.get(0).endsWith(".json"));
        }
    }
}
