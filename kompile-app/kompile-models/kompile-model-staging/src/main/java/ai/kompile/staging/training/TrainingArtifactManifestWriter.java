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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes deployable training artifact manifests consumed by staging promotion.
 */
public final class TrainingArtifactManifestWriter {

    public static final String SCHEMA_VERSION = "kompile.training-artifact.v1";
    public static final String MANIFEST_FILE = "training-artifact.json";

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    private TrainingArtifactManifestWriter() {
    }

    public static Path writeManifest(String taskId,
                                     String baseModelId,
                                     String trainedModelId,
                                     String trainingType,
                                     Path outputDir,
                                     Path modelPath,
                                     Map<String, Object> dataset,
                                     Map<String, Object> trainingConfig,
                                     Map<String, Object> metrics) throws IOException {
        return writeManifest(taskId, baseModelId, trainedModelId, trainingType, outputDir, modelPath,
                dataset, trainingConfig, metrics, Collections.emptyList());
    }

    public static Path writeManifest(String taskId,
                                     String baseModelId,
                                     String trainedModelId,
                                     String trainingType,
                                     Path outputDir,
                                     Path modelPath,
                                     Map<String, Object> dataset,
                                     Map<String, Object> trainingConfig,
                                     Map<String, Object> metrics,
                                     List<Map<String, Object>> extraArtifacts) throws IOException {
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutputDir);
        Path normalizedModelPath = modelPath.toAbsolutePath().normalize();
        Path manifestPath = normalizedOutputDir.resolve(MANIFEST_FILE).normalize();
        String modelFileName = normalizedModelPath.getFileName().toString();
        boolean modelFilePresent = Files.isRegularFile(normalizedModelPath);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("taskId", taskId);
        manifest.put("baseModelId", baseModelId);
        manifest.put("trainedModelId", trainedModelId);
        manifest.put("trainingType", trainingType);
        manifest.put("dataset", dataset != null ? new LinkedHashMap<>(dataset) : Collections.emptyMap());
        manifest.put("outputDir", normalizedOutputDir.toString());
        manifest.put("manifestFile", MANIFEST_FILE);
        manifest.put("modelFile", modelFileName);
        manifest.put("modelPath", normalizedModelPath.toString());
        manifest.put("modelFormat", inferModelArtifactFormat(modelFileName));
        manifest.put("deployable", modelFilePresent);
        manifest.put("exportable", modelFilePresent);
        manifest.put("registryEligible", modelFilePresent);
        manifest.put("registrySuggestion", registrySuggestion(trainedModelId, modelFileName, normalizedOutputDir));
        manifest.put("trainingConfig", trainingConfig != null ? new LinkedHashMap<>(trainingConfig) : Collections.emptyMap());
        manifest.put("metrics", metrics != null ? new LinkedHashMap<>(metrics) : Collections.emptyMap());

        List<Map<String, Object>> artifacts = new ArrayList<>();
        artifacts.add(artifactManifest("model", normalizedOutputDir, normalizedModelPath));
        if (extraArtifacts != null) {
            artifacts.addAll(extraArtifacts);
        }
        manifest.put("artifacts", artifacts);

        OBJECT_MAPPER.writeValue(manifestPath.toFile(), manifest);
        return manifestPath;
    }

    private static Map<String, Object> registrySuggestion(String trainedModelId, String modelFileName, Path outputDir) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("modelId", trainedModelId);
        suggestion.put("status", "STAGED");
        suggestion.put("path", "trained/" + trainedModelId);
        suggestion.put("modelFile", modelFileName);
        suggestion.put("sourceOutputDir", outputDir.toString());
        return suggestion;
    }

    private static Map<String, Object> artifactManifest(String role, Path root, Path file) throws IOException {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("role", role);
        artifact.put("path", file.toString());
        artifact.put("relativePath", relativePath(root, file));
        artifact.put("exists", Files.exists(file));
        artifact.put("file", Files.isRegularFile(file));
        if (Files.isRegularFile(file)) {
            artifact.put("sizeBytes", Files.size(file));
        }
        return artifact;
    }

    private static String relativePath(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private static String inferModelArtifactFormat(String modelFileName) {
        String lower = modelFileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb")) {
            return "SAMEDIFF_FLATBUFFERS";
        }
        if (lower.endsWith(".sdz")) {
            return "SAMEDIFF_ZIP";
        }
        if (lower.endsWith(".gguf")) {
            return "GGUF";
        }
        return "UNKNOWN";
    }
}
