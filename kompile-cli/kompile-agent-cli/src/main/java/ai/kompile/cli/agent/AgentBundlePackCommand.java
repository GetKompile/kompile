package ai.kompile.cli.agent;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pack", description = "Pack an agent directory into a deterministic .kagent archive.", mixinStandardHelpOptions = true)
public final class AgentBundlePackCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Agent bundle directory")
    private Path source;
    @CommandLine.Option(names = {"-o", "--output"}, description = "Output archive", required = true)
    private Path output;

    @Override
    public Integer call() {
        try {
            AgentBundleLoader.pack(source, output);
            System.out.println("Packed agent bundle: " + output.toAbsolutePath().normalize());
            return 0;
        } catch (Exception e) {
            System.err.println("Could not pack agent bundle: " + e.getMessage());
            return 1;
        }
    }
}
