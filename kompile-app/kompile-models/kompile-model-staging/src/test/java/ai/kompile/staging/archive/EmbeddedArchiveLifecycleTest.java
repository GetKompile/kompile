package ai.kompile.staging.archive;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedArchiveLifecycleTest {
    @Test void deletesArchiveAfterSynchronousImport() throws Exception { verify(false); }
    @Test void deletesArchiveWhenImportFails() throws Exception { verify(true); }

    private void verify(boolean fail) throws Exception {
        byte[] content = {1, 2, 3};
        AtomicReference<Path> staging = new AtomicReference<>();
        IOException failure = new IOException("intentional import failure");
        ArchiveModelManager manager = new ArchiveModelManager(null, null, null,
                new DefaultResourceLoader() {
                    @Override public Resource getResource(String location) {
                        return new ByteArrayResource(content);
                    }
                }) {
            @Override public void loadArchiveFile(Path archive) throws IOException {
                staging.set(archive);
                assertArrayEquals(content, Files.readAllBytes(archive));
                if (fail) throw failure;
            }
        };
        if (fail) {
            assertSame(failure, assertThrows(IOException.class, () -> manager.loadEmbeddedArchive("test")));
        } else {
            manager.loadEmbeddedArchive("test");
        }
        assertNotNull(staging.get());
        assertFalse(Files.exists(staging.get()), "the registry import no longer needs the archive copy");
    }
}
