package ai.konduit.pipelinegenerator.main.build;

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "build",subcommands = {
        GenerateImageAndSDK.class,
        NativeImageBuilder.class,
        PomGenerator.class,
        PipelineCommandGenerator.class,
        CloneBuildComponents.class
}, mixinStandardHelpOptions = false,
        description = "Configuration namespace for commands related to building native image binaries and SDKs")
public class BuildMain implements Callable<Integer> {
    public BuildMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new BuildMain());
        commandLine.usage(System.err);
        return 0;
    }
}
