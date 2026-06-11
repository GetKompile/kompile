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

package ai.kompile.process.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the default methods on {@link StepExecutionDispatcher}.
 *
 * <p>Each test uses a minimal anonymous implementation that throws
 * {@link UnsupportedOperationException} for abstract methods not under test,
 * so failures in unrelated abstract methods never mask a default-method bug.</p>
 */
class StepExecutionDispatcherTest {

    // ---------------------------------------------------------------------------
    // Minimal anonymous implementation helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns a dispatcher whose {@code executeExcel} returns the supplied map
     * and whose remaining abstract methods throw {@link UnsupportedOperationException}.
     */
    private StepExecutionDispatcher dispatcherWithExcelResult(Map<String, Object> excelResult) {
        return new StepExecutionDispatcher() {
            @Override
            public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeHttpCall(String method, String url,
                                                        Map<String, String> headers, Object body) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeScript(String language, String scriptBody,
                                                      Map<String, Object> runData) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> convertExcel(String spreadsheetGraphJson,
                                                     String targetLanguage) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeExcel(String spreadsheetGraphJson,
                                                     Map<String, Object> cellOverrides,
                                                     String targetLanguage,
                                                     String generatedCode) {
                return excelResult;
            }

            @Override
            public List<Map<String, Object>> listAvailableTools() {
                throw new UnsupportedOperationException("not under test");
            }
        };
    }

