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

package ai.kompile.staging.training;

import org.nd4j.autodiff.samediff.SameDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Optional integration layer for SameDiff FlatBuffers (.fb) checkpoint persistence.
 *
 * <p>This service wraps the ND4J {@link SameDiff} API directly (typed, no reflection) and
 * provides a {@link #isAvailable()} guard so callers can fall back to simulation when a
 * native ND4J backend is absent.  The compile-time dependency on {@code nd4j-api} is
 * already satisfied transitively via {@code nd4j-native} in the pom; no extra dep is
 * needed.  A running backend (CPU or CUDA) is required only at runtime for actual
 * load/save operations.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * if (checkpointService.isAvailable()) {
 *     Optional<SameDiff> model = checkpointService.load(modelFile);
 *     // ... train ...
 *     checkpointService.save(model, checkpointFile);
 * }
 * }</pre>
 */
@Service
public class SameDiffCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(SameDiffCheckpointService.class);

    /** Cached availability result so the backend probe runs at most once per JVM. */
    private volatile Boolean available = null;

    /**
     * Returns {@code true} when a working ND4J backend can be found at runtime.
     *
     * <p>The check is lazy and cached: the first call probes {@code Nd4j.getBackend()}
     * (which triggers the SPI lookup); subsequent calls return the cached result.</p>
     */
    public boolean isAvailable() {
        if (available != null) return available;
        synchronized (this) {
            if (available != null) return available;
            try {
                // Trigger ND4J backend SPI resolution without importing Nd4j directly
                // (Nd4j is in nd4j-api but its static init depends on backend presence).
                // We probe by attempting to touch the SameDiff class loader — if the
                // class loads, nd4j-api is present; then confirm a backend is on-classpath.
                Class.forName("org.nd4j.linalg.factory.Nd4j");
                available = true;
                log.info("ND4J backend available — SameDiff checkpoint integration enabled");
            } catch (ClassNotFoundException e) {
                available = false;
                log.info("ND4J Nd4j factory class not found — SameDiff checkpoint integration disabled (simulation mode)");
            } catch (Throwable t) {
                // UnsatisfiedLinkError, ExceptionInInitializerError, etc. when native lib absent
                available = false;
                log.info("ND4J backend not initializable ({}), SameDiff checkpoint integration disabled", t.getClass().getSimpleName());
            }
        }
        return available;
    }

    /**
     * Load a SameDiff graph from a FlatBuffers {@code .fb} file.
     *
     * @param fbFile path to a {@code .fb} checkpoint file
     * @return the loaded {@link SameDiff} instance, or empty if unavailable / the file is absent
     * @throws IOException if ND4J encounters a serialisation error
     */
    public Optional<SameDiff> load(File fbFile) throws IOException {
        if (!isAvailable()) {
            log.debug("SameDiff not available; skipping load of {}", fbFile);
            return Optional.empty();
        }
        if (fbFile == null || !fbFile.exists()) {
            log.debug("Model file not found: {}", fbFile);
            return Optional.empty();
        }
        log.info("Loading SameDiff checkpoint from {}", fbFile.getAbsolutePath());
        SameDiff sd = SameDiff.fromFlatFile(fbFile);
        log.info("Loaded SameDiff graph with {} variables from {}", sd.variables().size(), fbFile.getName());
        return Optional.of(sd);
    }

    /**
     * Save a SameDiff graph to a FlatBuffers {@code .fb} file.
     *
     * @param sd  the graph to serialise
     * @param out destination file (parent directories must exist)
     * @throws IOException if ND4J encounters a serialisation error or ND4J is not available
     */
    public void save(SameDiff sd, File out) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException("Cannot save SameDiff checkpoint: ND4J backend is not available");
        }
        if (sd == null) throw new IllegalArgumentException("SameDiff instance must not be null");
        if (out == null) throw new IllegalArgumentException("Output file must not be null");
        out.getParentFile().mkdirs();
        log.info("Saving SameDiff checkpoint to {}", out.getAbsolutePath());
        sd.asFlatFile(out);
        log.info("Checkpoint written ({} bytes) to {}", out.length(), out.getName());
    }

    /**
     * Convenience round-trip: load from {@code fbFile}, apply a no-op transform (for
     * testing/verification), and save to {@code dest}.
     *
     * @param fbFile source checkpoint
     * @param dest   destination checkpoint
     * @return {@code true} if the round-trip completed; {@code false} if ND4J is unavailable
     * @throws IOException on serialisation errors
     */
    public boolean roundTrip(File fbFile, File dest) throws IOException {
        Optional<SameDiff> loaded = load(fbFile);
        if (loaded.isEmpty()) return false;
        save(loaded.get(), dest);
        return true;
    }
}
