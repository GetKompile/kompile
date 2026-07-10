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

package ai.kompile.staging.compiler;

import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.web.dto.QuantizationComparisonRequest;
import ai.kompile.staging.web.dto.QuantizationComparisonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompilerServiceQuantizationComparisonTest {

    @TempDir
    Path tempDir;

    private CompilerService compilerService;

    @BeforeEach
    void setUp() throws Exception {
        compilerService = new CompilerService(new RegistryService(tempDir));
        Field modelsDir = CompilerService.class.getDeclaredField("modelsDir");
        modelsDir.setAccessible(true);
        modelsDir.set(compilerService, tempDir.toString());
    }

    @Test
    void compareQuantizationVariantsRejectsMissingBaseModelId() {
        QuantizationComparisonResponse response = compilerService.compareQuantizationVariants(
                QuantizationComparisonRequest.builder()
                        .variants(List.of(QuantizationComparisonRequest.Variant.builder()
                                .modelId("q4")
                                .build()))
                        .build());

        assertFalse(response.isSuccess());
        assertEquals("baseModelId is required", response.getError());
    }

    @Test
    void compareQuantizationVariantsRejectsEmptyVariants() {
        QuantizationComparisonResponse response = compilerService.compareQuantizationVariants(
                QuantizationComparisonRequest.builder()
                        .baseModelId("base")
                        .variants(List.of())
                        .build());

        assertFalse(response.isSuccess());
        assertEquals("At least one quantization variant is required", response.getError());
    }

    @Test
    void compareQuantizationVariantsReportsMissingModelFiles() {
        QuantizationComparisonResponse response = compilerService.compareQuantizationVariants(
                QuantizationComparisonRequest.builder()
                        .baseModelId("base-missing")
                        .variants(List.of(QuantizationComparisonRequest.Variant.builder()
                                .modelId("q4-missing")
                                .quantizationType("Q4_K_M")
                                .build()))
                        .build());

        assertFalse(response.isSuccess());
        assertNotNull(response.getBaseModel());
        assertFalse(response.getBaseModel().isSuccess());
        assertEquals("Model file not found: base-missing", response.getBaseModel().getError());
        assertEquals(1, response.getVariants().size());
        assertFalse(response.getVariants().get(0).isSuccess());
        assertEquals("Model file not found: q4-missing", response.getVariants().get(0).getError());
    }
}
