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

package ai.kompile.modelmanager;

import java.util.*;

/**
 * Platform classifiers and SDK constants for the SDX Runtime.
 *
 * Classifier strings follow the DL4J/nd4j-native-platform convention:
 * base platform optionally followed by a chip/feature suffix.
 *
 * Reference:
 *   - nd4j/nd4j-backends/nd4j-backend-impls/nd4j-native-platform/pom.xml
 *   - .github/workflows/build-deploy-android-arm64.yml
 *   - .github/actions/publish-sdx-runtime-sdk/action.yml
 */
public class SdkConstants {

    public static final String ENV_SDX_SDK_BASE_URL = "KOMPILE_SDX_SDK_BASE_URL";
    public static final String PROP_SDX_SDK_BASE_URL = "kompile.sdx.sdk.base-url";
    public static final String DEFAULT_SDX_SDK_BASE_URL = "https://github.com/deeplearning4j/deeplearning4j/releases/download/";
    public static final String DEFAULT_SDX_SDK_VERSION = "1.0.0-SNAPSHOT";

    // ==================== Base Platform Classifiers ====================

    public static final String LINUX_X86_64 = "linux-x86_64";
    public static final String LINUX_ARM64 = "linux-arm64";
    public static final String LINUX_ARMHF = "linux-armhf";
    public static final String MACOSX_X86_64 = "macosx-x86_64";
    public static final String MACOSX_ARM64 = "macosx-arm64";
    public static final String WINDOWS_X86_64 = "windows-x86_64";
    public static final String ANDROID_ARM = "android-arm";
    public static final String ANDROID_ARM64 = "android-arm64";
    public static final String ANDROID_X86 = "android-x86";
    public static final String ANDROID_X86_64 = "android-x86_64";
    public static final String IOS_ARM64 = "ios-arm64";
    public static final String IOS_X86_64 = "ios-x86_64";
    public static final String IOS_SIMULATOR_ARM64 = "ios-simulator-arm64";
    public static final String IOS_SIMULATOR_X86_64 = "ios-simulator-x86_64";

    // ==================== Chip/Feature Suffixes ====================

    public static final String SUFFIX_AVX2 = "avx2";
    public static final String SUFFIX_AVX512 = "avx512";
    public static final String SUFFIX_ONEDNN = "onednn";
    public static final String SUFFIX_COMPILE = "compile";
    public static final String SUFFIX_COMPILE_NNAPI = "compile-nnapi";
    public static final String SUFFIX_NNAPI = "nnapi";
    public static final String SUFFIX_ARMCOMPUTE = "armcompute";

    // ==================== Platform Groups ====================

    public static final List<String> ALL_BASE_PLATFORMS = List.of(
            LINUX_X86_64, LINUX_ARM64, LINUX_ARMHF,
            MACOSX_X86_64, MACOSX_ARM64,
            WINDOWS_X86_64,
            ANDROID_ARM, ANDROID_ARM64, ANDROID_X86, ANDROID_X86_64,
            IOS_ARM64, IOS_X86_64,
            IOS_SIMULATOR_ARM64, IOS_SIMULATOR_X86_64
    );

    public static final List<String> MOBILE_PLATFORMS = List.of(
            ANDROID_ARM, ANDROID_ARM64, ANDROID_X86, ANDROID_X86_64,
            IOS_ARM64, IOS_X86_64,
            IOS_SIMULATOR_ARM64, IOS_SIMULATOR_X86_64
    );

    public static final List<String> IOS_PLATFORMS = List.of(
            IOS_ARM64, IOS_X86_64,
            IOS_SIMULATOR_ARM64, IOS_SIMULATOR_X86_64
    );

    public static final List<String> ANDROID_PLATFORMS = List.of(
            ANDROID_ARM, ANDROID_ARM64, ANDROID_X86, ANDROID_X86_64
    );

    public static final List<String> DESKTOP_PLATFORMS = List.of(
            LINUX_X86_64, LINUX_ARM64, LINUX_ARMHF,
            MACOSX_X86_64, MACOSX_ARM64,
            WINDOWS_X86_64
    );

    // Maps base platform -> valid chip/feature suffixes
    private static final Map<String, List<String>> EXTENDED_CLASSIFIERS;

    static {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(LINUX_X86_64, List.of(SUFFIX_AVX2, SUFFIX_AVX512, SUFFIX_ONEDNN, SUFFIX_COMPILE));
        map.put(MACOSX_X86_64, List.of(SUFFIX_AVX2, SUFFIX_ONEDNN));
        map.put(MACOSX_ARM64, List.of(SUFFIX_COMPILE));
        map.put(WINDOWS_X86_64, List.of(SUFFIX_AVX2, SUFFIX_AVX512, SUFFIX_ONEDNN));
        map.put(ANDROID_ARM64, List.of(SUFFIX_NNAPI, SUFFIX_ARMCOMPUTE, SUFFIX_COMPILE, SUFFIX_COMPILE_NNAPI));
        map.put(ANDROID_X86_64, List.of(SUFFIX_ONEDNN, SUFFIX_COMPILE, SUFFIX_COMPILE_NNAPI));
        EXTENDED_CLASSIFIERS = Collections.unmodifiableMap(map);
    }

