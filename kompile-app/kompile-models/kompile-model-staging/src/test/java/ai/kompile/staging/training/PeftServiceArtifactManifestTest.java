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

package ai.kompile.staging.training;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.staging.web.dto.LoraConfigDto;
import ai.kompile.staging.web.dto.PeftConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PeftServiceArtifactManifestTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    private PeftService peftService;

    @BeforeEach
    void setUp() throws Exception {
        peftService = new PeftService();
        Field modelsDir = PeftService.class.getDeclaredField("modelsDir");
        modelsDir.setAccessible(true);
        modelsDir.set(peftService, tempDir.toString());
    }

    @Test
    void createPeftModelMarksEstimationOutputsNonDeployable() {
        Map<String, Object> result = peftService.createPeftModel("missing-base", loraRequest());

        assertEquals("created", result.get("status"));
        assertEquals("missing-base-peft-lora", result.get("outputModelId"));
        assertEquals(false, result.get("deployable"));
        assertEquals(false, result.get("registryEligible"));
        assertFalse(result.containsKey("manifestPath"));
    }

    @Test
    void createPeftModelWritesDeployableTrainingArtifactManifest() throws Exception {
        String baseModelId = "base-lora";
        writeBaseModelOrSkip(baseModelId);

        Map<String, Object> result = peftService.createPeftModel(baseModelId, loraRequest());

        assertEquals("created", result.get("status"));
        assertEquals("base-lora-peft-lora", result.get("outputModelId"));
        assertEquals(true, result.get("deployable"));
        assertEquals(true, result.get("registryEligible"));
        assertEquals("base-lora-peft-lora.fb", result.get("modelFile"));

        Path manifestPath = Path.of((String) result.get("manifestPath"));
        assertTrue(Files.isRegularFile(manifestPath));
        assertTrue(Files.isRegularFile(Path.of((String) result.get("outputPath"))));

        Map<String, Object> manifest = readManifest(manifestPath);
        assertEquals("kompile.training-artifact.v1", manifest.get("schemaVersion"));
        assertEquals(baseModelId, manifest.get("baseModelId"));
        assertEquals("base-lora-peft-lora", manifest.get("trainedModelId"));
        assertEquals("LORA", manifest.get("trainingType"));
        assertEquals("base-lora-peft-lora.fb", manifest.get("modelFile"));
        assertEquals("SAMEDIFF_FLATBUFFERS", manifest.get("modelFormat"));
        assertEquals(true, manifest.get("deployable"));

        @SuppressWarnings("unchecked")
        Map<String, Object> registrySuggestion = (Map<String, Object>) manifest.get("registrySuggestion");
        assertEquals("base-lora-peft-lora", registrySuggestion.get("modelId"));
        assertEquals("base-lora-peft-lora.fb", registrySuggestion.get("modelFile"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) manifest.get("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals("model", artifacts.get(0).get("role"));
        assertEquals("base-lora-peft-lora.fb", artifacts.get(0).get("relativePath"));
        assertEquals(true, artifacts.get(0).get("exists"));
    }

    private PeftConfigRequest loraRequest() {
        return PeftConfigRequest.builder()
                .peftType("LORA")
                .loraConfig(LoraConfigDto.builder()
                        .rank(2)
                        .alpha(2.0)
                        .targetModules(List.of("dense"))
                        .build())
                .build();
    }

    private void writeBaseModelOrSkip(String modelId) throws Exception {
        Path modelDir = tempDir.resolve(modelId);
        Files.createDirectories(modelDir);
        Path modelFile = modelDir.resolve(modelId + ".fb");

        SameDiff sd = null;
        try {
            sd = SameDiff.create();
            sd.var("dense.weight", Nd4j.ones(4, 4));
            sd.save(modelFile.toFile(), true);
        } catch (Throwable t) {
            assumeTrue(false, "ND4J backend not available for PEFT artifact manifest test: " + t.getMessage());
        } finally {
            if (sd != null) {
                try {
                    sd.close();
                } catch (Exception ignored) {
                    // Best effort cleanup for backend-backed SameDiff instances.
                }
            }
        }
        assumeTrue(Files.isRegularFile(modelFile), "Base SameDiff model file was not created");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readManifest(Path manifestPath) throws Exception {
        Object parsed = OBJECT_MAPPER.readValue(manifestPath.toFile(), Map.class);
        assertNotNull(parsed);
        return (Map<String, Object>) parsed;
    }
}
