package ai.kompile.cli.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LfsPointerFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void lfsPointerStubsAreDetected() throws Exception {
        Path pointer = Files.writeString(tempDir.resolve("model.gguf"),
                "version https://git-lfs.github.com/spec/v1\n"
                        + "oid sha256:abc123\n"
                        + "size 4000000000\n");
        assertTrue(LfsPointerFiles.isPointer(pointer));
    }

    @Test
    void xetPointerStubsAreDetected() throws Exception {
        Path pointer = Files.writeString(tempDir.resolve("model.gguf"),
                "version https://xet.example/spec/v1\noid sha256:abc\n");
        assertTrue(LfsPointerFiles.isPointer(pointer));
    }

    @Test
    void realWeightFilesAreNotPointers() throws Exception {
        // Binary content that cannot decode as ASCII must not be misread.
        byte[] weights = new byte[256];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = (byte) (i | 0x80);
        }
        Path model = Files.write(tempDir.resolve("model.gguf"), weights);
        assertFalse(LfsPointerFiles.isPointer(model));

        Path smallText = Files.writeString(tempDir.resolve("config.json"), "{}");
        assertFalse(LfsPointerFiles.isPointer(smallText));
    }

    @Test
    void oversizedFilesAreNeverTreatedAsPointers() throws Exception {
        Path big = Files.writeString(tempDir.resolve("big.gguf"),
                "version https://git-lfs.github.com/spec/v1\n" + "x".repeat(8192));
        assertFalse(LfsPointerFiles.isPointer(big),
                "a >4KB file is not a stub even if it starts like one");
    }

    @Test
    void requireRealFilesRejectsUnfetchedWeights() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(repo.resolve("config.json"), "{}");
        Files.writeString(repo.resolve("model.safetensors"),
                "version https://git-lfs.github.com/spec/v1\noid sha256:abc\n");

        IOException error = assertThrows(IOException.class,
                () -> LfsPointerFiles.requireRealFiles(repo, "model.safetensors"));
        assertTrue(error.getMessage().contains("git-lfs"));
        assertTrue(error.getMessage().contains(repo.toString()));
    }

    @Test
    void requireRealFilesAcceptsFetchedWeights() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(repo.resolve("config.json"), "{}");
        Files.write(repo.resolve("model.safetensors"),
                new byte[]{1, 2, 3, (byte) 200});

        LfsPointerFiles.requireRealFiles(repo, "model.safetensors");
    }

    @Test
    void describeProblemListsPointersAndStaysNullWhenClean() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        assertNull(LfsPointerFiles.describeProblem(repo));

        Files.writeString(repo.resolve("model.gguf"),
                "version https://git-lfs.github.com/spec/v1\noid sha256:abc\n",
                StandardCharsets.US_ASCII);
        String description = LfsPointerFiles.describeProblem(repo);
        assertTrue(description.contains("Unfetched Git LFS/Xet pointer stubs"));
        assertTrue(description.contains("model.gguf"));
        assertTrue(description.contains("git lfs pull"));
    }
}
