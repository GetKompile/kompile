package ai.kompile.staging.training;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SameDiffCheckpointService}.
 *
 * <p>Two scenarios are covered:
 * <ol>
 *   <li><b>Fallback path</b> — when {@link SameDiffCheckpointService#isAvailable()} returns
 *       {@code false} (no native ND4J backend), load() returns empty and save() throws; the
 *       simulation path in TrainingService must still work.</li>
 *   <li><b>Round-trip path</b> — when a native ND4J backend IS available (CPU backend on the
 *       test classpath), build a tiny SameDiff graph, save it as a {@code .fb} checkpoint via
 *       {@code asFlatFile()}, reload it via {@code fromFlatFile()}, and assert structural
 *       equality (same variable names).</li>
 * </ol>
 *
 * <p>The round-trip test is skipped automatically if the ND4J backend is absent so the
 * module builds cleanly in environments without native libraries.</p>
 */
class SameDiffCheckpointServiceTest {

    @TempDir
    Path tempDir;

    private SameDiffCheckpointService service;

    @BeforeEach
    void setUp() {
        service = new SameDiffCheckpointService();
    }

    // -------------------------------------------------------------------------
    // Fallback tests — always run regardless of ND4J availability
    // -------------------------------------------------------------------------

    @Test
    void isAvailable_returnsConsistentResult() {
        // The result must be deterministic across repeated calls.
        boolean first = service.isAvailable();
        boolean second = service.isAvailable();
        assertEquals(first, second, "isAvailable() must return the same result on repeated calls");
    }

    @Test
    void load_returnsEmpty_whenFileAbsent() throws IOException {
        File absent = tempDir.resolve("no-such-file.fb").toFile();
        Optional<?> result = service.load(absent);
        assertTrue(result.isEmpty(), "load() must return empty when file does not exist");
    }

    @Test
    void load_returnsEmpty_whenFileIsNull() throws IOException {
        Optional<?> result = service.load(null);
        assertTrue(result.isEmpty(), "load() must return empty for null input");
    }

    @Test
    void save_throwsIllegalArgument_whenSdIsNull() {
        // save() must reject null regardless of backend availability.
        // If backend is absent it throws IllegalStateException first; if present, IllegalArgumentException.
        File dest = tempDir.resolve("out.fb").toFile();
        assertThrows(Exception.class, () -> service.save(null, dest),
                "save(null, dest) must throw");
    }

    // -------------------------------------------------------------------------
    // Round-trip test — only runs when a native ND4J backend is available
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_saveThenLoad_preservesVariableNames() throws IOException {
        if (!service.isAvailable()) {
            // ND4J backend absent: verify that load() returns empty for a non-empty file
            // (i.e., the fallback path is exercised cleanly).
            File fakeCheckpoint = tempDir.resolve("fake.fb").toFile();
            Files.write(fakeCheckpoint.toPath(), new byte[]{0x1, 0x2, 0x3, 0x4});
            Optional<?> result = service.load(fakeCheckpoint);
            assertTrue(result.isEmpty(),
                    "When ND4J is absent, load() must return empty even for a non-empty file");
            return;
        }

        // ND4J backend is present: build a minimal SameDiff graph, save, reload, compare.
        org.nd4j.autodiff.samediff.SameDiff sd = org.nd4j.autodiff.samediff.SameDiff.create();

        // Tiny graph: x (placeholder) → y = x + 1
        org.nd4j.autodiff.samediff.SDVariable x = sd.placeHolder("x",
                org.nd4j.linalg.api.buffer.DataType.FLOAT, 1);
        @SuppressWarnings("unused")
        org.nd4j.autodiff.samediff.SDVariable y = x.add("y", 1.0);

        File checkpoint = tempDir.resolve("model.fb").toFile();
        service.save(sd, checkpoint);
        assertTrue(checkpoint.exists(), "Checkpoint file must be created");
        assertTrue(checkpoint.length() > 0, "Checkpoint file must be non-empty");

        // Reload
        Optional<org.nd4j.autodiff.samediff.SameDiff> loaded = service.load(checkpoint);
        assertTrue(loaded.isPresent(), "load() must return a non-empty Optional after save");

        org.nd4j.autodiff.samediff.SameDiff reloaded = loaded.get();
        assertNotNull(reloaded, "Reloaded SameDiff must not be null");

        // Structural equality: original variable names must be present
        assertTrue(reloaded.hasVariable("x"),
                "Reloaded graph must contain variable 'x'");
        assertTrue(reloaded.hasVariable("y"),
                "Reloaded graph must contain variable 'y'");
    }

    @Test
    void roundTrip_helperMethod_writesReadableCheckpoint() throws IOException {
        if (!service.isAvailable()) {
            // When ND4J absent, roundTrip() should return false (not throw)
            File src = tempDir.resolve("src.fb").toFile();
            File dst = tempDir.resolve("dst.fb").toFile();
            Files.write(src.toPath(), new byte[]{0x1, 0x2});
            boolean result = service.roundTrip(src, dst);
            assertFalse(result, "roundTrip() must return false when ND4J is unavailable");
            assertFalse(dst.exists(), "Destination must NOT be created when ND4J is unavailable");
            return;
        }

        // Build and persist a tiny graph.
        org.nd4j.autodiff.samediff.SameDiff sd = org.nd4j.autodiff.samediff.SameDiff.create();
        org.nd4j.autodiff.samediff.SDVariable a = sd.placeHolder("a",
                org.nd4j.linalg.api.buffer.DataType.FLOAT, 2, 2);
        @SuppressWarnings("unused")
        org.nd4j.autodiff.samediff.SDVariable b = sd.math().square("b", a);

        File src = tempDir.resolve("source.fb").toFile();
        service.save(sd, src);

        File dst = tempDir.resolve("copy.fb").toFile();
        boolean ok = service.roundTrip(src, dst);

        assertTrue(ok, "roundTrip() must return true when ND4J is available");
        assertTrue(dst.exists(), "Destination checkpoint must exist after roundTrip");
        assertTrue(dst.length() > 0, "Destination checkpoint must be non-empty");
    }
}
