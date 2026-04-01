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

@CommandLine.Command(name = "download", mixinStandardHelpOptions = true,
        description = "Download an SDX SDK artifact and/or SDZ model bundle.")
public class SdkDownload implements Callable<Integer> {

    @CommandLine.Option(names = "--model", required = true,
            description = "Model ID to download (e.g., smollm-135m)")
    private String model;

    @CommandLine.Option(names = "--platform", required = true,
            description = "Target platform classifier (e.g., ios-arm64, android-arm64)")
    private String platform;

    @CommandLine.Option(names = "--chip",
            description = "Optional chip/feature suffix (e.g., nnapi, armcompute, avx2, compile)")
    private String chip;

    @CommandLine.Option(names = "--sdk-version",
            description = "SDK version (default: " + SdkConstants.DEFAULT_SDX_SDK_VERSION + ")")
    private String sdkVersion;

    @CommandLine.Option(names = "--sdk-base-url",
            description = "Override SDK base download URL")
    private String sdkBaseUrl;

    @CommandLine.Option(names = "--output-dir",
            description = "Output directory (default: downloads to ~/.kompile/models/)")
    private File outputDir;

    @Override
    public Integer call() throws Exception {
        KompileModelManager manager = new KompileModelManager();

        // Resolve full classifier
        String classifier = SdkConstants.resolveClassifier(platform, chip);
        System.out.println("Resolved platform classifier: " + classifier);

        // Download SDK artifact
        System.out.println("\n--- Downloading SDX Runtime SDK ---");
        SdkDescriptor sdkDescriptor = SdkConstants.createSdxRuntimeDescriptor(sdkVersion, sdkBaseUrl);
        if (!sdkDescriptor.hasPlatform(classifier)) {
            System.err.println("No SDK artifact available for platform: " + classifier);
            System.err.println("Available platforms for this SDK:");
            sdkDescriptor.getPlatformArtifacts().keySet().stream()
                    .filter(p -> p.startsWith(platform.split("-")[0]))
                    .sorted()
                    .forEach(p -> System.err.println("  " + p));
            return 1;
        }

        try {
            Path sdkPath = manager.downloadSdk(sdkDescriptor, classifier);
            System.out.println("SDK downloaded to: " + sdkPath);

            if (outputDir != null) {
                Path dest = outputDir.toPath().resolve(sdkPath.getFileName());
                Files.createDirectories(outputDir.toPath());
                Files.copy(sdkPath, dest, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Copied to: " + dest);
            }
        } catch (Exception e) {
            System.err.println("SDK download failed: " + e.getMessage());
            System.err.println("(This is expected if the SDK hasn't been published yet)");
        }

        // Download SDZ model bundle
        System.out.println("\n--- Downloading SDZ Model Bundle ---");
        ModelDescriptor sdzDescriptor = SdkConstants.getSdzBundleDescriptor(model);
        if (sdzDescriptor == null) {
            System.err.println("No SDZ bundle descriptor found for model: " + model);
            System.err.println("Check available models with: kompile sdk list --type model");
            return 1;
        }

        try {
            Path modelPath = manager.downloadSdzBundle(sdzDescriptor);
            System.out.println("Model bundle downloaded to: " + modelPath);

            if (outputDir != null) {
                Path dest = outputDir.toPath().resolve(modelPath.getFileName());
                Files.copy(modelPath, dest, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Copied to: " + dest);
            }
        } catch (Exception e) {
            System.err.println("Model download failed: " + e.getMessage());
            System.err.println("(This is expected if the model hasn't been published yet)");
        }

        System.out.println("\nDone.");
        return 0;
    }
}
