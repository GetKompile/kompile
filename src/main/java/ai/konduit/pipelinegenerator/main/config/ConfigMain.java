package ai.konduit.pipelinegenerator.main.config;

import ai.konduit.pipelinegenerator.main.config.python.GeneratePythonConfig;
import ai.konduit.pipelinegenerator.main.config.python.GeneratePythonVariableConfig;
import picocli.CommandLine;

import java.util.concurrent.Callable;


@CommandLine.Command(name = "config",
       description = "Generate configuration",
        subcommands = {
        GeneratePythonConfig.class,
        GeneratePythonVariableConfig.class,
}, mixinStandardHelpOptions = false)
public class ConfigMain implements Callable<Integer> {
    public ConfigMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new ConfigMain());
        commandLine.usage(System.err);
        return 0;
    }

    public static void main(String...args) throws Exception {
        new ConfigMain().call();
    }

}
