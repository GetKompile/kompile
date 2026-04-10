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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing Triton JIT compilation cache.
 * Wraps NativeOps Triton cache methods and provides disk-based cache bundle management.
 *
 * <p>Cache bundles are stored as {@code .tkcache} files in the configured Triton cache directory.</p>
 */
@Service
public class TritonCacheService {

    private static final Logger log = LoggerFactory.getLogger(TritonCacheService.class);
    private static final String CACHE_EXTENSION = ".tkcache";

    @Autowired
    private VlmOrchestrationConfigService orchestrationConfigService;

    /**
     * Import a cached Triton compilation bundle for the given model.
     *
     * @param modelId The model identifier
     * @return true if cache was found and imported
     */
    public boolean importCache(String modelId) {
        String cacheDir = orchestrationConfigService.getConfig().resolveTritonCacheDir();
        Path cacheFile = Path.of(cacheDir, modelId + CACHE_EXTENSION);

        if (!Files.exists(cacheFile)) {
            log.debug("No Triton cache bundle found for model '{}' at {}", modelId, cacheFile);
            return false;
        }

        try {
            long sizeBytes = Files.size(cacheFile);
            log.info("Importing Triton cache for '{}' ({} bytes) from {}", modelId, sizeBytes, cacheFile);

            // Import via NativeOps
            int imported = org.nd4j.nativeblas.NativeOpsHolder.getInstance()
                    .getDeviceNativeOps()
                    .importTritonCacheBundle(cacheFile.toString(), true);
            if (imported < 0) {
                log.warn("importTritonCacheBundle returned {} for '{}'", imported, modelId);
                return false;
            }

            log.info("Triton cache imported successfully for '{}'", modelId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to import Triton cache for '{}': {}", modelId, e.getMessage());
            return false;
        }
    }

    /**
     * Export the current in-memory Triton compiled modules to a cache bundle on disk.
     *
     * @param modelId The model identifier
     * @return true if export succeeded
     */
    public boolean exportCache(String modelId) {
        String cacheDir = orchestrationConfigService.getConfig().resolveTritonCacheDir();
        Path cacheDirPath = Path.of(cacheDir);
        Path cacheFile = cacheDirPath.resolve(modelId + CACHE_EXTENSION);

        try {
            if (!Files.exists(cacheDirPath)) {
                Files.createDirectories(cacheDirPath);
            }

            log.info("Exporting Triton cache for '{}' to {}", modelId, cacheFile);

            int exported = org.nd4j.nativeblas.NativeOpsHolder.getInstance()
                    .getDeviceNativeOps()
                    .exportTritonCacheBundle(cacheFile.toString());
            if (exported < 0) {
                log.warn("exportTritonCacheBundle returned {} for '{}'", exported, modelId);
                return false;
            }

            long sizeBytes = Files.exists(cacheFile) ? Files.size(cacheFile) : 0;
            log.info("Triton cache exported for '{}': {} bytes", modelId, sizeBytes);
            return true;
        } catch (Exception e) {
            log.warn("Failed to export Triton cache for '{}': {}", modelId, e.getMessage());
            return false;
        }
    }

    /**
     * Invalidate all in-memory Triton compiled modules.
     * Frees GPU memory used by CUmodule objects.
     */
    public void invalidateAll() {
        try {
            log.info("Invalidating all in-memory Triton compiled modules");
            org.nd4j.nativeblas.NativeOpsHolder.getInstance()
                    .getDeviceNativeOps()
                    .invalidateTritonCache();
            log.info("Triton cache invalidated");
        } catch (Exception e) {
            log.warn("Failed to invalidate Triton cache: {}", e.getMessage());
        }
    }

    /**
     * Get statistics about the Triton cache directory.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        String cacheDir = orchestrationConfigService.getConfig().resolveTritonCacheDir();
        Path cacheDirPath = Path.of(cacheDir);

        stats.put("cacheDir", cacheDir);
        stats.put("enabled", Boolean.TRUE.equals(orchestrationConfigService.getConfig().tritonCacheEnabled()));
        stats.put("autoImport", Boolean.TRUE.equals(orchestrationConfigService.getConfig().tritonAutoImport()));
        stats.put("autoExport", Boolean.TRUE.equals(orchestrationConfigService.getConfig().tritonAutoExport()));

        if (Files.exists(cacheDirPath) && Files.isDirectory(cacheDirPath)) {
            try {
                List<Map<String, Object>> bundles = listBundlesInternal(cacheDirPath);
                long totalSize = bundles.stream()
                        .mapToLong(b -> (Long) b.get("sizeBytes"))
                        .sum();
                stats.put("bundleCount", bundles.size());
                stats.put("totalSizeBytes", totalSize);
                stats.put("totalSizeMb", totalSize / (1024.0 * 1024.0));
                stats.put("exists", true);
            } catch (IOException e) {
                stats.put("bundleCount", 0);
                stats.put("totalSizeBytes", 0);
                stats.put("exists", true);
                stats.put("error", e.getMessage());
            }
        } else {
            stats.put("bundleCount", 0);
            stats.put("totalSizeBytes", 0);
            stats.put("exists", false);
        }

        return stats;
    }

    /**
     * List all cache bundle files on disk.
     */
    public List<Map<String, Object>> listBundles() {
        String cacheDir = orchestrationConfigService.getConfig().resolveTritonCacheDir();
        Path cacheDirPath = Path.of(cacheDir);

        if (!Files.exists(cacheDirPath) || !Files.isDirectory(cacheDirPath)) {
            return Collections.emptyList();
        }

        try {
            return listBundlesInternal(cacheDirPath);
        } catch (IOException e) {
            log.warn("Failed to list Triton cache bundles: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> listBundlesInternal(Path cacheDirPath) throws IOException {
        try (Stream<Path> files = Files.list(cacheDirPath)) {
            return files
                    .filter(p -> p.toString().endsWith(CACHE_EXTENSION))
                    .map(p -> {
                        Map<String, Object> bundle = new LinkedHashMap<>();
                        bundle.put("filename", p.getFileName().toString());
                        String modelId = p.getFileName().toString()
                                .replace(CACHE_EXTENSION, "");
                        bundle.put("modelId", modelId);
                        try {
                            bundle.put("sizeBytes", Files.size(p));
                            bundle.put("sizeMb", Files.size(p) / (1024.0 * 1024.0));
                            bundle.put("lastModified", Files.getLastModifiedTime(p).toString());
                        } catch (IOException e) {
                            bundle.put("sizeBytes", 0L);
                            bundle.put("sizeMb", 0.0);
                            bundle.put("lastModified", "unknown");
                        }
                        return bundle;
                    })
                    .collect(Collectors.toList());
        }
    }
}
