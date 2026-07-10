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

package ai.kompile.staging.web;

import ai.kompile.staging.compiler.CompilerService;
import ai.kompile.staging.web.dto.QuantizationComparisonRequest;
import ai.kompile.staging.web.dto.QuantizationComparisonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompilerControllerTest {

    @Mock
    private CompilerService compilerService;

    private CompilerController controller;

    @BeforeEach
    void setUp() {
        controller = new CompilerController(compilerService);
    }

    @Test
    void compareQuantizationVariantsDelegatesValidRequest() {
        QuantizationComparisonRequest request = QuantizationComparisonRequest.builder()
                .baseModelId("qwen-base")
                .variants(List.of(QuantizationComparisonRequest.Variant.builder()
                        .modelId("qwen-q4")
                        .quantizationType("Q4_K_M")
                        .build()))
                .build();
        QuantizationComparisonResponse expected = QuantizationComparisonResponse.builder()
                .success(true)
                .baseModelId("qwen-base")
                .bestSizeReductionModelId("qwen-q4")
                .build();
        when(compilerService.compareQuantizationVariants(request)).thenReturn(expected);

        ResponseEntity<QuantizationComparisonResponse> response = controller.compareQuantizationVariants(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
        verify(compilerService).compareQuantizationVariants(request);
    }

    @Test
    void compareQuantizationVariantsRejectsMissingBaseline() {
        QuantizationComparisonRequest request = QuantizationComparisonRequest.builder()
                .variants(List.of(QuantizationComparisonRequest.Variant.builder()
                        .modelId("qwen-q4")
                        .build()))
                .build();

        ResponseEntity<QuantizationComparisonResponse> response = controller.compareQuantizationVariants(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().isSuccess());
        assertEquals("baseModelId is required", response.getBody().getError());
    }

    @Test
    void compareQuantizationVariantsRejectsEmptyVariants() {
        QuantizationComparisonRequest request = QuantizationComparisonRequest.builder()
                .baseModelId("qwen-base")
                .variants(List.of())
                .build();

        ResponseEntity<QuantizationComparisonResponse> response = controller.compareQuantizationVariants(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().isSuccess());
        assertEquals("At least one quantization variant is required", response.getBody().getError());
    }
}
