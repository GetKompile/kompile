/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.process;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.process.discovery.mining.ActivityEmbedder;
import ai.kompile.process.discovery.mining.ActivityEmbedder.ActivityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application bridge from the shared text embedding model to process activity embeddings. */
@Component
public class EmbeddingModelActivityEmbedder implements ActivityEmbedder {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelActivityEmbedder.class);
    private static final int MAX_CONTEXT_TEXT_CHARS = 4096;

    private final EmbeddingModel embeddingModel;

    @Autowired
    public EmbeddingModelActivityEmbedder(ObjectProvider<EmbeddingModel> embeddingModels) {
        this(embeddingModels == null ? null : embeddingModels.orderedStream()
                .filter(EmbeddingModel::canEmbed)
                .findFirst()
                .orElse(null));
    }

    EmbeddingModelActivityEmbedder(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Map<String, double[]> embed(List<String> activityLabels) {
        if (activityLabels == null) {
            return Map.of();
        }
        return embedContexts(activityLabels.stream()
                .filter(label -> label != null && !label.isBlank())
                .distinct()
                .map(label -> new ActivityContext(label, List.of()))
                .toList());
    }

    @Override
    public Map<String, double[]> embedContexts(List<ActivityContext> activities) {
        if (embeddingModel == null || !embeddingModel.canEmbed()
                || activities == null || activities.isEmpty()) {
            return Map.of();
        }

        Map<String, String> textByLabel = new LinkedHashMap<>();
        for (ActivityContext activity : activities) {
            if (activity != null) {
                textByLabel.putIfAbsent(activity.label(), embeddingText(activity));
            }
        }
        if (textByLabel.isEmpty()) {
            return Map.of();
        }

        List<String> labels = new ArrayList<>(textByLabel.keySet());
        List<String> texts = labels.stream().map(textByLabel::get).toList();
        int preferred = embeddingModel.getOptimalBatchSize();
        int maximum = embeddingModel.getMaxBatchSize();
        int batchSize = Math.max(1, preferred > 0 ? preferred : 32);
        if (maximum > 0) {
            batchSize = Math.min(batchSize, maximum);
        }

        Map<String, double[]> out = new LinkedHashMap<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            try {
                List<float[]> vectors = embeddingModel.embedBatch(texts.subList(start, end));
                if (vectors == null) {
                    continue;
                }
                int count = Math.min(vectors.size(), end - start);
                for (int i = 0; i < count; i++) {
                    double[] vector = validVector(vectors.get(i));
                    if (vector != null) {
                        out.put(labels.get(start + i), vector);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Activity embedding batch {}-{} failed: {}", start, end, e.getMessage());
            }
        }
        return out;
    }

    @Override
    public String embeddingSource() {
        return "TEXT_EMBEDDING_MODEL";
    }

    @Override
    public String embeddingModel() {
        return embeddingModel == null ? null : embeddingModel.getModelIdentifier();
    }

    private static String embeddingText(ActivityContext activity) {
        StringBuilder text = new StringBuilder(activity.label());
        for (String context : activity.contexts()) {
            if (context == null || context.isBlank()) {
                continue;
            }
            text.append(". Context: ").append(context.trim());
            if (text.length() >= MAX_CONTEXT_TEXT_CHARS) {
                text.setLength(MAX_CONTEXT_TEXT_CHARS);
                break;
            }
        }
        return text.toString();
    }

    private static double[] validVector(float[] source) {
        if (source == null || source.length == 0) {
            return null;
        }
        double[] out = new double[source.length];
        boolean nonZero = false;
        for (int i = 0; i < source.length; i++) {
            if (!Float.isFinite(source[i])) {
                return null;
            }
            out[i] = source[i];
            nonZero |= source[i] != 0.0f;
        }
        return nonZero ? out : null;
    }
}
