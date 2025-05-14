/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.runtime.pipeline;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;

import java.util.Objects;

/**
 * Executes a {@link SequencePipeline} by processing data through each step's runner in order.
 */

public class SequencePipelineExecutor extends BasePipelineExecutor {

    /**
     * Constructs a SequencePipelineExecutor.
     *
     * @param pipeline The SequencePipeline this executor will run.
     * @param initializeRunners If true, runners will be instantiated and initialized immediately.
     * @throws Exception if runner instantiation or initialization fails.
     */
    public SequencePipelineExecutor(SequencePipeline pipeline, boolean initializeRunners) throws Exception {
        super(pipeline, initializeRunners);
        if (runners.isEmpty() && !pipeline.getSteps().isEmpty()) {
            // This case should ideally be caught by initializeRunners if it throws an exception.
            // However, if initializeRunners was false, this check is important before first exec.
        } else if (runners.size() != pipeline.getSteps().size()) {
            // This indicates a mismatch, possibly due to an issue in BasePipelineExecutor's initialization
            // or if initializeRunners was false and then an incorrect number of runners were somehow added.
            // Consider throwing an exception here if initializeRunners was true.
        }
    }

    /**
     * Ensures runners are initialized. This can be called if the executor was created
     * with {@code initializeRunners = false}.
     * @throws Exception if initialization fails.
     */
    public void ensureRunnersInitialized() throws Exception {
        if (runners.isEmpty() && !pipeline.getSteps().isEmpty()) {
            super.initializeRunners(); // Call base class method to do the actual initialization
            if (runners.size() != pipeline.getSteps().size()) {
                throw new IllegalStateException("Runner initialization mismatch for pipeline " + pipeline.id());
            }
        }
    }


    @Override
    public Data exec(Data input, Context context) throws Exception {
        checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null for exec.");
        Objects.requireNonNull(context, "Context cannot be null for exec.");

        // Ensure runners are initialized if they weren't at construction
        ensureRunnersInitialized();

        if (runners.isEmpty()) {
            return input;
        }

        Data currentData = input;
        int stepNum = 0;
        for (PipelineStepRunner runner : runners) {
            if (!runner.isInitialized()) {
                // This should ideally not happen if ensureRunnersInitialized or constructor init worked.
                throw new IllegalStateException("Runner " + runner.getClass().getName() +
                        " for step " + stepNum + " in pipeline " + pipeline.id() +
                        " is not initialized prior to exec().");
            }

            String stepRunnerName = runner.getClass().getSimpleName();
            String eventName = String.format("step.%d.%s.exec", stepNum, stepRunnerName);
            Context stepContext = context.child("step." + stepNum + "." + stepRunnerName);


            try {
                Data finalCurrentData = currentData;
                currentData = context.profiler().profile(eventName, () -> runner.exec(finalCurrentData, stepContext));

                context.metrics().counter("pipeline.step.executions.total", "Total number of step executions",
                                "pipeline_id", pipeline.id(), "step_runner", stepRunnerName, "step_index", String.valueOf(stepNum))
                        .increment();


                if (currentData == null) {
                    throw new IllegalStateException("PipelineStepRunner " + stepRunnerName + " at step " + stepNum +
                            " returned null Data object, which is not permitted.");
                }

            } catch (Exception e) {
                context.metrics().counter("pipeline.step.errors.total", "Total number of step execution errors",
                                "pipeline_id", pipeline.id(), "step_runner", stepRunnerName, "step_index", String.valueOf(stepNum))
                        .increment();
                throw e; // Re-throw to be caught by higher-level error handling or the future
            }
            stepNum++;
        }
        return currentData;
    }
}