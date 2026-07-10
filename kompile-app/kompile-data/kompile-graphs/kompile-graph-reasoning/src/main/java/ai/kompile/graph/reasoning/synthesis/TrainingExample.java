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
package ai.kompile.graph.reasoning.synthesis;

/**
 * One labeled training row for the answer scorer: a candidate's {@link AnswerFeatures} vector for a
 * given query, plus whether that candidate was the correct answer. {@code query} groups a candidate set
 * so evaluation can rank within a query (MRR) and split held-out by query (never leak a query across
 * train/test). Record-shaped → serializes to/from the dataset JSON.
 */
public record TrainingExample(String query, String candidate, double[] features, int label) {

    public TrainingExample {
        features = features == null ? new double[0] : features.clone();
    }

    @Override
    public double[] features() {
        return features.clone();
    }

    public boolean correct() {
        return label > 0;
    }
}
