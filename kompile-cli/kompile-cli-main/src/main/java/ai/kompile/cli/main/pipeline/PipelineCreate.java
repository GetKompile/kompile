package ai.kompile.cli.main.pipeline;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "create",
        mixinStandardHelpOptions = true,
        description = "Create or update a pipeline definition in ~/.kompile/data/pipelines/.%n%n" +
                "Saves a UnifiedPipelineDefinition JSON file so it can be served with%n" +
                "'kompile pipeline serve --pipeline-id=<id>'.%n%n" +
                "Examples:%n" +
                "  kompile pipeline create -f my-pipeline.json%n" +
                "  kompile pipeline create -f llm-pipeline.json --id=my-llm%n")
public class PipelineCreate implements Callable<Integer> {

    @CommandLine.Option(names = {"-f", "--file"}, required = true,
            description = "Pipeline definition JSON file (UnifiedPipelineDefinition format)")
    private File definitionFile;

    @CommandLine.Option(names = {"--id"},
            description = "Override the pipeline ID (default: read from file)")
    private String overrideId;

    @Override
    public Integer call() throws Exception {
        if (!definitionFile.exists()) {
            System.err.println("File not found: " + definitionFile.getAbsolutePath());
            return 1;
        }

        ObjectMapper mapper = ObjectMappers.getJsonMapper();

        UnifiedPipelineDefinition definition;
        try {
            definition = mapper.readValue(definitionFile, UnifiedPipelineDefinition.class);
        } catch (Exception e) {
            System.err.println("Failed to parse pipeline definition: " + e.getMessage());
            System.err.println("Expected UnifiedPipelineDefinition JSON format.");
            return 1;
        }

        // Apply overrides
        if (overrideId != null && !overrideId.isBlank()) {
            definition.setPipelineId(overrideId);
        }

        // Validate
        if (definition.getPipelineId() == null || definition.getPipelineId().isBlank()) {
            System.err.println("Pipeline ID is required. Set 'pipelineId' in JSON or use --id flag.");
            return 1;
        }

        if (definition.getPipelineSpec() == null || definition.getPipelineSpec().isEmpty()) {
            System.err.println("Pipeline spec (pipelineSpec) is required.");
            return 1;
        }

        // Set timestamps
        String now = Instant.now().toString();
        if (definition.getCreatedAt() == null) {
            definition.setCreatedAt(now);
        }
        definition.setUpdatedAt(now);

        // Save
        Path dir = KompileHome.dataDir().toPath().resolve("pipelines");
        Files.createDirectories(dir);
        Path outFile = dir.resolve(definition.getPipelineId() + ".unified.json");
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition);
        Files.writeString(outFile, json);

        System.out.println("Saved pipeline: " + definition.getPipelineId());
        System.out.println("  File: " + outFile);
        System.out.println("  Kind: " + (definition.getKind() != null ? definition.getKind() : "GENERIC"));
        System.out.println("  Topology: " + (definition.getTopology() != null ? definition.getTopology() : "SEQUENCE"));

        if (definition.getServing() != null) {
            System.out.println("  Heap: " + definition.getServing().getHeapSize());
            System.out.println("  Port: " + (definition.getServing().getPort() > 0 ? definition.getServing().getPort() : "auto"));
        }

        System.out.println("\nTo serve: kompile pipeline serve --pipeline-id=" + definition.getPipelineId());
        return 0;
    }
}
