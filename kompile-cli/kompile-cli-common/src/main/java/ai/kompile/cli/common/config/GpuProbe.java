/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.common.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hardware GPU detection for init-time provisioning.
 *
 * <p>{@link #probe()} runs {@code nvidia-smi} and parses the output.
 * On any failure (binary absent, timeout, parse error) it returns
 * {@code List.of()} — it never throws.</p>
 */
public final class GpuProbe {

    private GpuProbe() { }

    /**
     * Immutable description of one GPU device as reported by nvidia-smi.
     *
     * @param index              nvidia-smi index (matches cudaIndexMappings.nvidiaSmiIndex in gpu-device-config.json)
     * @param name               device name (e.g. "NVIDIA GeForce RTX 4090")
     * @param vramMb             total VRAM in MiB as reported by nvidia-smi
     * @param computeCapability  CUDA compute capability as a double (e.g. 8.9 for Ada Lovelace);
     *                           0.0 when the driver does not report it
     */
    public record GpuInfo(int index, String name, long vramMb, double computeCapability) {
        /** Backward-compatible constructor: sets computeCapability to 0.0. */
        public GpuInfo(int index, String name, long vramMb) {
            this(index, name, vramMb, 0.0);
        }
    }

    /**
     * Run {@code nvidia-smi} and return discovered GPUs with CUDA runtime index assignments.
     *
     * <p>Attempts to query {@code compute_cap} for fastest-first ordering.  If the driver
     * does not support that field the query retries without it and falls back to VRAM-desc
     * ordering (still better than identity for mixed-GPU boxes like 3070 Ti + 4090).</p>
     *
     * @return list of discovered GPUs (empty on any failure); CUDA runtime index 0 is
     *         assigned to the fastest GPU (highest compute capability, then most VRAM)
     */
    public static List<GpuInfo> probe() {
        // Try with compute_cap first; fall back to 3-field if driver rejects the field.
        List<GpuInfo> gpus = runNvidiaSmi("index,name,memory.total,compute_cap");
        if (gpus.isEmpty()) {
            gpus = runNvidiaSmi("index,name,memory.total");
        }
        return gpus;
    }

    /** Runs a single nvidia-smi query and returns parsed GPU list, empty on any failure. */
    private static List<GpuInfo> runNvidiaSmi(String queryFields) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=" + queryFields,
                    "--format=csv,noheader,nounits"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return List.of();
            }
            if (proc.exitValue() != 0) {
                return List.of();
            }
            return parseCsv(output.toString());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Assign CUDA runtime indices to a list of GPUs using fastest-first ordering.
     *
     * <p>Sort order: compute capability descending → VRAM descending → nvidia-smi index
     * ascending.  Returns a map of {@code nvidiaSmiIndex → cudaRuntimeIndex}.  The GPU
     * with the highest compute capability (or most VRAM when capabilities are equal) gets
     * {@code cudaRuntimeIndex = 0}, matching CUDA's {@code FASTEST_FIRST} default.</p>
     *
     * <p>This is a pure function — no nvidia-smi calls.</p>
     *
     * @param gpus the list of GPUs as returned by {@link #probe()} or {@link #parseCsv(String)}
     * @return map from nvidia-smi index to assigned CUDA runtime index
     */
    static Map<Integer, Integer> assignCudaRuntimeOrder(List<GpuInfo> gpus) {
        List<GpuInfo> sorted = new ArrayList<>(gpus);
        sorted.sort((a, b) -> {
            int cc = Double.compare(b.computeCapability(), a.computeCapability());
            if (cc != 0) return cc;
            int vr = Long.compare(b.vramMb(), a.vramMb());
            if (vr != 0) return vr;
            return Integer.compare(a.index(), b.index());
        });
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            result.put(sorted.get(i).index(), i);
        }
        return result;
    }

    /**
     * Parse the CSV output of nvidia-smi (one GPU per line,
     * fields: index, name, memory.total [, compute_cap]).
     *
     * <p>The optional 4th field {@code compute_cap} (e.g. "8.9") is parsed as a
     * {@code double}.  It is omitted on older drivers; in that case
     * {@link GpuInfo#computeCapability()} defaults to 0.0.</p>
     *
     * <p>Package-private so unit tests can drive it directly without
     * requiring the nvidia-smi binary.</p>
     *
     * @param csv raw nvidia-smi CSV output (newline-delimited rows)
     * @return parsed GPU list; skips malformed rows silently
     */
    static List<GpuInfo> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<GpuInfo> result = new ArrayList<>();
        for (String line : csv.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            // Split on ", " (nvidia-smi CSV separator)
            // Use limit=4 to avoid splitting names that contain commas
            String[] parts = line.split(",\\s*", 4);
            if (parts.length < 3) continue;
            try {
                int index = Integer.parseInt(parts[0].strip());
                String name = parts[1].strip();
                long vramMb = Long.parseLong(parts[2].strip());
                double computeCap = 0.0;
                if (parts.length >= 4) {
                    String capStr = parts[3].strip();
                    if (!capStr.isEmpty()) {
                        try {
                            computeCap = Double.parseDouble(capStr);
                        } catch (NumberFormatException ignored) {
                            // compute_cap not available or not a number — leave 0.0
                        }
                    }
                }
                result.add(new GpuInfo(index, name, vramMb, computeCap));
            } catch (NumberFormatException ignored) {
                // Skip malformed rows
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns true if the ND4J CUDA backend classes are on the classpath.
     * This is the same classpath check used by {@link HardwareAutoConfigurator#detectGpuAvailable()}.
     */
    public static boolean cudaClasspathPresent() {
        return HardwareAutoConfigurator.detectGpuAvailable();
    }
}
