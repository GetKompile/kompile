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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.build.util.ModuleAppender;
import ai.kompile.pipelines.framework.api.Pipeline;
// Assuming KompileServingConfig and ServerProtocol are part of a new server configuration API.
// If not, this part needs to be re-evaluated based on how server configurations are handled.
// import ai.kompile.pipelines.server.config.KompileServingConfig; // Hypothetical
// import ai.kompile.pipelines.server.config.ServerProtocol;       // Hypothetical

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Generates a `build pom-generate` command string based on a pipeline or server configuration file.
 * This facilitates the creation of tailored build configurations (POM files) for native images or SDKs.
 */
@CommandLine.Command(name = "pipeline-command-generate",
        description = "Generates a 'build pom-generate' command based on a pipeline or server configuration file.",
        mixinStandardHelpOptions = true)
public class PipelineCommandGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--pipelineFile", "--configFile"},
            description = "Path to the pipeline or server configuration file (JSON or YAML).",
            required = true)
    private File configFile;

    @CommandLine.Option(names = {"--protocol"}, description = "Protocol for serving (e.g., HTTP, GRPC). Used to infer if it's a server build.")
    private String protocol = "http"; // Default implies server context if not a simple pipeline

    @CommandLine.Option(names = {"--imageName"}, description = "The name for the output native image or SDK artifact.")
    private String imageName = "kompile-serving-app"; // Default image name

    @CommandLine.Option(names = {"--mainClass"}, description = "The main class for the application (if generating an executable JAR/image).")
    private String mainClass; // e.g., "ai.kompile.server.AppMain" or specific pipeline runner main

    @CommandLine.Option(names = {"--nd4jBackend"}, description = "The ND4J backend to include (e.g., nd4j-native, nd4j-cuda-11.x).")
    private String nd4jBackend = "nd4j-native";

    @CommandLine.Option(names = {"--nd4jBackendClassifier"}, description = "Classifier for the ND4J backend (e.g., linux-x86_64, windows-x86_64-avx2).")
    private String nd4jBackendClassifier = "";

    @CommandLine.Option(names = {"--extraDependencies"}, description = "Comma-separated extra Maven dependencies (groupId:artifactId:version[:classifier]).")
    private String extraDependencies;

    @CommandLine.Option(names = {"--includeResources"}, description = "Comma-separated list of additional resources to include in the build.")
    private String includeResources;

    @CommandLine.Option(names = {"--isServer"}, description = "Explicitly treat the configFile as a server configuration. If false, attempts to parse as a pipeline first.")
    private boolean isServer = false;

    @CommandLine.Option(names = {"--assembly"}, description = "If true, generate command for a Maven assembly build instead of a native image.")
    private boolean assembly = false;

    @CommandLine.Option(names = {"--nativeImageJvmArg"}, description = "Extra JVM arguments for the native image build process (e.g., -Xmx4g). Passed as -J<arg>.")
    private String[] nativeImageJvmArgs;

    @CommandLine.Option(names = {"--numpySharedLibrary"}, description = "If true, configure for building a NumPy-compatible shared library.")
    private boolean numpySharedLibrary = false;

    @CommandLine.Option(names = {"--outputFile"}, description = "Target path for the generated pom.xml by the sub-command.")
    private File outputFile = new File("pom-generated.xml");


    @Override
    public Void call() throws Exception {
        StringBuilder command = new StringBuilder("kompile build pom-generate "); // Use full kompile command path

        // Base flags for pom-generate
        if (mainClass != null && !mainClass.isEmpty()) {
            command.append(" --mainClass=").append(quoteIfNecessary(mainClass)).append(" ");
        }
        if (imageName != null && !imageName.isEmpty()) {
            command.append(" --imageName=").append(quoteIfNecessary(imageName)).append(" ");
        }
        if (extraDependencies != null && !extraDependencies.isEmpty()) {
            command.append(" --extraDependencies=").append(quoteIfNecessary(extraDependencies)).append(" ");
        }
        if (includeResources != null && !includeResources.isEmpty()) {
            command.append(" --includeResources=").append(quoteIfNecessary(includeResources)).append(" ");
        }
        if (nd4jBackend != null && !nd4jBackend.isEmpty()) {
            command.append(" --nd4jBackend=").append(quoteIfNecessary(nd4jBackend)).append(" ");
        }
        if (nd4jBackendClassifier != null && !nd4jBackendClassifier.isEmpty()) {
            command.append(" --nd4jBackendClassifier=").append(quoteIfNecessary(nd4jBackendClassifier)).append(" ");
        }
        if (outputFile != null) {
            command.append(" --outputFile=").append(quoteIfNecessary(outputFile.getAbsolutePath())).append(" ");
        }

        command.append("--assembly=").append(assembly).append(" ");
        command.append("--numpySharedLibrary=").append(numpySharedLibrary).append(" ");

        if (nativeImageJvmArgs != null) {
            for (String jvmArg : nativeImageJvmArgs) {
                command.append("--nativeImageJvmArg=").append(quoteIfNecessary(jvmArg)).append(" ");
            }
        }

        Pipeline pipelineToAnalyze = null;
        ObjectMapper mapper;
        if (configFile.getName().toLowerCase().endsWith(".json")) {
            mapper = ObjectMappers.getJsonMapper();
        } else if (configFile.getName().toLowerCase().endsWith(".yaml") || configFile.getName().toLowerCase().endsWith(".yml")) {
            mapper = ObjectMappers.getYamlMapper();
        } else {
            System.err.println("Error: Unknown configuration file format for " + configFile.getName() + ". Please use .json, .yaml, or .yml.");
            return null;
        }

        try {
            if (isServer) {
                // Hypothetical: Parse as a new KompileServingConfig if it exists and contains a Pipeline
                // KompileServingConfig servingConfig = mapper.readValue(configFile, KompileServingConfig.class);
                // pipelineToAnalyze = servingConfig.getPipeline(); // Assuming getPipeline() method
                // command.append("--server=true "); // Indicate to pom-generate it's a server build

                // For now, let's assume if --isServer, we just parse the pipeline part from a known structure
                // or the user is responsible for the mainClass.
                // If KompileServingConfig is just a wrapper for Pipeline and server settings,
                // we might deserialize a Map and extract the 'pipeline' field.
                // This part is highly dependent on the actual new server config structure.
                // Placeholder: trying to read it as a Pipeline directly if --isServer is used simply
                // to guide module inclusion based on an embedded pipeline.
                System.out.println("Info: --isServer flag is set. Analyzing pipeline definition within the config.");
                try {
                    // Attempt to parse directly as Pipeline. If server config wraps it, this needs adjustment.
                    pipelineToAnalyze = mapper.readValue(configFile, Pipeline.class);
                    command.append("--server=true "); // Signal to pom-generator this is for a server context
                } catch (Exception eOuter) {
                    try {
                        // If direct parse as Pipeline fails, try to see if it's a map with a "pipeline" field
                        Map<String,Object> map = mapper.readValue(configFile, Map.class);
                        if(map.containsKey("pipeline")) {
                            pipelineToAnalyze = mapper.convertValue(map.get("pipeline"), Pipeline.class);
                            command.append("--server=true ");
                        } else {
                            System.err.println("Error: --isServer was true, but could not directly parse as Pipeline or find a 'pipeline' field in the config file: " + configFile.getAbsolutePath());
                            throw eOuter;
                        }
                    } catch (Exception eInner) {
                        System.err.println("Error: Failed to parse pipeline from server configuration file: " + configFile.getAbsolutePath() + ". Details: " + eInner.getMessage());
                        throw eOuter; // rethrow original exception
                    }
                }

            } else {
                // Attempt to parse as a direct Pipeline configuration
                pipelineToAnalyze = mapper.readValue(configFile, Pipeline.class);
                // If protocol implies a server, add --server flag (heuristic)
                if (protocol != null && (protocol.equalsIgnoreCase("http") || protocol.equalsIgnoreCase("grpc"))) {
                    command.append("--server=true ");
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading or parsing configuration file: " + configFile.getAbsolutePath() + ". Details: " + e.getMessage());
            return null;
        }


        if (pipelineToAnalyze != null) {
            if (pipelineToAnalyze.getSteps() == null || pipelineToAnalyze.getSteps().isEmpty()) {
                System.err.println("Warning: The provided pipeline definition in " + configFile.getName() + " has no steps.");
            } else {
                appendPipelineStepModules(command, pipelineToAnalyze);
            }
        } else if (!assembly) { // If not an assembly and no pipeline to analyze, it's an issue.
            System.err.println("Error: No pipeline could be analyzed from " + configFile.getName() + ", which is needed for determining step modules unless it's an assembly-only build.");
            return null;
        }


        System.out.println(command.toString().trim());
        return null;
    }

    private void appendPipelineStepModules(StringBuilder command, Pipeline pipeline) throws IOException {
        Set<String> modules = ModuleAppender.getModulesFromPipeline(pipeline);
        for (String module : modules) {
            command.append("--").append(module).append("=true ");
        }
    }

    private String quoteIfNecessary(String value) {
        if (value != null && (value.contains(" ") || value.contains(","))) {
            return "\"" + value + "\"";
        }
        return value;
    }

}