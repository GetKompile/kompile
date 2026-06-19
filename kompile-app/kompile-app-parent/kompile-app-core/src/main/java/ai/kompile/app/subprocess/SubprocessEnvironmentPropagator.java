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

package ai.kompile.app.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for propagating ND4J/CUDA/threading environment variables
 * from the parent process to all subprocess types.
 *
 * <p>Every subprocess launcher that runs a JVM with ND4J on the classpath MUST call
 * {@link #propagateToEnvironment(Map, String, String)} to ensure consistent
 * environment variable coverage. This prevents the bug where some subprocess types
 * miss critical env vars (e.g., Triton cache dirs, CUDA config, thread counts)
 * that other types propagate correctly.</p>
 *
 * <p>This is the SINGLE SOURCE OF TRUTH for subprocess environment propagation.
 * Do not duplicate this logic in individual launchers.</p>
 */
public final class SubprocessEnvironmentPropagator {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessEnvironmentPropagator.class);

    /** Default Triton kernel cache directory when none is configured. */
    private static final String DEFAULT_TRITON_CACHE_DIR =
            System.getProperty("user.home") + "/.kompile/cache/triton/triton_cache";

    private SubprocessEnvironmentPropagator() {}

    /**
     * All known ND4J/CUDA/threading/JavaCPP environment variables that should be
     * propagated from parent to subprocess. This is the canonical list — any subprocess
     * launcher should use this instead of maintaining its own partial list.
     */
    private static final List<String> KNOWN_VARS = List.of(
            // ND4J core
            "ND4J_BACKEND",
            "ND4J_RESOURCES_DIR",
            "ND4J_DATA_BUFFER_OPS",
            "ND4J_ALLOW_FALLBACK",
            "ND4J_HEAP_SPACE",
            "ND4J_OFF_HEAP_SPACE",
            // Threading
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "OPENBLAS_NUM_THREADS",
            "GOTO_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS",
            "NUMEXPR_NUM_THREADS",
            // CUDA
            "CUDA_VISIBLE_DEVICES",
            "CUDA_DEVICE_ORDER",
            "CUDA_LAUNCH_BLOCKING",
            "CUDA_CACHE_PATH",
            "SD_CUDA_PINNED_HOST_LIMIT",
            // JavaCPP
            "JAVACPP_PLATFORM",
            "JAVACPP_CACHESFX",
            // Kompile
            "KOMPILE_MODELS_DIR",
            "KOMPILE_EMBEDDING_MODEL",
            "KOMPILE_INDEX_PATH",
            // Triton
            "ND4J_TRITON_CACHE_DIR",
            "ND4J_TRITON_DUMP_DIR",
            // DSP diagnostics
            "ND4J_DSP_DIAGNOSTICS",
            "ND4J_DSP_DIAGNOSTICS_LEVEL",
            "ND4J_DSP_DIAGNOSTICS_BUFFER_SIZE",
            "ND4J_DSP_OOM_CAPTURE_DIR",
            "ND4J_DSP_OOM_CAPTURE_ENABLED"
    );

    /**
     * Prefixes used to sweep any additional env vars not in the explicit list.
     */
    private static final String[] SWEEP_PREFIXES = {
            "ND4J_", "KOMPILE_", "CUDA_", "SD_"
    };

    /**
     * Convenience overload: propagate all env vars with no config-based overrides.
     * Uses the default Triton cache dir ({@code ~/.kompile/cache/triton/triton_cache})
     * if no {@code ND4J_TRITON_CACHE_DIR} env var is set.
     *
     * @param env the subprocess ProcessBuilder environment map to modify
     */
    public static void propagateToEnvironment(Map<String, String> env) {
        propagateToEnvironment(env, null, null);
    }

    /**
     * Propagate all known ND4J/CUDA/threading environment variables to a subprocess
     * environment map. Also sweeps all ND4J_, KOMPILE_, CUDA_, and SD_ prefixed vars.
     *
     * <p>If no Triton cache dir is available from any source (env var, config override,
     * or system property), the default {@code ~/.kompile/cache/triton/triton_cache} is
     * used and the directory is created if it does not exist.</p>
     *
     * @param env              the subprocess ProcessBuilder environment map to modify
     * @param tritonCacheDir   optional Triton cache dir override (from config); null to skip
     * @param tritonDumpDir    optional Triton dump dir override (from config); null to skip
     */
    public static void propagateToEnvironment(Map<String, String> env, String tritonCacheDir, String tritonDumpDir) {
        int propagated = 0;

        // Propagate all known vars from parent environment
        for (String var : KNOWN_VARS) {
            String val = System.getenv(var);
            if (val != null && !val.isEmpty()) {
                env.put(var, val);
                propagated++;
            }
        }

        // Sweep all prefixed vars not already covered
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String key = e.getKey();
            if (!env.containsKey(key)) {
                for (String prefix : SWEEP_PREFIXES) {
                    if (key.startsWith(prefix)) {
                        env.put(key, e.getValue());
                        propagated++;
                        break;
                    }
                }
            }
        }

        // When DSP diagnostics are enabled, auto-set level to "full" for C++ stdout output
        if (env.containsKey("ND4J_DSP_DIAGNOSTICS") && !env.containsKey("ND4J_DSP_DIAGNOSTICS_LEVEL")) {
            env.put("ND4J_DSP_DIAGNOSTICS_LEVEL", "full");
        }

        // Propagate triton cache/dump dirs from config when not already set via env var
        if (tritonCacheDir != null && !tritonCacheDir.isBlank() && !env.containsKey("ND4J_TRITON_CACHE_DIR")) {
            env.put("ND4J_TRITON_CACHE_DIR", tritonCacheDir);
        }
        if (tritonDumpDir != null && !tritonDumpDir.isBlank() && !env.containsKey("ND4J_TRITON_DUMP_DIR")) {
            env.put("ND4J_TRITON_DUMP_DIR", tritonDumpDir);
        }

        // Fall back: check system property, then use default. The native code reads
        // ND4J_TRITON_CACHE_DIR directly — without it, kernels recompile every launch.
        if (!env.containsKey("ND4J_TRITON_CACHE_DIR")) {
            String fromProp = System.getProperty("nd4j.triton.cacheDir");
            String effectiveDir = fromProp != null ? fromProp : DEFAULT_TRITON_CACHE_DIR;
            try {
                Files.createDirectories(Path.of(effectiveDir));
            } catch (Exception ex) {
                logger.warn("Could not create Triton cache directory {}: {}", effectiveDir, ex.getMessage());
            }
            env.put("ND4J_TRITON_CACHE_DIR", effectiveDir);
            logger.info("[ENV PROPAGATION] Set ND4J_TRITON_CACHE_DIR={} (default)", effectiveDir);
        }

        logger.info("[ENV PROPAGATION] Propagated {} environment variables to subprocess", propagated);
    }

    /**
     * Build the standard list of -D system property flags that should be forwarded
     * to any subprocess JVM. Scans parent system properties for known ND4J/JavaCPP prefixes.
     *
     * @return list of "-Dkey=value" strings ready to add to a JVM command
     */
    public static List<String> buildSystemPropertyFlags() {
        String[] prefixes = {
                "org.nd4j.",
                "org.bytedeco.",
                "nd4j.",
                "cuda.",
                "cudnn.",
                "openblas.",
                "mkl.",
                "ai.djl.",
                "onnxruntime.",
        };

        List<String> flags = new java.util.ArrayList<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    String value = System.getProperty(key);
                    if (value != null && !value.isBlank()) {
                        flags.add("-D" + key + "=" + value);
                    }
                    break;
                }
            }
        }
        return flags;
    }
}
