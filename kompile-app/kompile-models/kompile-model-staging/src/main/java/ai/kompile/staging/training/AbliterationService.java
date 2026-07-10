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

import ai.kompile.staging.config.StagingPropertyKeys;
import ai.kompile.staging.web.dto.AbliterationRequest;
import ai.kompile.staging.web.dto.AbliterationResponse;
import org.eclipse.deeplearning4j.llm.editing.AbliterationConfig;
import org.eclipse.deeplearning4j.llm.editing.RefusalDirection;
import org.eclipse.deeplearning4j.llm.editing.RefusalDirectionFinder;
import org.eclipse.deeplearning4j.llm.editing.WeightOrthogonalizer;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service for training-free abliteration/model editing workflows.
 */
@Service
public class AbliterationService {

    private static final Logger log = LoggerFactory.getLogger(AbliterationService.class);

    @Value(StagingPropertyKeys.MODELS_DIR_VALUE)
    private String modelsDir;

    /**
     * Apply abliteration to a model and write a deployable artifact manifest.
     */
    public AbliterationResponse applyAbliteration(AbliterationRequest request) {
        if (request == null || isBlank(request.getModelId())) {
            return failure("modelId is required");
        }

        String modelId = request.getModelId().trim();
        String outputModelId = sanitizeModelId(firstNonBlank(request.getOutputModelId(), modelId + "-abliterated"));
        File modelFile = resolveModelFile(modelId);
        if (modelFile == null || !modelFile.exists()) {
            return failure("Model file not found: " + modelId).toBuilder()
                    .modelId(modelId)
                    .outputModelId(outputModelId)
                    .build();
        }

        SameDiff sd = null;
        try {
            AbliterationConfig config = buildConfig(request);
            sd = SameDiff.load(modelFile, true);
            List<RefusalDirection> candidates = resolveDirections(sd, request, config);
            if (candidates.isEmpty()) {
                return failure("No refusal directions were provided or computed").toBuilder()
                        .modelId(modelId)
                        .outputModelId(outputModelId)
                        .build();
            }

            List<RefusalDirection> appliedDirections = selectDirections(candidates, config);
            List<String> modifiedWeights = new WeightOrthogonalizer()
                    .orthogonalizeWeights(sd, appliedDirections, config);
            if (modifiedWeights.isEmpty()) {
                return failure("No model weights matched the abliteration target patterns").toBuilder()
                        .modelId(modelId)
                        .outputModelId(outputModelId)
                        .candidatesFound(candidates.size())
                        .directionsApplied(appliedDirections.size())
                        .build();
            }

            File outputDir = new File(modelsDir, outputModelId);
            outputDir.mkdirs();
            File outputFile = new File(outputDir, outputModelId + ".fb");
            sd.save(outputFile, true);

            Map<String, Object> trainingConfig = trainingConfigManifest(request, config, hasDirectDirections(request));
            trainingConfig.put("appliedDirections", directionManifest(appliedDirections));

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("candidatesFound", candidates.size());
            metrics.put("directionsApplied", appliedDirections.size());
            metrics.put("weightsModified", modifiedWeights.size());

            Path manifestPath = TrainingArtifactManifestWriter.writeManifest(
                    "abliteration-" + outputModelId,
                    modelId,
                    outputModelId,
                    "ABLITERATION",
                    outputDir.toPath(),
                    outputFile.toPath(),
                    Collections.emptyMap(),
                    trainingConfig,
                    metrics);

            log.info("Applied abliteration: model={}, output={}, weightsModified={}",
                    modelId, outputModelId, modifiedWeights.size());

            return AbliterationResponse.builder()
                    .success(true)
                    .modelId(modelId)
                    .outputModelId(outputModelId)
                    .outputDir(outputDir.getAbsolutePath())
                    .outputPath(outputFile.getAbsolutePath())
                    .modelFile(outputFile.getName())
                    .manifestPath(manifestPath.toString())
                    .deployable(true)
                    .registryEligible(true)
                    .candidatesFound(candidates.size())
                    .directionsApplied(appliedDirections.size())
                    .weightsModified(modifiedWeights.size())
                    .modifiedWeightNames(modifiedWeights)
                    .appliedDirections(directionResults(appliedDirections))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to apply abliteration to model: {}", modelId, e);
            return failure("Abliteration failed: " + e.getMessage()).toBuilder()
                    .modelId(modelId)
                    .outputModelId(outputModelId)
                    .build();
        } finally {
            if (sd != null) {
                try {
                    sd.close();
                } catch (Exception e) {
                    log.warn("Failed to close SameDiff model", e);
                }
            }
        }
    }

