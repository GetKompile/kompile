package ai.kompile.cli.main.pipeline;

import ai.kompile.cli.main.exec.NewStepCreator;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "pipeline",
        subcommands = {
                PipelineValidate.class,
                PipelineListSteps.class,
                NewStepCreator.class
        },
        mixinStandardHelpOptions = false,
        description = "Validate pipeline definitions and inspect authoring schemas.\n" +
                "Use the stdio MCP 'pipeline' tool to create, version, run, cancel, promote, " +
                "and roll back managed pipelines.")
public class PipelineMain implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new PipelineMain());
        commandLine.usage(System.err);
        return 0;
    }
}
