/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.app.subprocess.model.ModelInitSubprocessArgs;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher;
import io.anserini.encoder.samediff.SameDiffEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EncoderGraphPreWarmService}.
 *
 * <p>Proves:
 * <ol>
 *   <li>The init step invokes the encoder warm-load (model-init subprocess) for a staged model
 *       whose graph-optimization cache is absent or stale.</li>
 *   <li>The step is idempotent: no subprocess is launched when a valid cache already exists.</li>
 *   <li>The step is non-fatal: a subprocess launch failure does not propagate an exception.</li>
 * </ol>
 *
 * <p>Injectable {@code modelIdSource} / {@code modelInfoSource} suppliers (package-private
 * fields) replace the {@link ai.kompile.embedding.anserini.AnseriniEncoderFactory} static
 * calls so no static-mocking infrastructure is required.  {@code REGISTRY_BASE} is reset
 * to the per-test {@link TempDir} via {@link ReflectionTestUtils}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EncoderGraphPreWarmServiceTest {

    private static final String MODEL_ID  = "bge-base-en-v1.5";
    private static final String MODEL_FILE = "model.sdz";
    private static final String VOCAB_FILE = "vocab.txt";

    @Mock
    private ModelInitSubprocessLauncher launcher;

    @TempDir
    Path tempDir;

    private EncoderGraphPreWarmService service;
    private Path originalRegistryBase;
    private String originalCacheBackend;

    @BeforeEach
    void setUp() {
        service = new EncoderGraphPreWarmService();
        ReflectionTestUtils.setField(service, "launcher", launcher);

        // Redirect REGISTRY_BASE to the temp dir so resolveModelPath finds real files
        originalRegistryBase = EncoderGraphPreWarmService.REGISTRY_BASE;
        EncoderGraphPreWarmService.REGISTRY_BASE = tempDir;
        originalCacheBackend = System.getProperty("kompile.embedding.samediff.cacheBackend");
        System.setProperty("kompile.embedding.samediff.cacheBackend", "prewarm-test");

        // Inject suppliers: model ID source returns our test model; info source returns
        // an empty 'path' (so resolveModelPath falls back to <REGISTRY_BASE>/<modelId>/<file>).
        service.modelIdSource  = () -> Set.of(MODEL_ID);
        service.modelInfoSource = id -> Map.of(
                "path", "", "modelFile", MODEL_FILE, "vocabFile", VOCAB_FILE);

        // Default launcher stub: succeed silently
        when(launcher.launchModelInit(any(), isNull(), isNull(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void tearDown() {
        // Restore the static field so other test classes are not affected
        EncoderGraphPreWarmService.REGISTRY_BASE = originalRegistryBase;
        if (originalCacheBackend == null) {
            System.clearProperty("kompile.embedding.samediff.cacheBackend");
        } else {
            System.setProperty("kompile.embedding.samediff.cacheBackend", originalCacheBackend);
        }
    }

    // ── isOptCacheValid ──────────────────────────────────────────────────────

    @Test
    void isOptCacheValid_returnsFalseWhenOptFileAbsent() throws IOException {
        Path modelFile = tempDir.resolve(MODEL_FILE);
        Files.writeString(modelFile, "dummy");
        assertFalse(SameDiffEncoder.hasValidOptimizationCache(modelFile),
                "No opt file → cache invalid");
    }

    @Test
    void isOptCacheValid_returnsFalseWhenFingerprintFileAbsent() throws IOException {
        Path modelFile = tempDir.resolve(MODEL_FILE);
        Files.writeString(modelFile, "dummy");
        Files.writeString(tempDir.resolve("model.opt.sdz"), "opt");
        // No .fp file
        assertFalse(SameDiffEncoder.hasValidOptimizationCache(modelFile),
                "Missing fingerprint → cache invalid");
    }

    @Test
    void isOptCacheValid_returnsFalseWhenFingerprintStale() throws IOException {
        Path modelFile = tempDir.resolve(MODEL_FILE);
        Files.writeString(modelFile, "dummy");
        Files.writeString(tempDir.resolve("model.opt.sdz"), "opt");
        // Write stale fingerprint (wrong mtime = 0)
        Files.writeString(tempDir.resolve("model.opt.sdz.fp"),
                Files.size(modelFile) + ",0");
        assertFalse(SameDiffEncoder.hasValidOptimizationCache(modelFile),
                "Stale mtime → cache invalid");
    }

    @Test
    void isOptCacheValid_returnsTrueWhenFingerprintFresh() throws IOException {
        Path modelFile = tempDir.resolve(MODEL_FILE);
        Files.writeString(modelFile, "some-model-bytes");
        Files.writeString(tempDir.resolve("model.opt.sdz"), "optimized-bytes");
        String fp = cacheFingerprint(modelFile);
        Files.writeString(tempDir.resolve("model.opt.sdz.fp"), fp);
        assertTrue(SameDiffEncoder.hasValidOptimizationCache(modelFile),
                "Matching fingerprint → cache valid");
    }

    // ── runPreWarm — subprocess triggered for model without valid cache ───────

    @Test
    @SuppressWarnings("unchecked")
    void runPreWarm_launchesSubprocessWhenCacheMissing() throws IOException {
        // Create model file in <tempDir>/<modelId>/model.sdz — no opt cache
        Path modelDir = tempDir.resolve(MODEL_ID);
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve(MODEL_FILE), "model-bytes");
        Files.writeString(modelDir.resolve(VOCAB_FILE), "[UNK]\n");

        service.runPreWarm();

        ArgumentCaptor<ModelInitSubprocessArgs> cap =
                ArgumentCaptor.forClass(ModelInitSubprocessArgs.class);
        verify(launcher, times(1)).launchModelInit(cap.capture(), isNull(), isNull(), any());

        ModelInitSubprocessArgs args = cap.getValue();
        assertEquals(MODEL_ID, args.modelIdentifier(),
                "Subprocess must target the staged model");
        assertTrue(args.taskId().startsWith("prewarm-"),
                "taskId should carry prewarm- prefix");
        assertTrue(args.skipValidation(),
                "Pre-warm skips validation — cache write is the goal");
        assertEquals("registry", args.modelSourceType());
    }

    // ── runPreWarm — idempotent when valid cache exists ───────────────────────

    @Test
    void runPreWarm_skipsModelWithValidCache() throws IOException {
        // Create model + a valid opt cache + fingerprint
        Path modelDir = tempDir.resolve(MODEL_ID);
        Files.createDirectories(modelDir);
        Path modelFile = modelDir.resolve(MODEL_FILE);
        Files.writeString(modelFile, "model-bytes");
        Files.writeString(modelDir.resolve(VOCAB_FILE), "[UNK]\n");

        Path optFile = modelDir.resolve("model.opt.sdz");
        Files.writeString(optFile, "optimized-bytes");
        String fp = cacheFingerprint(modelFile);
        Files.writeString(modelDir.resolve("model.opt.sdz.fp"), fp);

        service.runPreWarm();

        verify(launcher, never()).launchModelInit(any(), any(), any(), any());
    }

    // ── runPreWarm — non-fatal on subprocess launch failure ──────────────────

    @Test
    @SuppressWarnings("unchecked")
    void runPreWarm_isNonFatalWhenSubprocessThrows() throws IOException {
        // Model present, no cache → subprocess will be attempted
        Path modelDir = tempDir.resolve(MODEL_ID);
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve(MODEL_FILE), "model-bytes");
        Files.writeString(modelDir.resolve(VOCAB_FILE), "[UNK]\n");

        when(launcher.launchModelInit(any(), isNull(), isNull(), any()))
                .thenThrow(new RuntimeException("subprocess spawn failed"));

        // Must not propagate the exception out of runPreWarm
        assertDoesNotThrow(() -> service.runPreWarm(),
                "Pre-warm failure must be non-fatal");
    }

    // ── runPreWarm — model file absent → no subprocess ─────────────────────

    @Test
    void runPreWarm_skipsWhenModelFileNotOnDisk() {
        // modelIdSource returns MODEL_ID but the file doesn't exist on disk
        service.runPreWarm();
        verifyNoInteractions(launcher);
    }

    @Test
    void runPreWarm_skipsIncompleteBundleWithoutVocabulary() throws IOException {
        Path modelDir = tempDir.resolve(MODEL_ID);
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve(MODEL_FILE), "model-bytes");

        service.runPreWarm();

        verifyNoInteractions(launcher);
    }

    @Test
    void resolveModelPathUsesModelIdDirectoryBeforeRegistryPath() throws IOException {
        Path primary = tempDir.resolve(MODEL_ID);
        Path alternate = tempDir.resolve("encoders").resolve(MODEL_ID);
        Files.createDirectories(primary);
        Files.createDirectories(alternate);
        Files.writeString(primary.resolve(MODEL_FILE), "primary");
        Files.writeString(primary.resolve(VOCAB_FILE), "[UNK]\n");
        Files.writeString(alternate.resolve(MODEL_FILE), "alternate");
        Files.writeString(alternate.resolve(VOCAB_FILE), "[UNK]\n");
        service.modelInfoSource = id -> Map.of(
                "path", "encoders/" + MODEL_ID,
                "modelFile", MODEL_FILE,
                "vocabFile", VOCAB_FILE);

        assertEquals(primary.resolve(MODEL_FILE), service.resolveModelPath(MODEL_ID));
    }

    // ── runPreWarm — no models registered → nothing to do ──────────────────

    @Test
    void runPreWarm_doesNothingWhenRegistryEmpty() {
        service.modelIdSource = Set::of; // empty set
        service.runPreWarm();
        verifyNoInteractions(launcher);
    }

    // ── schedulePreWarm — no-op without launcher ─────────────────────────────

    @Test
    void schedulePreWarm_silentNoopWhenLauncherAbsent() {
        EncoderGraphPreWarmService noLauncher = new EncoderGraphPreWarmService();
        // launcher field left null
        assertDoesNotThrow(noLauncher::schedulePreWarm,
                "schedulePreWarm must be safe when no launcher is wired");
    }

    private static String cacheFingerprint(Path modelFile) {
        return ReflectionTestUtils.invokeMethod(
                SameDiffEncoder.class, "computeSourceFingerprint", modelFile);
    }
}
