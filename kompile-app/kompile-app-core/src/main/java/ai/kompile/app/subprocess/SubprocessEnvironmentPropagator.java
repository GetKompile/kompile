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

import java.util.List;
import java.util.Map;

/**
 * Shared utility for propagating ND4J/CUDA/threading environment variables
 * from the parent process to all subprocess types.
 *
 * <p>Every subprocess launcher should call {@link #propagateToEnvironment(Map, String, String)}
 * to ensure consistent environment variable coverage. This prevents the bug where
 * some subprocess types miss critical env vars (e.g., CUDA_VISIBLE_DEVICES, thread
 * counts, Triton cache dirs) that other types propagate correctly.</p>
 */
public final class SubprocessEnvironmentPropagator {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessEnvironmentPropagator.class);

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
     * Propagate all known ND4J/CUDA/threading environment variables to a subprocess
     * environment map. Also sweeps all ND4J_ and KOMPILE_ prefixed vars.
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

        // Sweep all ND4J_ and KOMPILE_ prefixed vars not already covered
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String key = e.getKey();
            if ((key.startsWith("ND4J_") || key.startsWith("KOMPILE_")) && !env.containsKey(key)) {
                env.put(key, e.getValue());
                propagated++;
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
