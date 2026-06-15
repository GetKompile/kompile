package ai.kompile.cli.main.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "exec",
        mixinStandardHelpOptions = true,
        description = "Execute a pipeline from a JSON or YAML configuration file.")
public class PipelineExec implements Callable<Integer> {

    @CommandLine.Option(names = {"-f", "--file"}, required = true,
            description = "Pipeline configuration file (JSON or YAML)")
    private File pipelineFile;

    @CommandLine.Option(names = {"-i", "--input"},
            description = "Input data as JSON string or path to JSON file")
    private String input;

    @CommandLine.Option(names = {"-o", "--output"},
            description = "Output file path (writes result as JSON)")
    private File outputFile;

    @CommandLine.Option(names = {"--format"}, defaultValue = "json",
            description = "Output format: json or yaml (default: ${DEFAULT-VALUE})")
    private String format;

    @Override
    public Integer call() throws Exception {
        if (!pipelineFile.exists()) {
            System.err.println("Pipeline file not found: " + pipelineFile.getAbsolutePath());
            return 1;
        }

        // Determine mapper from file extension
        ObjectMapper mapper = pipelineFile.getName().endsWith(".yaml") || pipelineFile.getName().endsWith(".yml")
                ? ObjectMappers.getYamlMapper()
                : ObjectMappers.getJsonMapper();

        System.err.println("Loading pipeline from: " + pipelineFile.getAbsolutePath());
        Pipeline pipeline = mapper.readValue(pipelineFile, Pipeline.class);

        // Validate
        try {
            pipeline.validate();
            System.err.println("Pipeline validated successfully (" + pipeline.getSteps().size() + " steps)");
        } catch (Exception e) {
            System.err.println("Pipeline validation failed: " + e.getMessage());
            return 1;
        }

        // Prepare input
        Data inputData = Data.empty();
        if (input != null && !input.isBlank()) {
            File inputFile = new File(input);
            if (inputFile.exists()) {
                inputData = Data.fromJson(inputFile);
            } else {
                inputData = Data.fromJson(input);
            }
            System.err.println("Input data loaded");
        }

        // Execute
        System.err.println("Executing pipeline...");
        long start = System.currentTimeMillis();
        PipelineExecutor executor = pipeline.createExecutor();
        Data output = executor.exec(inputData);
        executor.close();
        long duration = System.currentTimeMillis() - start;

        System.err.println("Pipeline executed in " + duration + "ms");

        // Output
        ObjectMapper outputMapper = "yaml".equals(format) ? ObjectMappers.getYamlMapper() : ObjectMappers.getJsonMapper();
        String resultJson = outputMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output.toMap());

        if (outputFile != null) {
            Files.writeString(outputFile.toPath(), resultJson);
            System.err.println("Output written to: " + outputFile.getAbsolutePath());
        } else {
            System.out.println(resultJson);
        }

        return 0;
    }
}
