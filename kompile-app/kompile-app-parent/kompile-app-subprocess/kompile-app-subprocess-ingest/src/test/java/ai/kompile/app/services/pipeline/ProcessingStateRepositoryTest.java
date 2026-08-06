package ai.kompile.app.services.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingStateRepositoryTest {

    @TempDir
    Path tempDirectory;

    private final String originalDataDirectory = System.getProperty("kompile.data.dir");

    @AfterEach
    void restoreDataDirectory() {
        if (originalDataDirectory == null) {
            System.clearProperty("kompile.data.dir");
        } else {
            System.setProperty("kompile.data.dir", originalDataDirectory);
        }
    }

    @Test
    void missingExplicitStateDirectoryUsesProjectDataDirectory() {
        System.setProperty("kompile.data.dir", tempDirectory.toString());
        ProcessingStateRepository repository = new ProcessingStateRepository(null);

        try {
            repository.init();
            assertTrue(Files.isDirectory(tempDirectory.resolve("state").resolve("processing-state")));
        } finally {
            repository.shutdown();
        }
    }

    @Test
    void explicitManagedStateDirectoryRemainsSupported() {
        Path configuredStateDirectory = tempDirectory.resolve("configured-state");
        ProcessingStateRepository repository = new ProcessingStateRepository(configuredStateDirectory.toString());

        try {
            repository.init();
            assertTrue(Files.isDirectory(configuredStateDirectory.resolve("processing-state")));
        } finally {
            repository.shutdown();
        }
    }
}
