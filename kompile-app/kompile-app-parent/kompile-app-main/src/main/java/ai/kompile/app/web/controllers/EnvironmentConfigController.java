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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.AppIndexConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST controller for environment and CLI configuration diagnostics.
 * Provides endpoints to check kompile data directories, environment variables,
 * subprocess configuration, and model role assignments.
 */
@RestController
@RequestMapping("/api/environment")
public class EnvironmentConfigController {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentConfigController.class);

    @Value("${kompile.data.dir:${user.home}/.kompile}")
    private String kompileDataDir;

    private final AppIndexConfigService appIndexConfigService;

    @Autowired
    public EnvironmentConfigController(AppIndexConfigService appIndexConfigService) {
        this.appIndexConfigService = appIndexConfigService;
    }

    @Value("${kompile.ingest.subprocess.java-path:java}")
    private String subprocessJavaPath;

    @Value("${kompile.ingest.subprocess.heap-size:4g}")
    private String subprocessHeapSize;

    @Value("${kompile.ingest.subprocess.enabled:true}")
    private boolean subprocessEnabled;

    @Value("${kompile.vectorpopulation.subprocess.enabled:true}")
    private boolean vectorPopulationSubprocessEnabled;

    @Value("${kompile.vectorpopulation.subprocess.heap-size:4g}")
    private String vectorPopulationHeapSize;

    @Value("${kompile.models.roles.dense-retrieval:bge-base-en-v1.5}")
    private String denseRetrievalModel;

    @Value("${kompile.models.roles.sparse-retrieval:}")
    private String sparseRetrievalModel;

    @Value("${kompile.models.roles.reranking:ms-marco-MiniLM-L-6-v2}")
    private String rerankingModel;

    @Value("${kompile.models.registry.path:${user.home}/.kompile/models/registry.json}")
    private String modelRegistryPath;

    @Value("${kompile.backup.backup-path:${user.home}/.kompile/backups}")
    private String backupPath;

    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Get comprehensive environment and CLI configuration status.
     * This endpoint checks directory structures, environment variables,
     * subprocess configuration, and model assignments.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getEnvironmentStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            response.put("timestamp", System.currentTimeMillis());

            // 1. Kompile Data Directory Checks
            response.put("directories", checkDirectories());

            // 2. Environment Variables
            response.put("environmentVariables", checkEnvironmentVariables());

            // 3. Subprocess Configuration
            response.put("subprocess", checkSubprocessConfiguration());

            // 4. Model Role Configuration
            response.put("modelRoles", checkModelRoles());

            // 5. Server Configuration
            response.put("server", checkServerConfiguration());

            // 6. Disk Space
            response.put("diskSpace", checkDiskSpace());

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting environment status", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private Map<String, Object> checkDirectories() {
        Map<String, Object> directories = new LinkedHashMap<>();

        // Main kompile directory
        Path kompilePath = Paths.get(kompileDataDir);
        directories.put("kompileDataDir", createDirectoryStatus(kompilePath, true));

        // Config directory
        Path configPath = kompilePath.resolve("config");
        directories.put("configDir", createDirectoryStatus(configPath, false));

        // Models directory
        Path modelsPath = kompilePath.resolve("models");
        String modelCacheEnv = System.getenv("KOMPILE_MODEL_CACHE_DIR");
        if (modelCacheEnv != null && !modelCacheEnv.isEmpty()) {
            modelsPath = Paths.get(modelCacheEnv);
        }
        directories.put("modelsDir", createDirectoryStatus(modelsPath, false));

        // State directory
        Path statePath = kompilePath.resolve("state");
        directories.put("stateDir", createDirectoryStatus(statePath, false));

        // Backups directory
        Path backupsPath = Paths.get(backupPath);
        directories.put("backupsDir", createDirectoryStatus(backupsPath, false));

        // Vector store index path
        String vectorStoreIndexPath = appIndexConfigService.getConfiguration().getVectorStorePath();
        if (vectorStoreIndexPath != null) {
            Path vectorIndexPath = Paths.get(vectorStoreIndexPath);
            directories.put("vectorIndexDir", createDirectoryStatus(vectorIndexPath, false));
        } else {
            directories.put("vectorIndexDir", Map.of("status", "info", "message", "Vector index path not configured"));
        }

        return directories;
    }

    private Map<String, Object> createDirectoryStatus(Path path, boolean required) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("path", path.toString());
        status.put("required", required);

        boolean exists = Files.exists(path);
        status.put("exists", exists);

        if (exists) {
            status.put("isDirectory", Files.isDirectory(path));
            status.put("readable", Files.isReadable(path));
            status.put("writable", Files.isWritable(path));

            // Count files if it's a directory
            if (Files.isDirectory(path)) {
                try (var dirStream = Files.list(path)) {
                    long fileCount = dirStream.count();
                    status.put("fileCount", fileCount);
                } catch (IOException e) {
                    status.put("fileCount", -1);
                }
            }
        }

        // Determine status
        if (required && !exists) {
            status.put("status", "fail");
            status.put("message", "Required directory does not exist");
        } else if (!exists) {
            status.put("status", "warning");
            status.put("message", "Directory does not exist (will be created when needed)");
        } else if (!Files.isWritable(path)) {
            status.put("status", "warning");
            status.put("message", "Directory is not writable");
        } else {
            status.put("status", "pass");
            status.put("message", "Directory exists and is accessible");
        }

        return status;
    }

    private Map<String, Object> checkEnvironmentVariables() {
        Map<String, Object> envVars = new LinkedHashMap<>();

        // Kompile-specific environment variables
        envVars.put("KOMPILE_MODEL_CACHE_DIR", createEnvVarStatus("KOMPILE_MODEL_CACHE_DIR", false,
                "Override default model cache location"));
        envVars.put("KOMPILE_DENSE_MODEL", createEnvVarStatus("KOMPILE_DENSE_MODEL", false,
                "Override default dense retrieval model"));
        envVars.put("KOMPILE_SPARSE_MODEL", createEnvVarStatus("KOMPILE_SPARSE_MODEL", false,
                "Override sparse retrieval model"));
        envVars.put("KOMPILE_RERANKING_MODEL", createEnvVarStatus("KOMPILE_RERANKING_MODEL", false,
                "Override reranking model"));

        // LLM API Keys (check if set, don't expose values)
        envVars.put("OPENAI_API_KEY", createEnvVarStatus("OPENAI_API_KEY", false,
                "OpenAI API key for LLM access", true));
        envVars.put("ANTHROPIC_API_KEY", createEnvVarStatus("ANTHROPIC_API_KEY", false,
                "Anthropic API key for Claude access", true));
        envVars.put("GOOGLE_API_KEY", createEnvVarStatus("GOOGLE_API_KEY", false,
                "Google API key for Gemini access", true));

        // OAuth credentials (check if set, don't expose values)
        envVars.put("GOOGLE_CLIENT_ID", createEnvVarStatus("GOOGLE_CLIENT_ID", false,
                "Google OAuth client ID for Drive integration", true));
        envVars.put("MICROSOFT_CLIENT_ID", createEnvVarStatus("MICROSOFT_CLIENT_ID", false,
                "Microsoft OAuth client ID for SharePoint/OneDrive", true));
        envVars.put("ATLASSIAN_CLIENT_ID", createEnvVarStatus("ATLASSIAN_CLIENT_ID", false,
                "Atlassian OAuth client ID for Confluence/Jira", true));
        envVars.put("NOTION_CLIENT_ID", createEnvVarStatus("NOTION_CLIENT_ID", false,
                "Notion OAuth client ID", true));

        // Java/System environment
        envVars.put("JAVA_HOME", createEnvVarStatus("JAVA_HOME", false,
                "Java installation directory"));
        envVars.put("MAVEN_HOME", createEnvVarStatus("MAVEN_HOME", false,
                "Maven installation directory"));

        return envVars;
    }

    private Map<String, Object> createEnvVarStatus(String name, boolean required, String description) {
        return createEnvVarStatus(name, required, description, false);
    }

    private Map<String, Object> createEnvVarStatus(String name, boolean required, String description,
            boolean sensitive) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", name);
        status.put("description", description);
        status.put("required", required);
        status.put("sensitive", sensitive);

        String value = System.getenv(name);
        boolean isSet = value != null && !value.isEmpty();
        status.put("isSet", isSet);

        if (!sensitive && isSet) {
            // Only show non-sensitive values
            status.put("value", value);
        }

        if (required && !isSet) {
            status.put("status", "fail");
            status.put("message", "Required environment variable is not set");
        } else if (!isSet) {
            status.put("status", "info");
            status.put("message", "Optional environment variable is not set");
        } else {
            status.put("status", "pass");
            status.put("message", "Environment variable is set");
        }

        return status;
    }

    private Map<String, Object> checkSubprocessConfiguration() {
        Map<String, Object> subprocess = new LinkedHashMap<>();

        // Ingest subprocess
        Map<String, Object> ingest = new LinkedHashMap<>();
        ingest.put("enabled", subprocessEnabled);
        ingest.put("javaPath", subprocessJavaPath);
        ingest.put("heapSize", subprocessHeapSize);
        ingest.put("javaPathValid", isJavaPathValid(subprocessJavaPath));

        if (subprocessEnabled && !isJavaPathValid(subprocessJavaPath)) {
            ingest.put("status", "fail");
            ingest.put("message", "Java path is invalid or not executable");
        } else if (subprocessEnabled) {
            ingest.put("status", "pass");
            ingest.put("message", "Subprocess configuration is valid");
        } else {
            ingest.put("status", "info");
            ingest.put("message", "Subprocess is disabled");
        }
        subprocess.put("ingest", ingest);

        // Vector population subprocess
        Map<String, Object> vectorPopulation = new LinkedHashMap<>();
        vectorPopulation.put("enabled", vectorPopulationSubprocessEnabled);
        vectorPopulation.put("heapSize", vectorPopulationHeapSize);

        if (vectorPopulationSubprocessEnabled) {
            vectorPopulation.put("status", "pass");
            vectorPopulation.put("message", "Vector population subprocess is enabled");
        } else {
            vectorPopulation.put("status", "info");
            vectorPopulation.put("message", "Vector population subprocess is disabled");
        }
        subprocess.put("vectorPopulation", vectorPopulation);

        // Memory check
        Runtime runtime = Runtime.getRuntime();
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        subprocess.put("currentJvmMaxHeapMB", maxMemoryMB);
        subprocess.put("availableProcessors", runtime.availableProcessors());

        // Parse heap sizes and check if they're reasonable
        long ingestHeapMB = parseHeapSize(subprocessHeapSize);
        long vectorHeapMB = parseHeapSize(vectorPopulationHeapSize);
        subprocess.put("ingestHeapMB", ingestHeapMB);
        subprocess.put("vectorPopulationHeapMB", vectorHeapMB);

        return subprocess;
    }

    private boolean isJavaPathValid(String javaPath) {
        if (javaPath == null || javaPath.isEmpty()) {
            return false;
        }

        // If it's just "java", check if it's on PATH
        if (javaPath.equals("java")) {
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-version");
                pb.redirectErrorStream(true);
                process = pb.start();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) process.destroyForcibly();
                return false;
            } catch (Exception e) {
                return false;
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }

        // Check if it's a valid file path
        File javaFile = new File(javaPath);
        return javaFile.exists() && javaFile.canExecute();
    }

    private long parseHeapSize(String heapSize) {
        if (heapSize == null || heapSize.isEmpty()) {
            return 0;
        }

        heapSize = heapSize.toLowerCase().trim();
        try {
            if (heapSize.endsWith("g")) {
                return Long.parseLong(heapSize.substring(0, heapSize.length() - 1)) * 1024;
            } else if (heapSize.endsWith("m")) {
                return Long.parseLong(heapSize.substring(0, heapSize.length() - 1));
            } else if (heapSize.endsWith("k")) {
                return Long.parseLong(heapSize.substring(0, heapSize.length() - 1)) / 1024;
            } else {
                return Long.parseLong(heapSize) / (1024 * 1024);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Object> checkModelRoles() {
        Map<String, Object> modelRoles = new LinkedHashMap<>();

        // Dense retrieval model
        Map<String, Object> dense = new LinkedHashMap<>();
        dense.put("configuredModel", denseRetrievalModel);
        String denseOverride = System.getenv("KOMPILE_DENSE_MODEL");
        if (denseOverride != null && !denseOverride.isEmpty()) {
            dense.put("overrideFromEnv", denseOverride);
            dense.put("effectiveModel", denseOverride);
        } else {
            dense.put("effectiveModel", denseRetrievalModel);
        }
        dense.put("status", (denseRetrievalModel != null && !denseRetrievalModel.isEmpty()) ? "pass" : "warning");
        modelRoles.put("denseRetrieval", dense);

        // Sparse retrieval model
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("configuredModel", sparseRetrievalModel);
        String sparseOverride = System.getenv("KOMPILE_SPARSE_MODEL");
        if (sparseOverride != null && !sparseOverride.isEmpty()) {
            sparse.put("overrideFromEnv", sparseOverride);
            sparse.put("effectiveModel", sparseOverride);
        } else {
            sparse.put("effectiveModel", sparseRetrievalModel);
        }
        sparse.put("status", "info"); // Sparse is optional
        modelRoles.put("sparseRetrieval", sparse);

        // Reranking model
        Map<String, Object> reranking = new LinkedHashMap<>();
        reranking.put("configuredModel", rerankingModel);
        String rerankOverride = System.getenv("KOMPILE_RERANKING_MODEL");
        if (rerankOverride != null && !rerankOverride.isEmpty()) {
            reranking.put("overrideFromEnv", rerankOverride);
            reranking.put("effectiveModel", rerankOverride);
        } else {
            reranking.put("effectiveModel", rerankingModel);
        }
        reranking.put("status", (rerankingModel != null && !rerankingModel.isEmpty()) ? "pass" : "info");
        modelRoles.put("reranking", reranking);

        // Model registry
        Map<String, Object> registry = new LinkedHashMap<>();
        Path registryPath = Paths.get(modelRegistryPath);
        registry.put("path", modelRegistryPath);
        registry.put("exists", Files.exists(registryPath));
        if (Files.exists(registryPath)) {
            try {
                registry.put("sizeBytes", Files.size(registryPath));
                registry.put("status", "pass");
            } catch (IOException e) {
                registry.put("status", "warning");
                registry.put("error", e.getMessage());
            }
        } else {
            registry.put("status", "info");
            registry.put("message", "Model registry file does not exist yet");
        }
        modelRoles.put("registry", registry);

        return modelRoles;
    }

    private Map<String, Object> checkServerConfiguration() {
        Map<String, Object> server = new LinkedHashMap<>();

        server.put("port", serverPort);
        server.put("javaVersion", System.getProperty("java.version"));
        server.put("javaVendor", System.getProperty("java.vendor"));
        server.put("osName", System.getProperty("os.name"));
        server.put("osArch", System.getProperty("os.arch"));
        server.put("userDir", System.getProperty("user.dir"));
        server.put("userHome", System.getProperty("user.home"));
        server.put("tempDir", System.getProperty("java.io.tmpdir"));

        // Check temp dir space
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        if (tempDir.exists()) {
            long freeSpaceGB = tempDir.getFreeSpace() / (1024L * 1024L * 1024L);
            server.put("tempDirFreeSpaceGB", freeSpaceGB);
            if (freeSpaceGB < 1) {
                server.put("tempDirStatus", "warning");
                server.put("tempDirMessage", "Less than 1GB free in temp directory");
            } else {
                server.put("tempDirStatus", "pass");
            }
        }

        return server;
    }

    private Map<String, Object> checkDiskSpace() {
        Map<String, Object> diskSpace = new LinkedHashMap<>();

        // Check kompile data directory disk space
        Path kompilePath = Paths.get(kompileDataDir);
        if (Files.exists(kompilePath)) {
            File kompileDir = kompilePath.toFile();
            long totalSpaceGB = kompileDir.getTotalSpace() / (1024L * 1024L * 1024L);
            long freeSpaceGB = kompileDir.getFreeSpace() / (1024L * 1024L * 1024L);
            long usableSpaceGB = kompileDir.getUsableSpace() / (1024L * 1024L * 1024L);

            diskSpace.put("kompileDataDirTotalGB", totalSpaceGB);
            diskSpace.put("kompileDataDirFreeGB", freeSpaceGB);
            diskSpace.put("kompileDataDirUsableGB", usableSpaceGB);

            if (freeSpaceGB < 5) {
                diskSpace.put("status", "warning");
                diskSpace.put("message", "Less than 5GB free disk space");
            } else if (freeSpaceGB < 1) {
                diskSpace.put("status", "fail");
                diskSpace.put("message", "Critical: Less than 1GB free disk space");
            } else {
                diskSpace.put("status", "pass");
                diskSpace.put("message", "Adequate disk space available");
            }
        } else {
            diskSpace.put("status", "unknown");
            diskSpace.put("message", "Kompile data directory does not exist");
        }

        return diskSpace;
    }
}