    private List<RefusalDirection> resolveDirections(SameDiff sd,
                                                     AbliterationRequest request,
                                                     AbliterationConfig config) {
        if (hasDirectDirections(request)) {
            return directionsFromRequest(request);
        }
        if (!isEmpty(request.getHarmfulActivations()) && !isEmpty(request.getHarmlessActivations())) {
            Map<String, INDArray> harmful = activationMap(request.getHarmfulActivations());
            Map<String, INDArray> harmless = activationMap(request.getHarmlessActivations());
            return new RefusalDirectionFinder().findRefusalDirections(sd, harmful, harmless, config);
        }
        return Collections.emptyList();
    }

    private AbliterationConfig buildConfig(AbliterationRequest request) {
        AbliterationConfig.AbliterationConfigBuilder builder = AbliterationConfig.builder();
        if (!isBlank(request.getMethod())) {
            builder.method(AbliterationConfig.Method.valueOf(normalizeEnum(request.getMethod())));
        }
        if (!isBlank(request.getLayerSelectionStrategy())) {
            builder.layerSelectionStrategy(AbliterationConfig.LayerSelection.valueOf(
                    normalizeEnum(request.getLayerSelectionStrategy())));
        }
        if (request.getTopK() != null && request.getTopK() > 0) {
            builder.topK(request.getTopK());
        }
        if (request.getAblationStrength() != null) {
            builder.ablationStrength(request.getAblationStrength());
        }
        if (request.getWinsorize() != null) {
            builder.winsorize(request.getWinsorize());
        }
        if (request.getWinsorizePercentile() != null) {
            builder.winsorizePercentile(request.getWinsorizePercentile());
        }
        if (request.getTargetWeightPatterns() != null && !request.getTargetWeightPatterns().isEmpty()) {
            builder.targetWeightPatterns(new ArrayList<>(request.getTargetWeightPatterns()));
        }
        return builder.build();
    }

    private List<RefusalDirection> directionsFromRequest(AbliterationRequest request) {
        List<RefusalDirection> result = new ArrayList<>();
        for (AbliterationRequest.Direction direction : request.getDirections()) {
            if (direction == null || direction.getValues() == null || direction.getValues().isEmpty()) {
                continue;
            }
            double[] values = new double[direction.getValues().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = direction.getValues().get(i);
            }
            result.add(new RefusalDirection(
                    firstNonBlank(direction.getLayerName(), "manual-direction"),
                    parsePosition(direction.getPosition()),
                    Nd4j.create(values),
                    direction.getScore() != null ? direction.getScore() : 1.0,
                    direction.getLayerIndex() != null ? direction.getLayerIndex() : -1));
        }
        return result;
    }

    private Map<String, INDArray> activationMap(Map<String, List<List<Double>>> source) {
        Map<String, INDArray> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<Double>>> entry : source.entrySet()) {
            if (isBlank(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            result.put(entry.getKey(), toActivationArray(entry.getValue()));
        }
        return result;
    }

    private INDArray toActivationArray(List<List<Double>> rows) {
        int rowCount = rows.size();
        int colCount = rows.get(0) != null ? rows.get(0).size() : 0;
        if (colCount == 0) {
            throw new IllegalArgumentException("Activation rows must not be empty");
        }
        double[][] values = new double[rowCount][colCount];
        for (int r = 0; r < rowCount; r++) {
            List<Double> row = rows.get(r);
            if (row == null || row.size() != colCount) {
                throw new IllegalArgumentException("Activation rows must be rectangular");
            }
            for (int c = 0; c < colCount; c++) {
                values[r][c] = row.get(c);
            }
        }
        return Nd4j.create(values);
    }

    private List<RefusalDirection> selectDirections(List<RefusalDirection> candidates, AbliterationConfig config) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        AbliterationConfig.LayerSelection selection = config.getLayerSelectionStrategy();
        if (selection == AbliterationConfig.LayerSelection.ALL_LAYERS) {
            return new ArrayList<>(candidates);
        }
        int count = selection == AbliterationConfig.LayerSelection.TOP_K
                ? Math.max(1, Math.min(config.getTopK(), candidates.size()))
                : 1;
        return new ArrayList<>(candidates.subList(0, Math.min(count, candidates.size())));
    }

