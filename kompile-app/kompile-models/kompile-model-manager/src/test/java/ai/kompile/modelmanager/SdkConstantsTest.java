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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SdkConstantsTest {

    @Test
    void createKompileReasoningDescriptor_sdkId() {
        SdkDescriptor d = SdkConstants.createKompileReasoningDescriptor(null, null);
        assertEquals("kompile-reasoning", d.getSdkId(),
                "sdkId must be 'kompile-reasoning'");
    }

    @Test
    void createKompileReasoningDescriptor_hasLinuxX86_64() {
        SdkDescriptor d = SdkConstants.createKompileReasoningDescriptor("0.1.0", null);
        assertTrue(d.hasPlatform(SdkConstants.LINUX_X86_64),
                "must have linux-x86_64 artifact");
        SdkDescriptor.PlatformArtifact art = d.getArtifact(SdkConstants.LINUX_X86_64);
        assertNotNull(art);
        assertEquals("zip", art.getPackaging());
        assertTrue(art.getDownloadUrl().contains("kgr-v0.1.0"),
                "download URL must contain kgr-v0.1.0 tag");
        assertTrue(art.getDownloadUrl().contains("kompile-reasoning-linux-x86_64.zip"),
                "download URL must contain correct artifact filename");
    }

    @Test
    void createKompileReasoningDescriptor_hasIosArm64Placeholder() {
        SdkDescriptor d = SdkConstants.createKompileReasoningDescriptor("0.1.0", null);
        assertTrue(d.hasPlatform(SdkConstants.IOS_ARM64),
                "must have ios-arm64 placeholder artifact");
        SdkDescriptor.PlatformArtifact art = d.getArtifact(SdkConstants.IOS_ARM64);
        assertNotNull(art);
        assertEquals("xcframework", art.getPackaging(),
                "ios-arm64 must use xcframework packaging");
    }

    @Test
    void createKompileReasoningDescriptor_hasAndroidArm64Placeholder() {
        SdkDescriptor d = SdkConstants.createKompileReasoningDescriptor("0.1.0", null);
        assertTrue(d.hasPlatform(SdkConstants.ANDROID_ARM64),
                "must have android-arm64 placeholder artifact");
        SdkDescriptor.PlatformArtifact art = d.getArtifact(SdkConstants.ANDROID_ARM64);
        assertNotNull(art);
        assertEquals("aar", art.getPackaging(),
                "android-arm64 must use aar packaging");
    }

    @Test
    void createKompileReasoningDescriptor_urlContainsKgrTag() {
        SdkDescriptor d = SdkConstants.createKompileReasoningDescriptor("1.2.3", null);
        SdkDescriptor.PlatformArtifact art = d.getArtifact(SdkConstants.LINUX_X86_64);
        assertNotNull(art);
        assertTrue(art.getDownloadUrl().contains("kgr-v"),
                "URL must contain 'kgr-v' tag family prefix");
    }

    @Test
    void createKompileLocalSdkDescriptor_sdkId() {
        SdkDescriptor d = SdkConstants.createKompileLocalSdkDescriptor(null, null);
        assertEquals("kompile-local-sdk", d.getSdkId(),
                "sdkId must be 'kompile-local-sdk'");
    }

    @Test
    void createKompileLocalSdkDescriptor_hasLinuxX86_64() {
        SdkDescriptor d = SdkConstants.createKompileLocalSdkDescriptor("0.1.0", null);
        assertTrue(d.hasPlatform(SdkConstants.LINUX_X86_64),
                "must have linux-x86_64 artifact");
        SdkDescriptor.PlatformArtifact art = d.getArtifact(SdkConstants.LINUX_X86_64);
        assertNotNull(art);
        assertEquals("zip", art.getPackaging());
        assertTrue(art.getDownloadUrl().contains("kompile-local-sdk-v0.1.0"),
                "download URL must contain kompile-local-sdk-v0.1.0 tag");
        assertTrue(art.getDownloadUrl().contains("kompile-local-sdk-linux-x86_64.zip"),
                "download URL must contain correct artifact filename");
    }

    @Test
    void createSdxRuntimeDescriptor_regressionStillWorks() {
        SdkDescriptor d = SdkConstants.createSdxRuntimeDescriptor(null, null);
        assertEquals("sdx-runtime", d.getSdkId());
        assertTrue(d.hasPlatform(SdkConstants.LINUX_X86_64),
                "sdx-runtime must still have linux-x86_64");
        assertTrue(d.hasPlatform(SdkConstants.IOS_ARM64),
                "sdx-runtime must still have ios-arm64");
        assertTrue(d.hasPlatform(SdkConstants.ANDROID_ARM64),
                "sdx-runtime must still have android-arm64");
    }

    @Test
    void resolveKgrBaseUrl_returnsDefaultWhenNoPropertySet() {
        // Remove any system property that might be set in test environment
        System.clearProperty(SdkConstants.PROP_KGR_SDK_BASE_URL);
        // If no env var is set, must be the default
        if (System.getenv(SdkConstants.ENV_KGR_SDK_BASE_URL) == null) {
            String url = SdkConstants.resolveKgrBaseUrl();
            assertEquals(SdkConstants.DEFAULT_KGR_SDK_BASE_URL, url,
                    "must return DEFAULT_KGR_SDK_BASE_URL when no env/property override");
        }
    }

    @Test
    void resolveKgrBaseUrl_respectsSystemProperty() {
        String override = "https://my.mirror.example.com/releases/";
        System.setProperty(SdkConstants.PROP_KGR_SDK_BASE_URL, override);
        try {
            // Only apply if env var not set (env var takes precedence)
            if (System.getenv(SdkConstants.ENV_KGR_SDK_BASE_URL) == null) {
                assertEquals(override, SdkConstants.resolveKgrBaseUrl(),
                        "must return system property override when env var not set");
            }
        } finally {
            System.clearProperty(SdkConstants.PROP_KGR_SDK_BASE_URL);
        }
    }
}
