package ai.konduit.pipelinegenerator.main.helpers;

import ai.konduit.pipelinegenerator.main.MainCommand;
import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "helper",
        description = "Entry point for higher level helpers containing common patterns for pipeline creation",
        subcommands = {
        NDArrayPipelineHelper.class
}, mixinStandardHelpOptions = false)
public class HelperEntry implements Callable<Integer>  {

    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new HelperEntry());
        commandLine.usage(System.err);
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new HelperEntry());
        commandLine.usage(System.err);
        return 0;
    }
}
