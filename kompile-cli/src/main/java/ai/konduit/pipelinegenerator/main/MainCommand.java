package ai.konduit.pipelinegenerator.main;

import ai.konduit.pipelinegenerator.main.konvert.Konvert;
import ai.konduit.pipelinegenerator.main.nd4j.Nd4jOptimizer;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "pipeline",subcommands = {
        NativeImageBuilder.class,
        PipelineCommandGenerator.class,
        GraalVmPrint.class,
        PomGenerator.class,
        PrintJavacppPythonPath.class,
        Nd4jOptimizer.class,
        AnalyzeOps.class,
        Serve.class,
        SameDiffPrint.class,
        Konvert.class
}, mixinStandardHelpOptions = false)
public class MainCommand implements Callable<Integer> {

    public static void main(String...args) throws Exception {
        CommandLine commandLine = new CommandLine(new MainCommand());
        if(args == null || args.length < 1) {
            commandLine.usage(System.err);
        }


        int exit = commandLine.execute(args);
        if(!args[0].equals("serve") && args.length > 1 && !args[1].equals("serve"))
            System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        return null;
    }
}
