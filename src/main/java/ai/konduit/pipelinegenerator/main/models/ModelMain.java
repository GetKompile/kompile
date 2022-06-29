package ai.konduit.pipelinegenerator.main.models;

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "model",mixinStandardHelpOptions = false,
        subcommands = {
                SameDiffSummary.class,
                Convert.class
        },
        description = "Utilities related to models including execution and debugging.")
public class ModelMain implements Callable<Integer> {
    public ModelMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new ModelMain());
        commandLine.usage(System.err);
        return 0;
    }
}
