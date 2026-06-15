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

package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.DeviceRoutingConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of well-known {@link JobResourceProfile} instances.
 *
 * <p>GPU memory budgets match the defaults in
 * {@link ai.kompile.app.services.GpuResourceManager}.</p>
 */
public final class JobResourceProfiles {

    private static final long GB = 1024L * 1024 * 1024;

    /** All registered profiles keyed by serviceType */
    private static final Map<String, JobResourceProfile> REGISTRY = new ConcurrentHashMap<>();

    // --- Standard ingest (load → chunk → embed → index) ---
    public static final JobResourceProfile INGEST = register(
            new JobResourceProfile(
                    DeviceRoutingConfig.SERVICE_INGEST, "Document Ingest",
                    true, 2 * GB, 4 * GB,
                    true, 4,
                    List.of(),
                    List.of(), // no conflicts
                    List.of(DeviceRoutingConfig.SERVICE_INGEST) // batch with same type
            )
    );

    // --- Vector population (re-embed from keyword index) ---
    public static final JobResourceProfile VECTOR_POPULATION = register(
            new JobResourceProfile(
                    DeviceRoutingConfig.SERVICE_VECTOR_POPULATION, "Vector Population",
                    true, 1 * GB, 4 * GB,
                    false, 1,
                    List.of(),
                    List.of(), List.of()
            )
    );

    // --- Simple crawl (filesystem/web, no models) ---
    public static final JobResourceProfile CRAWL = register(
            JobResourceProfile.cpuOnly("crawl", "Web/File Crawl", 2 * GB)
    );

    // --- Unified crawl-graph pipeline (8 phases, GPU only at tail) ---
    public static final JobResourceProfile UNIFIED_CRAWL = register(
            new JobResourceProfile(
                    "unifiedCrawl", "Unified Crawl + Graph",
                    true, 5 * GB, 8 * GB,
                    false, 1,
                    List.of(
                            new JobResourceProfile.PhaseResourceProfile(
                                    "LOADING", false, 0, 60, true),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "CONVERTING", false, 0, 30, true),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "ROUTING", false, 0, 10, true),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "CHUNKING", false, 0, 30, true),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "GRAPH_EXTRACTION", false, 0, 120, true),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "ENTITY_RESOLUTION", true, 5 * GB, 60, false),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "EDGE_COMPUTATION", true, 5 * GB, 30, false),
                            new JobResourceProfile.PhaseResourceProfile(
                                    "VECTOR_INDEXING", true, 5 * GB, 60, false)
                    ),
                    List.of(), List.of()
            )
    );

    // --- Training (full GPU) ---
    public static final JobResourceProfile TRAINING = register(
            new JobResourceProfile(
                    "training", "Model Training",
                    true, 18 * GB, 16 * GB,
                    false, 1,
                    List.of(),
                    List.of(DeviceRoutingConfig.SERVICE_VLM), // conflicts with VLM
                    List.of()
            )
    );

    // --- VLM test/inference ---
    public static final JobResourceProfile VLM = register(
            new JobResourceProfile(
                    DeviceRoutingConfig.SERVICE_VLM, "VLM Inference",
                    true, 18 * GB, 16 * GB,
                    false, 1,
                    List.of(),
                    List.of("training"), // conflicts with training
                    List.of()
            )
    );

    // --- Model init (warmup/calibration) ---
    public static final JobResourceProfile MODEL_INIT = register(
            new JobResourceProfile(
                    DeviceRoutingConfig.SERVICE_MODEL_INIT, "Model Initialization",
                    true, 2 * GB, 4 * GB,
                    false, 1,
                    List.of(),
                    List.of(), List.of()
            )
    );

    // --- LLM serving (registered under both "llm" and "llmServing" keys) ---
    public static final JobResourceProfile LLM_SERVING = register(
            new JobResourceProfile(
                    DeviceRoutingConfig.SERVICE_LLM, "LLM Serving",
                    true, 18 * GB, 16 * GB,
                    false, 1,
                    List.of(),
                    List.of(), List.of()
            )
    );
    static {
        // ServingSubprocessLauncher submits jobs with jobType "llmServing"
        REGISTRY.put("llmServing", LLM_SERVING);
    }

    // --- Embedding (SameDiff/ONNX encoder inference) ---
    public static final JobResourceProfile EMBEDDING = register(
            new JobResourceProfile(
                    DeviceRoutingConfig.SERVICE_EMBEDDING, "Embedding Inference",
                    true, 1 * GB, 2 * GB,
                    true, 2,
                    List.of(),
                    List.of(), List.of()
            )
    );

    private static JobResourceProfile register(JobResourceProfile profile) {
        REGISTRY.put(profile.serviceType(), profile);
        return profile;
    }

    /**
     * Get all registered profiles.
     */
    public static Map<String, JobResourceProfile> all() {
        return Map.copyOf(REGISTRY);
    }

    /**
     * Get profile by service type, or null if not found.
     */
    public static JobResourceProfile get(String serviceType) {
        return REGISTRY.get(serviceType);
    }

    /**
     * Register a custom profile (e.g., from user config override).
     */
    public static void registerCustom(JobResourceProfile profile) {
        REGISTRY.put(profile.serviceType(), profile);
    }

    private JobResourceProfiles() {}
}
