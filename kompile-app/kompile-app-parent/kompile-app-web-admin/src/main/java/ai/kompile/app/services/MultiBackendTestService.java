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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test service for validating multi-backend (CPU/GPU) execution.
 * Verifies CPU fallback, op delegation, transfer metrics, HybridDataBuffer ownership,
 * and numerical accuracy across devices.
 */
@Service
public class MultiBackendTestService {

    private static final Logger log = LoggerFactory.getLogger(MultiBackendTestService.class);
    private static final String DISABLED_MESSAGE =
            "Multi-backend ND4J validation is disabled in app-main; run validation through a managed subprocess";

    public record TestResult(String testName, boolean passed, long durationMs,
                              String details, String error) {}

    /**
     * Run all multi-backend validation tests.
     */
    public List<TestResult> runAllTests() {
        return List.of(
                disabled("executioner_type"),
                disabled("cpu_fallback"),
                disabled("basic_matmul"),
                disabled("cross_device_accuracy"),
                disabled("memory_allocation")
        );
    }

    /**
     * Test that the executioner is properly installed.
     */
    public TestResult testExecutionerType() {
        return disabled("executioner_type");
    }

    /**
     * Test CPU fallback: create a tensor, execute an op, verify result.
     */
    public TestResult testCpuFallback() {
        return disabled("cpu_fallback");
    }

    /**
     * Test basic matrix multiplication correctness.
     */
    public TestResult testBasicMatmul() {
        return disabled("basic_matmul");
    }

    /**
     * Test cross-device numerical accuracy: compare results across execution paths.
     */
    public TestResult testCrossDeviceAccuracy() {
        return disabled("cross_device_accuracy");
    }

    /**
     * Test memory allocation and deallocation.
     */
    public TestResult testMemoryAllocation() {
        return disabled("memory_allocation");
    }

    /**
     * Get status summary of multi-backend configuration.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("disabled", true);
        status.put("message", DISABLED_MESSAGE);
        return status;
    }

    private TestResult disabled(String testName) {
        log.debug("Refusing main-process ND4J validation test: {}", testName);
        return new TestResult(testName, false, 0, null, DISABLED_MESSAGE);
    }
}
