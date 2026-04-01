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

import ai.kompile.app.scaffold.TemplateContext;
import ai.kompile.app.scaffold.TemplateEngine;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelDescriptor;
import ai.kompile.modelmanager.SdkConstants;
import ai.kompile.modelmanager.SdkDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ScaffoldService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScaffoldService.class);

    private final KompileModelManager modelManager;
    private final TemplateEngine templateEngine = new TemplateEngine();

    @Autowired
    public ScaffoldService(@Autowired(required = false) KompileModelManager modelManager) {
        this.modelManager = modelManager != null ? modelManager : new KompileModelManager();
    }

    public record ScaffoldRequest(
            String platform,
            String projectName,
            String packageName,
            String modelId,
            String inferenceMode,
            boolean includeModel,
            boolean includeSdk
    ) {}

    /**
     * Generates a scaffold project as a ZIP file.
     *
     * @param request scaffold configuration
     * @return path to the generated ZIP file (in a temp directory)
     * @throws IOException if generation fails
     */
    public Path generateScaffoldZip(ScaffoldRequest request) throws IOException {
        String platform = request.platform() != null ? request.platform() : "ios";
        String projectName = request.projectName() != null ? request.projectName() : "KompileChat";
        String packageName = request.packageName() != null ? request.packageName() : "ai.kompile.chat";
        String modelId = request.modelId() != null ? request.modelId() : "smollm-135m";
        String inferenceMode = request.inferenceMode() != null ? request.inferenceMode() : "local";

        if (!"ios".equals(platform) && !"android".equals(platform)) {
            throw new IllegalArgumentException("Platform must be 'ios' or 'android'");
        }

        // Build template context
        TemplateContext context = new TemplateContext();
        context.setPlatform(platform);
        context.setProjectName(projectName);
        context.setPackageName(packageName);
        context.setModelId(modelId);
        context.setModelFileName(modelId + ".sdz");
        context.setInferenceMode(inferenceMode);

        // Create temp directory for project output
        Path tempDir = Files.createTempDirectory("kompile-scaffold-");
        Path projectDir = tempDir.resolve(projectName);
        Files.createDirectories(projectDir);

        // Process templates
        String templateResourcePath = "templates/mobile/" + platform;
        templateEngine.processTemplates(templateResourcePath, projectDir, context);
        LOGGER.info("Generated scaffold project at {}", projectDir);

        // Include model bundle if requested
        if (request.includeModel()) {
            try {
                ModelDescriptor descriptor = SdkConstants.getSdzBundleDescriptor(modelId);
                if (descriptor != null) {
                    Path modelPath = modelManager.downloadSdzBundle(descriptor);
                    Path modelDest;
                    if ("ios".equals(platform)) {
                        modelDest = projectDir.resolve(projectName).resolve("Resources/Models").resolve(modelId + ".sdz");
                    } else {
                        modelDest = projectDir.resolve("app/src/main/assets/models").resolve(modelId + ".sdz");
                    }
                    Files.createDirectories(modelDest.getParent());
                    Files.copy(modelPath, modelDest, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Included model bundle: {}", modelId);
                } else {
                    LOGGER.warn("No SDZ bundle descriptor found for model: {}", modelId);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to include model bundle: {}", e.getMessage());
            }
        }

        // Include SDK if requested
        if (request.includeSdk()) {
            try {
                String sdkPlatform = "ios".equals(platform) ? SdkConstants.IOS_ARM64 : SdkConstants.ANDROID_ARM64;
                SdkDescriptor sdkDescriptor = SdkConstants.createSdxRuntimeDescriptor(null, null);
                Path sdkPath = modelManager.downloadSdk(sdkDescriptor, sdkPlatform);
                Path sdkDest;
                if ("ios".equals(platform)) {
                    sdkDest = projectDir.resolve(projectName).resolve("Frameworks").resolve(sdkPath.getFileName().toString());
                } else {
                    sdkDest = projectDir.resolve("app/libs").resolve(sdkPath.getFileName().toString());
                }
                Files.createDirectories(sdkDest.getParent());
                Files.copy(sdkPath, sdkDest, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Included SDK for platform: {}", sdkPlatform);
            } catch (Exception e) {
                LOGGER.warn("Failed to include SDK: {}", e.getMessage());
            }
        }

        // Create ZIP
        Path zipFile = tempDir.resolve(projectName + ".zip");
        zipDirectory(projectDir, zipFile);
        LOGGER.info("Created scaffold ZIP: {}", zipFile);

        return zipFile;
    }

    private void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Path basePath = sourceDir.getParent();
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = basePath.relativize(file).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourceDir)) {
                        String entryName = basePath.relativize(dir).toString() + "/";
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