    private Map<String, Object> trainingConfigManifest(AbliterationRequest request,
                                                       AbliterationConfig config,
                                                       boolean directDirections) {
        Map<String, Object> trainingConfig = new LinkedHashMap<>();
        trainingConfig.put("inputMode", directDirections ? "DIRECTIONS" : "PRECOMPUTED_ACTIVATIONS");
        trainingConfig.put("method", config.getMethod().name());
        trainingConfig.put("layerSelectionStrategy", config.getLayerSelectionStrategy().name());
        trainingConfig.put("topK", config.getTopK());
        trainingConfig.put("ablationStrength", config.getAblationStrength());
        trainingConfig.put("winsorize", config.isWinsorize());
        trainingConfig.put("winsorizePercentile", config.getWinsorizePercentile());
        trainingConfig.put("targetWeightPatterns", new ArrayList<>(config.getTargetWeightPatterns()));
        trainingConfig.put("harmfulActivationKeys", request.getHarmfulActivations() != null
                ? new ArrayList<>(request.getHarmfulActivations().keySet())
                : Collections.emptyList());
        trainingConfig.put("harmlessActivationKeys", request.getHarmlessActivations() != null
                ? new ArrayList<>(request.getHarmlessActivations().keySet())
                : Collections.emptyList());
        return trainingConfig;
    }

    private List<Map<String, Object>> directionManifest(List<RefusalDirection> directions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RefusalDirection direction : directions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layerName", direction.getLayerName());
            item.put("position", direction.getPosition() != null ? direction.getPosition().name() : null);
            item.put("score", direction.getScore());
            item.put("layerIndex", direction.getLayerIndex());
            item.put("dimension", direction.getDirection() != null ? direction.getDirection().length() : 0L);
            result.add(item);
        }
        return result;
    }

    private List<AbliterationResponse.DirectionResult> directionResults(List<RefusalDirection> directions) {
        List<AbliterationResponse.DirectionResult> result = new ArrayList<>();
        for (RefusalDirection direction : directions) {
            result.add(AbliterationResponse.DirectionResult.builder()
                    .layerName(direction.getLayerName())
                    .position(direction.getPosition() != null ? direction.getPosition().name() : null)
                    .score(direction.getScore())
                    .layerIndex(direction.getLayerIndex())
                    .dimension(direction.getDirection() != null ? direction.getDirection().length() : 0L)
                    .build());
        }
        return result;
    }

    private AbliterationConfig.ResidualStreamPosition parsePosition(String value) {
        if (isBlank(value)) {
            return AbliterationConfig.ResidualStreamPosition.POST_MLP;
        }
        return AbliterationConfig.ResidualStreamPosition.valueOf(normalizeEnum(value));
    }

    private File resolveModelFile(String modelId) {
        if (isBlank(modelId)) {
            return null;
        }

        File direct = new File(modelId);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }

        File modelDir = new File(modelsDir, modelId);
        if (modelDir.isDirectory()) {
            File fb = new File(modelDir, modelId + ".fb");
            if (fb.exists()) return fb;
            File sdz = new File(modelDir, modelId + ".sdz");
            if (sdz.exists()) return sdz;
            File[] fbFiles = modelDir.listFiles((dir, name) -> name.endsWith(".fb"));
            if (fbFiles != null && fbFiles.length > 0) return fbFiles[0];
        }

        File directFb = new File(modelsDir, modelId + ".fb");
        if (directFb.exists()) {
            return directFb;
        }

        return null;
    }

    private boolean hasDirectDirections(AbliterationRequest request) {
        return request.getDirections() != null && !request.getDirections().isEmpty();
    }

    private boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    private String normalizeEnum(String value) {
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private String sanitizeModelId(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AbliterationResponse failure(String error) {
        return AbliterationResponse.builder()
                .success(false)
                .error(error)
                .deployable(false)
                .registryEligible(false)
                .build();
    }
}
