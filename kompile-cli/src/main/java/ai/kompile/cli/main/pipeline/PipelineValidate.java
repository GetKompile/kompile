package ai.kompile.cli.main.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate a pipeline configuration file.")
public class PipelineValidate implements Callable<Integer> {

    @CommandLine.Option(names = {"-f", "--file"}, required = true,
            description = "Pipeline configuration file (JSON or YAML)")
    private File pipelineFile;

    @Override
    public Integer call() throws Exception {
        if (!pipelineFile.exists()) {
            System.err.println("Pipeline file not found: " + pipelineFile.getAbsolutePath());
            return 1;
        }

        ObjectMapper mapper = pipelineFile.getName().endsWith(".yaml") || pipelineFile.getName().endsWith(".yml")
                ? ObjectMappers.getYamlMapper()
                : ObjectMappers.getJsonMapper();

        System.err.println("Loading pipeline from: " + pipelineFile.getAbsolutePath());
        Pipeline pipeline;
        try {
            pipeline = mapper.readValue(pipelineFile, Pipeline.class);
        } catch (Exception e) {
            System.err.println("FAILED: Could not parse pipeline file: " + e.getMessage());
            return 1;
        }

        // Structural validation
        boolean valid = true;
        try {
            pipeline.validate();
            System.err.println("Structural validation: PASSED");
        } catch (Exception e) {
            System.err.println("Structural validation: FAILED - " + e.getMessage());
            valid = false;
        }

        // Check runner classes
        Set<String> availableRunners = new HashSet<>();
        ServiceLoader<PipelineStepRunnerFactory> factories = ServiceLoader.load(PipelineStepRunnerFactory.class);
        for (PipelineStepRunnerFactory factory : factories) {
            availableRunners.add(factory.getRunnerType());
        }

        System.err.println("\nSteps (" + pipeline.getSteps().size() + "):");
        for (int i = 0; i < pipeline.getSteps().size(); i++) {
            StepConfig step = pipeline.getSteps().get(i);
            String runner = step.runnerClassName();
            boolean found = availableRunners.contains(runner);
            System.err.printf("  [%d] %s %s%n", i, runner, found ? "(available)" : "(WARNING: not found via ServiceLoader)");
        }

        if (valid) {
            System.err.println("\nResult: Pipeline is VALID");
            return 0;
        } else {
            System.err.println("\nResult: Pipeline is INVALID");
            return 1;
        }
    }
}
