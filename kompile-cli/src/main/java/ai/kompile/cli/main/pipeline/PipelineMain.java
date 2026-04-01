package ai.kompile.cli.main.pipeline;

import ai.kompile.cli.main.exec.NewStepCreator;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "pipeline",
        subcommands = {
                PipelineExec.class,
                PipelineValidate.class,
                PipelineListSteps.class,
                PipelineServe.class,
                NewStepCreator.class
        },
        mixinStandardHelpOptions = false,
        description = "Commands for managing and executing pipelines.\n" +
                "Pipelines compose multiple processing steps (Python, ONNX, SameDiff, DL4J, etc.) " +
                "into reusable workflows.")
public class PipelineMain implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new PipelineMain());
        commandLine.usage(System.err);
        return 0;
    }
}
