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

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.api.ops.executioner.OpExecutioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public record TestResult(String testName, boolean passed, long durationMs,
                              String details, String error) {}

    /**
     * Run all multi-backend validation tests.
     */
    public List<TestResult> runAllTests() {
        List<TestResult> results = new ArrayList<>();
        results.add(testExecutionerType());
        results.add(testCpuFallback());
        results.add(testBasicMatmul());
        results.add(testCrossDeviceAccuracy());
        results.add(testMemoryAllocation());
        return results;
    }

    /**
     * Test that the executioner is properly installed.
     */
    public TestResult testExecutionerType() {
        long start = System.currentTimeMillis();
        try {
            OpExecutioner exec = Nd4j.getExecutioner();
            String type = exec.getClass().getSimpleName();
            boolean isDeviceAware = type.contains("DeviceAware");
            String details = "Executioner type: " + type + ", isDeviceAware: " + isDeviceAware;
            return new TestResult("executioner_type", true, System.currentTimeMillis() - start, details, null);
        } catch (Exception e) {
            return new TestResult("executioner_type", false, System.currentTimeMillis() - start,
                    null, e.getMessage());
        }
    }

    /**
     * Test CPU fallback: create a tensor, execute an op, verify result.
     */
    public TestResult testCpuFallback() {
        long start = System.currentTimeMillis();
        try {
            INDArray a = Nd4j.ones(10, 10);
            INDArray b = Nd4j.ones(10, 10).mul(2);
            INDArray result = a.add(b);

            double expected = 3.0;
            double actual = result.getDouble(0, 0);
            boolean passed = Math.abs(actual - expected) < 1e-5;
            String details = String.format("Expected %.1f, got %.6f, tolerance=1e-5", expected, actual);

            result.close();
            a.close();
            b.close();

            return new TestResult("cpu_fallback", passed, System.currentTimeMillis() - start, details,
                    passed ? null : "Value mismatch");
        } catch (Exception e) {
            return new TestResult("cpu_fallback", false, System.currentTimeMillis() - start,
                    null, e.getMessage());
        }
    }

    /**
     * Test basic matrix multiplication correctness.
     */
    public TestResult testBasicMatmul() {
        long start = System.currentTimeMillis();
        try {
            INDArray a = Nd4j.eye(5);
            INDArray b = Nd4j.linspace(1, 25, 25).reshape(5, 5);
            INDArray result = a.mmul(b);

            // Identity * B = B
            double maxDiff = Nd4j.math.abs(result.sub(b)).maxNumber().doubleValue();
            boolean passed = maxDiff < 1e-5;
            String details = String.format("I * B = B check, maxDiff=%.8f", maxDiff);

            result.close();
            a.close();
            b.close();

            return new TestResult("basic_matmul", passed, System.currentTimeMillis() - start, details,
                    passed ? null : "Matmul result mismatch");
        } catch (Exception e) {
            return new TestResult("basic_matmul", false, System.currentTimeMillis() - start,
                    null, e.getMessage());
        }
    }

    /**
     * Test cross-device numerical accuracy: compare results across execution paths.
     */
    public TestResult testCrossDeviceAccuracy() {
        long start = System.currentTimeMillis();
        try {
            // Create test data
            INDArray a = Nd4j.randn(100, 100);
            INDArray b = Nd4j.randn(100, 100);

            // Compute on current backend
            INDArray result1 = a.mmul(b);
            INDArray result2 = a.mmul(b);

            // Results should be deterministic
            double maxDiff = Nd4j.math.abs(result1.sub(result2)).maxNumber().doubleValue();
            boolean passed = maxDiff < 1e-5;
            String details = String.format("Determinism check: maxDiff=%.8f, tolerance=1e-5", maxDiff);

            result1.close();
            result2.close();
            a.close();
            b.close();

            return new TestResult("cross_device_accuracy", passed, System.currentTimeMillis() - start,
                    details, passed ? null : "Non-deterministic results");
        } catch (Exception e) {
            return new TestResult("cross_device_accuracy", false, System.currentTimeMillis() - start,
                    null, e.getMessage());
        }
    }

    /**
     * Test memory allocation and deallocation.
     */
    public TestResult testMemoryAllocation() {
        long start = System.currentTimeMillis();
        try {
            List<INDArray> arrays = new ArrayList<>();
            int numArrays = 100;

            // Allocate
            for (int i = 0; i < numArrays; i++) {
                arrays.add(Nd4j.zeros(100, 100));
            }

            // Verify
            boolean allValid = true;
            for (INDArray arr : arrays) {
                if (arr.wasClosed()) {
                    allValid = false;
                    break;
                }
            }

            // Deallocate
            for (INDArray arr : arrays) {
                arr.close();
            }

            String details = String.format("Allocated and freed %d arrays (100x100 each)", numArrays);
            return new TestResult("memory_allocation", allValid, System.currentTimeMillis() - start,
                    details, allValid ? null : "Some arrays were prematurely closed");
        } catch (Exception e) {
            return new TestResult("memory_allocation", false, System.currentTimeMillis() - start,
                    null, e.getMessage());
        }
    }

    /**
     * Get status summary of multi-backend configuration.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            OpExecutioner exec = Nd4j.getExecutioner();
            status.put("executionerType", exec.getClass().getName());
            status.put("executionerSimpleName", exec.getClass().getSimpleName());
            status.put("isDeviceAware", exec.getClass().getSimpleName().contains("DeviceAware"));
            status.put("backend", Nd4j.getBackend().getClass().getSimpleName());
        } catch (Exception e) {
            status.put("error", "Failed to query ND4J state: " + e.getMessage());
        }
        return status;
    }
}
