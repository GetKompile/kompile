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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
        description = "List available SDX SDKs and SDZ model bundles.")
public class SdkList implements Callable<Integer> {

    @CommandLine.Option(names = "--platform", description = "Filter by platform classifier (e.g., ios-arm64, android-arm64)")
    private String platform;

    @CommandLine.Option(names = "--type", defaultValue = "all",
            description = "Type to list: sdk, model, or all (default: ${DEFAULT-VALUE})")
    private String type;

    @CommandLine.Option(names = "--sdk-base-url", description = "Override SDK base URL")
    private String sdkBaseUrl;

    @Override
    public Integer call() throws Exception {
        KompileModelManager manager = new KompileModelManager();

        if ("sdk".equals(type) || "all".equals(type)) {
            listSdks(manager);
        }

        if ("model".equals(type) || "all".equals(type)) {
            listModels(manager);
        }

        return 0;
    }

    private void listSdks(KompileModelManager manager) {
        System.out.println("=== SDX Runtime SDKs ===");
        System.out.printf("%-30s %-12s %-25s %-12s %-8s%n",
                "SDK ID", "Version", "Platform", "Packaging", "Cached");
        System.out.println("-".repeat(90));

        List<SdkDescriptor> sdks = manager.listAvailableSdks();
        for (SdkDescriptor sdk : sdks) {
            for (Map.Entry<String, SdkDescriptor.PlatformArtifact> entry : sdk.getPlatformArtifacts().entrySet()) {
                String classifier = entry.getKey();
                if (platform != null && !classifier.equals(platform) && !classifier.startsWith(platform)) {
                    continue;
                }
                SdkDescriptor.PlatformArtifact artifact = entry.getValue();
                boolean cached = manager.isSdkCached(sdk.getVersion(), classifier);
                System.out.printf("%-30s %-12s %-25s %-12s %-8s%n",
                        sdk.getSdkId(), sdk.getVersion(), classifier,
                        artifact.getPackaging(), cached ? "yes" : "no");
            }
        }
        System.out.println();
    }

    private void listModels(KompileModelManager manager) {
        System.out.println("=== SDZ Model Bundles ===");
        System.out.printf("%-25s %-10s %-15s %-15s%n",
                "Model ID", "Version", "Type", "Context Length");
        System.out.println("-".repeat(70));

        List<ModelDescriptor> bundles = manager.listAvailableSdzBundles();
        for (ModelDescriptor bundle : bundles) {
            String modelType = bundle.getMetadataString("model_type");
            Object ctxLen = bundle.getMetadata().get("context_length");
            System.out.printf("%-25s %-10s %-15s %-15s%n",
                    bundle.getModelId(), bundle.getVersion(),
                    modelType != null ? modelType : "unknown",
                    ctxLen != null ? ctxLen.toString() : "unknown");
        }
        System.out.println();
    }
}
