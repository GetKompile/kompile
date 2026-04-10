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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.MultiBackendTestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/multi-backend")
public class MultiBackendTestController {

    private final MultiBackendTestService testService;

    public MultiBackendTestController(MultiBackendTestService testService) {
        this.testService = testService;
    }

    @PostMapping("/tests/run")
    public ResponseEntity<Map<String, Object>> runTests() {
        List<MultiBackendTestService.TestResult> results = testService.runAllTests();
        long passed = results.stream().filter(MultiBackendTestService.TestResult::passed).count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTests", results.size());
        response.put("passed", passed);
        response.put("failed", results.size() - passed);
        response.put("results", results);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tests/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(testService.getStatus());
    }

    @PostMapping("/tests/cpu-fallback")
    public ResponseEntity<MultiBackendTestService.TestResult> testCpuFallback() {
        return ResponseEntity.ok(testService.testCpuFallback());
    }

    @PostMapping("/tests/matmul")
    public ResponseEntity<MultiBackendTestService.TestResult> testMatmul() {
        return ResponseEntity.ok(testService.testBasicMatmul());
    }

    @PostMapping("/tests/accuracy")
    public ResponseEntity<MultiBackendTestService.TestResult> testAccuracy() {
        return ResponseEntity.ok(testService.testCrossDeviceAccuracy());
    }
}
