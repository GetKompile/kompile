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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a platform-multiplied SDK artifact. Unlike ModelDescriptor (which is
 * platform-independent), an SDK has N platform artifacts (e.g., iOS .xcframework,
 * Android .aar, desktop .zip).
 */
public class SdkDescriptor {

    private final String sdkId;
    private final String version;
    private final String baseDownloadUrl;
    private final Map<String, PlatformArtifact> platformArtifacts;

    public SdkDescriptor(String sdkId, String version, String baseDownloadUrl,
                         Map<String, PlatformArtifact> platformArtifacts) {
        this.sdkId = Objects.requireNonNull(sdkId, "sdkId cannot be null");
        this.version = Objects.requireNonNull(version, "version cannot be null");
        this.baseDownloadUrl = Objects.requireNonNull(baseDownloadUrl, "baseDownloadUrl cannot be null");
        this.platformArtifacts = platformArtifacts == null ? Collections.emptyMap() : platformArtifacts;
    }

    public String getSdkId() {
        return sdkId;
    }

    public String getVersion() {
        return version;
    }

    public String getBaseDownloadUrl() {
        return baseDownloadUrl;
    }

    public Map<String, PlatformArtifact> getPlatformArtifacts() {
        return platformArtifacts;
    }

    /**
     * Get the artifact for a specific platform classifier (e.g., "ios-arm64", "android-arm64-nnapi").
     */
    public PlatformArtifact getArtifact(String platformClassifier) {
        return platformArtifacts.get(platformClassifier);
    }

    /**
     * Check if an artifact exists for the given platform classifier.
     */
    public boolean hasPlatform(String platformClassifier) {
        return platformArtifacts.containsKey(platformClassifier);
    }

    @Override
    public String toString() {
        return "SdkDescriptor{sdkId='" + sdkId + "', version='" + version + "', platforms=" + platformArtifacts.size() + "}";
    }

    /**
     * A single platform-specific artifact within an SDK.
     */
    public static class PlatformArtifact {
        private final String platform;
        private final String artifactFileName;
        private final String packaging; // zip, aar, xcframework
        private final String downloadUrl;
        private final String checksum;

        public PlatformArtifact(String platform, String artifactFileName, String packaging,
                                String downloadUrl, String checksum) {
            this.platform = Objects.requireNonNull(platform);
            this.artifactFileName = Objects.requireNonNull(artifactFileName);
            this.packaging = Objects.requireNonNull(packaging);
            this.downloadUrl = Objects.requireNonNull(downloadUrl);
            this.checksum = checksum;
        }

        public String getPlatform() {
            return platform;
        }

        public String getArtifactFileName() {
            return artifactFileName;
        }

        public String getPackaging() {
            return packaging;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getChecksum() {
            return checksum;
        }

        @Override
        public String toString() {
            return "PlatformArtifact{platform='" + platform + "', file='" + artifactFileName +
                    "', packaging='" + packaging + "'}";
        }
    }
}
