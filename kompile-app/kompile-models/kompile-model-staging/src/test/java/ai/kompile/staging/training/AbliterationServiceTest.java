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
import ai.kompile.staging.web.dto.AbliterationRequest;
import ai.kompile.staging.web.dto.AbliterationResponse;
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

class AbliterationServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    private AbliterationService abliterationService;

    @BeforeEach
    void setUp() throws Exception {
        abliterationService = new AbliterationService();
        Field modelsDir = AbliterationService.class.getDeclaredField("modelsDir");
        modelsDir.setAccessible(true);
        modelsDir.set(abliterationService, tempDir.toString());
    }

    @Test
    void applyAbliterationRejectsMissingModelId() {
        AbliterationResponse response = abliterationService.applyAbliteration(AbliterationRequest.builder().build());

        assertFalse(response.isSuccess());
        assertEquals("modelId is required", response.getError());
        assertFalse(response.isDeployable());
    }

    @Test
    void applyAbliterationWritesDeployableTrainingArtifactManifest() throws Exception {
        String modelId = "abliteration-base";
        writeBaseModelOrSkip(modelId);

        AbliterationResponse response = abliterationService.applyAbliteration(AbliterationRequest.builder()
                .modelId(modelId)
                .outputModelId("abliteration-edited")
                .method("DIFF_IN_MEANS")
                .ablationStrength(1.0)
                .targetWeightPatterns(List.of(".*lm_head.*"))
                .directions(List.of(AbliterationRequest.Direction.builder()
                        .layerName("lm_head.weight")
                        .position("POST_MLP")
                        .values(List.of(1.0, 0.0, 0.0, 0.0))
                        .score(1.0)
                        .layerIndex(0)
                        .build()))
                .build());

        assertTrue(response.isSuccess(), response.getError());
        assertEquals("abliteration-edited", response.getOutputModelId());
        assertEquals(true, response.isDeployable());
        assertEquals(true, response.isRegistryEligible());
        assertEquals(1, response.getCandidatesFound());
        assertEquals(1, response.getDirectionsApplied());
        assertEquals(1, response.getWeightsModified());
        assertEquals(List.of("lm_head.weight"), response.getModifiedWeightNames());

        Path manifestPath = Path.of(response.getManifestPath());
        assertTrue(Files.isRegularFile(manifestPath));
        assertTrue(Files.isRegularFile(Path.of(response.getOutputPath())));

        Map<String, Object> manifest = readManifest(manifestPath);
        assertEquals("kompile.training-artifact.v1", manifest.get("schemaVersion"));
        assertEquals(modelId, manifest.get("baseModelId"));
        assertEquals("abliteration-edited", manifest.get("trainedModelId"));
        assertEquals("ABLITERATION", manifest.get("trainingType"));
        assertEquals("abliteration-edited.fb", manifest.get("modelFile"));
        assertEquals("SAMEDIFF_FLATBUFFERS", manifest.get("modelFormat"));
        assertEquals(true, manifest.get("deployable"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trainingConfig = (Map<String, Object>) manifest.get("trainingConfig");
        assertEquals("DIRECTIONS", trainingConfig.get("inputMode"));
        assertEquals("DIFF_IN_MEANS", trainingConfig.get("method"));
        assertEquals(1.0, trainingConfig.get("ablationStrength"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) manifest.get("metrics");
        assertEquals(1, ((Number) metrics.get("weightsModified")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) manifest.get("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals("model", artifacts.get(0).get("role"));
        assertEquals("abliteration-edited.fb", artifacts.get(0).get("relativePath"));
    }

    private void writeBaseModelOrSkip(String modelId) throws Exception {
        Path modelDir = tempDir.resolve(modelId);
        Files.createDirectories(modelDir);
        Path modelFile = modelDir.resolve(modelId + ".fb");

        SameDiff sd = null;
        try {
            sd = SameDiff.create();
            sd.var("lm_head.weight", Nd4j.eye(4));
            sd.save(modelFile.toFile(), true);
        } catch (Throwable t) {
            assumeTrue(false, "ND4J backend not available for abliteration test: " + t.getMessage());
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
