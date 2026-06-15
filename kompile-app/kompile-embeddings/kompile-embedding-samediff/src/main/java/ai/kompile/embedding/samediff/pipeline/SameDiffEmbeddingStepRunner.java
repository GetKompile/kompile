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

// THIS IS THE CODE YOU PROVIDED FOR REVIEW (and asked me to add the rest of the methods to)
package ai.kompile.embedding.samediff.pipeline;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.samediff.SameDiffEmbeddingModelImpl;
import ai.kompile.embedding.samediff.config.SameDiffEmbeddingProperties;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import org.nd4j.common.base.Preconditions;

public class SameDiffEmbeddingStepRunner implements PipelineStepRunner {

    private  SameDiffEmbeddingStepConfig config = null;
    private EmbeddingModel embeddingModel;

    // ISSUE 2: This constructor initializes 'config' field from a 'config' parameter.
    // However, the PipelineStepRunnerFactory.create() we established uses a no-arg constructor for runners.
    // This means this constructor (SameDiffEmbeddingStepRunner(SameDiffEmbeddingStepConfig config))
    // will NOT be called by the factory.
    // All initialization of fields like 'config' and 'embeddingModel' must occur in the init() method.
    // Therefore, 'config' cannot be final here if initialized in init().
    public SameDiffEmbeddingStepRunner(SameDiffEmbeddingStepConfig config) {
        Preconditions.checkNotNull(config, "SameDiffEmbeddingStepConfig cannot be null.");
        Preconditions.checkNotNull(config.getModelUri(), "Model URI in SameDiffEmbeddingStepConfig cannot be null.");
        this.config = config; // If 'config' is final, this assignment must happen here.

        // This block correctly uses the 'config' parameter passed to *this* constructor.
        SameDiffEmbeddingProperties stepSpecificProperties = new SameDiffEmbeddingProperties();
        stepSpecificProperties.setModelUri(config.getModelUri());
        stepSpecificProperties.setInputTensorName(config.getInputTensorName());
        stepSpecificProperties.setOutputTensorName(config.getOutputTensorName());
        this.embeddingModel = new SameDiffEmbeddingModelImpl(stepSpecificProperties);
    }



    public SameDiffEmbeddingStepRunner() {}

    // The following methods are the actual interface methods from PipelineStepRunner.
    // They are currently placeholders in the code you provided.
    // The logic from the constructor and 'transform' needs to be correctly placed within these.
    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        // Field 'this.config' (if non-final) and 'this.embeddingModel' MUST be initialized here.
        // 'stepConfig' will be the general StepConfig from the pipeline definition.
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        // The logic from your 'transform' method (corrected) should go here.
        return null;
    }

    @Override
    public boolean isInitialized() {
        // Should return the state of an 'initialized' boolean flag set in init().
        return false;
    }

    @Override
    public void close() throws Exception {
        // Clean up embedding model to release native resources (SameDiff OpContexts, etc.)
        if (embeddingModel != null) {
            try {
                embeddingModel.close();
            } catch (Exception e) {
                // Log but don't propagate to ensure cleanup continues
            }
            embeddingModel = null;
        }
        config = null;
    }
}