    /**
     * Returns valid chip/feature suffixes for the given base platform.
     */
    public static List<String> getExtendedClassifiers(String basePlatform) {
        return EXTENDED_CLASSIFIERS.getOrDefault(basePlatform, Collections.emptyList());
    }

    /**
     * Builds a full classifier string from a base platform and optional chip feature.
     * e.g., resolveClassifier("android-arm64", "nnapi") → "android-arm64-nnapi"
     *       resolveClassifier("ios-arm64", null) → "ios-arm64"
     */
    public static String resolveClassifier(String basePlatform, String chipFeature) {
        if (chipFeature == null || chipFeature.isEmpty()) {
            return basePlatform;
        }
        return basePlatform + "-" + chipFeature;
    }

    /**
     * Determines the packaging type for a given platform classifier.
     */
    public static String getPackagingForPlatform(String classifier) {
        if (classifier.startsWith("ios")) {
            return "xcframework";
        } else if (classifier.startsWith("android")) {
            return "aar";
        } else {
            return "zip";
        }
    }

    /**
     * Determines the artifact file name for a platform classifier and SDK version.
     */
    public static String getArtifactFileName(String sdkId, String classifier) {
        String packaging = getPackagingForPlatform(classifier);
        String suffix;
        if ("xcframework".equals(packaging)) {
            suffix = ".xcframework.zip";
        } else if ("aar".equals(packaging)) {
            suffix = ".aar";
        } else {
            suffix = ".zip";
        }
        return sdkId + "-" + classifier + suffix;
    }

    /**
     * Resolves the effective base URL from environment variable, system property, or default.
     */
    public static String resolveBaseUrl() {
        String envUrl = System.getenv(ENV_SDX_SDK_BASE_URL);
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            return envUrl.trim();
        }
        String propUrl = System.getProperty(PROP_SDX_SDK_BASE_URL);
        if (propUrl != null && !propUrl.trim().isEmpty()) {
            return propUrl.trim();
        }
        return DEFAULT_SDX_SDK_BASE_URL;
    }

    /**
     * Creates an SdkDescriptor for the SDX Runtime with all known platform artifacts.
     */
    public static SdkDescriptor createSdxRuntimeDescriptor(String version, String baseUrl) {
        if (version == null) version = DEFAULT_SDX_SDK_VERSION;
        if (baseUrl == null) baseUrl = resolveBaseUrl();

        String sdkId = "sdx-runtime";
        String versionUrl = baseUrl.endsWith("/") ? baseUrl + "sdx-v" + version + "/" : baseUrl + "/sdx-v" + version + "/";

        Map<String, SdkDescriptor.PlatformArtifact> artifacts = new LinkedHashMap<>();

        // Add base platform artifacts
        for (String platform : ALL_BASE_PLATFORMS) {
            addArtifact(artifacts, sdkId, platform, versionUrl);
        }

        // Add extended classifier artifacts
        for (Map.Entry<String, List<String>> entry : EXTENDED_CLASSIFIERS.entrySet()) {
            String basePlatform = entry.getKey();
            for (String suffix : entry.getValue()) {
                String classifier = resolveClassifier(basePlatform, suffix);
                addArtifact(artifacts, sdkId, classifier, versionUrl);
            }
        }

        return new SdkDescriptor(sdkId, version, versionUrl, artifacts);
    }

    private static void addArtifact(Map<String, SdkDescriptor.PlatformArtifact> artifacts,
                                    String sdkId, String classifier, String baseUrl) {
        String fileName = getArtifactFileName(sdkId, classifier);
        String downloadUrl = baseUrl + fileName;
        String packaging = getPackagingForPlatform(classifier);
        artifacts.put(classifier, new SdkDescriptor.PlatformArtifact(
                classifier, fileName, packaging, downloadUrl, null));
    }

    /**
     * Returns a default SDZ model bundle descriptor for a well-known model.
     */
    public static ModelDescriptor getSdzBundleDescriptor(String modelId) {
        // Placeholder — real URLs will come from registry.json or be configured by the user
        switch (modelId) {
            case "smollm-135m":
                return new ModelDescriptor(
                        "smollm-135m", ModelType.SDX_MODEL_BUNDLE,
                        DEFAULT_SDX_SDK_BASE_URL + "sdz-v1.0/smollm-135m.sdz",
                        "sdz-bundles/smollm-135m/smollm-135m.sdz",
                        "1.0", null,
                        Map.of("model_type", "causal_lm", "context_length", 2048)
                );
            default:
                return null;
        }
    }
}
