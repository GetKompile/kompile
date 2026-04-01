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

package ai.kompile.cli.main.sdk;

import ai.kompile.modelmanager.*;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "scaffold", mixinStandardHelpOptions = true,
        description = "Scaffold a mobile chat app project with SDX Runtime integration.")
public class SdkScaffold implements Callable<Integer> {

    @CommandLine.Option(names = "--model", required = true,
            description = "Model ID (e.g., smollm-135m)")
    private String model;

    @CommandLine.Option(names = "--platform", required = true,
            description = "Target platform: ios or android")
    private String platform;

    @CommandLine.Option(names = "--project-name", defaultValue = "KompileChat",
            description = "Project name (default: ${DEFAULT-VALUE})")
    private String projectName;

    @CommandLine.Option(names = "--package-name", defaultValue = "ai.kompile.chat",
            description = "Package name (default: ${DEFAULT-VALUE})")
    private String packageName;

    @CommandLine.Option(names = "--mode", defaultValue = "local",
            description = "Inference mode: local, hybrid, or remote (default: ${DEFAULT-VALUE})")
    private String mode;

    @CommandLine.Option(names = "--api-key-placeholder", defaultValue = "YOUR_API_KEY_HERE",
            description = "API key placeholder for remote/hybrid mode")
    private String apiKeyPlaceholder;

    @CommandLine.Option(names = "--output-dir",
            description = "Output directory (default: ./{projectName})")
    private File outputDir;

    @CommandLine.Option(names = "--include-model", defaultValue = "true",
            description = "Include model bundle in project (default: ${DEFAULT-VALUE})")
    private boolean includeModel;

    @Override
    public Integer call() throws Exception {
        if (!"ios".equals(platform) && !"android".equals(platform)) {
            System.err.println("Platform must be 'ios' or 'android', got: " + platform);
            return 1;
        }

        Path output = outputDir != null ? outputDir.toPath() : Path.of(projectName);
        if (Files.exists(output) && Files.list(output).findAny().isPresent()) {
            System.err.println("Output directory already exists and is not empty: " + output);
            System.err.println("Please specify a different directory with --output-dir or remove the existing one.");
            return 1;
        }

        System.out.println("Scaffolding " + platform + " project: " + projectName);
        System.out.println("  Model: " + model);
        System.out.println("  Mode: " + mode);
        System.out.println("  Output: " + output.toAbsolutePath());

        KompileModelManager manager = new KompileModelManager();

        // 1. Download SDK artifact
        System.out.println("\nStep 1: Downloading SDK...");
        String sdkClassifier;
        if ("ios".equals(platform)) {
            sdkClassifier = SdkConstants.IOS_ARM64;
        } else {
            sdkClassifier = SdkConstants.ANDROID_ARM64;
        }

        SdkDescriptor sdkDescriptor = SdkConstants.createSdxRuntimeDescriptor(null, null);
        Path sdkArtifactPath = null;
        try {
            sdkArtifactPath = manager.downloadSdk(sdkDescriptor, sdkClassifier);
            System.out.println("  SDK ready: " + sdkArtifactPath);
        } catch (Exception e) {
            System.err.println("  SDK download failed (will scaffold without SDK binary): " + e.getMessage());
        }

        // 2. Download model bundle
        Path modelBundlePath = null;
        if (includeModel) {
            System.out.println("\nStep 2: Downloading model bundle...");
            ModelDescriptor sdzDescriptor = SdkConstants.getSdzBundleDescriptor(model);
            if (sdzDescriptor != null) {
                try {
                    modelBundlePath = manager.downloadSdzBundle(sdzDescriptor);
                    System.out.println("  Model ready: " + modelBundlePath);
                } catch (Exception e) {
                    System.err.println("  Model download failed (will scaffold without model): " + e.getMessage());
                }
            } else {
                System.err.println("  No SDZ descriptor for model: " + model + " (scaffolding without model)");
            }
        }

        // 3. Process templates
        System.out.println("\nStep 3: Generating project files...");
        TemplateContext context = new TemplateContext();
        context.setProjectName(projectName);
        context.setPackageName(packageName);
        context.setModelId(model);
        context.setModelFileName(model + ".sdz");
        context.setSdkVersion(sdkDescriptor.getVersion());
        context.setInferenceMode(mode);
        context.setApiKeyPlaceholder(apiKeyPlaceholder);
        context.setPlatform(platform);

        TemplateEngine engine = new TemplateEngine();
        String templatePath = "templates/mobile/" + platform;
        engine.processTemplates(templatePath, output, context);
        System.out.println("  Project files generated.");

        // 4. Copy SDK binary into project
        if (sdkArtifactPath != null) {
            System.out.println("\nStep 4: Copying SDK binary...");
            Path sdkDest;
            if ("ios".equals(platform)) {
                sdkDest = output.resolve("Frameworks");
            } else {
                sdkDest = output.resolve("app/libs");
            }
            Files.createDirectories(sdkDest);
            Files.copy(sdkArtifactPath, sdkDest.resolve(sdkArtifactPath.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            System.out.println("  SDK binary copied to: " + sdkDest);
        }

        // 5. Copy model bundle
        if (modelBundlePath != null) {
            System.out.println("\nStep 5: Copying model bundle...");
            Path modelDest;
            if ("ios".equals(platform)) {
                modelDest = output.resolve(projectName).resolve("Resources/Models");
            } else {
                modelDest = output.resolve("app/src/main/assets/models");
            }
            Files.createDirectories(modelDest);
            Files.copy(modelBundlePath, modelDest.resolve(modelBundlePath.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            System.out.println("  Model copied to: " + modelDest);
        }

        // 6. Print build instructions
        System.out.println("\n=== Project scaffolded successfully! ===\n");
        if ("ios".equals(platform)) {
            System.out.println("To build the iOS project:");
            System.out.println("  1. Open " + output.toAbsolutePath() + "/" + projectName + ".xcodeproj in Xcode");
            System.out.println("  2. Select your development team in Signing & Capabilities");
            System.out.println("  3. Build and run on a device or simulator");
            if ("remote".equals(mode) || "hybrid".equals(mode)) {
                System.out.println("  4. Set your API key in Settings within the app");
            }
        } else {
            System.out.println("To build the Android project:");
            System.out.println("  1. Open " + output.toAbsolutePath() + " in Android Studio");
            System.out.println("  2. Sync Gradle and build");
            System.out.println("  3. Run on a device or emulator");
            if ("remote".equals(mode) || "hybrid".equals(mode)) {
                System.out.println("  4. Set your API key in Settings within the app");
            }
        }

        return 0;
    }
}
