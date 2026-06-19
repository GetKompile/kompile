package ai.kompile.cli.main.pipeline;

import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.utils.StringUtils;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list-steps",
        mixinStandardHelpOptions = true,
        description = "List all available pipeline step types.")
public class PipelineListSteps implements Callable<Integer> {

    @CommandLine.Option(names = {"--format"}, defaultValue = "table",
            description = "Output format: table, json, or yaml (default: ${DEFAULT-VALUE})")
    private String format;

    @CommandLine.Option(names = {"-v", "--verbose"},
            description = "Show detailed information including parameters")
    private boolean verbose;

    @Override
    public Integer call() throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();

        ServiceLoader<PipelineStepRunnerFactory> factories = ServiceLoader.load(PipelineStepRunnerFactory.class);
        Set<String> seen = new HashSet<>();

        for (PipelineStepRunnerFactory factory : factories) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", factory.stepTypeName());
            entry.put("runnerClass", factory.getRunnerType());
            seen.add(factory.getRunnerType());

            try {
                StepSchema schema = factory.getSchema();
                if (schema != null) {
                    entry.put("description", schema.getDescription());
                    entry.put("parameterCount", schema.getParameters().size());
                    entry.put("inputCount", schema.getInputs().size());
                    entry.put("outputCount", schema.getOutputs().size());
                    if (verbose) {
                        entry.put("parameters", schema.getParameters());
                        entry.put("inputs", schema.getInputs());
                        entry.put("outputs", schema.getOutputs());
                    }
                }
            } catch (Exception e) {
                entry.put("description", "(no schema)");
                entry.put("parameterCount", 0);
            }
            steps.add(entry);
        }

        // Also check SchemaRegistry
        try {
            SchemaRegistry registry = SchemaRegistry.getInstance();
            for (StepSchema schema : registry.getAllSchemas()) {
                if (!seen.contains(schema.getRunnerClassName())) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", schema.getName());
                    entry.put("runnerClass", schema.getRunnerClassName());
                    entry.put("description", schema.getDescription());
                    entry.put("parameterCount", schema.getParameters().size());
                    entry.put("inputCount", schema.getInputs().size());
                    entry.put("outputCount", schema.getOutputs().size());
                    if (verbose) {
                        entry.put("parameters", schema.getParameters());
                        entry.put("inputs", schema.getInputs());
                        entry.put("outputs", schema.getOutputs());
                    }
                    steps.add(entry);
                }
            }
        } catch (Exception ignored) {
        }

        if (steps.isEmpty()) {
            System.err.println("No pipeline step types found on classpath.");
            return 0;
        }

        switch (format.toLowerCase()) {
            case "json":
                System.out.println(ObjectMappers.getJsonMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(steps));
                break;
            case "yaml":
                System.out.println(ObjectMappers.getYamlMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(steps));
                break;
            default:
                printTable(steps);
                break;
        }

        return 0;
    }

    private void printTable(List<Map<String, Object>> steps) {
        // Calculate column widths
        int nameWidth = 20, classWidth = 50, descWidth = 40, paramWidth = 6;
        for (Map<String, Object> step : steps) {
            nameWidth = Math.max(nameWidth, String.valueOf(step.get("name")).length());
            classWidth = Math.max(classWidth, String.valueOf(step.get("runnerClass")).length());
            String desc = step.get("description") != null ? String.valueOf(step.get("description")) : "";
            descWidth = Math.max(descWidth, Math.min(desc.length(), 60));
        }
        nameWidth = Math.min(nameWidth, 30);
        classWidth = Math.min(classWidth, 60);
        descWidth = Math.min(descWidth, 60);

        String fmt = "%-" + nameWidth + "s  %-" + classWidth + "s  %-" + descWidth + "s  %" + paramWidth + "s%n";
        System.err.printf(fmt, "NAME", "RUNNER CLASS", "DESCRIPTION", "PARAMS");
        System.err.println("-".repeat(nameWidth + classWidth + descWidth + paramWidth + 6));

        for (Map<String, Object> step : steps) {
            String name = StringUtils.truncate(String.valueOf(step.get("name")), nameWidth);
            String cls = StringUtils.truncate(String.valueOf(step.get("runnerClass")), classWidth);
            String desc = step.get("description") != null ? StringUtils.truncate(String.valueOf(step.get("description")), descWidth) : "";
            Object params = step.get("parameterCount");
            System.err.printf(fmt, name, cls, desc, params != null ? params : "0");
        }

        System.err.println("\n" + steps.size() + " step type(s) available");
    }

}
