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

package ai.kompile.cli.main.build.util;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;

import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphNodeConfig;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.StandardGraphNodeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

/**
 * Utility class to determine necessary Kompile modules based on the runners
 * specified in a {@link Pipeline} configuration.
 * This helps in packaging only the required dependencies.
 */
public class ModuleAppender {

    /**
     * Analyzes the pipeline and returns a set of module identifiers based on the
     * {@link StepConfig#runnerClassName()} of each step.
     * The mapping from runnerClassName to module identifier is heuristic or
     * based on predefined conventions.
     *
     * @param pipeline The pipeline to analyze.
     * @return A set of string identifiers for the required modules.
     * @throws java.io.IOException If there's an issue processing (though less likely now without file I/O here).
     */
    public static Set<String> getModulesFromPipeline(Pipeline pipeline) throws java.io.IOException {
        Set<String> modulesToAdd = new HashSet<>();
        if (pipeline instanceof SequencePipeline) {
            SequencePipeline sequencePipeline = (SequencePipeline) pipeline;
            for (StepConfig stepConfig : sequencePipeline.getSteps()) {
                addModuleForRunner(modulesToAdd, stepConfig.runnerClassName());
            }
        } else if (pipeline instanceof GraphPipeline) {
            GraphPipeline graphPipeline = (GraphPipeline) pipeline;
            for (GraphNodeConfig graphNodeConfig : graphPipeline.getGraphNodes().values()) {
                if (graphNodeConfig instanceof StandardGraphNodeConfig) {
                    StepConfig stepConfig = ((StandardGraphNodeConfig) graphNodeConfig).getStepConfig();
                    if (stepConfig != null) {
                        addModuleForRunner(modulesToAdd, stepConfig.runnerClassName());
                    }
                }
                // Other graph node types (MERGE, SWITCH, etc.) are part of the runtime framework
                // and don't map to separate user-level step modules in the same way.
            }
        } else {
            // Fallback for Pipeline implementations that might just provide a flat list of steps
            for (StepConfig stepConfig : pipeline.getSteps()) {
                addModuleForRunner(modulesToAdd, stepConfig.runnerClassName());
            }
        }
        return modulesToAdd;
    }

    /**
     * Determines the module identifier based on the runner's class name.
     * This uses a heuristic based on package names. A more robust solution might involve
     * a central registry mapping runners to their modules, or annotations on runners.
     *
     * @param modulesToAdd The set to add the module identifier to.
     * @param runnerClassName The fully qualified class name of the runner.
     */
    private static void addModuleForRunner(Set<String> modulesToAdd, String runnerClassName) {
        if (runnerClassName == null || runnerClassName.trim().isEmpty()) {
            return;
        }

        // Heuristic: derive module from package structure.
        // Example: ai.kompile.pipelines.steps.python.PythonRunner -> python
        // Example: ai.kompile.pipelines.steps.onnx.OnnxRuntimeRunner -> onnx
        // Example: ai.kompile.pipelines.steps.image.ImageCropRunner -> image
        // Example: ai.kompile.pipelines.steps.tensorflow.TensorFlowRunner -> tensorflow (if exists)

        if (runnerClassName.startsWith("ai.kompile.pipelines.steps.")) {
            String[] parts = runnerClassName.split("\\.");
            if (parts.length > 4) { // ai.kompile.pipelines.steps.<moduleName>.<RunnerName>
                String moduleNameCandidate = parts[4];
                // Normalize common module names
                switch (moduleNameCandidate.toLowerCase()) {
                    case "deeplearning4j":
                        modulesToAdd.add("dl4j");
                        break;
                    case "documentparser":
                        modulesToAdd.add("doc"); // if "doc" is the chosen short name
                        break;
                    case "samediff": // Handles both samediff main and trainer if they are in this package
                        modulesToAdd.add("samediff");
                        break;
                    // Add other specific normalizations or direct mappings as needed
                    default:
                        modulesToAdd.add(moduleNameCandidate.toLowerCase());
                        break;
                }
            }
        } else {
            // For runners not following the pattern, a more explicit mapping would be needed.
            // E.g., if some runners are in ai.kompile.special.SomeRunner -> maps to "specialmodule"
            // For now, log a warning or skip if no clear mapping.
            System.err.println("Warning: Could not determine module for runner: " + runnerClassName);
        }

        // Logging and ClassifierOutput steps are often part of core or a common utility module,
        // or their dependencies are pulled in by the framework runtime.
        // We don't add specific "logging" or "classifier-output" modules unless they are standalone step modules.
    }

    /**
     * Example main method to test module appending from a pipeline configuration file.
     *
     * @param args Command line arguments, expects the path to a pipeline JSON/YAML file as the first argument.
     * @throws Exception if parsing or processing fails.
     */
    public static void main(String... args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java ai.kompile.cli.main.build.util.ModuleAppender <path_to_pipeline_config.[json|yaml]>");
            return;
        }
        File pipelineFile = new File(args[0]);
        if (!pipelineFile.exists()) {
            System.err.println("Pipeline file not found: " + pipelineFile.getAbsolutePath());
            return;
        }

        ObjectMapper mapper;
        if (pipelineFile.getName().endsWith(".json")) {
            mapper = ObjectMappers.getJsonMapper();
        } else if (pipelineFile.getName().endsWith(".yaml") || pipelineFile.getName().endsWith(".yml")) {
            mapper = ObjectMappers.getYamlMapper();
        } else {
            System.err.println("Unsupported file extension. Please use .json, .yaml, or .yml");
            return;
        }

        String pipelineJson = FileUtils.readFileToString(pipelineFile, StandardCharsets.UTF_8);
        // Deserialize as the generic Pipeline interface. Jackson will use @class if present,
        // or try to map to known subtypes if registered (SequencePipeline, GraphPipeline).
        Pipeline pipeline = mapper.readValue(pipelineJson, Pipeline.class);

        System.out.println("Determined modules for pipeline '" + pipeline.id() + "': " +
                ModuleAppender.getModulesFromPipeline(pipeline));
    }
}