    /**
     * Returns a dispatcher whose {@code invokeTool} returns the supplied map and
     * captures the last call's arguments into the provided holder arrays.
     * All other abstract methods throw {@link UnsupportedOperationException}.
     */
    private StepExecutionDispatcher dispatcherCapturingToolCall(Map<String, Object> toolResult,
                                                                  String[] capturedName,
                                                                  Map<String, Object>[] capturedArgs) {
        return new StepExecutionDispatcher() {
            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
                capturedName[0] = toolName;
                capturedArgs[0] = arguments;
                return toolResult;
            }

            @Override
            public Map<String, Object> executeHttpCall(String method, String url,
                                                        Map<String, String> headers, Object body) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeScript(String language, String scriptBody,
                                                      Map<String, Object> runData) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> convertExcel(String spreadsheetGraphJson,
                                                     String targetLanguage) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeExcel(String spreadsheetGraphJson,
                                                     Map<String, Object> cellOverrides,
                                                     String targetLanguage,
                                                     String generatedCode) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public List<Map<String, Object>> listAvailableTools() {
                throw new UnsupportedOperationException("not under test");
            }
        };
    }

    /**
     * Returns a dispatcher whose {@code executeExcel} captures all four arguments
     * into the provided single-element holder arrays so tests can verify forwarding.
     */
    private StepExecutionDispatcher dispatcherCapturingExcelCall(Map<String, Object> result,
                                                                   String[] capturedGraph,
                                                                   Map<String, Object>[] capturedOverrides,
                                                                   String[] capturedLang,
                                                                   String[] capturedCode) {
        return new StepExecutionDispatcher() {
            @Override
            public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeHttpCall(String method, String url,
                                                        Map<String, String> headers, Object body) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeScript(String language, String scriptBody,
                                                      Map<String, Object> runData) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> convertExcel(String spreadsheetGraphJson,
                                                     String targetLanguage) {
                throw new UnsupportedOperationException("not under test");
            }

            @Override
            public Map<String, Object> executeExcel(String spreadsheetGraphJson,
                                                     Map<String, Object> cellOverrides,
                                                     String targetLanguage,
                                                     String generatedCode) {
                capturedGraph[0]     = spreadsheetGraphJson;
                capturedOverrides[0] = cellOverrides;
                capturedLang[0]      = targetLanguage;
                capturedCode[0]      = generatedCode;
                return result;
            }

            @Override
            public List<Map<String, Object>> listAvailableTools() {
                throw new UnsupportedOperationException("not under test");
            }
        };
    }

    // ---------------------------------------------------------------------------
    // Tests for resolveExcelGraphJson
    // ---------------------------------------------------------------------------

    @Test
    void resolveExcelGraphJson_defaultReturnsNull() {
        StepExecutionDispatcher dispatcher = dispatcherWithExcelResult(Map.of());

        String result = dispatcher.resolveExcelGraphJson(List.of("node-1", "node-2"));

        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------------------
    // Tests for executeExcelWithResult
    // ---------------------------------------------------------------------------

    @Test
    void executeExcelWithResult_delegatesToExecuteExcel() {
        Map<String, Object> excelOutputs = Map.of("A1", 42.0, "B2", "hello");
        StepExecutionDispatcher dispatcher = dispatcherWithExcelResult(excelOutputs);

        DispatchResult result = dispatcher.executeExcelWithResult("{}", Map.of(), "javascript", null);

        assertThat(result.getOutputs()).isEqualTo(excelOutputs);
        assertThat(result.hasDiscoveredNodeIds()).isFalse();
        assertThat(result.getDiscoveredGraphNodeIds()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeExcelWithResult_passesAllParameters() {
        String expectedGraph    = "{\"cells\":[]}";
        Map<String, Object> expectedOverrides = Map.of("A1", 99.0, "B1", "override");
        String expectedLang     = "python";
        String expectedCode     = "def compute(): return {}";

        String[]              capturedGraph     = new String[1];
        Map<String, Object>[] capturedOverrides = new Map[1];
        String[]              capturedLang      = new String[1];
        String[]              capturedCode      = new String[1];

        StepExecutionDispatcher dispatcher = dispatcherCapturingExcelCall(
                Map.of(), capturedGraph, capturedOverrides, capturedLang, capturedCode);

        dispatcher.executeExcelWithResult(expectedGraph, expectedOverrides, expectedLang, expectedCode);

        assertThat(capturedGraph[0]).isEqualTo(expectedGraph);
        assertThat(capturedOverrides[0]).isEqualTo(expectedOverrides);
        assertThat(capturedLang[0]).isEqualTo(expectedLang);
        assertThat(capturedCode[0]).isEqualTo(expectedCode);
    }

    @Test
    void executeExcelWithResult_nullOutputsHandledGracefully() {
        // executeExcel returns null — DispatchResult.of(null) must produce an empty map
        StepExecutionDispatcher dispatcher = dispatcherWithExcelResult(null);

        DispatchResult result = dispatcher.executeExcelWithResult("{}", Map.of(), "javascript", null);

        assertThat(result.getOutputs()).isNotNull().isEmpty();
        assertThat(result.hasDiscoveredNodeIds()).isFalse();
    }

    // ---------------------------------------------------------------------------
    // Tests for invokeToolWithResult
    // ---------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void invokeToolWithResult_delegatesToInvokeTool() {
        Map<String, Object> toolOutputs = Map.of("answer", "Paris", "confidence", 0.98);
        String[] capturedName = new String[1];
        Map<String, Object>[] capturedArgs = new Map[1];

        StepExecutionDispatcher dispatcher = dispatcherCapturingToolCall(
                toolOutputs, capturedName, capturedArgs);

        DispatchResult result = dispatcher.invokeToolWithResult("geo_lookup", Map.of("city", "Paris"));

        assertThat(result.getOutputs()).isEqualTo(toolOutputs);
        assertThat(result.hasDiscoveredNodeIds()).isFalse();
        assertThat(result.getDiscoveredGraphNodeIds()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeToolWithResult_passesToolNameAndArguments() {
        String expectedTool = "rag_query";
        Map<String, Object> expectedArgs = new HashMap<>();
        expectedArgs.put("query", "What is GraalVM?");
        expectedArgs.put("maxResults", 5);

        String[] capturedName = new String[1];
        Map<String, Object>[] capturedArgs = new Map[1];

        StepExecutionDispatcher dispatcher = dispatcherCapturingToolCall(
                Map.of("documents", List.of()), capturedName, capturedArgs);

        dispatcher.invokeToolWithResult(expectedTool, expectedArgs);

        assertThat(capturedName[0]).isEqualTo(expectedTool);
        assertThat(capturedArgs[0]).isEqualTo(expectedArgs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeToolWithResult_nullOutputsHandledGracefully() {
        // invokeTool returns null — DispatchResult.of(null) must produce an empty map
        String[] capturedName = new String[1];
        Map<String, Object>[] capturedArgs = new Map[1];

        StepExecutionDispatcher dispatcher = dispatcherCapturingToolCall(
                null, capturedName, capturedArgs);

        DispatchResult result = dispatcher.invokeToolWithResult("any_tool", Map.of());

        assertThat(result.getOutputs()).isNotNull().isEmpty();
        assertThat(result.hasDiscoveredNodeIds()).isFalse();
    }
}
