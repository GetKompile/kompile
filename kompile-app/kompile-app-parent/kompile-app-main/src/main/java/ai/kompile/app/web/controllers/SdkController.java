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

import ai.kompile.app.services.ScaffoldService;
import ai.kompile.modelmanager.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sdk")
@CrossOrigin(origins = "*")
public class SdkController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SdkController.class);
    private final KompileModelManager modelManager;
    private final ScaffoldService scaffoldService;

    @Autowired
    public SdkController(@Autowired(required = false) KompileModelManager modelManager,
                          ScaffoldService scaffoldService) {
        this.modelManager = modelManager != null ? modelManager : new KompileModelManager();
        this.scaffoldService = scaffoldService;
    }

    /**
     * List available platforms with their extended classifier options.
     */
    @GetMapping("/platforms")
    public ResponseEntity<Map<String, Object>> getPlatforms() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("basePlatforms", SdkConstants.ALL_BASE_PLATFORMS);
        result.put("mobilePlatforms", SdkConstants.MOBILE_PLATFORMS);
        result.put("iosPlatforms", SdkConstants.IOS_PLATFORMS);
        result.put("androidPlatforms", SdkConstants.ANDROID_PLATFORMS);
        result.put("desktopPlatforms", SdkConstants.DESKTOP_PLATFORMS);

        // Build extended classifiers map
        Map<String, List<String>> extendedClassifiers = new LinkedHashMap<>();
        for (String platform : SdkConstants.ALL_BASE_PLATFORMS) {
            List<String> extensions = SdkConstants.getExtendedClassifiers(platform);
            if (!extensions.isEmpty()) {
                extendedClassifiers.put(platform, extensions);
            }
        }
        result.put("extendedClassifiers", extendedClassifiers);

        return ResponseEntity.ok(result);
    }

    /**
     * List available SDKs and their platform artifacts.
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listSdks(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false, defaultValue = "all") String type) {

        Map<String, Object> result = new LinkedHashMap<>();

        if ("sdk".equals(type) || "all".equals(type)) {
            List<Map<String, Object>> sdks = new ArrayList<>();
            for (SdkDescriptor sdk : modelManager.listAvailableSdks()) {
                Map<String, Object> sdkMap = new LinkedHashMap<>();
                sdkMap.put("sdkId", sdk.getSdkId());
                sdkMap.put("version", sdk.getVersion());

                List<Map<String, Object>> artifacts = new ArrayList<>();
                for (Map.Entry<String, SdkDescriptor.PlatformArtifact> entry : sdk.getPlatformArtifacts().entrySet()) {
                    if (platform != null && !entry.getKey().equals(platform) && !entry.getKey().startsWith(platform)) {
                        continue;
                    }
                    SdkDescriptor.PlatformArtifact artifact = entry.getValue();
                    Map<String, Object> artifactMap = new LinkedHashMap<>();
                    artifactMap.put("platform", entry.getKey());
                    artifactMap.put("artifactFileName", artifact.getArtifactFileName());
                    artifactMap.put("packaging", artifact.getPackaging());
                    artifactMap.put("downloadUrl", artifact.getDownloadUrl());
                    artifactMap.put("cached", modelManager.isSdkCached(sdk.getVersion(), entry.getKey()));
                    artifacts.add(artifactMap);
                }
                sdkMap.put("artifacts", artifacts);
                sdks.add(sdkMap);
            }
            result.put("sdks", sdks);
        }

        if ("model".equals(type) || "all".equals(type)) {
            List<Map<String, Object>> models = new ArrayList<>();
            for (ModelDescriptor desc : modelManager.listAvailableSdzBundles()) {
                Map<String, Object> modelMap = new LinkedHashMap<>();
                modelMap.put("modelId", desc.getModelId());
                modelMap.put("version", desc.getVersion());
                modelMap.put("downloadUrl", desc.getDownloadUrl());
                modelMap.put("metadata", desc.getMetadata());
                // Check if cached
                Path cached = modelManager.getBaseCachePath().resolve(desc.getExpectedCacheSubpath());
                modelMap.put("cached", java.nio.file.Files.exists(cached));
                models.add(modelMap);
            }
            result.put("models", models);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Download an SDK artifact for a specific platform.
     */
    @PostMapping("/download-sdk")
    public ResponseEntity<?> downloadSdk(@RequestBody Map<String, String> request) {
        String platformClassifier = request.get("platform");
        String sdkVersion = request.get("sdkVersion");
        String chip = request.get("chip");

        if (platformClassifier == null || platformClassifier.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform is required"));
        }

        // Resolve full classifier
        String fullClassifier = SdkConstants.resolveClassifier(platformClassifier, chip);

        try {
            SdkDescriptor sdkDescriptor = SdkConstants.createSdxRuntimeDescriptor(sdkVersion, null);
            Path artifactPath = modelManager.downloadSdk(sdkDescriptor, fullClassifier);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("platform", fullClassifier);
            result.put("path", artifactPath.toString());
            result.put("fileName", artifactPath.getFileName().toString());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LOGGER.error("SDK download failed for platform {}: {}", fullClassifier, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Download failed: " + e.getMessage()));
        }
    }

    /**
     * Download an SDZ model bundle.
     */
    @PostMapping("/download-model")
    public ResponseEntity<?> downloadModel(@RequestBody Map<String, String> request) {
        String modelId = request.get("modelId");

        if (modelId == null || modelId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "modelId is required"));
        }

        try {
            ModelDescriptor descriptor = SdkConstants.getSdzBundleDescriptor(modelId);
            if (descriptor == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No SDZ bundle descriptor found for model: " + modelId));
            }

            Path modelPath = modelManager.downloadSdzBundle(descriptor);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("modelId", modelId);
            result.put("path", modelPath.toString());
            result.put("fileName", modelPath.getFileName().toString());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LOGGER.error("Model download failed for {}: {}", modelId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Download failed: " + e.getMessage()));
        }
    }

    /**
     * Get scaffold template info for a platform.
     */
    @GetMapping("/scaffold-info")
    public ResponseEntity<Map<String, Object>> getScaffoldInfo(
            @RequestParam String platform) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform);

        if ("ios".equals(platform)) {
            result.put("templateType", "SwiftUI");
            result.put("language", "Swift");
            result.put("sdkPlatform", SdkConstants.IOS_ARM64);
            result.put("features", List.of(
                    "Multi-session chat with NavigationSplitView",
                    "Streaming responses (local AsyncStream / remote SSE)",
                    "Document import (Files.app, URL)",
                    "On-device RAG with vector search",
                    "Expandable source cards in chat",
                    "Settings with inference mode, API keys, RAG config",
                    "SwiftData persistence"
            ));
            result.put("requirements", Map.of(
                    "xcode", "15.0+",
                    "ios", "16.0+",
                    "swift", "5.9+"
            ));
        } else if ("android".equals(platform)) {
            result.put("templateType", "Jetpack Compose");
            result.put("language", "Kotlin");
            result.put("sdkPlatform", SdkConstants.ANDROID_ARM64);
            result.put("features", List.of(
                    "Multi-session chat with Navigation Compose",
                    "Streaming responses (local Flow / remote OkHttp SSE)",
                    "Document import (file picker, URL)",
                    "On-device RAG with vector search",
                    "Expandable source cards in chat",
                    "Settings with inference mode, API keys, RAG config",
                    "Room persistence"
            ));
            result.put("requirements", Map.of(
                    "androidStudio", "Hedgehog+",
                    "minSdk", "26",
                    "kotlin", "1.9+"
            ));
        } else {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Platform must be 'ios' or 'android'"));
        }

        // Available models
        List<Map<String, Object>> models = modelManager.listAvailableSdzBundles().stream()
                .map(desc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("modelId", desc.getModelId());
                    m.put("version", desc.getVersion());
                    m.put("metadata", desc.getMetadata());
                    return m;
                })
                .collect(Collectors.toList());
        result.put("availableModels", models);

        return ResponseEntity.ok(result);
    }

    /**
     * Get SDK cache status summary.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cacheDirectory", modelManager.getBaseCachePath().toString());

        // Count cached SDKs
        int cachedSdks = 0;
        int totalSdkPlatforms = 0;
        for (SdkDescriptor sdk : modelManager.listAvailableSdks()) {
            for (String classifier : sdk.getPlatformArtifacts().keySet()) {
                totalSdkPlatforms++;
                if (modelManager.isSdkCached(sdk.getVersion(), classifier)) {
                    cachedSdks++;
                }
            }
        }

        // Count cached models
        int cachedModels = 0;
        int totalModels = 0;
        for (ModelDescriptor desc : modelManager.listAvailableSdzBundles()) {
            totalModels++;
            Path cached = modelManager.getBaseCachePath().resolve(desc.getExpectedCacheSubpath());
            if (java.nio.file.Files.exists(cached)) {
                cachedModels++;
            }
        }

        result.put("cachedSdkPlatforms", cachedSdks);
        result.put("totalSdkPlatforms", totalSdkPlatforms);
        result.put("cachedModels", cachedModels);
        result.put("totalModels", totalModels);

        return ResponseEntity.ok(result);
    }

    /**
     * Generate a scaffold mobile project and return it as a ZIP download.
     */
    @PostMapping("/scaffold")
    public ResponseEntity<?> scaffoldProject(@RequestBody Map<String, Object> request) {
        try {
            String platform = (String) request.getOrDefault("platform", "ios");
            String projectName = (String) request.getOrDefault("projectName", "KompileChat");
            String packageName = (String) request.getOrDefault("packageName", "ai.kompile.chat");
            String modelId = (String) request.getOrDefault("modelId", "smollm-135m");
            String inferenceMode = (String) request.getOrDefault("inferenceMode", "local");
            boolean includeModel = Boolean.TRUE.equals(request.get("includeModel"));
            boolean includeSdk = Boolean.TRUE.equals(request.get("includeSdk"));

            ScaffoldService.ScaffoldRequest scaffoldRequest = new ScaffoldService.ScaffoldRequest(
                    platform, projectName, packageName, modelId, inferenceMode, includeModel, includeSdk
            );

            Path zipFile = scaffoldService.generateScaffoldZip(scaffoldRequest);
            Resource resource = new FileSystemResource(zipFile.toFile());
            String filename = zipFile.getFileName().toString();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(java.nio.file.Files.size(zipFile)))
                    .body(resource);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOGGER.error("Scaffold generation failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Scaffold generation failed: " + e.getMessage()));
        }
    }
}
