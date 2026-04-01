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

import java.util.HashMap;
import java.util.Map;

/**
 * Data class containing all substitution variables for mobile project templates.
 */
public class TemplateContext {
    private String projectName;
    private String packageName;
    private String packagePath;
    private String modelId;
    private String modelFileName;
    private String sdkVersion;
    private String inferenceMode; // local, hybrid, remote
    private String apiKeyPlaceholder;
    private String platform; // ios, android

    // Android-specific
    private String gradleVersion = "8.2";
    private String agpVersion = "8.2.2";
    private String kotlinVersion = "1.9.22";
    private String composeBomVersion = "2024.02.00";
    private String roomVersion = "2.6.1";
    private String minSdk = "26";
    private String targetSdk = "34";
    private String compileSdk = "34";

    // iOS-specific
    private String iosDeploymentTarget = "16.0";
    private String swiftToolsVersion = "5.9";

    public TemplateContext() {}

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) {
        this.packageName = packageName;
        this.packagePath = packageName.replace('.', '/');
    }

    public String getPackagePath() { return packagePath; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getModelFileName() { return modelFileName; }
    public void setModelFileName(String modelFileName) { this.modelFileName = modelFileName; }

    public String getSdkVersion() { return sdkVersion; }
    public void setSdkVersion(String sdkVersion) { this.sdkVersion = sdkVersion; }

    public String getInferenceMode() { return inferenceMode; }
    public void setInferenceMode(String inferenceMode) { this.inferenceMode = inferenceMode; }

    public String getApiKeyPlaceholder() { return apiKeyPlaceholder; }
    public void setApiKeyPlaceholder(String apiKeyPlaceholder) { this.apiKeyPlaceholder = apiKeyPlaceholder; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getGradleVersion() { return gradleVersion; }
    public void setGradleVersion(String gradleVersion) { this.gradleVersion = gradleVersion; }

    public String getAgpVersion() { return agpVersion; }
    public void setAgpVersion(String agpVersion) { this.agpVersion = agpVersion; }

    public String getKotlinVersion() { return kotlinVersion; }
    public void setKotlinVersion(String kotlinVersion) { this.kotlinVersion = kotlinVersion; }

    public String getComposeBomVersion() { return composeBomVersion; }
    public void setComposeBomVersion(String composeBomVersion) { this.composeBomVersion = composeBomVersion; }

    public String getRoomVersion() { return roomVersion; }
    public void setRoomVersion(String roomVersion) { this.roomVersion = roomVersion; }

    public String getMinSdk() { return minSdk; }
    public void setMinSdk(String minSdk) { this.minSdk = minSdk; }

    public String getTargetSdk() { return targetSdk; }
    public void setTargetSdk(String targetSdk) { this.targetSdk = targetSdk; }

    public String getCompileSdk() { return compileSdk; }
    public void setCompileSdk(String compileSdk) { this.compileSdk = compileSdk; }

    public String getIosDeploymentTarget() { return iosDeploymentTarget; }
    public void setIosDeploymentTarget(String iosDeploymentTarget) { this.iosDeploymentTarget = iosDeploymentTarget; }

    public String getSwiftToolsVersion() { return swiftToolsVersion; }
    public void setSwiftToolsVersion(String swiftToolsVersion) { this.swiftToolsVersion = swiftToolsVersion; }

    /**
     * Converts this context to a map of variable name -> value for template substitution.
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("projectName", projectName != null ? projectName : "KompileChat");
        map.put("packageName", packageName != null ? packageName : "ai.kompile.chat");
        map.put("packagePath", packagePath != null ? packagePath : "ai/kompile/chat");
        map.put("modelId", modelId != null ? modelId : "smollm-135m");
        map.put("modelFileName", modelFileName != null ? modelFileName : "smollm-135m.sdz");
        map.put("sdkVersion", sdkVersion != null ? sdkVersion : "1.0.0");
        map.put("inferenceMode", inferenceMode != null ? inferenceMode : "local");
        map.put("apiKeyPlaceholder", apiKeyPlaceholder != null ? apiKeyPlaceholder : "YOUR_API_KEY_HERE");
        map.put("platform", platform != null ? platform : "ios");
        map.put("gradleVersion", gradleVersion);
        map.put("agpVersion", agpVersion);
        map.put("kotlinVersion", kotlinVersion);
        map.put("composeBomVersion", composeBomVersion);
        map.put("roomVersion", roomVersion);
        map.put("minSdk", minSdk);
        map.put("targetSdk", targetSdk);
        map.put("compileSdk", compileSdk);
        map.put("iosDeploymentTarget", iosDeploymentTarget);
        map.put("swiftToolsVersion", swiftToolsVersion);
        return map;
    }
}
