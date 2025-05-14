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

package ai.kompile.cli.main.exec;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;

import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command to combine multiple {@link StepConfig} JSON/YAML files into a single
 * {@link SequencePipeline} configuration file.
 */
@CommandLine.Command(name = "sequence-pipeline-combine",
        aliases = {"sqc"}, // Shorter alias
        description = "Combines multiple StepConfig files into a SequencePipeline configuration.",
        mixinStandardHelpOptions = true)
public class SequencePipelineCombiner implements Callable<Void> {

    @CommandLine.Parameters(index = "0..*", arity = "1..*", description = "One or more paths to StepConfig JSON or YAML files.")
    private List<File> stepConfigFiles;

    @CommandLine.Option(names = {"-o", "--output-file"}, description = "Output file for the combined SequencePipeline. If not specified, prints to stdout.")
    private File outputFile;

    @CommandLine.Option(names = {"-f", "--output-format"}, description = "Output format for the pipeline: json or yaml (default: ${DEFAULT-VALUE})", defaultValue = "json")
    private String outputFormat = "json";

    @CommandLine.Option(names = {"--id"}, description = "Optional ID for the generated SequencePipeline.")
    private String pipelineId;


    @Override
    public Void call() throws Exception {
        List<StepConfig> stepConfigs = new ArrayList<>();
        ObjectMapper universalMapper = ObjectMappers.getJsonMapper(); // Can use JSON mapper as fallback if type not obvious
        ObjectMapper yamlMapper = ObjectMappers.getYamlMapper();
        ObjectMapper jsonMapper = ObjectMappers.getJsonMapper();


        for (File f : stepConfigFiles) {
            if (!f.exists() || !f.isFile()) {
                System.err.println("Error: StepConfig file not found or is not a file: " + f.getAbsolutePath());
                return null; // Or throw exception
            }
            String fileName = f.getName().toLowerCase();
            ObjectMapper mapperToUse;
            if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                mapperToUse = yamlMapper;
            } else if (fileName.endsWith(".json")) {
                mapperToUse = jsonMapper;
            } else {
                System.err.println("Warning: Cannot determine format for file " + f.getName() + " by extension. Attempting to parse as JSON.");
                mapperToUse = universalMapper; // Try JSON as a common default
            }

            try {
                // Deserialize as GenericStepConfig as it's the most likely concrete type from CLI output
                StepConfig stepConfig = mapperToUse.readValue(f, GenericStepConfig.class);
                stepConfigs.add(stepConfig);
            } catch (Exception e) {
                System.err.println("Error parsing StepConfig file: " + f.getAbsolutePath() + ". Details: " + e.getMessage());
                // Optionally, re-throw or collect errors
                return null;
            }
        }

        Pipeline pipeline = new SequencePipeline(pipelineId, stepConfigs);

        ObjectMapper outputMapper = outputFormat.equalsIgnoreCase("yaml") || outputFormat.equalsIgnoreCase("yml") ?
                ObjectMappers.getYamlMapper() : ObjectMappers.getJsonMapper();
        String pipelineString = outputMapper.writerWithDefaultPrettyPrinter().writeValueAsString(pipeline);

        if (outputFile != null) {
            FileUtils.writeStringToFile(outputFile, pipelineString, StandardCharsets.UTF_8);
            System.out.println("SequencePipeline configuration written to " + outputFile.getAbsolutePath());
        } else {
            System.out.println(pipelineString);
        }

        return null;
    }
}