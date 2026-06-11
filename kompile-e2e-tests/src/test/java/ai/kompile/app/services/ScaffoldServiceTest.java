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

import ai.kompile.modelmanager.KompileModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ScaffoldService}.
 *
 * Full scaffold generation requires classpath template resources that are not present in the
 * unit-test classpath, so tests focus on the public API surface, ScaffoldRequest record, and
 * the platform validation guard that fires before any template processing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScaffoldServiceTest {

    @Mock
    private KompileModelManager modelManager;

    private ScaffoldService scaffoldService;

    @BeforeEach
    void setUp() {
        scaffoldService = new ScaffoldService(modelManager);
    }

    // ===== Constructor =====

    @Test
    void constructor_withNullModelManager_doesNotThrow() {
        assertThatCode(() -> new ScaffoldService(null)).doesNotThrowAnyException();
    }

    // ===== ScaffoldRequest record =====

    @Test
    void scaffoldRequest_fieldsAccessible() {
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                "ios", "MyApp", "ai.myapp", "smollm-135m", "local", true, false);

        assertThat(request.platform()).isEqualTo("ios");
        assertThat(request.projectName()).isEqualTo("MyApp");
        assertThat(request.packageName()).isEqualTo("ai.myapp");
        assertThat(request.modelId()).isEqualTo("smollm-135m");
        assertThat(request.inferenceMode()).isEqualTo("local");
        assertThat(request.includeModel()).isTrue();
        assertThat(request.includeSdk()).isFalse();
    }

    @Test
    void scaffoldRequest_allNullFields_acceptedByRecord() {
        // Record itself has no validation — ScaffoldService applies defaults
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                null, null, null, null, null, false, false);
        assertThat(request.platform()).isNull();
        assertThat(request.projectName()).isNull();
    }

    // ===== Platform validation =====

    @Test
    void generateScaffoldZip_unknownPlatform_throwsIllegalArgument() {
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                "windows", "MyApp", "ai.myapp", "smollm-135m", "local", false, false);

        assertThatThrownBy(() -> scaffoldService.generateScaffoldZip(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Platform must be 'ios' or 'android'");
    }

    @Test
    void generateScaffoldZip_emptyPlatform_throwsIllegalArgument() {
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                "", "App", "pkg", "model", "local", false, false);

        assertThatThrownBy(() -> scaffoldService.generateScaffoldZip(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateScaffoldZip_iosPlatform_generatesZip() throws Exception {
        // Templates exist in resources — generation should succeed
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                "ios", "MyApp", "ai.myapp", "smollm-135m", "local", false, false);

        java.nio.file.Path zip = scaffoldService.generateScaffoldZip(request);
        assertThat(zip).isNotNull();
        assertThat(zip.toString()).endsWith(".zip");
    }

    @Test
    void generateScaffoldZip_androidPlatform_generatesZip() throws Exception {
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                "android", "AndroidApp", "ai.android", "model", "local", false, false);

        java.nio.file.Path zip = scaffoldService.generateScaffoldZip(request);
        assertThat(zip).isNotNull();
        assertThat(zip.toString()).endsWith(".zip");
    }

    @Test
    void generateScaffoldZip_nullPlatform_defaultsToIos() throws Exception {
        // null platform → defaults to "ios" — generation should succeed
        ScaffoldService.ScaffoldRequest request = new ScaffoldService.ScaffoldRequest(
                null, "App", "pkg", null, null, false, false);

        java.nio.file.Path zip = scaffoldService.generateScaffoldZip(request);
        assertThat(zip).isNotNull();
    }

    // ===== ScaffoldRequest equality/hashCode =====

    @Test
    void scaffoldRequest_equalWhenSameFields() {
        ScaffoldService.ScaffoldRequest r1 = new ScaffoldService.ScaffoldRequest(
                "ios", "App", "pkg", "model", "local", false, false);
        ScaffoldService.ScaffoldRequest r2 = new ScaffoldService.ScaffoldRequest(
                "ios", "App", "pkg", "model", "local", false, false);
        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    @Test
    void scaffoldRequest_notEqualWhenDifferentPlatform() {
        ScaffoldService.ScaffoldRequest r1 = new ScaffoldService.ScaffoldRequest(
                "ios", "App", "pkg", "model", "local", false, false);
        ScaffoldService.ScaffoldRequest r2 = new ScaffoldService.ScaffoldRequest(
                "android", "App", "pkg", "model", "local", false, false);
        assertThat(r1).isNotEqualTo(r2);
    }
}
